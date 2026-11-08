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
 * Valida los 8 escenarios de seguridad exigidos:
 * 1. Recuperación exitosa en cuenta consistente.
 * 2. Mutaciones locales pendientes legítimas (reconocidas y preservadas sin error).
 * 3. Discrepancia de revisiones / regresión remota (bloquea recuperación).
 * 4. Fallo de usuario / email distinto al piloto (bloquea recuperación).
 * 5. Firebase inaccesible / fallo de red en Source.SERVER (bloquea recuperación).
 * 6. Migración previa inconclusa o checkpoints de cutover (bloquea recuperación).
 * 7. Invariante de seguridad: ante cualquier bloqueo NO se modifica el estado persistente ni las mutaciones locales.
 * 8. Transición formal y durable a C4_ACTIVE cuando el diagnóstico es favorable.
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
            auth = auth,
            expectedPilotEmail = pilotEmail
        )
    }

    @After
    fun tearDown() {
        database.close()
        C4SyncConfig.reset()
    }

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
    fun `diagnoseHaltedAccount succeeds with legitimate pending local mutations`() = runBlocking {
        val pendingBeer = BeerEntity(
            id = 2,
            syncId = "beer_pending_1",
            ownerUid = pilotUid,
            type = BeerType.JARRA,
            timestamp = 2000L,
            syncStatus = SyncStatus.PENDING,
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
    fun `diagnoseHaltedAccount blocks recovery when user email is not pilot user email`() = runBlocking {
        every { currentUser.email } returns "attacker@evil.com"

        val result = manager.diagnoseHaltedAccount(pilotUid)

        assertTrue("Debe bloquearse si la cuenta no coincide con el email piloto", result is C4HaltedDiagnosisResult.Blocked)
        val blocked = result as C4HaltedDiagnosisResult.Blocked
        assertTrue(blocked.reason.contains("Cuenta no autorizada"))
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
    fun `diagnoseHaltedAccount blocks recovery when cutover is in progress or checkpoint exists`() = runBlocking {
        // Caso A: CUTOVER_IN_PROGRESS persistido
        C4SyncStateStore.setEngineMode(context, pilotUid, SyncEngineMode.CUTOVER_IN_PROGRESS)
        val resultA = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultA is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultA as C4HaltedDiagnosisResult.Blocked).reason.contains("CUTOVER_IN_PROGRESS"))

        // Caso B: Checkpoint de migración interrumpida
        C4SyncStateStore.setEngineMode(context, pilotUid, SyncEngineMode.HALTED)
        C4SyncStateStore.recordMigrationCheckpoint(context, pilotUid, "CUTOVER_STARTED")
        val resultB = manager.diagnoseHaltedAccount(pilotUid)
        assertTrue(resultB is C4HaltedDiagnosisResult.Blocked)
        assertTrue((resultB as C4HaltedDiagnosisResult.Blocked).reason.contains("checkpoint de cutover inconcluso"))
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
