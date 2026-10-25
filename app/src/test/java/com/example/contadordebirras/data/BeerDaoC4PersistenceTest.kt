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
     * 4. ACK antiguo frente a nueva mutación (anti-regresión por concurrencia).
     * Si local tenía M1, el usuario editó a M2, y luego llega el ACK retrasado de M1:
     * El DAO debe retornar false (0 filas actualizadas) y NO modificar beers ni confirmar M1.
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

        // Verificar que M2 sigue PENDING en historial y M1 sigue SUPERSEDED
        val m1Record = historyDao.getMutationById("M1", ownerUid)!!
        assertEquals(MutationStatus.SUPERSEDED, m1Record.status)
        assertNull(m1Record.confirmedRevision)

        val m2Record = historyDao.getMutationById("M2", ownerUid)!!
        assertEquals(MutationStatus.PENDING, m2Record.status)
        assertNull(m2Record.confirmedRevision)

        // Verificar también la consulta directa confirmPushMutationFullSuccess
        val directResult = beerDao.confirmPushMutationFullSuccess(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M1",
            newRemoteRevision = 6L
        )
        assertEquals(0, directResult)
    }

    /**
     * 5. (Auditoría #1) PATCH parcial aplazado a C4.4:
     * Ninguna ruta parcial puede modificar beers ni historial; se preservan intactos
     * baseRevision causal (5L), dirtyFields ("COMMENT,PHOTO") y photoUri ("file:///local/david.jpg").
     */
    @Test
    fun partialPatch_preservesCausalBaseRevisionAndPendingFields() {
        val syncId = "beer-partial-patch"
        val ownerUid = "user_david"

        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            comment = "Comentario local pendiente",
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

        // Intento de patch parcial vía confirmPartialPatchWithHistory
        val success = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M1",
            newRemoteRevision = 6L,
            confirmedFields = "PHOTO",
            remainingDirtyFields = "COMMENT",
            newRemotePhotoUrl = "https://storage/david.jpg"
        )
        assertFalse("El patch parcial debe estar aplazado formalmente hasta C4.4", success)

        // Intento directo vía confirmPushMutationPartialPatch
        val rowsDirect = beerDao.confirmPushMutationPartialPatch(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M1",
            newRemoteRevision = 6L,
            remainingDirtyFields = "COMMENT",
            newRemotePhotoUrl = "https://storage/david.jpg"
        )
        assertEquals("confirmPushMutationPartialPatch aplazado debe retornar 0", 0, rowsDirect)

        val updatedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.PENDING, updatedBeer.syncStatus)
        assertEquals("remoteRevision debe mantenerse en 5L", 5L, updatedBeer.remoteRevision)
        assertEquals("baseRevision DEBE conservar 5L causal", 5L, updatedBeer.baseRevision)
        assertEquals("COMMENT,PHOTO", updatedBeer.dirtyFields)
        assertEquals("M1", updatedBeer.lastMutationId)
        assertEquals("file:///local/david.jpg", updatedBeer.photoUri)

        val histRecord = historyDao.getMutationById("M1", ownerUid)!!
        assertEquals(MutationStatus.PENDING, histRecord.status)
        assertNull(histRecord.confirmedRevision)
    }

    /**
     * 6. (Auditoría #2) PATCH parcial aplazado no modifica historial ni transiciona prematuramente:
     * La mutación permanece intacta en estado PENDING.
     */
    @Test
    fun partialPatch_marksHistoryAsPartiallyConfirmedNotFullConfirmed() {
        val syncId = "beer-partial-hist"
        val ownerUid = "user_david"

        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = "COMMENT,TYPE",
            lastMutationId = "M_PARTIAL"
        )
        beerDao.insertBeer(beer)
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "M_PARTIAL",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "COMMENT,TYPE",
            baseRevision = 2L,
            status = MutationStatus.PENDING
        ))

        val success = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_PARTIAL",
            newRemoteRevision = 3L,
            confirmedFields = "TYPE",
            remainingDirtyFields = "COMMENT"
        )
        assertFalse("confirmPartialPatchWithHistory aplazado debe retornar false", success)

        val histRecord = historyDao.getMutationById("M_PARTIAL", ownerUid)!!
        assertEquals("El estado en historial debe permanecer PENDING", MutationStatus.PENDING, histRecord.status)
        assertNull(histRecord.confirmedRevision)
    }

    /**
     * 7. (Auditoría #3) ACK completo con historial ausente:
     * Retorna false y la cerveza permanece en PENDING sin modificaciones.
     */
    @Test
    fun confirmPushSuccessWithHistory_failsWhenHistoryMissing() {
        val syncId = "beer-no-history"
        val ownerUid = "user_elena"

        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = "ALL_FIELDS",
            lastMutationId = "mut-orphan"
        )
        beerDao.insertBeer(beer)
        // No insertamos nada en local_mutation_history

        val success = beerDao.confirmPushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "mut-orphan",
            newRemoteRevision = 1L
        )

        assertFalse("Debe fallar porque falta el registro de historial", success)

        val preservedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.PENDING, preservedBeer.syncStatus)
        assertEquals("mut-orphan", preservedBeer.lastMutationId)
        assertEquals(0L, preservedBeer.remoteRevision)
        assertEquals(0L, preservedBeer.baseRevision)
    }

    /**
     * 8. (Auditoría #4) ACK con historial de otro syncId:
     * Retorna false, no confirma ninguna tabla.
     */
    @Test
    fun confirmPushSuccessWithHistory_failsWhenHistoryBelongsToDifferentSyncId() {
        val ownerUid = "user_felix"
        val syncIdA = "beer-AAA"
        val syncIdB = "beer-BBB"
        val mutationId = "mut-cross-sync"

        val beerA = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncIdA,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "COMMENT",
            lastMutationId = mutationId
        )
        beerDao.insertBeer(beerA)

        // Historial con el mismo mutationId pero asignado a syncIdB
        val historyB = LocalMutationHistoryEntity(
            mutationId = mutationId,
            syncId = syncIdB,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "COMMENT",
            baseRevision = 1L,
            status = MutationStatus.PENDING
        )
        historyDao.insertMutation(historyB)

        val success = beerDao.confirmPushSuccessWithHistory(
            syncId = syncIdA,
            ownerUid = ownerUid,
            expectedMutationId = mutationId,
            newRemoteRevision = 2L
        )

        assertFalse("Debe fallar por discrepancia de syncId entre beers e historial", success)

        // Verificar que beerA permanece PENDING
        val refreshedA = beerDao.getBeerBySyncId(syncIdA, ownerUid)!!
        assertEquals(SyncStatus.PENDING, refreshedA.syncStatus)
        assertEquals(mutationId, refreshedA.lastMutationId)

        // Verificar que el historial B sigue PENDING y sin confirmación
        val refreshedHist = historyDao.getMutationById(mutationId, ownerUid)!!
        assertEquals(MutationStatus.PENDING, refreshedHist.status)
        assertNull(refreshedHist.confirmedRevision)
    }

    /**
     * 9. (Auditoría #6) Borrado con mutationId incorrecto:
     * Retorna 0/false, no elimina de beers.
     */
    @Test
    fun deletionSafety_failsOnMismatchedMutationId() {
        val syncId = "beer-del-mismatch"
        val ownerUid = "user_gustav"

        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 3L,
            baseRevision = 3L,
            lastMutationId = "del-mut-correct"
        )
        beerDao.insertBeer(beer)
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "del-mut-correct",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "DELETED",
            baseRevision = 3L,
            status = MutationStatus.PENDING
        ))

        // Intento con expectedMutationId erróneo vía transacción completa
        val successTx = beerDao.confirmDeletePushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "del-mut-WRONG",
            newRemoteRevision = 4L
        )
        assertFalse("Transacción debe retornar false", successTx)

        // Intento con expectedMutationId erróneo vía query directa
        val rowsDirect = beerDao.confirmDeletePushSuccess(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "del-mut-WRONG",
            newRemoteRevision = 4L
        )
        assertEquals("Query directa debe afectar 0 filas", 0, rowsDirect)

        // La cerveza NO debe haber sido eliminada
        val stillExists = beerDao.getBeerBySyncId(syncId, ownerUid)
        assertNotNull("La cerveza no debe haber sido eliminada", stillExists)
        assertEquals(SyncStatus.DELETED, stillExists!!.syncStatus)
    }

    /**
     * 10. (Auditoría #7) Borrado sin evidencia suficiente (o sin historial correspondiente):
     * No elimina la entidad de beers.
     */
    @Test
    fun deletionSafety_failsWithoutSufficientEvidenceOrHistory() {
        val syncId = "beer-del-evidence"
        val ownerUid = "user_hugo"

        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 5L,
            baseRevision = 5L,
            lastMutationId = "del-mut-hugo"
        )
        beerDao.insertBeer(beer)

        // Caso 1: Sin historial correspondiente en local_mutation_history
        val successNoHist = beerDao.confirmDeletePushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "del-mut-hugo",
            newRemoteRevision = 6L
        )
        assertFalse("Debe fallar si falta el historial", successNoHist)
        assertNotNull(beerDao.getBeerBySyncId(syncId, ownerUid))

        // Insertar historial para el Caso 2
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "del-mut-hugo",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "DELETED",
            baseRevision = 5L,
            status = MutationStatus.PENDING
        ))

        // Caso 2: Sin evidencia remota suficiente (newRemoteRevision = 4L < remoteRevision actual 5L)
        val successStaleRev = beerDao.confirmDeletePushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "del-mut-hugo",
            newRemoteRevision = 4L
        )
        assertFalse("Debe fallar si newRemoteRevision es menor a la remota conocida", successStaleRev)

        val rowsStaleDirect = beerDao.confirmDeletePushSuccess(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "del-mut-hugo",
            newRemoteRevision = 4L
        )
        assertEquals(0, rowsStaleDirect)

        assertNotNull("La cerveza debe seguir existiendo intacta", beerDao.getBeerBySyncId(syncId, ownerUid))
    }

    /**
     * 11. (Auditoría #8) Eliminación explícita de foto (clearRemotePhoto = true) vs
     * ausencia de cambio (clearRemotePhoto = false, newRemotePhotoUrl = null) vs nueva URL.
     */
    @Test
    fun photoHandling_explicitClearVsNoChangeVsNewUrl() {
        val ownerUid = "user_irene"

        // 1. Ausencia de cambio (clearRemotePhoto = false, newRemotePhotoUrl = null)
        // La foto remota y el source deben permanecer INTACTOS.
        val syncIdNoChange = "beer-photo-nochange"
        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncIdNoChange,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            remotePhotoUrl = "https://cdn.example.com/original.jpg",
            photoSource = "remote_sync",
            dirtyFields = "COMMENT",
            lastMutationId = "M1"
        ))

        val rowsNoChange = beerDao.confirmPushMutationFullSuccess(
            syncId = syncIdNoChange,
            ownerUid = ownerUid,
            expectedMutationId = "M1",
            newRemoteRevision = 6L,
            newRemotePhotoUrl = null,
            newPhotoSource = null,
            clearRemotePhoto = false
        )
        assertEquals(1, rowsNoChange)

        val beerNoChange = beerDao.getBeerBySyncId(syncIdNoChange, ownerUid)!!
        assertEquals("https://cdn.example.com/original.jpg", beerNoChange.remotePhotoUrl)
        assertEquals("remote_sync", beerNoChange.photoSource)

        // 2. Eliminación explícita de foto (clearRemotePhoto = true)
        // remotePhotoUrl y photoSource deben pasar a NULL.
        val syncIdClear = "beer-photo-clear"
        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncIdClear,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 6L,
            remotePhotoUrl = "https://cdn.example.com/to_delete.jpg",
            photoSource = "remote_sync",
            dirtyFields = "PHOTO",
            lastMutationId = "M2"
        ))

        val rowsClear = beerDao.confirmPushMutationFullSuccess(
            syncId = syncIdClear,
            ownerUid = ownerUid,
            expectedMutationId = "M2",
            newRemoteRevision = 7L,
            clearRemotePhoto = true
        )
        assertEquals(1, rowsClear)

        val beerClear = beerDao.getBeerBySyncId(syncIdClear, ownerUid)!!
        assertNull("remotePhotoUrl debe ser NULL tras clearRemotePhoto = true", beerClear.remotePhotoUrl)
        assertNull("photoSource debe ser NULL tras clearRemotePhoto = true", beerClear.photoSource)

        // 3. Asignación de nueva URL de foto
        val syncIdNewUrl = "beer-photo-newurl"
        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncIdNewUrl,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 7L,
            baseRevision = 7L,
            remotePhotoUrl = "https://cdn.example.com/old.jpg",
            photoSource = "remote_sync",
            dirtyFields = "PHOTO",
            lastMutationId = "M3"
        ))

        val rowsNewUrl = beerDao.confirmPushMutationFullSuccess(
            syncId = syncIdNewUrl,
            ownerUid = ownerUid,
            expectedMutationId = "M3",
            newRemoteRevision = 8L,
            newRemotePhotoUrl = "https://cdn.example.com/updated.jpg",
            newPhotoSource = "firebase_storage",
            clearRemotePhoto = false
        )
        assertEquals(1, rowsNewUrl)

        val beerNewUrl = beerDao.getBeerBySyncId(syncIdNewUrl, ownerUid)!!
        assertEquals("https://cdn.example.com/updated.jpg", beerNewUrl.remotePhotoUrl)
        assertEquals("firebase_storage", beerNewUrl.photoSource)
    }

    /**
     * 12. (Auditoría #9) Regresión de preservación de photoUri como campo estrictamente local
     * a lo largo de PUSH exitoso, PATCH parcial, PULL de actualización y PULL tombstone.
     */
    @Test
    fun photoUri_isStrictlyProtectedAsLocalOnlyAcrossAllOperations() {
        val syncId = "beer-photo-uri-local"
        val ownerUid = "user_juan"
        val localUri = "content://media/external/images/media/999"

        val initialBeer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            photoUri = localUri,
            remotePhotoUrl = null,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "COMMENT,PHOTO",
            lastMutationId = "M_LOCAL"
        )
        beerDao.insertBeer(initialBeer)
        historyDao.insertMutation(LocalMutationHistoryEntity("M_LOCAL", syncId, ownerUid, 1000L, "COMMENT,PHOTO", 1L, MutationStatus.PENDING))

        // A. PATCH parcial: no debe alterar photoUri
        beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_LOCAL",
            newRemoteRevision = 2L,
            confirmedFields = "PHOTO",
            remainingDirtyFields = "COMMENT",
            newRemotePhotoUrl = "https://storage/juan.jpg"
        )
        var beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(localUri, beer.photoUri)

        // B. PUSH full: no debe alterar photoUri
        beerDao.confirmPushMutationFullSuccess(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_LOCAL",
            newRemoteRevision = 3L
        )
        beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(localUri, beer.photoUri)
        assertEquals(SyncStatus.SYNCED, beer.syncStatus)

        // C. PULL actualización remota: no debe alterar photoUri
        beerDao.applyRemoteUpdateToSyncedBeer(
            syncId = syncId,
            ownerUid = ownerUid,
            newRemoteRevision = 4L,
            type = BeerType.BOTELLIN,
            timestamp = 2000L,
            latitude = null,
            longitude = null,
            comment = "Actualizado desde otro teléfono",
            locationName = null,
            remotePhotoUrl = "https://storage/other.jpg",
            photoSource = "other_device"
        )
        beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(localUri, beer.photoUri)
        assertEquals("https://storage/other.jpg", beer.remotePhotoUrl)

        // D. Tombstone remoto: no debe alterar photoUri
        beerDao.applyRemoteTombstoneToSyncedBeer(
            syncId = syncId,
            ownerUid = ownerUid,
            newRemoteRevision = 5L,
            deletedAt = 3000L
        )
        beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(localUri, beer.photoUri)
        assertEquals(SyncStatus.DELETED, beer.syncStatus)
    }

    /**
     * 13. PULL sobre SYNCED vs PENDING:
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
     * 14. Tombstone remoto aplicado a SYNCED:
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
     * 15. Transacción atómica completa: confirmación exitosa de PUSH con actualización de historial.
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
            newRemotePhotoUrl = "https://storage/heidi.jpg",
            newPhotoSource = "camera"
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

    /**
     * 16. (Auditoría C4.1B #1) Borrado físico rechazado sin prueba real de tombstone confirmado:
     * Una revisión remota superior entregada por el llamador (newRemoteRevision = 5L) NO autoriza
     * el borrado físico de la entidad; la fila en beers (syncStatus = DELETED) y su historial
     * durable se preservan íntegramente en la base de datos local.
     */
    @Test
    fun hardDelete_rejectedWithoutRealTombstoneProof_preservesDeletedRowAndHistory() {
        val syncId = "beer-atomic-del"
        val ownerUid = "user_karl"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 4L,
            baseRevision = 4L,
            lastMutationId = "del-karl-1"
        ))
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "del-karl-1",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "DELETED",
            baseRevision = 4L,
            status = MutationStatus.PENDING
        ))

        val success = beerDao.confirmDeletePushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "del-karl-1",
            newRemoteRevision = 5L
        )

        assertFalse("El borrado físico debe ser rechazado sin prueba real de tombstone confirmado", success)

        // La cerveza NO debe haber sido eliminada de beers: se preserva localmente en estado DELETED
        val beer = beerDao.getBeerBySyncId(syncId, ownerUid)
        assertNotNull("La cerveza DELETED debe preservarse intacta en beers", beer)
        assertEquals(SyncStatus.DELETED, beer!!.syncStatus)
        assertEquals("del-karl-1", beer.lastMutationId)
        assertEquals(4L, beer.remoteRevision)

        // El registro de historial debe preservarse en PENDING
        val histRecord = historyDao.getMutationById("del-karl-1", ownerUid)!!
        assertEquals(MutationStatus.PENDING, histRecord.status)
        assertNull(histRecord.confirmedRevision)
    }

    /**
     * 17. (Auditoría C4.1B #1) Borrado físico rechazado sin prueba real de tombstone confirmado:
     * Proporcionar una revisión remota arbitrariamente alta (ej. 100L) no demuestra que Firestore
     * haya confirmado un tombstone y no debe borrar físicamente la fila.
     */
    @Test
    fun borradoFisico_rechazadoSinPruebaRealDeTombstoneConfirmado() {
        val syncId = "beer-del-high-rev"
        val ownerUid = "user_lucas"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 1L,
            baseRevision = 1L,
            lastMutationId = "del-mut-high"
        ))
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "del-mut-high",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "DELETED",
            baseRevision = 1L,
            status = MutationStatus.PENDING
        ))

        // Intento con revisión 100L
        val success = beerDao.confirmDeletePushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "del-mut-high",
            newRemoteRevision = 100L
        )
        assertFalse("El borrado físico debe ser rechazado incondicionalmente antes de C4.3/C4.4", success)

        val preservedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)
        assertNotNull("La entidad DELETED debe preservarse localmente", preservedBeer)
        assertEquals(SyncStatus.DELETED, preservedBeer!!.syncStatus)

        val directResult = beerDao.confirmDeletePushSuccess(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "del-mut-high",
            newRemoteRevision = 100L
        )
        assertEquals("confirmDeletePushSuccess aplazado debe retornar 0", 0, directResult)
    }

    /**
     * 18. (Auditoría C4.1B #2) PATCH no puede eliminar arbitrariamente COMMENT de COMMENT+PHOTO:
     * Si no se proporciona evidencia explícita de confirmación de COMMENT (confirmedFields),
     * o se suministran campos confirmados que no justifican la eliminación de COMMENT, el PATCH debe ser rechazado.
     */
    @Test
    fun patch_cannotArbitrarilyEliminateCommentFromCommentAndPhoto() {
        val syncId = "beer-patch-no-drop"
        val ownerUid = "user_mateo"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            comment = "Notas de cata",
            photoUri = "file:///local/photo.jpg",
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = "COMMENT,PHOTO",
            lastMutationId = "M_DROP_TEST"
        ))
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "M_DROP_TEST",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "COMMENT,PHOTO",
            baseRevision = 2L,
            status = MutationStatus.PENDING
        ))

        // Intento 1: Sin confirmedFields (null) intentando dejar solo PHOTO (eliminando COMMENT a ciegas)
        val successNullConfirmed = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_DROP_TEST",
            newRemoteRevision = 3L,
            remainingDirtyFields = "PHOTO",
            confirmedFields = null
        )
        assertFalse("No se puede eliminar COMMENT sin evidencia explícita de confirmación", successNullConfirmed)

        // Intento 2: Con confirmedFields incoherente (ej. LOCATION en lugar de COMMENT)
        val successWrongConfirmed = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_DROP_TEST",
            newRemoteRevision = 3L,
            remainingDirtyFields = "PHOTO",
            confirmedFields = "LOCATION"
        )
        assertFalse("confirmedFields incoherente no debe permitir eliminar COMMENT", successWrongConfirmed)

        // Verificar que la cerveza en beers conserva intactos sus dirtyFields originales
        val beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals("COMMENT,PHOTO", beer.dirtyFields)
        assertEquals(SyncStatus.PENDING, beer.syncStatus)
        assertEquals(2L, beer.baseRevision)
        assertEquals(2L, beer.remoteRevision)
    }

    /**
     * 19. (Auditoría C4.1B #2) PATCH no puede añadir campos inexistentes o ficticios:
     * Tokens corruptos, desconocidos o que no formaban parte de los dirtyFields originales deben ser rechazados.
     */
    @Test
    fun patch_cannotAddNonExistentFields() {
        val syncId = "beer-patch-fictitious"
        val ownerUid = "user_nicolas"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "COMMENT",
            lastMutationId = "M_FICTITIOUS"
        ))
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "M_FICTITIOUS",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "COMMENT",
            baseRevision = 1L,
            status = MutationStatus.PENDING
        ))

        // Caso A: Campo inventado no existente en enum BeerDirtyField
        val successInvented = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_FICTITIOUS",
            newRemoteRevision = 2L,
            remainingDirtyFields = "UNKNOWN_FIELD",
            confirmedFields = "COMMENT"
        )
        assertFalse("Campo ficticio no debe ser aceptado", successInvented)

        // Caso B: Campo válido del enum pero que NO estaba en los dirtyFields originales de la cerveza
        val successAddedField = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_FICTITIOUS",
            newRemoteRevision = 2L,
            remainingDirtyFields = "LOCATION",
            confirmedFields = "COMMENT"
        )
        assertFalse("Campo no presente originalmente no puede ser añadido vía patch", successAddedField)
    }

    /**
     * 20. (Auditoría C4.1B #2) PATCH parcial con campos restantes vacíos no debe generar un PENDING incoherente:
     * Si remainingDirtyFields está vacío o en blanco, debe rechazarse para evitar un estado PENDING sin dirtyFields.
     */
    @Test
    fun patch_partialWithEmptyRemainingFields_rejectedToAvoidIncoherentPending() {
        val syncId = "beer-patch-empty-rem"
        val ownerUid = "user_oscar"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "COMMENT",
            lastMutationId = "M_EMPTY_TEST"
        ))
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "M_EMPTY_TEST",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "COMMENT",
            baseRevision = 1L,
            status = MutationStatus.PENDING
        ))

        // Intento con remainingDirtyFields vacío
        val successEmpty = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_EMPTY_TEST",
            newRemoteRevision = 2L,
            remainingDirtyFields = "",
            confirmedFields = "COMMENT"
        )
        assertFalse("remainingDirtyFields vacío debe ser rechazado en la ruta parcial", successEmpty)

        // Intento con remainingDirtyFields en blanco (espacios)
        val successBlank = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_EMPTY_TEST",
            newRemoteRevision = 2L,
            remainingDirtyFields = "   ",
            confirmedFields = "COMMENT"
        )
        assertFalse("remainingDirtyFields en blanco debe ser rechazado", successBlank)

        val preservedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.PENDING, preservedBeer.syncStatus)
        assertEquals("COMMENT", preservedBeer.dirtyFields)
    }

    /**
     * 21. (Auditoría C4.1B #3) PARTIALLY_CONFIRMED aparece en las consultas de trabajo pendiente:
     * getPendingMutations debe incluir registros PARTIALLY_CONFIRMED además de PENDING.
     */
    @Test
    fun history_partiallyConfirmedAppearsInPendingWorkQueries() {
        val ownerUid = "user_pablo"

        val mutPending = LocalMutationHistoryEntity(
            mutationId = "mut-pending-1",
            syncId = "beer-pablo-1",
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "TYPE",
            status = MutationStatus.PENDING
        )
        val mutPartial = LocalMutationHistoryEntity(
            mutationId = "mut-partial-1",
            syncId = "beer-pablo-2",
            ownerUid = ownerUid,
            timestamp = 2000L,
            dirtyFields = "COMMENT",
            baseRevision = 1L,
            status = MutationStatus.PARTIALLY_CONFIRMED,
            confirmedRevision = 2L
        )
        val mutConfirmed = LocalMutationHistoryEntity(
            mutationId = "mut-confirmed-1",
            syncId = "beer-pablo-3",
            ownerUid = ownerUid,
            timestamp = 3000L,
            dirtyFields = "PHOTO",
            baseRevision = 1L,
            status = MutationStatus.CONFIRMED,
            confirmedRevision = 2L
        )

        historyDao.insertMutation(mutPending)
        historyDao.insertMutation(mutPartial)
        historyDao.insertMutation(mutConfirmed)

        val pendingWork = historyDao.getPendingMutations(ownerUid)
        assertEquals(2, pendingWork.size)
        val pendingIds = pendingWork.map { it.mutationId }.toSet()
        assertTrue("PENDING debe figurar en trabajo pendiente", pendingIds.contains("mut-pending-1"))
        assertTrue("PARTIALLY_CONFIRMED debe figurar en trabajo pendiente", pendingIds.contains("mut-partial-1"))
        assertFalse("CONFIRMED no debe figurar en trabajo pendiente", pendingIds.contains("mut-confirmed-1"))
    }

    /**
     * 22. (Auditoría C4.1B #3) Mutación PARTIALLY_CONFIRMED puede pasar a SUPERSEDED cuando corresponda:
     * Si el usuario realiza una nueva edición local sobre una entidad que tenía una mutación parcialmente confirmada,
     * esa mutación debe poder transicionar a SUPERSEDED.
     */
    @Test
    fun history_partiallyConfirmedTransitionsToSupersededWhenReplaced() {
        val syncId = "beer-partial-to-superseded"
        val ownerUid = "user_quentin"

        val m1 = LocalMutationHistoryEntity(
            mutationId = "M1_PARTIAL",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "COMMENT,PHOTO",
            baseRevision = 1L,
            status = MutationStatus.PARTIALLY_CONFIRMED,
            confirmedRevision = 2L
        )
        val m2 = LocalMutationHistoryEntity(
            mutationId = "M2_NEW",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 2000L,
            dirtyFields = "COMMENT",
            baseRevision = 1L,
            status = MutationStatus.PENDING
        )
        historyDao.insertMutation(m1)
        historyDao.insertMutation(m2)

        // Caso A: markMutationSuperseded directo
        val updatedRows = historyDao.markMutationSuperseded("M1_PARTIAL", ownerUid)
        assertEquals(1, updatedRows)

        val m1Record = historyDao.getMutationById("M1_PARTIAL", ownerUid)!!
        assertEquals(MutationStatus.SUPERSEDED, m1Record.status)
        assertEquals(2L, m1Record.confirmedRevision) // Preserva el ACK parcial previo

        // Caso B: supersedePriorPendingMutations
        val m3 = LocalMutationHistoryEntity(
            mutationId = "M3_PARTIAL_2",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 3000L,
            dirtyFields = "TYPE",
            status = MutationStatus.PARTIALLY_CONFIRMED
        )
        historyDao.insertMutation(m3)

        val batchSuperseded = historyDao.supersedePriorPendingMutations(syncId, ownerUid, activeMutationId = "M4_ACTIVE")
        assertTrue(batchSuperseded >= 1)

        val m3Record = historyDao.getMutationById("M3_PARTIAL_2", ownerUid)!!
        assertEquals(MutationStatus.SUPERSEDED, m3Record.status)

        // Caso C: beerDao.supersedePriorPendingHistoryRecords
        val m4 = LocalMutationHistoryEntity(
            mutationId = "M4_PARTIAL_3",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 4000L,
            dirtyFields = "LOCATION",
            status = MutationStatus.PARTIALLY_CONFIRMED
        )
        historyDao.insertMutation(m4)

        val daoSuperseded = beerDao.supersedePriorPendingHistoryRecords(syncId, ownerUid, activeMutationId = "M5_FINAL")
        assertTrue(daoSuperseded >= 1)

        val m4Record = historyDao.getMutationById("M4_PARTIAL_3", ownerUid)!!
        assertEquals(MutationStatus.SUPERSEDED, m4Record.status)
    }

    /**
     * 23. (C4.1B Cierre Inseguro #1) Una llamada directa no puede guardar dirtyFields inválidos:
     * La llamada directa a confirmPushMutationPartialPatch no ejecuta updates SQL sin validación y retorna 0.
     */
    @Test
    fun directCall_cannotSaveInvalidDirtyFields() {
        val syncId = "beer-direct-invalid"
        val ownerUid = "user_direct_1"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "COMMENT",
            lastMutationId = "M_DIRECT_INV"
        ))

        val rows = beerDao.confirmPushMutationPartialPatch(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_DIRECT_INV",
            newRemoteRevision = 2L,
            remainingDirtyFields = "INVALID_CORRUPT_FIELD"
        )
        assertEquals("Llamada directa debe retornar 0", 0, rows)

        val preservedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals("COMMENT", preservedBeer.dirtyFields)
        assertEquals(1L, preservedBeer.remoteRevision)
        assertEquals(1L, preservedBeer.baseRevision)
    }

    /**
     * 24. (C4.1B Cierre Inseguro #2) Una llamada directa no puede eliminar COMMENT arbitrariamente:
     * Una entidad con COMMENT,PHOTO no puede ver eliminado COMMENT mediante confirmPushMutationPartialPatch.
     */
    @Test
    fun directCall_cannotArbitrarilyEliminateComment() {
        val syncId = "beer-direct-drop-comment"
        val ownerUid = "user_direct_2"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = "COMMENT,PHOTO",
            lastMutationId = "M_DIRECT_DROP"
        ))

        // Intento directo de guardar solo PHOTO para eliminar COMMENT
        val rows = beerDao.confirmPushMutationPartialPatch(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_DIRECT_DROP",
            newRemoteRevision = 3L,
            remainingDirtyFields = "PHOTO"
        )
        assertEquals("Llamada directa debe retornar 0", 0, rows)

        val preservedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals("COMMENT,PHOTO", preservedBeer.dirtyFields)
    }

    /**
     * 25. (C4.1B Cierre Inseguro #3) Una llamada directa no puede operar sin historial o validación necesaria:
     * Llamadas que carezcan de validación u omitan el contrato transaccional retornan 0 sin tocar beers.
     */
    @Test
    fun directCall_cannotOperateWithoutHistoryOrValidation() {
        val syncId = "beer-direct-no-hist"
        val ownerUid = "user_direct_3"

        beerDao.insertBeer(BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "LOCATION",
            lastMutationId = "M_DIRECT_NO_HIST"
        ))
        // Sin historial registrado

        val rows = beerDao.confirmPushMutationPartialPatch(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_DIRECT_NO_HIST",
            newRemoteRevision = 2L,
            remainingDirtyFields = "LOCATION"
        )
        assertEquals(0, rows)

        val preserved = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals("LOCATION", preserved.dirtyFields)
        assertEquals(1L, preserved.remoteRevision)
    }

    /**
     * 26. (C4.1B Cierre Inseguro #4) Al aplazar el PATCH, ninguna ruta parcial modifica beers ni historial:
     * Tanto confirmPushMutationPartialPatch como confirmPartialPatchWithHistory preservan beers e historial intactos.
     */
    @Test
    fun deferredPartialPatch_noPartialRouteModifiesBeersOrHistory() {
        val syncId = "beer-all-routes-deferred"
        val ownerUid = "user_direct_4"

        val initialBeer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            comment = "Notas de cata iniciales",
            photoUri = "file:///local/preserved.jpg",
            syncId = syncId,
            ownerUid = ownerUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = "COMMENT,PHOTO",
            lastMutationId = "M_DEF_TEST"
        )
        beerDao.insertBeer(initialBeer)
        historyDao.insertMutation(LocalMutationHistoryEntity(
            mutationId = "M_DEF_TEST",
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = 1000L,
            dirtyFields = "COMMENT,PHOTO",
            baseRevision = 5L,
            status = MutationStatus.PENDING
        ))

        // 1. Ruta transaccional aplazada
        val txResult = beerDao.confirmPartialPatchWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_DEF_TEST",
            newRemoteRevision = 6L,
            confirmedFields = "PHOTO",
            remainingDirtyFields = "COMMENT",
            newRemotePhotoUrl = "https://storage/new.jpg"
        )
        assertFalse("confirmPartialPatchWithHistory debe retornar false al estar aplazado", txResult)

        // 2. Ruta directa aplazada
        val directResult = beerDao.confirmPushMutationPartialPatch(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = "M_DEF_TEST",
            newRemoteRevision = 6L,
            remainingDirtyFields = "COMMENT",
            newRemotePhotoUrl = "https://storage/new.jpg"
        )
        assertEquals("confirmPushMutationPartialPatch debe retornar 0 al estar aplazado", 0, directResult)

        // Verificación de beers intacto
        val beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.PENDING, beer.syncStatus)
        assertEquals("COMMENT,PHOTO", beer.dirtyFields)
        assertEquals(5L, beer.remoteRevision)
        assertEquals(5L, beer.baseRevision)
        assertEquals("Notas de cata iniciales", beer.comment)
        assertEquals("file:///local/preserved.jpg", beer.photoUri)
        assertNull(beer.remotePhotoUrl)

        // Verificación de local_mutation_history intacto
        val hist = historyDao.getMutationById("M_DEF_TEST", ownerUid)!!
        assertEquals(MutationStatus.PENDING, hist.status)
        assertNull(hist.confirmedRevision)
    }
}
