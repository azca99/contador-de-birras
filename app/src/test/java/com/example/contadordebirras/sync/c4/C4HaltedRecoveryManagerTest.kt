package com.example.contadordebirras.sync.c4

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryDao
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Suite de pruebas unitarias exhaustiva para [C4HaltedRecoveryManager].
 *
 * Valida los requisitos de seguridad reforzados:
 * 1. Estado persistente estrictamente HALTED (rechazando LEGACY, C4_ACTIVE y CUTOVER_IN_PROGRESS).
 * 2. Identidad inmutable del usuario piloto (estrictamente C4SyncConstants.PILOT_USER_EMAIL).
 * 3. Criterio conservador para checkpoints y bloqueo remoto (rechazo de checkpoints desconocidos o incompletos,
 *    bloqueo remoto incompatible, y excepción justificada de piloto en emulador).
 * 4. Integridad exhaustiva:
 *    - Mutaciones PENDING huérfanas o incoherentes con entidades locales.
 *    - Cervezas SYNCED desaparecidas del servidor.
 *    - Verificación rigurosa de tombstones pendientes.
 *    - Ediciones pendientes con revisión incompatible vs. recuperación idempotente demostrable.
 *    - Garantía no destructiva: ante cualquier rechazo, Room, SharedPreferences y Config quedan inalterados.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C4HaltedRecoveryManagerTest {

    private lateinit var context: Context
    private lateinit var database: BeerDatabase
    private lateinit var beerDao: BeerDao
    private lateinit var mutationDao: LocalMutationHistoryDao

    private val firestoreAdapter = mockk<FirestoreC4SyncAdapter>()
    private val auth = mockk<FirebaseAuth>()
    private val currentUser = mockk<FirebaseUser>()

    private val pilotUid = "pilot_uid_123"
    private val pilotEmail = C4SyncConstants.PILOT_USER_EMAIL

    private lateinit var manager: C4HaltedRecoveryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        beerDao = database.beerDao()
        mutationDao = database.localMutationHistoryDao()

        C4SyncConfig.reset()
        C4SyncStateStore.setEngineMode(context, pilotUid, SyncEngineMode.HALTED)
        C4SyncConfig.engineMode = SyncEngineMode.HALTED
        C4SyncConfig.isEnabled = false

        every { auth.currentUser } returns currentUser
        every { currentUser.uid } returns pilotUid
        every { currentUser.email } returns pilotEmail

        every { firestoreAdapter.firestoreHost } returns "10.0.2.2:8888"
        every { firestoreAdapter.expectedProjectId } returns C4SyncConstants.DEMO_PROJECT_ID
        coEvery { firestoreAdapter.readAccountProtocolLock(pilotUid) } returns AccountProtocolLock(
            syncProtocolVersion = "C4",
            minSupportedAppVersionCode = 3L,
            c4MigratedAt = 1000L,
            accountSyncState = "MIGRATED_TO_C4"
        )
        coEvery { firestoreAdapter.queryServerDocuments(pilotUid) } returns emptyList()

        manager = C4HaltedRecoveryManager(
            context = context,
            database = database,
            firestoreAdapter = firestoreAdapter,
            auth = auth
        )
    }

    @After
    fun tearDown() {
        database.close()
        C4SyncConfig.reset()
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Estado persistente estrictamente HALTED
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `diagnoseHaltedAccount blocks recovery when persisted state is not exactly HALTED`() = runBlocking {
        // Caso LEGACY
        C4SyncStateStore.setEngineMode(context, pilotUid, SyncEngineMode.LEGACY)
        val resultLegacy = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultLegacy is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultLegacy as C4HaltedDiagnosisResult.Blocked).reason.contains("LEGACY"))

        // Caso C4_ACTIVE
        C4SyncStateStore.setEngineMode(context, pilotUid, SyncEngineMode.C4_ACTIVE)
        val resultActive = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultActive is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultActive as C4HaltedDiagnosisResult.Blocked).reason.contains("C4_ACTIVE"))

        // Caso CUTOVER_IN_PROGRESS
        C4SyncStateStore.setEngineMode(context, pilotUid, SyncEngineMode.CUTOVER_IN_PROGRESS)
        val resultCutover = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultCutover is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultCutover as C4HaltedDiagnosisResult.Blocked).reason.contains("CUTOVER_IN_PROGRESS"))
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Identidad inmutable del usuario piloto
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `diagnoseHaltedAccount strictly enforces C4SyncConstants PILOT_USER_EMAIL`() = runBlocking {
        every { currentUser.email } returns "attacker@evil.com"

        val result = manager.diagnoseHaltedAccount(pilotUid)

        assertTrue("Debe bloquearse si la cuenta no coincide exactamente con el email piloto oficial", result is C4HaltedDiagnosisResult.Blocked)
        val blocked = result as C4HaltedDiagnosisResult.Blocked
        assertTrue(blocked.reason.contains(C4SyncConstants.PILOT_USER_EMAIL))
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Criterio conservador para checkpoints y bloqueo remoto
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `diagnoseHaltedAccount blocks recovery on unknown or incomplete checkpoint`() = runBlocking {
        // Checkpoint desconocido
        C4SyncStateStore.recordMigrationCheckpoint(context, pilotUid, "UNKNOWN_STAGE_42")
        val resultUnknown = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultUnknown is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultUnknown as C4HaltedDiagnosisResult.Blocked).reason.contains("checkpoint de migración inconcluso o desconocido"))

        // Checkpoint de migración interrumpida
        C4SyncStateStore.recordMigrationCheckpoint(context, pilotUid, "HALTED_RECOVERABLE")
        val resultHaltedRec = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultHaltedRec is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultHaltedRec as C4HaltedDiagnosisResult.Blocked).reason.contains("checkpoint de migración inconcluso o desconocido"))
    }

    @Test
    fun `diagnoseHaltedAccount blocks recovery on incompatible remote account lock`() = runBlocking {
        // Protocolo remoto incompatible
        coEvery { firestoreAdapter.readAccountProtocolLock(pilotUid) } returns AccountProtocolLock(
            syncProtocolVersion = "LEGACY",
            minSupportedAppVersionCode = 1L,
            c4MigratedAt = 0L,
            accountSyncState = "MIGRATED_TO_C4"
        )
        val resultProtocol = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultProtocol is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultProtocol as C4HaltedDiagnosisResult.Blocked).reason.contains("syncProtocolVersion=LEGACY"))

        // Estado de sincronización remoto incompleto
        coEvery { firestoreAdapter.readAccountProtocolLock(pilotUid) } returns AccountProtocolLock(
            syncProtocolVersion = "C4",
            minSupportedAppVersionCode = 3L,
            c4MigratedAt = 1000L,
            accountSyncState = "MIGRATING"
        )
        val resultState = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultState is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultState as C4HaltedDiagnosisResult.Blocked).reason.contains("MIGRATING"))
    }

    @Test
    fun `diagnoseHaltedAccount permits justified exception for pilot account without remote lock`() = runBlocking {
        coEvery { firestoreAdapter.readAccountProtocolLock(pilotUid) } returns null

        val result = manager.diagnoseHaltedAccount(pilotUid)

        assertTrue("La excepción justificada de cuenta piloto sin lock en emulador debe ser autorizada", result is C4HaltedDiagnosisResult.Eligible)
        val check = (result as C4HaltedDiagnosisResult.Eligible).checks.first { it.id == "remote_lock" }
        assertTrue(check.passed)
        assertTrue(check.detail.contains("excepción de cuenta piloto autorizada"))
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Integridad exhaustiva: mutaciones, tombstones, revisiones y documentos remotos
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `diagnoseHaltedAccount blocks recovery when pending mutation is orphan or local beer is SYNCED`() = runBlocking {
        // Caso A: Mutación PENDING huérfana (sin cerveza asociada en Room)
        val orphanMutation = LocalMutationHistoryEntity(
            mutationId = "mut_orphan_1",
            syncId = "beer_ghost",
            ownerUid = pilotUid,
            timestamp = 1000L,
            dirtyFields = BeerDirtyFieldsCodec.encode(setOf(BeerDirtyField.TYPE)) ?: "TYPE",
            baseRevision = 0L,
            status = MutationStatus.PENDING
        )
        mutationDao.insertMutation(orphanMutation)

        val resultOrphan = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultOrphan is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultOrphan as C4HaltedDiagnosisResult.Blocked).reason.contains("Mutación PENDING huérfana"))

        // Limpiar para Caso B
        database.clearAllTables()

        // Caso B: Cerveza marcada SYNCED pero con mutación PENDING activa
        val beer = BeerEntity(
            id = 1,
            syncId = "beer_incoherent",
            ownerUid = pilotUid,
            type = BeerType.CANA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L
        )
        beerDao.insertBeer(beer)
        val mutation = LocalMutationHistoryEntity(
            mutationId = "mut_incoherent_1",
            syncId = "beer_incoherent",
            ownerUid = pilotUid,
            timestamp = 1000L,
            dirtyFields = BeerDirtyFieldsCodec.encode(setOf(BeerDirtyField.TYPE)) ?: "TYPE",
            baseRevision = 1L,
            status = MutationStatus.PENDING
        )
        mutationDao.insertMutation(mutation)

        val resultIncoherent = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultIncoherent is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultIncoherent as C4HaltedDiagnosisResult.Blocked).reason.contains("Incoherencia local"))
    }

    @Test
    fun `diagnoseHaltedAccount blocks recovery when SYNCED beer disappeared from server`() = runBlocking {
        val syncedBeer = BeerEntity(
            id = 1,
            syncId = "beer_synced_vanished",
            ownerUid = pilotUid,
            type = BeerType.CANA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 2L
        )
        beerDao.insertBeer(syncedBeer)

        // El servidor devuelve lista vacía: la cerveza SYNCED desapareció sin explicación
        coEvery { firestoreAdapter.queryServerDocuments(pilotUid) } returns emptyList()

        val result = manager.diagnoseHaltedAccount(pilotUid)

        assertTrue("Debe bloquearse si una cerveza SYNCED no existe en el servidor", result is C4HaltedDiagnosisResult.Blocked)
        val blocked = result as C4HaltedDiagnosisResult.Blocked
        assertTrue(blocked.reason.contains("Cerveza SYNCED desaparecida del servidor"))
    }

    @Test
    fun `diagnoseHaltedAccount verifies pending tombstones`() = runBlocking {
        // Caso A: Cerveza DELETED sin mutación en historial (tombstone huérfano)
        val orphanTombstone = BeerEntity(
            id = 1,
            syncId = "beer_tomb_orphan",
            ownerUid = pilotUid,
            type = BeerType.CANA,
            timestamp = 1000L,
            syncStatus = SyncStatus.DELETED,
            deletedAt = 1500L,
            baseRevision = 1L,
            lastMutationId = "mut_tomb_1"
        )
        beerDao.insertBeer(orphanTombstone)

        val resultOrphan = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultOrphan is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultOrphan as C4HaltedDiagnosisResult.Blocked).reason.contains("Tombstone local huérfano"))

        // Añadir mutación para corregir el caso huérfano
        val mutation = LocalMutationHistoryEntity(
            mutationId = "mut_tomb_1",
            syncId = "beer_tomb_orphan",
            ownerUid = pilotUid,
            timestamp = 1500L,
            dirtyFields = "isDeleted",
            baseRevision = 1L,
            status = MutationStatus.PENDING
        )
        mutationDao.insertMutation(mutation)

        // Caso B: El documento remoto fue modificado concurrentemente (revisión 2 > baseRevision 1)
        val remoteDoc = C4FirestoreDocument(
            syncId = "beer_tomb_orphan",
            userId = pilotUid,
            type = BeerType.CANA.name,
            timestamp = 1000L,
            revision = 2L,
            isDeleted = false,
            lastMutationId = "mut_remote_concurrent"
        )
        coEvery { firestoreAdapter.queryServerDocuments(pilotUid) } returns listOf(remoteDoc)

        val resultConflict = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultConflict is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultConflict as C4HaltedDiagnosisResult.Blocked).reason.contains("Conflicto en tombstone pendiente"))
    }

    @Test
    fun `diagnoseHaltedAccount handles pending edits with incompatible revision vs demonstrable idempotent recovery`() = runBlocking {
        val pendingEdit = BeerEntity(
            id = 1,
            syncId = "beer_edit_1",
            ownerUid = pilotUid,
            type = BeerType.CANA,
            timestamp = 1000L,
            syncStatus = SyncStatus.PENDING,
            baseRevision = 1L,
            remoteRevision = 1L,
            lastMutationId = "mut_edit_local"
        )
        beerDao.insertBeer(pendingEdit)

        val mutation = LocalMutationHistoryEntity(
            mutationId = "mut_edit_local",
            syncId = "beer_edit_1",
            ownerUid = pilotUid,
            timestamp = 1200L,
            dirtyFields = BeerDirtyFieldsCodec.encode(setOf(BeerDirtyField.COMMENT)) ?: "COMMENT",
            baseRevision = 1L,
            status = MutationStatus.PENDING
        )
        mutationDao.insertMutation(mutation)

        // Escenario Incompatible: servidor avanzó a revisión 2 con una mutación distinta
        val conflictingDoc = C4FirestoreDocument(
            syncId = "beer_edit_1",
            userId = pilotUid,
            type = BeerType.CANA.name,
            timestamp = 1000L,
            revision = 2L,
            lastMutationId = "mut_remote_other"
        )
        coEvery { firestoreAdapter.queryServerDocuments(pilotUid) } returns listOf(conflictingDoc)

        val resultIncompatible = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue("Revisión incompatible debe bloquear", resultIncompatible is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultIncompatible as C4HaltedDiagnosisResult.Blocked).reason.contains("Edición pendiente con revisión incompatible"))

        // Escenario Favorable: recuperación idempotente demostrable (servidor avanzó pero con el mismo lastMutationId)
        val idempotentDoc = conflictingDoc.copy(lastMutationId = "mut_edit_local")
        coEvery { firestoreAdapter.queryServerDocuments(pilotUid) } returns listOf(idempotentDoc)

        val resultIdempotent = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue("Recuperación idempotente demostrable debe ser Eligible", resultIdempotent is C4HaltedDiagnosisResult.Eligible)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Escenarios generales y garantías de no modificación
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `diagnoseHaltedAccount succeeds when local and remote data are consistent`() = runBlocking {
        val beer = BeerEntity(
            id = 1,
            syncId = "beer_1",
            ownerUid = pilotUid,
            type = BeerType.CANA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L
        )
        beerDao.insertBeer(beer)

        val remoteDoc = C4FirestoreDocument(
            syncId = "beer_1",
            userId = pilotUid,
            type = BeerType.CANA.name,
            timestamp = 1000L,
            revision = 1L,
            lastMutationId = "mut_1"
        )
        coEvery { firestoreAdapter.queryServerDocuments(pilotUid) } returns listOf(remoteDoc)

        val result = manager.diagnoseHaltedAccount(pilotUid)

        assertTrue("El diagnóstico debería ser Eligible", result is C4HaltedDiagnosisResult.Eligible)
        val eligible = result as C4HaltedDiagnosisResult.Eligible
        assertEquals(1, eligible.localBeersCount)
        assertEquals(0, eligible.pendingMutationsCount)
        assertEquals(1, eligible.remoteDocumentsCount)
        assertTrue(eligible.checks.all { it.passed })
    }

    @Test
    fun `diagnoseHaltedAccount succeeds with legitimate pending local creations`() = runBlocking {
        val pendingBeer = BeerEntity(
            id = 2,
            syncId = "beer_pending_1",
            ownerUid = pilotUid,
            type = BeerType.JARRA,
            timestamp = 2000L,
            syncStatus = SyncStatus.PENDING,
            baseRevision = 0L,
            remoteRevision = 0L,
            lastMutationId = "mut_pending_1"
        )
        beerDao.insertBeer(pendingBeer)

        val mutation = LocalMutationHistoryEntity(
            mutationId = "mut_pending_1",
            syncId = "beer_pending_1",
            ownerUid = pilotUid,
            timestamp = 2000L,
            dirtyFields = BeerDirtyFieldsCodec.encode(setOf(BeerDirtyField.TYPE)) ?: "TYPE",
            baseRevision = 0L,
            status = MutationStatus.PENDING
        )
        mutationDao.insertMutation(mutation)

        val result = manager.diagnoseHaltedAccount(pilotUid)

        assertTrue("Mutaciones pendientes legítimas no deben considerarse error", result is C4HaltedDiagnosisResult.Eligible)
        val eligible = result as C4HaltedDiagnosisResult.Eligible
        assertEquals(1, eligible.localBeersCount)
        assertEquals(1, eligible.pendingMutationsCount)
        assertEquals(0, eligible.remoteDocumentsCount)
    }

    @Test
    fun `diagnoseHaltedAccount blocks recovery when remote document has revision regression`() = runBlocking {
        val beer = BeerEntity(
            id = 3,
            syncId = "beer_regressed",
            ownerUid = pilotUid,
            type = BeerType.CANA,
            timestamp = 3000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 3L
        )
        beerDao.insertBeer(beer)

        // Servidor tiene una revisión inferior (2L < 3L), violando monotonía
        val remoteDoc = C4FirestoreDocument(
            syncId = "beer_regressed",
            userId = pilotUid,
            type = BeerType.CANA.name,
            timestamp = 3000L,
            revision = 2L,
            lastMutationId = "mut_old"
        )
        coEvery { firestoreAdapter.queryServerDocuments(pilotUid) } returns listOf(remoteDoc)

        val result = manager.diagnoseHaltedAccount(pilotUid)

        assertTrue("Debe bloquearse ante regresión de revisión remota", result is C4HaltedDiagnosisResult.Blocked)
        val blocked = result as C4HaltedDiagnosisResult.Blocked
        assertTrue(blocked.reason.contains("Inconsistencia de monotonía"))
    }

    @Test
    fun `diagnoseHaltedAccount blocks recovery when Firebase is inaccessible`() = runBlocking {
        coEvery {
            firestoreAdapter.readAccountProtocolLock(pilotUid)
        } throws FirebaseFirestoreException("Fallo de conexión", FirebaseFirestoreException.Code.UNAVAILABLE)

        val result = manager.diagnoseHaltedAccount(pilotUid)

        assertTrue("Debe bloquearse si falla la lectura autoritativa Source.SERVER", result is C4HaltedDiagnosisResult.Blocked)
        val blocked = result as C4HaltedDiagnosisResult.Blocked
        assertTrue(blocked.reason.contains("Fallo de comunicación con Firestore Emulator"))
    }

    @Test
    fun `recoverHaltedAccount does not modify state store or config when diagnosis blocks`() = runBlocking {
        every { currentUser.email } returns "unauthorized_user@external.com"

        val mutation = LocalMutationHistoryEntity(
            mutationId = "mut_protected_1",
            syncId = "beer_protected_1",
            ownerUid = pilotUid,
            timestamp = 1000L,
            dirtyFields = BeerDirtyFieldsCodec.encode(setOf(BeerDirtyField.TYPE)) ?: "TYPE",
            baseRevision = 0L,
            status = MutationStatus.PENDING
        )
        mutationDao.insertMutation(mutation)

        val recoveryResult = manager.recoverHaltedAccount(pilotUid)

        assertTrue("La recuperación debe ser rechazada", recoveryResult is C4HaltedRecoveryResult.Blocked)
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, pilotUid))
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)

        // Las mutaciones locales deben permanecer completamente intactas
        val pendingMutations = mutationDao.getPendingMutations(pilotUid)
        assertEquals(1, pendingMutations.size)
        assertEquals("mut_protected_1", pendingMutations[0].mutationId)
    }

    @Test
    fun `recoverHaltedAccount transitions to C4_ACTIVE when diagnosis is eligible`() = runBlocking {
        val recoveryResult = manager.recoverHaltedAccount(pilotUid)

        assertTrue("La recuperación debe ser exitosa", recoveryResult is C4HaltedRecoveryResult.Success)
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncStateStore.getEngineMode(context, pilotUid))
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)
        assertTrue(C4SyncConfig.isEnabled)
    }
}
