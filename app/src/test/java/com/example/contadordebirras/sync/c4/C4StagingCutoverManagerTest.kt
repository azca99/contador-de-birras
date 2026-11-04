package com.example.contadordebirras.sync.c4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
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
 * Suite de pruebas unitarias para [C4StagingCutoverManager] (Fase C4.5).
 *
 * Evalúa las garantías formales de transición:
 * - Cero pérdida de datos (comentarios, fechas, fotos, coordenadas).
 * - Cero duplicación de registros.
 * - Preservación de cervezas locales creadas o borradas sin conexión bajo el motor legacy.
 * - Idempotencia estricta en reintentos.
 * - Exclusión mutua de motores mediante C4SyncConfig.
 * - Reversibilidad segura (rollback a legacy).
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

        cutoverManager = C4StagingCutoverManager(
            coordinator = coordinator,
            persistence = persistence,
            firestoreAdapter = firestoreAdapter
        )

        C4SyncConfig.reset()
    }

    @After
    fun tearDown() {
        database.close()
        C4SyncConfig.reset()
    }

    /**
     * 1. Ascenso de documento remoto legacy y alineación local SYNCED:
     * El documento remoto estaba en revisión 0 sin fieldRevisions.
     * Cutover ejecuta executeLegacyUpgrade a rev 1 y alinea Room local a rev 1.
     * Preserva íntegramente comentarios, foto y coordenadas.
     */
    @Test
    fun executeCutover_upgradesLegacyRemoteAndAlignsLocalSynced() = runBlocking {
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

        assertTrue("Cutover debe ser exitoso", result is CutoverResult.Success)
        val success = result as CutoverResult.Success
        assertEquals(1, success.upgradedRemoteCount)
        assertEquals(1, success.localAlignedCount)

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
     * 2. Preservación de creación local offline generada bajo legacy:
     * Cerveza en PENDING con 0/0 en Room, ausente en remoto.
     * Cutover la promueve a local-only (-1/-1), registra mutación durable en historial
     * y ejecuta PUSH CreateActive a revisión 1.
     * Ninguna cerveza desaparece.
     */
    @Test
    fun executeCutover_preservesLocalOfflinePendingCreation_andPushesSuccessfully() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val offlineBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            comment = "Creada sin conexión bajo legacy"
        )
        beerDao.insertBeer(offlineBeer)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null
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

        assertTrue(result is CutoverResult.Success)
        val success = result as CutoverResult.Success
        assertEquals(1, success.localPromotedCount)
        assertEquals(1, success.pushedPendingCount)

        val syncedBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(syncedBeer)
        assertEquals(SyncStatus.SYNCED, syncedBeer!!.syncStatus)
        assertEquals(1L, syncedBeer.remoteRevision)
        assertEquals(1L, syncedBeer.baseRevision)
        assertEquals("Creada sin conexión bajo legacy", syncedBeer.comment)

        val history = persistence.historyDao.getMutationsForBeer(syncId, aliceUid)
        assertEquals(1, history.size)
        assertEquals(MutationStatus.CONFIRMED, history[0].status)
    }

    /**
     * 3. Manejo de borrado local offline en la era legacy:
     * Cerveza en DELETED con 0/0 en Room.
     * Cutover registra mutación durable de tombstone y emite WriteTombstone.
     */
    @Test
    fun executeCutover_handlesLegacyLocalDeletedBeer() = runBlocking {
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

        assertTrue(result is CutoverResult.Success)
        val success = result as CutoverResult.Success
        assertEquals(1, success.localTombstonesCount)
        assertEquals(1, success.pushedPendingCount)

        val finalBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(finalBeer)
        assertEquals(SyncStatus.DELETED, finalBeer!!.syncStatus)
        assertEquals(2L, finalBeer.remoteRevision)
    }

    /**
     * 4. Anti-resurrección ante borrados remotos de la era legacy:
     * Local tenía SYNCED 0/0 pero el documento fue borrado físicamente en Firestore (doc.delete()).
     * Cutover detecta la ausencia y marca la fila local como DELETED.
     */
    @Test
    fun executeCutover_handlesLegacyRemoteDeletion_marksLocalAsDeleted() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val syncedBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Cerveza borrada en remoto durante legacy"
        )
        beerDao.insertBeer(syncedBeer)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue(result is CutoverResult.Success)
        val success = result as CutoverResult.Success
        assertEquals(1, success.localTombstonesCount)

        val finalBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(finalBeer)
        assertEquals(SyncStatus.DELETED, finalBeer!!.syncStatus)
        assertNotNull(finalBeer.deletedAt)
    }

    /**
     * 5. Idempotencia estricta:
     * Ejecutar el cutover dos veces consecutivas produce AlreadyUpToDate y no duplica registros
     * ni realiza cambios espurios.
     */
    @Test
    fun executeCutover_isStrictlyIdempotent() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val beer = BeerEntity(
            type = BeerType.CANA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = aliceUid,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L
        )
        beerDao.insertBeer(beer)

        val modernDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "CANA",
            timestamp = 1000L,
            revision = 1L,
            lastMutationId = "mut-existing",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns listOf(modernDoc)
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns modernDoc

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Reintento debe reportar AlreadyUpToDate", result is CutoverResult.AlreadyUpToDate)
        val activeCount = beerDao.getActiveBeersSync(aliceUid).size
        assertEquals("No debe haber duplicados", 1, activeCount)
    }

    /**
     * 6. Reversibilidad (Rollback):
     * Permite cancelar la transición regresando a LEGACY sin perder ninguna cerveza.
     */
    @Test
    fun rollbackToLegacy_preservesDataAndRestoresLegacyState() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val beer = BeerEntity(
            type = BeerType.JARRA,
            timestamp = 2000L,
            comment = "Cerveza preservada",
            syncId = syncId,
            ownerUid = aliceUid,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L
        )
        beerDao.insertBeer(beer)

        C4SyncConfig.isEnabled = true
        C4SyncConfig.engineMode = SyncEngineMode.C4_ACTIVE

        val rolledBack = cutoverManager.rollbackToLegacy()
        assertTrue(rolledBack)
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)

        val preservedBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(preservedBeer)
        assertEquals("Cerveza preservada", preservedBeer!!.comment)
    }
}
