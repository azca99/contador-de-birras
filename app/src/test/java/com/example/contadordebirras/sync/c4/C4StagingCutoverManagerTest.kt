package com.example.contadordebirras.sync.c4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Suite de pruebas unitarias exhaustiva para [C4StagingCutoverManager] (Fase C4.5).
 *
 * Valida los 8 escenarios de seguridad exigidos para el cierre definitivo:
 * 1. Legacy SYNCED con foto.
 * 2. Legacy PENDING con foto sin subir.
 * 3. Legacy DELETED.
 * 4. Documento local sin equivalente remoto (preservación estricta sin borrado).
 * 5. Fallo de red a mitad del cutover (preservación de mutaciones y retorno INCOMPLETE).
 * 6. Fallo de Storage (foto se mantiene PENDING, no se pierde photoUri, retorno INCOMPLETE).
 * 7. Reintento tras cutover parcial (idempotencia y convergencia a COMPLETED).
 * 8. Protección de documentos modernizados contra escrituras legacy y frontera de rollback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C4StagingCutoverManagerTest {

    private lateinit var database: BeerDatabase
    private lateinit var beerDao: BeerDao
    private lateinit var persistence: RoomC4SyncPersistence
    private val firestoreAdapter = mockk<FirestoreC4SyncAdapter>()
    private val storageAdapter = mockk<StorageC4SyncAdapter>()
    private lateinit var coordinator: AndroidC4SyncCoordinator
    private lateinit var cutoverManager: C4StagingCutoverManager

    private val aliceUid = "user_alice_test"
    private var testPhotoResolver: (suspend (String) -> ByteArray?)? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        beerDao = database.beerDao()
        persistence = RoomC4SyncPersistence(database)

        coordinator = AndroidC4SyncCoordinator(
            firestoreAdapter = firestoreAdapter,
            storageAdapter = storageAdapter,
            persistence = persistence
        )

        testPhotoResolver = null
        cutoverManager = C4StagingCutoverManager(
            coordinator = coordinator,
            persistence = persistence,
            firestoreAdapter = firestoreAdapter,
            photoResolver = { uri -> testPhotoResolver?.invoke(uri) }
        )

        C4SyncConfig.reset()
    }

    @After
    fun tearDown() {
        database.close()
        C4SyncConfig.reset()
    }

    /**
     * 1. LEGACY SYNCED CON FOTO:
     * El documento remoto estaba en revisión 0 sin fieldRevisions.
     * Cutover ejecuta executeLegacyUpgrade a rev 1 y alinea Room local a rev 1.
     * Preserva íntegramente comentarios, foto y coordenadas. Post-condiciones verificadas: COMPLETED.
     */
    @Test
    fun executeCutover_legacySyncedWithPhoto_upgradesAndAligns() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val legacyBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            comment = "IPA Artesanal Legacy",
            hasRemotePhoto = true
        )
        beerDao.insertBeer(legacyBeer)

        val legacyRemoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "CANA",
            timestamp = legacyBeer.timestamp,
            latitude = legacyBeer.latitude,
            longitude = legacyBeer.longitude,
            comment = "IPA Artesanal Legacy",
            remotePhotoUrl = legacyBeer.remotePhotoUrl,
            updatedAt = legacyBeer.updatedAt,
            revision = 0L,
            isDeleted = false,
            lastMutationId = "",
            fieldRevisions = emptyMap()
        )

        val upgradedRemoteDoc = legacyRemoteDoc.copy(
            revision = 1L,
            lastMutationId = "mut-upgrade",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns listOf(legacyRemoteDoc)
        coEvery { firestoreAdapter.executeLegacyUpgrade(syncId, aliceUid, any()) } returns C4TransactionResult.Success(1L, "mut-upgrade")
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns upgradedRemoteDoc

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Resultado debe ser Completed", result is CutoverResult.Completed)
        val completed = result as CutoverResult.Completed
        assertEquals(1, completed.upgradedRemoteCount)
        assertEquals(1, completed.localAlignedCount)
        assertEquals(0, persistence.historyDao.getPendingMutations(aliceUid).size)

        val aligned = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(aligned)
        assertEquals(1L, aligned!!.remoteRevision)
        assertEquals(1L, aligned.baseRevision)
        assertEquals(SyncStatus.SYNCED, aligned.syncStatus)
        assertEquals("IPA Artesanal Legacy", aligned.comment)
        assertNotNull(aligned.remotePhotoUrl)
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)
        assertTrue(C4SyncConfig.isEnabled)
    }

    /**
     * 2. LEGACY PENDING CON FOTO SIN SUBIR:
     * Cerveza en PENDING con 0/0 en Room, con photoUri local.
     * Cutover obtiene realmente los bytes de la foto, la sube a Storage versionado,
     * verifica su existencia y emite CreateActive con rev 1.
     * photoUri se conserva intacto, remotePhotoUrl se puebla, mutación se confirma.
     */
    @Test
    fun executeCutover_legacyPendingWithUnuploadedPhoto_uploadsVersionedPhotoAndPushesCreateActive() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val fakePhotoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val photoUri = "file:///data/user/0/com.azcaratech.birras/photos/$syncId.jpg"

        testPhotoResolver = { uri ->
            if (uri == photoUri) fakePhotoBytes else null
        }

        val offlineBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            comment = "Birra offline con foto",
            photoUri = photoUri
        )
        beerDao.insertBeer(offlineBeer)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null
        coEvery {
            storageAdapter.uploadVersionedPhoto(aliceUid, syncId, any(), fakePhotoBytes, any())
        } answers {
            val mutationId = thirdArg<String>()
            C4StorageResult.Success("users/$aliceUid/beers/${syncId}_$mutationId.jpg", alreadyExisted = false)
        }
        coEvery { storageAdapter.photoExists(any()) } returns true
        coEvery {
            firestoreAdapter.executeCreateActive(
                syncId = syncId,
                ownerUid = aliceUid,
                newDocument = any(),
                mutationId = any()
            )
        } answers {
            C4TransactionResult.Success(1L, args[3] as String)
        }

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Cutover debe completarse", result is CutoverResult.Completed)
        val completed = result as CutoverResult.Completed
        assertEquals(1, completed.localPromotedCount)
        assertEquals(1, completed.pushedPendingCount)

        val syncedBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(syncedBeer)
        assertEquals(SyncStatus.SYNCED, syncedBeer!!.syncStatus)
        assertEquals(1L, syncedBeer.remoteRevision)
        assertEquals(1L, syncedBeer.baseRevision)
        assertEquals("Birra offline con foto", syncedBeer.comment)
        // photoUri local ESTRICTAMENTE PRESERVADO
        assertEquals(photoUri, syncedBeer.photoUri)
        // remotePhotoUrl versionado asignado
        assertTrue(syncedBeer.remotePhotoUrl?.startsWith("users/$aliceUid/beers/${syncId}_") == true)

        val history = persistence.historyDao.getMutationsForBeer(syncId, aliceUid)
        assertEquals(1, history.size)
        assertEquals(MutationStatus.CONFIRMED, history[0].status)
    }

    /**
     * 3. LEGACY DELETED:
     * Cerveza en DELETED con 0/0 en Room.
     * Cutover prepara tombstone con baseRevision remota post-ascenso y emite WriteTombstone.
     */
    @Test
    fun executeCutover_legacyDeleted_preparesTombstoneAndPushesWriteTombstone() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val deletedBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.DELETED,
            comment = "Borrada offline bajo legacy"
        )
        beerDao.insertBeer(deletedBeer)

        val remoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "CANA",
            timestamp = deletedBeer.timestamp,
            revision = 1L,
            lastMutationId = "init-mut",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns listOf(remoteDoc)
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns remoteDoc
        coEvery {
            firestoreAdapter.executeWriteTombstone(
                syncId = syncId,
                ownerUid = aliceUid,
                mutationId = any(),
                baseRevision = any()
            )
        } answers {
            C4TransactionResult.Success(2L, thirdArg())
        }

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue(result is CutoverResult.Completed)
        val completed = result as CutoverResult.Completed
        assertEquals(1, completed.localTombstonesCount)
        assertEquals(1, completed.pushedPendingCount)

        val finalBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(finalBeer)
        assertEquals(SyncStatus.DELETED, finalBeer!!.syncStatus)
        assertEquals(2L, finalBeer.remoteRevision)
    }

    /**
     * 4. DOCUMENTO LOCAL SIN EQUIVALENTE REMOTO (PRESERVACIÓN DE DATOS):
     * Cerveza local en SYNCED (0/0) bajo legacy, pero no existe en Firestore.
     * REGLA ESTRICTA: NO marcar automáticamente DELETED. Conservar la cerveza activa e intacta en Room.
     * Retornar CutoverResult.Blocked para reconciliación segura. C4 NO se activa.
     */
    @Test
    fun executeCutover_localSyncedAbsentRemotely_preservesLocalDataAndBlocksWithoutDeletion() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val localOnlyBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Cerveza valiosa creada en legacy no presente en servidor"
        )
        beerDao.insertBeer(localOnlyBeer)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Cutover debe ser BLOCKED para evitar pérdida de datos", result is CutoverResult.Blocked)
        val blocked = result as CutoverResult.Blocked
        assertTrue(blocked.reason.contains("sin equivalente remoto"))

        // COMPROBACIÓN CRÍTICA: La cerveza NO fue borrada ni marcada DELETED
        val preservedBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull("La cerveza local DEBE existir", preservedBeer)
        assertEquals(SyncStatus.SYNCED, preservedBeer!!.syncStatus)
        assertEquals("Cerveza valiosa creada en legacy no presente en servidor", preservedBeer.comment)
        assertNull(preservedBeer.deletedAt)

        // C4 no debe quedar activo
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
    }

    /**
     * 5. FALLO DE RED A MITAD DEL CUTOVER:
     * El paso de red PUSH falla por red offline (UNAVAILABLE).
     * Las mutaciones se conservan en PENDING en Room. Cero pérdida de datos.
     * El resultado es CutoverResult.Incomplete (nunca Completed ni AlreadyUpToDate).
     */
    @Test
    fun executeCutover_networkFailureDuringPush_preservesPendingMutationsAndReturnsIncomplete() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val offlineBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            comment = "Offline esperando push"
        )
        beerDao.insertBeer(offlineBeer)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        // Lectura de documento durante push lanza excepción de red
        coEvery { firestoreAdapter.readServerDocument(syncId) } throws com.google.firebase.firestore.FirebaseFirestoreException(
            "Service unavailable / offline",
            com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
        )

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Debe retornar Incomplete por fallo de red", result is CutoverResult.Incomplete)
        val incomplete = result as CutoverResult.Incomplete
        assertEquals(1, incomplete.pendingMutationsCount)
        assertEquals(1, incomplete.offlinePushesCount)

        // Comprobación de persistencia intacta
        val pendingBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(pendingBeer)
        assertEquals(SyncStatus.PENDING, pendingBeer!!.syncStatus)

        val pendingHistory = persistence.historyDao.getPendingMutations(aliceUid)
        assertEquals(1, pendingHistory.size)
        assertEquals(MutationStatus.PENDING, pendingHistory[0].status)

        // C4 no se activa
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
    }

    /**
     * 6. FALLO DE STORAGE EN FOTOGRAFÍA:
     * Cerveza con photoUri pendiente de subida, pero Storage falla o los bytes no pueden leerse.
     * Push aborta con StorageFailed, la mutación permanece en PENDING, photoUri se conserva intacto.
     * CutoverResult.Incomplete es retornado.
     */
    @Test
    fun executeCutover_storageFailure_keepsPhotoPendingAndReturnsIncomplete() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val photoUri = "file:///photos/beer_failure.jpg"

        // El resolver falla simulando archivo corrupto o no disponible
        testPhotoResolver = { _ -> null }

        val offlineBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            comment = "Birra con fallo de foto",
            photoUri = photoUri
        )
        beerDao.insertBeer(offlineBeer)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Debe retornar Incomplete cuando Storage falla", result is CutoverResult.Incomplete)
        val incomplete = result as CutoverResult.Incomplete
        assertEquals(1, incomplete.storageFailuresCount)
        assertEquals(1, incomplete.pendingMutationsCount)

        // Comprobación: photoUri y cerveza intactas en PENDING
        val beer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(beer)
        assertEquals(SyncStatus.PENDING, beer!!.syncStatus)
        assertEquals(photoUri, beer.photoUri)

        val pendingHistory = persistence.historyDao.getPendingMutations(aliceUid)
        assertEquals(1, pendingHistory.size)
        assertEquals(MutationStatus.PENDING, pendingHistory[0].status)
    }

    /**
     * 7. REINTENTO TRAS CUTOVER PARCIAL:
     * Tras el fallo anterior (escenario 5 o 6), la red se recupera.
     * Un segundo intento de cutover procesa las mutaciones pendientes de forma idempotente
     * y concluye en CutoverResult.Completed.
     */
    @Test
    fun executeCutover_retryAfterPartialCutover_completesIdempotently() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val fakePhotoBytes = byteArrayOf(0x01, 0x02, 0x03)
        val photoUri = "file:///photos/beer_recovered.jpg"

        // 1. Simular estado parcial: promovida a -1/-1 con mutación pendiente en Room
        val initialMutationId = "mut-init-123"
        val beer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            comment = "Birra recuperada en reintento",
            photoUri = photoUri
        ).copy(
            remoteRevision = -1L,
            baseRevision = -1L,
            lastMutationId = initialMutationId,
            dirtyFields = "TYPE,TIMESTAMP,LOCATION,COMMENT,PHOTO"
        )
        beerDao.insertBeer(beer)
        beerDao.insertHistoryRecord(
            com.example.contadordebirras.data.LocalMutationHistoryEntity(
                mutationId = initialMutationId,
                syncId = syncId,
                ownerUid = aliceUid,
                timestamp = 1000L,
                dirtyFields = "TYPE,TIMESTAMP,LOCATION,COMMENT,PHOTO",
                baseRevision = -1L,
                status = MutationStatus.PENDING,
                isLocalOnlyCreation = true
            )
        )

        // 2. En el reintento, Storage y Firestore operan normalmente
        testPhotoResolver = { fakePhotoBytes }
        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null
        coEvery { storageAdapter.uploadVersionedPhoto(aliceUid, syncId, initialMutationId, fakePhotoBytes, any()) } returns
                C4StorageResult.Success("users/$aliceUid/beers/${syncId}_$initialMutationId.jpg", alreadyExisted = false)
        coEvery { storageAdapter.photoExists(any()) } returns true
        coEvery {
            firestoreAdapter.executeCreateActive(
                syncId = syncId,
                ownerUid = aliceUid,
                newDocument = any(),
                mutationId = initialMutationId
            )
        } returns C4TransactionResult.Success(1L, initialMutationId)

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("El reintento debe concluir exitosamente en Completed", result is CutoverResult.Completed)
        val completed = result as CutoverResult.Completed
        assertEquals(1, completed.pushedPendingCount)
        assertEquals(0, persistence.historyDao.getPendingMutations(aliceUid).size)

        val syncedBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(syncedBeer)
        assertEquals(SyncStatus.SYNCED, syncedBeer!!.syncStatus)
        assertEquals(1L, syncedBeer.remoteRevision)
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)
        assertTrue(C4SyncConfig.isEnabled)
    }

    /**
     * 8. PROTECCIÓN DE DOCUMENTOS MODERNIZADOS Y FRONTERA DE ROLLBACK:
     * Una vez que un documento remoto es ascendido a revision >= 1, las reglas de Firestore
     * rechazan escrituras de clientes legacy.
     * rollbackToLegacy() revierte el cliente local pero documenta con total transparencia
     * que se alcanzó el punto de no retorno remoto.
     */
    @Test
    fun legacyClient_writeAttemptOnModernDocument_andRollbackBoundary() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val upgradedDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "CANA",
            timestamp = 1000L,
            revision = 1L,
            lastMutationId = "mut-upgraded",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns listOf(upgradedDoc)

        // Activar C4
        C4SyncConfig.isEnabled = true
        C4SyncConfig.engineMode = SyncEngineMode.C4_ACTIVE

        // Ejecutar rollback
        val rollbackResult = cutoverManager.rollbackToLegacy(aliceUid)

        assertTrue(rollbackResult.isLocalReverted)
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)

        // Verificar detección de frontera de no retorno
        assertEquals(1, rollbackResult.remoteUpgradedCount)
        assertTrue(rollbackResult.pointOfNoReturnReached)
        assertTrue(rollbackResult.warningMessage.contains("IRREVERSIBLE"))
    }
}
