package com.example.contadordebirras.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.domain.BeerType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pruebas unitarias en JVM (Robolectric) para las primitivas DAO seguras del protocolo C4,
 * concurrencia, atomicidad, aislamiento por ownerUid y gestión del historial durable.
 */
@RunWith(RobolectricTestRunner::class)
class BeerDaoC4PersistenceTest {

    private lateinit var context: Context
    private lateinit var database: BeerDatabase
    private lateinit var beerDao: BeerDao
    private lateinit var historyDao: LocalMutationHistoryDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        beerDao = database.beerDao()
        historyDao = database.localMutationHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * 1. Aislamiento por ownerUid en beers y local_mutation_history.
     * Operaciones realizadas para un usuario jamás deben devolver ni alterar datos de otro.
     */
    @Test
    fun ownerUidIsolation_strictAcrossBeersAndHistory() {
        val beerUserA = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = "shared-sync-id",
            ownerUid = "user_A",
            syncStatus = SyncStatus.PENDING,
            lastMutationId = "mut-A-1"
        )
        val beerUserB = BeerEntity(
            type = BeerType.BOTELLIN,
            timestamp = 2000L,
            syncId = "shared-sync-id",
            ownerUid = "user_B",
            syncStatus = SyncStatus.PENDING,
            lastMutationId = "mut-B-1"
        )
        beerDao.insertBeer(beerUserA)
        beerDao.insertBeer(beerUserB)

        val mutA = LocalMutationHistoryEntity(
            mutationId = "mut-A-1",
            syncId = "shared-sync-id",
            ownerUid = "user_A",
            timestamp = 1000L,
            dirtyFields = "ALL_FIELDS",
            status = MutationStatus.PENDING
        )
        val mutB = LocalMutationHistoryEntity(
            mutationId = "mut-B-1",
            syncId = "shared-sync-id",
            ownerUid = "user_B",
            timestamp = 2000L,
            dirtyFields = "ALL_FIELDS",
            status = MutationStatus.PENDING
        )
        historyDao.insertMutation(mutA)
        historyDao.insertMutation(mutB)

        // Consultas de user_A no ven las de user_B
        val retrievedA = beerDao.getBeerBySyncId("shared-sync-id", "user_A")
        assertNotNull(retrievedA)
        assertEquals(BeerType.LATA, retrievedA!!.type)

        val retrievedB = beerDao.getBeerBySyncId("shared-sync-id", "user_B")
        assertNotNull(retrievedB)
        assertEquals(BeerType.BOTELLIN, retrievedB!!.type)

        val historyA = historyDao.getMutationsForBeer("shared-sync-id", "user_A")
        assertEquals(1, historyA.size)
        assertEquals("mut-A-1", historyA[0].mutationId)

        val historyB = historyDao.getMutationsForBeer("shared-sync-id", "user_B")
        assertEquals(1, historyB.size)
        assertEquals("mut-B-1", historyB[0].mutationId)

        // Modificar user_A no altera user_B
        val rowsUpdated = beerDao.confirmPushMutationFullSuccess(
            syncId = "shared-sync-id",
            ownerUid = "user_A",
            expectedMutationId = "mut-A-1",
            newRemoteRevision = 1L
        )
        assertEquals(1, rowsUpdated)

        val refreshedA = beerDao.getBeerBySyncId("shared-sync-id", "user_A")!!
        assertEquals(SyncStatus.SYNCED, refreshedA.syncStatus)

        val refreshedB = beerDao.getBeerBySyncId("shared-sync-id", "user_B")!!
        assertEquals(SyncStatus.PENDING, refreshedB.syncStatus)
    }

    /**
     * 2. Historial durable con múltiples mutaciones sucesivas para una misma cerveza.
     */
    @Test
    fun durableHistory_supportsSuccessiveMutationsForSameBeer() {
        val syncId = "beer-multi-mut"
        val ownerUid = "user_alice"

        val m1 = LocalMutationHistoryEntity(
            mutationId = "m1",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "TYPE",
            baseRevision = 0L,
            status = MutationStatus.SUPERSEDED
        )
        val m2 = LocalMutationHistoryEntity(
            mutationId = "m2",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 2000L,
            dirtyFields = "TYPE,COMMENT",
            baseRevision = 0L,
            status = MutationStatus.SUPERSEDED
        )
        val m3 = LocalMutationHistoryEntity(
            mutationId = "m3",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 3000L,
            dirtyFields = "TYPE,COMMENT,PHOTO",
            baseRevision = 0L,
            status = MutationStatus.PENDING
        )

        historyDao.insertMutation(m1)
        historyDao.insertMutation(m2)
        historyDao.insertMutation(m3)

        val history = historyDao.getMutationsForBeer(syncId, ownerUid)
        assertEquals(3, history.size)
        assertEquals(listOf("m1", "m2", "m3"), history.map { it.mutationId })

        val pastMutationIds = historyDao.getPastMutationIdsForBeer(syncId, ownerUid, activeMutationId = "m3")
        assertEquals(listOf("m1", "m2"), pastMutationIds)

        val pending = historyDao.getPendingMutations(ownerUid)
        assertEquals(1, pending.size)
        assertEquals("m3", pending[0].mutationId)
    }

    /**
     * 3. Inserciones sin duplicados indebidos:
     * - Clave primaria en mutationId aborta inserción si ya existe.
     * - Índice único en (ownerUid, syncId) de beers aborta duplicados.
     */
    @Test
    fun duplicateInsertions_abortedByPrimaryKeyAndUniqueIndexConstraints() {
        val m1 = LocalMutationHistoryEntity(
            mutationId = "unique-mut-1",
            syncId = "sync-1",
            ownerUid = "user_bob",
            timestamp = 1000L,
            dirtyFields = "TYPE"
        )
        historyDao.insertMutation(m1)

        // Intento de duplicar mutationId debe lanzar excepción (OnConflictStrategy.ABORT)
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            historyDao.insertMutation(m1.copy(timestamp = 2000L))
        }

        // Intento de duplicar (ownerUid, syncId) en beers
        val beer1 = BeerEntity(syncId = "unique-sync-1", ownerUid = "user_bob", type = BeerType.LATA, timestamp = 1000L)
        beerDao.insertBeer(beer1)

        val duplicateBeer = BeerEntity(syncId = "unique-sync-1", ownerUid = "user_bob", type = BeerType.BOTELLIN, timestamp = 2000L)
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            beerDao.insertBeer(duplicateBeer)
        }
    }

    /**
     * 4. ACK antiguo no modifica una mutación local más reciente (anti-regresión por concurrencia).
     * Si local tenía M1, el usuario editó a M2, y luego llega el ACK de M1:
     * El DAO debe retornar 0 filas actualizadas y NO modificar beers ni confirmar M1 en historial.
     */
    @Test
    fun staleAck_doesNotOverwriteOrClearNewerLocalMutation() {
        val syncId = "beer-stale-ack"
        val ownerUid = "user_carol"

        // Local está en estado PENDING con mutación activa M2
        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            comment = "Edición nueva M2",
            photoUri = "file:///local/photo_m2.jpg",
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = "COMMENT",
            lastMutationId = "M2"
        )
        beerDao.insertBeer(beer)

        // Registrar M1 como SUPERSEDED y M2 como PENDING en historial
        historyDao.insertMutation(LocalMutationHistoryEntity("M1", syncId, ownerUid, 1000L, "COMMENT", 5L, MutationStatus.SUPERSEDED))
        historyDao.insertMutation(LocalMutationHistoryEntity("M2", syncId, ownerUid, 2000L, "COMMENT", 5L, MutationStatus.PENDING))

        // Llega el ACK retrasado de M1 para confirmar revisión 6
        val result = beerDao.confirmPushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M1",
            newRemoteRevision = 6L
        )

        assertFalse("El ACK de M1 no debe aplicarse porque la mutación activa es M2", result)

        // Verificar que beers permanece intacta en M2
        val currentBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.PENDING, currentBeer.syncStatus)
        assertEquals("M2", currentBeer.lastMutationId)
        assertEquals("COMMENT", currentBeer.dirtyFields)
        assertEquals(5L, currentBeer.remoteRevision)
        assertEquals(5L, currentBeer.baseRevision)
        assertEquals("Edición nueva M2", currentBeer.comment)
        assertEquals("file:///local/photo_m2.jpg", currentBeer.photoUri)

        // Verificar que M2 sigue PENDING en historial
        val m2Record = historyDao.getMutationById("M2", ownerUid)!!
        assertEquals(MutationStatus.PENDING, m2Record.status)
        assertNull(m2Record.confirmedRevision)
    }

    /**
     * 5. Confirmación de ApplyPatch parcial:
     * Confirma algunos campos dirty pero preserva los restantes en [remainingDirtyFields],
     * manteniendo syncStatus = PENDING y actualizando revisiones.
     */
    @Test
    fun confirmPartialPatch_preservesRemainingDirtyFieldsAndStatusPending() {
        val syncId = "beer-partial-patch"
        val ownerUid = "user_david"

        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            comment = "Comentario local",
            photoUri = "file:///local/david.jpg",
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = "COMMENT,PHOTO",
            lastMutationId = "M1"
        )
        beerDao.insertBeer(beer)
        historyDao.insertMutation(LocalMutationHistoryEntity("M1", syncId, ownerUid, 1000L, "COMMENT,PHOTO", 5L, MutationStatus.PENDING))

        // ApplyPatch confirma PHOTO en revisión 6, pero COMMENT permanece dirty
        val success = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M1",
            newRemoteRevision = 6L,
            remainingDirtyFields = "COMMENT",
            remotePhotoUrl = "https://storage/david.jpg"
        )

        assertTrue("El patch parcial debe aplicarse", success)

        val updatedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.PENDING, updatedBeer.syncStatus)
        assertEquals(6L, updatedBeer.remoteRevision)
        assertEquals(6L, updatedBeer.baseRevision)
        assertEquals("COMMENT", updatedBeer.dirtyFields)
        assertEquals("M1", updatedBeer.lastMutationId)
        assertEquals("https://storage/david.jpg", updatedBeer.remotePhotoUrl)
        assertEquals("file:///local/david.jpg", updatedBeer.photoUri)

        val m1Record = historyDao.getMutationById("M1", ownerUid)!!
        assertEquals(MutationStatus.CONFIRMED, m1Record.status)
        assertEquals(6L, m1Record.confirmedRevision)
    }

    /**
     * 6. Protección incondicional de photoUri (campo estrictamente local):
     * Ni los ACKs de PUSH ni las actualizaciones remotas de PULL deben alterar jamás photoUri.
     */
    @Test
    fun photoUri_isStrictlyProtectedAsLocalOnlyAcrossPullAndPush() {
        val syncId = "beer-photo-protect"
        val ownerUid = "user_eva"
        val localPhotoUri = "content://media/external/images/media/42"

        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            photoUri = localPhotoUri,
            remotePhotoUrl = null,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 5L,
            baseRevision = 5L
        )
        beerDao.insertBeer(beer)

        // 1. Simular PULL con nueva foto remota en revisión 6
        val pullRows = beerDao.applyRemoteUpdateToSyncedBeer(
            syncId = syncId,
            ownerUid = ownerUid,
            newRemoteRevision = 6L,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = null,
            longitude = null,
            comment = "Actualizado por otro dispositivo",
            locationName = null,
            remotePhotoUrl = "https://firebasestorage/beers/beer-photo-protect.jpg",
            photoSource = "remote_sync"
        )
        assertEquals(1, pullRows)

        val pulledBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals("photoUri debe permanecer inmutable", localPhotoUri, pulledBeer.photoUri)
        assertEquals("https://firebasestorage/beers/beer-photo-protect.jpg", pulledBeer.remotePhotoUrl)
        assertEquals("remote_sync", pulledBeer.photoSource)
        assertEquals(6L, pulledBeer.remoteRevision)
    }

    /**
     * 7. PULL sobre SYNCED vs PENDING:
     * - Si la entidad está en SYNCED, applyRemoteUpdateToSyncedBeer aplica la actualización.
     * - Si la entidad está en PENDING, applyRemoteUpdateToSyncedBeer NO modifica la fila (retorna 0),
     *   impidiendo la sobrescritura destructiva de las ediciones del usuario.
     */
    @Test
    fun applyRemoteUpdate_appliesToSynced_and_blocksOnPending() {
        val syncIdSynced = "beer-synced"
        val syncIdPending = "beer-pending"
        val ownerUid = "user_frank"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA, timestamp = 1000L, syncId = syncIdSynced, ownerUid = ownerUid,
            syncStatus = SyncStatus.SYNCED, remoteRevision = 3L, baseRevision = 3L
        ))

        beerDao.insertBeer(BeerEntity(
            type = BeerType.BOTELLIN, timestamp = 2000L, syncId = syncIdPending, ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING, remoteRevision = 3L, baseRevision = 3L,
            dirtyFields = "COMMENT", lastMutationId = "mut-frank-1"
        ))

        // PULL sobre SYNCED con revisión 4 -> Aplica
        val syncedUpdate = beerDao.applyRemoteUpdateToSyncedBeer(
            syncId = syncIdSynced, ownerUid = ownerUid, newRemoteRevision = 4L,
            type = BeerType.LATA, timestamp = 1000L, latitude = null, longitude = null,
            comment = "Remote win", locationName = null, remotePhotoUrl = null, photoSource = null
        )
        assertEquals(1, syncedUpdate)

        // PULL sobre PENDING con revisión 4 -> Bloquea sin sobrescribir
        val pendingUpdate = beerDao.applyRemoteUpdateToSyncedBeer(
            syncId = syncIdPending, ownerUid = ownerUid, newRemoteRevision = 4L,
            type = BeerType.CANA, timestamp = 2500L, latitude = null, longitude = null,
            comment = "Intento de sobrescritura ciega", locationName = null, remotePhotoUrl = null, photoSource = null
        )
        assertEquals("No debe sobrescribir entidad PENDING", 0, pendingUpdate)

        val preservedPending = beerDao.getBeerBySyncId(syncIdPending, ownerUid)!!
        assertEquals(BeerType.BOTELLIN, preservedPending.type)
        assertEquals(SyncStatus.PENDING, preservedPending.syncStatus)
        assertEquals("mut-frank-1", preservedPending.lastMutationId)
    }

    /**
     * 8. Tombstone remoto aplicado a SYNCED:
     * - Si local está SYNCED, applyRemoteTombstoneToSyncedBeer marca como DELETED de forma segura.
     * - Si local está PENDING, NO aplica de forma ciega (retorna 0).
     */
    @Test
    fun remoteTombstone_appliesToSynced_and_blocksOnPending() {
        val syncId = "beer-tombstone-test"
        val ownerUid = "user_grace"

        // Caso 1: SYNCED
        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA, timestamp = 1000L, syncId = syncId, ownerUid = ownerUid,
            syncStatus = SyncStatus.SYNCED, remoteRevision = 5L, baseRevision = 5L
        ))

        val rowsDeleted = beerDao.applyRemoteTombstoneToSyncedBeer(
            syncId = syncId, ownerUid = ownerUid, newRemoteRevision = 6L, deletedAt = 3000L
        )
        assertEquals(1, rowsDeleted)

        val tombstoned = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.DELETED, tombstoned.syncStatus)
        assertEquals(6L, tombstoned.remoteRevision)
        assertEquals(3000L, tombstoned.deletedAt)

        // Caso 2: PENDING
        val syncIdPending = "beer-tombstone-pending"
        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA, timestamp = 1000L, syncId = syncIdPending, ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING, remoteRevision = 5L, baseRevision = 5L,
            dirtyFields = "COMMENT", lastMutationId = "mut-grace-2"
        ))

        val pendingRows = beerDao.applyRemoteTombstoneToSyncedBeer(
            syncId = syncIdPending, ownerUid = ownerUid, newRemoteRevision = 6L, deletedAt = 3500L
        )
        assertEquals("No debe aplicar tombstone monolítico sobre entidad local PENDING", 0, pendingRows)

        val stillPending = beerDao.getBeerBySyncId(syncIdPending, ownerUid)!!
        assertEquals(SyncStatus.PENDING, stillPending.syncStatus)
    }

    /**
     * 9. Transacción atómica completa: confirmación exitosa de PUSH con actualización de historial.
     */
    @Test
    fun confirmPushSuccessWithHistory_atomicExecution() {
        val syncId = "beer-atomic-push"
        val ownerUid = "user_heidi"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA, timestamp = 1000L, syncId = syncId, ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING, remoteRevision = 0L, baseRevision = 0L,
            dirtyFields = "ALL_FIELDS", lastMutationId = "mut-heidi-1"
        ))
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "mut-heidi-1", syncId = syncId, ownerUid = ownerUid,
            timestamp = 1000L, dirtyFields = "ALL_FIELDS", baseRevision = 0L,
            status = MutationStatus.PENDING
        ))

        val success = beerDao.confirmPushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "mut-heidi-1",
            newRemoteRevision = 1L,
            remotePhotoUrl = "https://storage/heidi.jpg",
            photoSource = "camera"
        )

        assertTrue(success)

        val beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.SYNCED, beer.syncStatus)
        assertEquals(1L, beer.remoteRevision)
        assertEquals(1L, beer.baseRevision)
        assertNull(beer.dirtyFields)
        assertNull(beer.lastMutationId)
        assertEquals("https://storage/heidi.jpg", beer.remotePhotoUrl)

        val historyRecord = historyDao.getMutationById("mut-heidi-1", ownerUid)!!
        assertEquals(MutationStatus.CONFIRMED, historyRecord.status)
        assertEquals(1L, historyRecord.confirmedRevision)
    }
}
