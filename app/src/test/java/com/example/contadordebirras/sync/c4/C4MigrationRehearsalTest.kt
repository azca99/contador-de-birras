package com.example.contadordebirras.sync.c4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.firestore.FirebaseFirestoreException
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Suite exhaustiva de simulación de migración y corte definitivo Legacy -> C4 (C4MigrationRehearsalTest).
 *
 * Valida de forma rigurosa:
 * 1. Simulación completa de corte sobre dataset heterogéneo (Legacy synced con foto legacy,
 *    legacy synced sin foto, offline created con foto local, modern edit con foto versionada,
 *    legacy deleted tombstone, histórico previo en Room y documento remoto descubierto).
 * 2. Postcondiciones estrictas: recuento exacto, preservación de IDs, fotos/URIs/URLs,
 *    revisiones incrementadas (>= 1), tombstones preservados (no resucitados), estados de mutación
 *    coherentes y ausencia total de duplicados.
 * 3. Casos de fallo y condiciones límite:
 *    - Interrupción de red mid-cutover.
 *    - Cierre inesperado antes y después de escribir el bloqueo remoto.
 *    - Repetición de proceso sobre cuenta ya migrada (idempotencia).
 *    - Fallo en subida de foto.
 *    - Discrepancias de datos (local ausente en remoto, corrupción remota).
 *    - Frontera de reversibilidad y bloqueo contra reactivación legacy.
 *    - Reinicio de app y recuperación persistente.
 *    - Regla fail-safe: conservación de datos en estado HALTED sin retroceso automático a Legacy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C4MigrationRehearsalTest {

    private lateinit var database: BeerDatabase
    private lateinit var beerDao: BeerDao
    private lateinit var persistence: RoomC4SyncPersistence
    private val firestoreAdapter = mockk<FirestoreC4SyncAdapter>()
    private val storageAdapter = mockk<StorageC4SyncAdapter>()
    private lateinit var coordinator: AndroidC4SyncCoordinator
    private lateinit var cutoverManager: C4StagingCutoverManager

    private val aliceUid = "user_rehearsal_alice"
    private var photoResolverHandler: (suspend (String) -> ByteArray?)? = null
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
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

        coEvery { firestoreAdapter.writeAccountProtocolLock(any(), any(), any()) } returns true

        photoResolverHandler = null
        cutoverManager = C4StagingCutoverManager(
            coordinator = coordinator,
            persistence = persistence,
            firestoreAdapter = firestoreAdapter,
            context = context,
            photoResolver = { uri -> photoResolverHandler?.invoke(uri) }
        )

        C4SyncConfig.reset()
        C4SyncStateStore.clearForTesting(context, aliceUid)
    }

    private fun getAllLocalBeers(ownerUid: String): List<BeerEntity> {
        return beerDao.getActiveBeersSync(ownerUid) +
                beerDao.getPendingSyncBeers(ownerUid).filter { it.syncStatus == SyncStatus.DELETED }
    }

    @After
    fun tearDown() {
        database.close()
        C4SyncStateStore.clearForTesting(context, aliceUid)
        C4SyncConfig.reset()
    }

    // =========================================================================
    // 1. SIMULACIÓN COMPLETA DE MIGRACIÓN SOBRE DATASET HETEROGÉNEO
    // =========================================================================

    /**
     * Simulación integral end-to-end sobre un dataset realista y heterogéneo:
     * - E1: Cerveza ya sincronizada bajo legacy con URL de foto legacy ("users/uid/beers/id.jpg").
     * - E2: Cerveza sincronizada bajo legacy sin foto.
     * - E3: Cerveza creada sin conexión (PENDING, sin doc remoto) con foto local capturada.
     * - E4: Cerveza existente modernizada con foto versionada y edición offline pendiente (COMMENT).
     * - E5: Cerveza borrada offline bajo legacy (tombstone pendiente de confirmación remota).
     * - E6: Registro histórico previo confirmado en local_mutation_history.
     * - E7: Cerveza remota existente en Firestore descubierta durante el cutover (no presente en Room).
     */
    @Test
    fun testFullMigrationRehearsal_endToEnd_heterogeneousDataset() = runBlocking {
        val syncId1 = "sync-rehearsal-01-legacy-photo"
        val syncId2 = "sync-rehearsal-02-legacy-nophoto"
        val syncId3 = "sync-rehearsal-03-offline-photo"
        val syncId4 = "sync-rehearsal-04-modern-edit"
        val syncId5 = "sync-rehearsal-05-legacy-deleted"
        val syncId7 = "sync-rehearsal-07-remote-discovered"

        val fakeOfflineBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val offlinePhotoUri = "file:///data/user/0/com.azcaratech.birras/photos/$syncId3.jpg"

        photoResolverHandler = { uri ->
            if (uri == offlinePhotoUri) fakeOfflineBytes else null
        }

        // --- 1. Preparar entidades en Room local ---
        // E1: Legacy synced con foto legacy
        val beer1 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId1,
            syncStatus = SyncStatus.SYNCED,
            comment = "Legacy con foto remota",
            hasRemotePhoto = true
        )
        beerDao.insertBeer(beer1)

        // E2: Legacy synced sin foto
        val beer2 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId2,
            syncStatus = SyncStatus.SYNCED,
            comment = "Legacy sin foto",
            hasRemotePhoto = false
        )
        beerDao.insertBeer(beer2)

        // E3: Offline creada sin conexión con foto local
        val beer3 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId3,
            syncStatus = SyncStatus.PENDING,
            comment = "Creada offline esperando cutover",
            photoUri = offlinePhotoUri,
            hasRemotePhoto = false
        )
        beerDao.insertBeer(beer3)

        // E4: Cerveza modernizada en rev 1 con foto versionada y edición local pendiente en COMMENT
        val mutationId4 = "mut-edit-4-pending"
        val beer4 = BeerEntity(
            id = 0,
            type = BeerType.PINTA,
            timestamp = 1000L,
            latitude = 40.4168,
            longitude = -3.7038,
            photoUri = null,
            comment = "Comentario actualizado offline",
            locationName = "Bar Moderno",
            syncId = syncId4,
            syncStatus = SyncStatus.PENDING,
            remotePhotoUrl = "users/$aliceUid/beers/${syncId4}_mut-v1-init.jpg",
            updatedAt = 2000L,
            photoSource = "GALLERY",
            ownerUid = aliceUid,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = BeerDirtyFieldsCodec.encode(setOf(BeerDirtyField.COMMENT)),
            lastMutationId = mutationId4,
            deletedAt = null
        )
        beerDao.insertBeer(beer4)
        beerDao.insertHistoryRecord(
            LocalMutationHistoryEntity(
                mutationId = mutationId4,
                syncId = syncId4,
                ownerUid = aliceUid,
                timestamp = 2000L,
                dirtyFields = "COMMENT",
                baseRevision = 1L,
                status = MutationStatus.PENDING,
                isLocalOnlyCreation = false
            )
        )

        // E5: Cerveza borrada offline bajo legacy (tombstone)
        val beer5 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId5,
            syncStatus = SyncStatus.DELETED,
            comment = "Borrada en legacy"
        )
        beerDao.insertBeer(beer5)

        // E6: Mutación histórica confirmada previa en local_mutation_history
        val historicalMutationId = "mut-historical-prior-confirmed"
        beerDao.insertHistoryRecord(
            LocalMutationHistoryEntity(
                mutationId = historicalMutationId,
                syncId = syncId1,
                ownerUid = aliceUid,
                timestamp = 500L,
                dirtyFields = "TYPE,TIMESTAMP,LOCATION,COMMENT,PHOTO",
                baseRevision = -1L,
                confirmedRevision = 1L,
                status = MutationStatus.CONFIRMED,
                isLocalOnlyCreation = true,
                confirmedAt = 600L
            )
        )

        // --- 2. Preparar documentos remotos en Firestore ---
        // Remoto E1: doc legacy en rev 0 sin fieldRevisions
        val remoteDoc1 = C4FirestoreDocument(
            syncId = syncId1,
            userId = aliceUid,
            type = "CANA",
            timestamp = beer1.timestamp,
            latitude = beer1.latitude,
            longitude = beer1.longitude,
            comment = beer1.comment,
            remotePhotoUrl = beer1.remotePhotoUrl,
            photoSource = beer1.photoSource,
            updatedAt = beer1.updatedAt,
            revision = 0L,
            isDeleted = false,
            lastMutationId = "",
            fieldRevisions = emptyMap()
        )
        val upgradedRemoteDoc1 = remoteDoc1.copy(
            revision = 1L,
            lastMutationId = "mut-upg-doc1",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        // Remoto E2: doc legacy en rev 0 sin fieldRevisions
        val remoteDoc2 = C4FirestoreDocument(
            syncId = syncId2,
            userId = aliceUid,
            type = "CANA",
            timestamp = beer2.timestamp,
            latitude = beer2.latitude,
            longitude = beer2.longitude,
            comment = beer2.comment,
            remotePhotoUrl = null,
            photoSource = null,
            updatedAt = beer2.updatedAt,
            revision = 0L,
            isDeleted = false,
            lastMutationId = "",
            fieldRevisions = emptyMap()
        )
        val upgradedRemoteDoc2 = remoteDoc2.copy(
            revision = 1L,
            lastMutationId = "mut-upg-doc2",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        // Remoto E4: doc modern en rev 1
        val remoteDoc4 = C4FirestoreDocument(
            syncId = syncId4,
            userId = aliceUid,
            type = "PINTA",
            timestamp = 1000L,
            latitude = 40.4168,
            longitude = -3.7038,
            comment = "Comentario inicial original",
            remotePhotoUrl = "users/$aliceUid/beers/${syncId4}_mut-v1-init.jpg",
            photoSource = "GALLERY",
            updatedAt = 1000L,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "mut-v1-init",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        // Remoto E5: doc legacy en rev 0
        val remoteDoc5 = C4FirestoreDocument(
            syncId = syncId5,
            userId = aliceUid,
            type = "CANA",
            timestamp = beer5.timestamp,
            latitude = beer5.latitude,
            longitude = beer5.longitude,
            comment = beer5.comment,
            remotePhotoUrl = null,
            photoSource = null,
            updatedAt = beer5.updatedAt,
            revision = 0L,
            isDeleted = false,
            lastMutationId = "",
            fieldRevisions = emptyMap()
        )
        val upgradedRemoteDoc5 = remoteDoc5.copy(
            revision = 1L,
            lastMutationId = "mut-upg-doc5",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        // Remoto E7: Cerveza remota existente en servidor no presente localmente
        val remoteDoc7 = C4FirestoreDocument(
            syncId = syncId7,
            userId = aliceUid,
            type = "JARRA",
            timestamp = 3000L,
            latitude = 41.3851,
            longitude = 2.1734,
            comment = "Cerveza creada desde otro dispositivo",
            locationName = "Cervecería Remota",
            remotePhotoUrl = "users/$aliceUid/beers/${syncId7}_mut-remote.jpg",
            photoSource = "CAMERA",
            updatedAt = 3000L,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "mut-remote-7",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        // Simular llamadas de Firestore y Storage
        // QueryServerDocuments devuelve inicialmente docs 1, 2, 4, 5, 7
        val initialRemoteList = listOf(remoteDoc1, remoteDoc2, remoteDoc4, remoteDoc5, remoteDoc7)
        val postUpgradeRemoteList = listOf(upgradedRemoteDoc1, upgradedRemoteDoc2, remoteDoc4, upgradedRemoteDoc5, remoteDoc7)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returnsMany listOf(
            initialRemoteList,
            postUpgradeRemoteList
        )

        // executeLegacyUpgrade para docs 1, 2, 5
        coEvery { firestoreAdapter.executeLegacyUpgrade(syncId1, aliceUid, any()) } returns C4TransactionResult.Success(1L, "mut-upg-doc1")
        coEvery { firestoreAdapter.executeLegacyUpgrade(syncId2, aliceUid, any()) } returns C4TransactionResult.Success(1L, "mut-upg-doc2")
        coEvery { firestoreAdapter.executeLegacyUpgrade(syncId5, aliceUid, any()) } returns C4TransactionResult.Success(1L, "mut-upg-doc5")

        // Lecturas directas de servidor
        coEvery { firestoreAdapter.readServerDocument(syncId1) } returns upgradedRemoteDoc1
        coEvery { firestoreAdapter.readServerDocument(syncId2) } returns upgradedRemoteDoc2
        coEvery { firestoreAdapter.readServerDocument(syncId3) } returns null
        coEvery { firestoreAdapter.readServerDocument(syncId4) } returns remoteDoc4
        coEvery { firestoreAdapter.readServerDocument(syncId5) } returns upgradedRemoteDoc5
        coEvery { firestoreAdapter.readServerDocument(syncId7) } returns remoteDoc7

        // Storage mocks para E3 (offline photo upload)
        coEvery {
            storageAdapter.uploadVersionedPhoto(aliceUid, syncId3, any(), fakeOfflineBytes, any())
        } answers {
            val mutId = thirdArg<String>()
            C4StorageResult.Success("users/$aliceUid/beers/${syncId3}_$mutId.jpg", alreadyExisted = false)
        }
        coEvery { storageAdapter.photoExists(any()) } returns true

        // Push mocks:
        // E3: CreateActive
        coEvery {
            firestoreAdapter.executeCreateActive(syncId = syncId3, ownerUid = aliceUid, newDocument = any(), mutationId = any())
        } answers {
            C4TransactionResult.Success(1L, args[3] as String)
        }
        // E4: ApplyPatch para edición en COMMENT
        coEvery {
            firestoreAdapter.executeApplyPatch(
                syncId = syncId4,
                ownerUid = aliceUid,
                mutationId = mutationId4,
                baseRevision = 1L,
                fieldsToWrite = setOf(BeerDirtyField.COMMENT),
                fieldValues = match { it["comment"] == "Comentario actualizado offline" },
                updatedPhotoUrl = any()
            )
        } returns C4TransactionResult.Success(2L, mutationId4)
        // E5: WriteTombstone
        coEvery {
            firestoreAdapter.executeWriteTombstone(
                syncId = syncId5,
                ownerUid = aliceUid,
                mutationId = any(),
                baseRevision = 1L
            )
        } answers {
            C4TransactionResult.Success(2L, thirdArg())
        }

        // --- 3. Ejecutar migración controlada de cutover ---
        val result = cutoverManager.executeCutover(aliceUid)

        // --- 4. Comprobaciones de resultado global ---
        assertTrue("Cutover debe concluir exitosamente en Completed", result is CutoverResult.Completed)
        val completed = result as CutoverResult.Completed
        assertEquals("3 docs legacy ascendidos a rev 1 (1, 2, 5)", 3, completed.upgradedRemoteCount)
        assertEquals("2 docs legacy locales SYNCED alineados (1, 2)", 2, completed.localAlignedCount)
        assertEquals("1 doc offline local promovido a CreateActive (3)", 1, completed.localPromotedCount)
        assertEquals("1 doc tombstone local preparado (5)", 1, completed.localTombstonesCount)
        assertEquals("1 doc remoto descubierto e insertado vía PULL (7)", 1, completed.remoteDiscoveredCount)
        assertEquals("3 mutaciones pendientes enviadas por PUSH (3, 4, 5)", 3, completed.pushedPendingCount)
        assertFalse("No debe marcarse alreadyMigrated en primera ejecución", completed.alreadyMigrated)

        // --- 5. Verificación estricta de postcondiciones ---

        // A. Recuento total exacto de entidades
        val allLocalBeers = getAllLocalBeers(aliceUid)
        assertEquals("Total de entidades en Room debe ser exactamente 6 (5 activas + 1 borrada)", 6, allLocalBeers.size)
        val activeBeers = beerDao.getActiveBeersSync(aliceUid)
        assertEquals("Total de entidades activas debe ser exactamente 5", 5, activeBeers.size)
        assertEquals(5, completed.totalActiveBeers)

        // B. Preservación estricta de IDs locales y remotos
        val localSyncIds = allLocalBeers.map { it.syncId }.toSet()
        val expectedSyncIds = setOf(syncId1, syncId2, syncId3, syncId4, syncId5, syncId7)
        assertEquals("Los IDs sincronizables deben coincidir exactamente", expectedSyncIds, localSyncIds)

        // C. Ausencia absoluta de duplicados
        val uniqueSyncIdsCount = allLocalBeers.map { it.syncId }.distinct().size
        assertEquals("No debe existir ningún syncId duplicado en la base de datos", allLocalBeers.size, uniqueSyncIdsCount)

        // D. Fotos y sus URIs / URLs preservadas
        // E1: Preserva remotePhotoUrl en formato legacy ("users/uid/beers/id.jpg")
        val finalBeer1 = beerDao.getBeerBySyncId(syncId1, aliceUid)
        assertNotNull(finalBeer1)
        assertEquals("users/$aliceUid/beers/$syncId1.jpg", finalBeer1!!.remotePhotoUrl)
        assertEquals(SyncStatus.SYNCED, finalBeer1.syncStatus)

        // E3: Preserva photoUri local y obtiene remotePhotoUrl versionado
        val finalBeer3 = beerDao.getBeerBySyncId(syncId3, aliceUid)
        assertNotNull(finalBeer3)
        assertEquals(offlinePhotoUri, finalBeer3!!.photoUri)
        assertTrue(
            "remotePhotoUrl debe tener prefijo de Storage versionado",
            finalBeer3.remotePhotoUrl?.startsWith("users/$aliceUid/beers/${syncId3}_") == true
        )
        assertEquals(SyncStatus.SYNCED, finalBeer3.syncStatus)

        // E4: Preserva foto versionada existente y actualiza comentario
        val finalBeer4 = beerDao.getBeerBySyncId(syncId4, aliceUid)
        assertNotNull(finalBeer4)
        assertEquals("users/$aliceUid/beers/${syncId4}_mut-v1-init.jpg", finalBeer4!!.remotePhotoUrl)
        assertEquals("Comentario actualizado offline", finalBeer4.comment)
        assertEquals(SyncStatus.SYNCED, finalBeer4.syncStatus)
        assertNull("dirtyFields debe haberse limpiado tras push confirmado", finalBeer4.dirtyFields)
        assertNull("lastMutationId debe haberse limpiado tras push confirmado", finalBeer4.lastMutationId)

        // E7: Descubierta vía PULL con su foto y metadatos remotos intactos
        val finalBeer7 = beerDao.getBeerBySyncId(syncId7, aliceUid)
        assertNotNull("Cerveza descubierta en servidor debe existir en Room", finalBeer7)
        assertEquals("users/$aliceUid/beers/${syncId7}_mut-remote.jpg", finalBeer7!!.remotePhotoUrl)
        assertEquals("Cerveza creada desde otro dispositivo", finalBeer7.comment)
        assertEquals(BeerType.JARRA, finalBeer7.type)
        assertEquals(SyncStatus.SYNCED, finalBeer7.syncStatus)

        // E. Revisiones incrementadas autoritativas (remoteRevision >= 1 en todas)
        assertTrue("Rev beer1 >= 1", finalBeer1.remoteRevision >= 1L)
        assertTrue("Rev beer2 >= 1", beerDao.getBeerBySyncId(syncId2, aliceUid)!!.remoteRevision >= 1L)
        assertTrue("Rev beer3 >= 1", finalBeer3.remoteRevision >= 1L)
        assertTrue("Rev beer4 >= 1 (esperado 2)", finalBeer4.remoteRevision == 2L)
        assertTrue("Rev beer7 >= 1", finalBeer7.remoteRevision >= 1L)

        // F. Tombstones preservados (no resucitados como activos)
        val finalBeer5 = beerDao.getBeerBySyncId(syncId5, aliceUid)
        assertNotNull(finalBeer5)
        assertEquals("La cerveza borrada debe permanecer en DELETED", SyncStatus.DELETED, finalBeer5!!.syncStatus)
        assertNotNull("deletedAt debe permanecer asignado", finalBeer5.deletedAt)
        assertTrue("Revisión del tombstone debe ser 2", finalBeer5.remoteRevision == 2L)
        assertFalse("El tombstone NO debe figurar en cervezas activas", activeBeers.any { it.syncId == syncId5 })

        // G. Historial de mutaciones completo y con estados coherentes
        val pendingMutations = persistence.historyDao.getPendingMutations(aliceUid)
        assertEquals("No deben quedar mutaciones en estado PENDING", 0, pendingMutations.size)

        // Mutación histórica inicial intacta
        val histRecord = beerDao.getHistoryRecordById(historicalMutationId, aliceUid)
        assertNotNull(histRecord)
        assertEquals(MutationStatus.CONFIRMED, histRecord!!.status)

        // Mutación E4 confirmada
        val histRecord4 = beerDao.getHistoryRecordById(mutationId4, aliceUid)
        assertNotNull(histRecord4)
        assertEquals(MutationStatus.CONFIRMED, histRecord4!!.status)
        assertEquals(2L, histRecord4.confirmedRevision)

        // H. Bloqueo de protocolo remoto escrito y motor en C4_ACTIVE
        coVerify(exactly = 1) { firestoreAdapter.writeAccountProtocolLock(eq(aliceUid), eq(3), any()) }
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)
        assertTrue(C4SyncConfig.isEnabled)
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncStateStore.getEngineMode(context, aliceUid))
        assertEquals("COMPLETED", C4SyncStateStore.getMigrationCheckpoint(context, aliceUid))
    }

    // =========================================================================
    // 2. CASOS DE FALLO Y CONDICIONES LÍMITE
    // =========================================================================

    /**
     * Fallo 1: Interrupción de red a mitad del corte (UNAVAILABLE durante PUSH).
     * Regla fail-safe: Conservar mutaciones en PENDING, registrar estado HALTED,
     * nunca volver automáticamente a Legacy.
     */
    @Test
    fun testFailure_networkInterruptionMidCutover_preservesDataInHalted() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val offlineBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            comment = "Offline esperando push que fallará"
        )
        beerDao.insertBeer(offlineBeer)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        coEvery { firestoreAdapter.readServerDocument(syncId) } throws FirebaseFirestoreException(
            "Network connection lost during cutover push",
            FirebaseFirestoreException.Code.UNAVAILABLE
        )

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Debe retornar Incomplete por fallo de red", result is CutoverResult.Incomplete)
        val incomplete = result as CutoverResult.Incomplete
        assertEquals(1, incomplete.pendingMutationsCount)
        assertEquals(1, incomplete.offlinePushesCount)

        // Verificación de datos intactos en Room
        val localBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(localBeer)
        assertEquals(SyncStatus.PENDING, localBeer!!.syncStatus)
        assertEquals("Offline esperando push que fallará", localBeer.comment)

        val pendingMutations = persistence.historyDao.getPendingMutations(aliceUid)
        assertEquals("La mutación permanece en PENDING para reintento seguro", 1, pendingMutations.size)

        // Fail-safe: C4 no queda activo y el motor permanece en HALTED
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, aliceUid))
        assertEquals("HALTED_RECOVERABLE", C4SyncStateStore.getMigrationCheckpoint(context, aliceUid))
    }

    /**
     * Fallo 2A: Cierre inesperado o error antes de escribir el bloqueo remoto
     * (writeAccountProtocolLock devuelve false).
     * No se activa C4_ACTIVE, se registra checkpoint HALTED_RECOVERABLE_LOCK_FAILED
     * y el motor permanece en HALTED.
     */
    @Test
    fun testFailure_remoteLockFails_persistsHaltedStateAndRefusesC4Active() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val beer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Cerveza para cutover con lock remoto fallido"
        )
        beerDao.insertBeer(beer)

        val remoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "CANA",
            timestamp = 1000L,
            revision = 0L,
            lastMutationId = "",
            fieldRevisions = emptyMap()
        )
        val upgradedRemoteDoc = remoteDoc.copy(
            revision = 1L,
            lastMutationId = "mut-upg",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returnsMany listOf(
            listOf(remoteDoc),
            listOf(upgradedRemoteDoc)
        )
        coEvery { firestoreAdapter.executeLegacyUpgrade(syncId, aliceUid, any()) } returns C4TransactionResult.Success(1L, "mut-upg")
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns upgradedRemoteDoc

        // Simular que el bloqueo remoto falla
        coEvery { firestoreAdapter.writeAccountProtocolLock(aliceUid, any(), any()) } returns false

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Debe retornar Blocked cuando la escritura del lock remoto falla", result is CutoverResult.Blocked)
        val blocked = result as CutoverResult.Blocked
        assertTrue(blocked.reason.contains("bloqueo de protocolo C4"))

        // Motor en HALTED seguro
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, aliceUid))
        assertEquals("HALTED_RECOVERABLE_LOCK_FAILED", C4SyncStateStore.getMigrationCheckpoint(context, aliceUid))
    }

    /**
     * Fallo 2B: Reinicio de proceso tras corte completado con éxito.
     * C4SyncStateStore conserva C4_ACTIVE y checkpoint COMPLETED de forma duradera.
     */
    @Test
    fun testPersistence_afterSuccessfulCutover_persistsActiveStateAcrossProcessRestart() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val beer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Cerveza migrada"
        )
        beerDao.insertBeer(beer)

        val remoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "CANA",
            timestamp = 1000L,
            revision = 0L,
            lastMutationId = "",
            fieldRevisions = emptyMap()
        )
        val upgradedRemoteDoc = remoteDoc.copy(
            revision = 1L,
            lastMutationId = "mut-upg",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returnsMany listOf(
            listOf(remoteDoc),
            listOf(upgradedRemoteDoc)
        )
        coEvery { firestoreAdapter.executeLegacyUpgrade(syncId, aliceUid, any()) } returns C4TransactionResult.Success(1L, "mut-upg")
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns upgradedRemoteDoc
        coEvery { firestoreAdapter.writeAccountProtocolLock(aliceUid, any(), any()) } returns true

        val result = cutoverManager.executeCutover(aliceUid)
        assertTrue(result is CutoverResult.Completed)

        // Simular reinicio de proceso: reset de memoria volátil C4SyncConfig
        C4SyncConfig.reset()
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode) // default en memoria volátil

        // Comprobar que el almacenamiento persistente retiene el estado C4_ACTIVE
        val recoveredMode = C4SyncStateStore.getEngineMode(context, aliceUid)
        val recoveredCheckpoint = C4SyncStateStore.getMigrationCheckpoint(context, aliceUid)
        assertEquals("Al reiniciar, el estado persistente debe ser C4_ACTIVE", SyncEngineMode.C4_ACTIVE, recoveredMode)
        assertEquals("Checkpoint persistente debe ser COMPLETED", "COMPLETED", recoveredCheckpoint)
    }

    /**
     * Fallo 3: Repetición del proceso sobre una cuenta ya migrada (Idempotencia).
     * No duplica entidades, no genera mutaciones adicionales, retorna Completed(alreadyMigrated = true).
     */
    @Test
    fun testIdempotence_repeatedExecutionOnAlreadyMigratedAccount_succeedsWithoutDuplicates() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        // Cerveza ya migrada en Room: revision 1L, baseRevision 1L, SYNCED, sin dirtyFields
        val migratedBeer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            photoUri = null,
            comment = "Cerveza ya migrada previamente",
            locationName = "Local",
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            remotePhotoUrl = null,
            updatedAt = 1000L,
            photoSource = null,
            ownerUid = aliceUid,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null
        )
        beerDao.insertBeer(migratedBeer)

        val modernRemoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            comment = "Cerveza ya migrada previamente",
            remotePhotoUrl = null,
            photoSource = null,
            updatedAt = 1000L,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "mut-prev-init",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns listOf(modernRemoteDoc)
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns modernRemoteDoc
        coEvery { firestoreAdapter.writeAccountProtocolLock(aliceUid, any(), any()) } returns true

        // Ejecutar cutover por segunda vez
        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Debe concluir en Completed", result is CutoverResult.Completed)
        val completed = result as CutoverResult.Completed
        assertTrue("Debe indicar alreadyMigrated = true", completed.alreadyMigrated)
        assertEquals(0, completed.upgradedRemoteCount)
        assertEquals(0, completed.localAlignedCount)
        assertEquals(0, completed.localPromotedCount)
        assertEquals(0, completed.localTombstonesCount)
        assertEquals(0, completed.pushedPendingCount)

        // Comprobación de ausencia de duplicados
        val allBeers = getAllLocalBeers(aliceUid)
        assertEquals(1, allBeers.size)
        assertEquals(1, completed.totalActiveBeers)
    }

    /**
     * Fallo 4: Fallo en la subida de una fotografía durante la migración.
     * La fotografía no puede leerse o Storage falla.
     * La entidad se conserva en PENDING, photoUri se preserva intacto, resultado Incomplete.
     */
    @Test
    fun testFailure_photoUploadFailure_retainsPendingPhotoAndHalts() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val photoUri = "file:///data/user/0/photos/unreadable_photo.jpg"

        // El resolver devuelve null simulando archivo ilegible
        photoResolverHandler = { _ -> null }

        val offlineBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            comment = "Birra con foto corrupta",
            photoUri = photoUri
        )
        beerDao.insertBeer(offlineBeer)

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Debe retornar Incomplete cuando falla la subida de foto", result is CutoverResult.Incomplete)
        val incomplete = result as CutoverResult.Incomplete
        assertEquals(1, incomplete.storageFailuresCount)
        assertEquals(1, incomplete.pendingMutationsCount)

        // Comprobación estricta de preservación
        val beer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull(beer)
        assertEquals(SyncStatus.PENDING, beer!!.syncStatus)
        assertEquals("photoUri debe conservarse intacto", photoUri, beer.photoUri)

        val pendingHistory = persistence.historyDao.getPendingMutations(aliceUid)
        assertEquals(1, pendingHistory.size)
        assertEquals(MutationStatus.PENDING, pendingHistory[0].status)

        // El motor queda HALTED de forma persistente
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, aliceUid))
    }

    /**
     * Fallo 5: Discrepancias entre datos locales y remotos (Cerveza local SYNCED ausente en servidor).
     * Prevalidación aborta inmediatamente sin modificar Firestore ni Room.
     * Cero pérdida de datos, motor detenido en HALTED.
     */
    @Test
    fun testFailure_localSyncedBeerAbsentRemotely_blocksCutoverWithoutDeletingLocalData() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val localSyncedBeer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = aliceUid,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Cerveza local valiosa no subida a Firestore"
        )
        beerDao.insertBeer(localSyncedBeer)

        // Firestore vacío para este usuario
        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns emptyList()

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Prevalidación debe bloquear para evitar pérdida de datos", result is CutoverResult.Blocked)
        val blocked = result as CutoverResult.Blocked
        assertTrue(blocked.reason.contains("Prevalidación BLOQUEADA"))

        // La cerveza local NO fue eliminada ni marcada DELETED
        val preservedBeer = beerDao.getBeerBySyncId(syncId, aliceUid)
        assertNotNull("La cerveza local DEBE preservarse intacta", preservedBeer)
        assertEquals(SyncStatus.SYNCED, preservedBeer!!.syncStatus)
        assertEquals("Cerveza local valiosa no subida a Firestore", preservedBeer.comment)
        assertNull(preservedBeer.deletedAt)

        // Ningún ascenso o lock fue invocado
        coVerify(exactly = 0) { firestoreAdapter.executeLegacyUpgrade(any(), any(), any()) }
        coVerify(exactly = 0) { firestoreAdapter.writeAccountProtocolLock(any(), any(), any()) }

        // El motor permanece HALTED
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
    }

    /**
     * Fallo 6: Documentos remotos corruptos (revisión > 0 con fieldRevisions vacío).
     * Prevalidación defensiva bloquea la migración antes de operar.
     */
    @Test
    fun testFailure_remoteCorruptedDoc_blocksCutoverDefensively() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val corruptRemoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "CANA",
            timestamp = 1000L,
            revision = 1L,
            lastMutationId = "mut-corrupt",
            fieldRevisions = emptyMap() // Corrupto: revision > 0 pero fieldRevisions vacío
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns listOf(corruptRemoteDoc)

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Prevalidación debe bloquear por documento remoto corrupto", result is CutoverResult.Blocked)
        val blocked = result as CutoverResult.Blocked
        assertTrue(blocked.reason.contains("documento(s) remotos corruptos"))

        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
    }

    /**
     * Fallo 7: Intento de rollback o reactivación legacy tras modernización remota.
     * Tras ascender documentos en Firestore a rev >= 1, rollbackToLegacy()
     * detecta el punto de no retorno y prohíbe taxativamente reactivar el motor legacy.
     */
    @Test
    fun testSecurity_legacyClientWriteAttemptAfterCutover_blockedByRollbackBoundary() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val modernDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "CANA",
            timestamp = 1000L,
            revision = 1L,
            lastMutationId = "mut-modern",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns listOf(modernDoc)

        // Estado inicial activo
        C4SyncConfig.isEnabled = true
        C4SyncConfig.engineMode = SyncEngineMode.C4_ACTIVE

        // Invocar rollback
        val rollbackResult = cutoverManager.rollbackToLegacy(aliceUid)

        assertTrue("Local debe revertirse de C4_ACTIVE", rollbackResult.isLocalReverted)
        assertTrue("Debe declarar punto de no retorno alcanzado", rollbackResult.pointOfNoReturnReached)
        assertFalse("NUNCA debe reactivar escrituras legacy", rollbackResult.isLegacyReactivated)
        assertEquals("El motor debe detenerse en HALTED", SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
        assertTrue(rollbackResult.warningMessage.contains("NO se reactivan escrituras legacy"))
    }

    /**
     * Regla Fail-Safe: Ante cualquier excepción inesperada, el sistema detiene el motor
     * en HALTED, registra INTERRUPTED_RECOVERABLE y jamás retrocede a Legacy automáticamente.
     */
    @Test
    fun testFailSafe_unexpectedException_haltsAndNeverRevertsToLegacy() = runBlocking {
        // Simular excepción imprevista durante la prevalidación
        coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } throws RuntimeException("Error catastrófico imprevisto de base de datos")

        val result = cutoverManager.executeCutover(aliceUid)

        assertTrue("Debe retornar Blocked ante excepción", result is CutoverResult.Blocked)
        val blocked = result as CutoverResult.Blocked
        assertTrue(blocked.reason.contains("Prevalidación fallida") || blocked.reason.contains("Excepción inesperada"))

        // Motor detenido en HALTED por seguridad fail-safe
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, aliceUid))
    }

    /**
     * Integración con Base de Datos Legacy Existente (con esquema legacy 0/0) y Recuperación en Cliente B.
     *
     * Demuestra:
     * 1. Una base Room con datos puramente legacy (0/0, sin historial previo en local_mutation_history)
     *    migra íntegramente sin pérdidas hacia el protocolo C4.
     * 2. Todas las entidades preexistentes se alinean o promueven, y las mutaciones se confirman.
     * 3. Recuperación completa desde un cliente con base local totalmente nueva (Cliente B).
     */
    @Test
    fun testMigration_existingLegacyDatabase_preservesAllDataAndRecoversCleanly() = runBlocking {
        val legacyDb = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val syncId1 = "sync-leg-01-synced"
        val syncId2 = "sync-leg-02-offline-pending"
        val syncId3 = "sync-leg-03-deleted"

        val photoBytes = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val photoUri = "file:///data/user/0/com.daniel.contadordebirras/photos/$syncId2.jpg"

        try {
            val legacyDao = legacyDb.beerDao()

            // 1. Sembrar base legacy existente (0/0, sin historial de mutaciones previo)
            val legacyBeer1 = LegacyBeerFixtures.createLegacyRoomBeer(
                ownerUid = aliceUid,
                syncId = syncId1,
                syncStatus = SyncStatus.SYNCED,
                comment = "Cerveza legacy preexistente",
                hasRemotePhoto = true
            )
            legacyDao.insertBeer(legacyBeer1)

            val legacyBeer2 = LegacyBeerFixtures.createLegacyRoomBeer(
                ownerUid = aliceUid,
                syncId = syncId2,
                syncStatus = SyncStatus.PENDING,
                comment = "Cerveza offline preexistente",
                photoUri = photoUri
            )
            legacyDao.insertBeer(legacyBeer2)

            val legacyBeer3 = LegacyBeerFixtures.createLegacyRoomBeer(
                ownerUid = aliceUid,
                syncId = syncId3,
                syncStatus = SyncStatus.DELETED,
                comment = "Cerveza borrada preexistente"
            )
            legacyDao.insertBeer(legacyBeer3)

            // Comprobar que en la base legacy inicial NO hay historial de mutaciones
            assertEquals(0, legacyDb.localMutationHistoryDao().getPendingMutations(aliceUid).size)

            // 2. Mock de respuestas de Firestore & Storage
            val remoteDoc1 = C4FirestoreDocument(
                syncId = syncId1,
                userId = aliceUid,
                type = "CANA",
                timestamp = legacyBeer1.timestamp,
                latitude = legacyBeer1.latitude,
                longitude = legacyBeer1.longitude,
                comment = legacyBeer1.comment,
                remotePhotoUrl = legacyBeer1.remotePhotoUrl,
                updatedAt = legacyBeer1.updatedAt,
                revision = 0L,
                isDeleted = false,
                lastMutationId = "",
                fieldRevisions = emptyMap()
            )
            val upgradedDoc1 = remoteDoc1.copy(
                revision = 1L,
                lastMutationId = "mut-upg-leg-1",
                fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
            )

            val remoteDoc3 = C4FirestoreDocument(
                syncId = syncId3,
                userId = aliceUid,
                type = "CANA",
                timestamp = legacyBeer3.timestamp,
                latitude = legacyBeer3.latitude,
                longitude = legacyBeer3.longitude,
                comment = legacyBeer3.comment,
                remotePhotoUrl = null,
                updatedAt = legacyBeer3.updatedAt,
                revision = 0L,
                isDeleted = false,
                lastMutationId = "",
                fieldRevisions = emptyMap()
            )
            val upgradedDoc3 = remoteDoc3.copy(
                revision = 1L,
                lastMutationId = "mut-upg-leg-3",
                fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
            )

            coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returnsMany listOf(
                listOf(remoteDoc1, remoteDoc3),
                listOf(upgradedDoc1, upgradedDoc3)
            )
            coEvery { firestoreAdapter.executeLegacyUpgrade(syncId1, aliceUid, any()) } returns C4TransactionResult.Success(1L, "mut-upg-leg-1")
            coEvery { firestoreAdapter.executeLegacyUpgrade(syncId3, aliceUid, any()) } returns C4TransactionResult.Success(1L, "mut-upg-leg-3")
            coEvery { firestoreAdapter.readServerDocument(syncId1) } returns upgradedDoc1
            coEvery { firestoreAdapter.readServerDocument(syncId2) } returns null
            coEvery { firestoreAdapter.readServerDocument(syncId3) } returns upgradedDoc3

            coEvery {
                storageAdapter.uploadVersionedPhoto(aliceUid, syncId2, any(), photoBytes, any())
            } answers {
                val mutId = thirdArg<String>()
                C4StorageResult.Success("users/$aliceUid/beers/${syncId2}_$mutId.jpg", alreadyExisted = false)
            }
            coEvery { storageAdapter.photoExists(any()) } returns true

            coEvery {
                firestoreAdapter.executeCreateActive(syncId = syncId2, ownerUid = aliceUid, newDocument = any(), mutationId = any())
            } answers {
                C4TransactionResult.Success(1L, args[3] as String)
            }
            coEvery {
                firestoreAdapter.executeWriteTombstone(syncId = syncId3, ownerUid = aliceUid, mutationId = any(), baseRevision = 1L)
            } answers {
                C4TransactionResult.Success(2L, thirdArg())
            }
            coEvery { firestoreAdapter.writeAccountProtocolLock(eq(aliceUid), eq(3), any()) } returns true

            val legacyPersistence = RoomC4SyncPersistence(legacyDb)
            val legacyCoordinator = AndroidC4SyncCoordinator(
                firestoreAdapter = firestoreAdapter,
                storageAdapter = storageAdapter,
                persistence = legacyPersistence
            )
            val legacyCutoverManager = C4StagingCutoverManager(
                coordinator = legacyCoordinator,
                persistence = legacyPersistence,
                firestoreAdapter = firestoreAdapter,
                context = context,
                photoResolver = { uri -> if (uri == photoUri) photoBytes else null }
            )

            // 3. Ejecutar migración sobre la base existente
            val result = legacyCutoverManager.executeCutover(aliceUid)
            assertTrue("Cutover debe ser Completed", result is CutoverResult.Completed)

            val activeBeers = legacyDao.getActiveBeersSync(aliceUid)
            assertEquals("Deben existir 2 cervezas activas", 2, activeBeers.size)

            val final1 = legacyDao.getBeerBySyncId(syncId1, aliceUid)!!
            assertEquals(SyncStatus.SYNCED, final1.syncStatus)
            assertEquals(1L, final1.remoteRevision)
            assertEquals("users/$aliceUid/beers/$syncId1.jpg", final1.remotePhotoUrl)

            val final2 = legacyDao.getBeerBySyncId(syncId2, aliceUid)!!
            assertEquals(SyncStatus.SYNCED, final2.syncStatus)
            assertEquals(1L, final2.remoteRevision)
            assertEquals(photoUri, final2.photoUri)
            assertTrue(final2.remotePhotoUrl?.startsWith("users/$aliceUid/beers/${syncId2}_") == true)

            val final3 = legacyDao.getBeerBySyncId(syncId3, aliceUid)!!
            assertEquals(SyncStatus.DELETED, final3.syncStatus)
            assertEquals(2L, final3.remoteRevision)

            val pendingMutations = legacyDb.localMutationHistoryDao().getPendingMutations(aliceUid)
            assertEquals("Cero mutaciones pendientes tras migración", 0, pendingMutations.size)

            // 4. Simular recuperación desde Cliente B (base local nueva)
            val clientBDb = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
                .allowMainThreadQueries()
                .build()

            // Remoto post-migración: doc1 (rev 1), doc2 (rev 1), doc3 (rev 2, tombstone)
            val modernDoc2 = C4FirestoreDocument(
                syncId = syncId2,
                userId = aliceUid,
                type = "CANA",
                timestamp = legacyBeer2.timestamp,
                latitude = legacyBeer2.latitude,
                longitude = legacyBeer2.longitude,
                comment = legacyBeer2.comment,
                remotePhotoUrl = final2.remotePhotoUrl,
                photoSource = null,
                updatedAt = legacyBeer2.updatedAt,
                revision = 1L,
                isDeleted = false,
                lastMutationId = "mut-create-leg-2",
                fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
            )
            val tombstoneDoc3 = upgradedDoc3.copy(
                revision = 2L,
                isDeleted = true,
                lastMutationId = "mut-tomb-leg-3",
                fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(2L)
            )

            coEvery { firestoreAdapter.queryServerDocuments(aliceUid) } returns listOf(upgradedDoc1, modernDoc2, tombstoneDoc3)
            coEvery { firestoreAdapter.readServerDocument(syncId1) } returns upgradedDoc1
            coEvery { firestoreAdapter.readServerDocument(syncId2) } returns modernDoc2
            coEvery { firestoreAdapter.readServerDocument(syncId3) } returns tombstoneDoc3

            val clientBPersistence = RoomC4SyncPersistence(clientBDb)
            val clientBCoordinator = AndroidC4SyncCoordinator(
                firestoreAdapter = firestoreAdapter,
                storageAdapter = storageAdapter,
                persistence = clientBPersistence
            )

            // Cliente B ejecuta PULL de colección
            val pullResults = clientBCoordinator.executePullCollection(aliceUid)
            assertTrue("Cliente B debe procesar la colección remota", pullResults.isNotEmpty())

            val clientBActiveBeers = clientBDb.beerDao().getActiveBeersSync(aliceUid)
            assertEquals("Cliente B debe haber recuperado exactamente 2 cervezas activas", 2, clientBActiveBeers.size)

            val clientBBeer1 = clientBDb.beerDao().getBeerBySyncId(syncId1, aliceUid)!!
            assertEquals(SyncStatus.SYNCED, clientBBeer1.syncStatus)
            assertEquals(1L, clientBBeer1.remoteRevision)
            assertEquals("users/$aliceUid/beers/$syncId1.jpg", clientBBeer1.remotePhotoUrl)

            val clientBBeer2 = clientBDb.beerDao().getBeerBySyncId(syncId2, aliceUid)!!
            assertEquals(SyncStatus.SYNCED, clientBBeer2.syncStatus)
            assertEquals(1L, clientBBeer2.remoteRevision)
            assertEquals(final2.remotePhotoUrl, clientBBeer2.remotePhotoUrl)

            clientBDb.close()
        } finally {
            legacyDb.close()
        }
    }
}
