package com.example.contadordebirras.domain

import android.content.Context
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.sync.c4.C4SyncConfig
import com.example.contadordebirras.sync.c4.SyncEngineMode
import com.google.firebase.auth.FirebaseUser
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BeerRepositoryC4PilotTest {

    private lateinit var beerDao: BeerDao
    private lateinit var authRepository: AuthRepository
    private lateinit var context: Context
    private lateinit var syncScheduler: BeerSyncScheduler
    private val currentUserFlow = MutableStateFlow<FirebaseUser?>(null)

    private var currentTime = 1000L
    private var nextMutationId = "mut-c4-001"

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        beerDao = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)

        every { authRepository.currentUser } returns currentUserFlow

        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        val user = mockk<FirebaseUser>(relaxed = true)
        every { user.uid } returns "pilot_user_uid"
        currentUserFlow.value = user

        C4SyncConfig.isEnabled = true
        C4SyncConfig.engineMode = SyncEngineMode.C4_ACTIVE
    }

    @After
    fun tearDown() {
        C4SyncConfig.reset()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createRepository(): BeerRepository {
        val repo = BeerRepository(
            beerDao = beerDao,
            context = context,
            authRepository = authRepository,
            syncScheduler = syncScheduler,
            nowProvider = { currentTime },
            mutationIdProvider = { nextMutationId }
        )
        val expectedUid = currentUserFlow.value?.uid
        if (expectedUid != null) {
            verify(timeout = 2000, exactly = 1) { syncScheduler.requestSync(expectedUid) }
            clearMocks(syncScheduler, answers = false)
        }
        return repo
    }

    @Test
    fun `addBeer in C4 mode records durable mutation in local_mutation_history and requests sync`() = runTest {
        val repo = createRepository()
        currentTime = 5000L
        nextMutationId = "mut-add-c4"

        val historySlot = slot<LocalMutationHistoryEntity>()
        val beerSlot = slot<BeerEntity>()

        every { beerDao.insertHistoryRecord(capture(historySlot)) } returns 1L
        every { beerDao.insertBeer(capture(beerSlot)) } returns 101L

        val resultId = repo.addBeer(BeerType.BOTELLIN, 3000L, 40.4, -3.7, "c4_photo.jpg", "Birra C4 de prueba", "CAMERA")

        assertEquals(101L, resultId)

        // Verify local_mutation_history record
        val history = historySlot.captured
        assertEquals("mut-add-c4", history.mutationId)
        assertEquals("pilot_user_uid", history.ownerUid)
        assertEquals(MutationStatus.PENDING, history.status)
        assertTrue(history.isLocalOnlyCreation)
        assertEquals(0L, history.baseRevision)
        assertEquals(5000L, history.timestamp)
        assertTrue(history.dirtyFields.contains("COMMENT"))
        assertTrue(history.dirtyFields.contains("TYPE"))

        // Verify beer entity
        val beer = beerSlot.captured
        assertEquals(history.syncId, beer.syncId)
        assertEquals("mut-add-c4", beer.lastMutationId)
        assertEquals("pilot_user_uid", beer.ownerUid)
        assertEquals(SyncStatus.PENDING, beer.syncStatus)

        // Verify sync requested
        verify(exactly = 1) { syncScheduler.requestSync("pilot_user_uid") }
    }

    @Test
    fun `addBeer throws IllegalStateException when engine mode is HALTED`() = runTest {
        C4SyncConfig.engineMode = SyncEngineMode.HALTED
        val repo = createRepository()

        try {
            repo.addBeer(BeerType.CANA, 2000L)
            fail("Expected IllegalStateException due to HALTED mode")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("HALTED") == true)
        }

        verify(exactly = 0) { beerDao.insertBeer(any()) }
        verify(exactly = 0) { beerDao.insertHistoryRecord(any()) }
    }

    @Test
    fun `addBeer throws IllegalStateException when user is not authenticated in C4 mode`() = runTest {
        currentUserFlow.value = null
        val repo = createRepository()

        try {
            repo.addBeer(BeerType.CANA, 2000L)
            fail("Expected IllegalStateException due to unauthenticated C4 session")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("sesión de usuario") == true)
        }

        verify(exactly = 0) { beerDao.insertBeer(any()) }
    }

    @Test
    fun `deleteBeer in C4 mode records durable tombstone mutation and requests sync`() = runTest {
        val repo = createRepository()
        currentTime = 6000L
        nextMutationId = "mut-delete-c4"

        val existingBeer = BeerEntity(
            id = 42,
            syncId = "beer-to-delete",
            type = BeerType.CANA,
            timestamp = 1000L,
            ownerUid = "pilot_user_uid",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 2L,
            baseRevision = 2L
        )

        every { beerDao.getBeerById(42, "pilot_user_uid") } returns existingBeer
        val historySlot = slot<LocalMutationHistoryEntity>()
        every { beerDao.insertHistoryRecord(capture(historySlot)) } returns 1L
        every { beerDao.softDeleteBeerLocalMutation(any(), any(), any(), any(), any(), any()) } returns 1

        repo.deleteBeer(existingBeer)

        val history = historySlot.captured
        assertEquals("mut-delete-c4", history.mutationId)
        assertEquals("beer-to-delete", history.syncId)
        assertEquals("pilot_user_uid", history.ownerUid)
        assertEquals(MutationStatus.PENDING, history.status)
        assertFalse(history.isLocalOnlyCreation)
        assertEquals(2L, history.baseRevision)

        verify(exactly = 1) {
            beerDao.softDeleteBeerLocalMutation(
                id = 42,
                deletedAt = 6000L,
                updatedAt = 6000L,
                ownerUid = "pilot_user_uid",
                baseRevision = 2L,
                lastMutationId = "mut-delete-c4"
            )
        }
        verify(exactly = 1) { syncScheduler.requestSync("pilot_user_uid") }
    }

    @Test
    fun `updateBeerDetails in C4 mode records durable patch mutation and requests sync`() = runTest {
        val repo = createRepository()
        currentTime = 7000L
        nextMutationId = "mut-update-c4"

        val existingBeer = BeerEntity(
            id = 42,
            syncId = "beer-to-update",
            type = BeerType.CANA,
            timestamp = 1000L,
            ownerUid = "pilot_user_uid",
            comment = "original",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 3L,
            baseRevision = 3L
        )

        every { beerDao.getBeerById(42, "pilot_user_uid") } returns existingBeer
        val historySlot = slot<LocalMutationHistoryEntity>()
        every { beerDao.insertHistoryRecord(capture(historySlot)) } returns 1L
        every { beerDao.updateBeerDetailsLocalMutation(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1

        repo.updateBeerDetails(
            beerId = 42,
            type = BeerType.PINTA,
            comment = "modificado",
            typeChanged = true,
            commentChanged = true
        )

        val history = historySlot.captured
        assertEquals("mut-update-c4", history.mutationId)
        assertEquals("beer-to-update", history.syncId)
        assertEquals(MutationStatus.PENDING, history.status)
        assertFalse(history.isLocalOnlyCreation)
        assertEquals(3L, history.baseRevision)
        assertTrue(history.dirtyFields.contains("COMMENT"))
        assertTrue(history.dirtyFields.contains("TYPE"))

        verify(exactly = 1) {
            beerDao.updateBeerDetailsLocalMutation(
                id = 42,
                type = BeerType.PINTA,
                comment = "modificado",
                updatedAt = 7000L,
                ownerUid = "pilot_user_uid",
                baseRevision = 3L,
                dirtyFields = "COMMENT,TYPE",
                lastMutationId = "mut-update-c4"
            )
        }
        verify(exactly = 1) { syncScheduler.requestSync("pilot_user_uid") }
    }

    @Test
    fun `addBeer in pilot package fails when authenticated user email is not pilot email`() = runTest {
        every { context.packageName } returns "com.daniel.contadordebirras.c4pilot"
        val foreignUser = mockk<FirebaseUser>(relaxed = true)
        every { foreignUser.uid } returns "foreign_uid"
        every { foreignUser.email } returns "other_user@test.com"
        currentUserFlow.value = foreignUser

        val repo = createRepository()

        try {
            repo.addBeer(BeerType.CANA, 2000L)
            fail("Expected IllegalStateException due to unauthorized email")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("cuenta piloto autorizada") == true)
        }
        verify(exactly = 0) { beerDao.insertBeer(any()) }
    }

    @Test
    fun `addBeer in pilot package fails when mode is not C4_ACTIVE and never executes legacy`() = runTest {
        every { context.packageName } returns "com.daniel.contadordebirras.c4pilot"
        val user = mockk<FirebaseUser>(relaxed = true)
        every { user.uid } returns "pilot_user_uid"
        every { user.email } returns BeerRepository.C4_PILOT_USER_EMAIL
        currentUserFlow.value = user

        C4SyncConfig.engineMode = SyncEngineMode.LEGACY
        val repo = createRepository()

        try {
            repo.addBeer(BeerType.CANA, 2000L)
            fail("Expected IllegalStateException because mode is not C4_ACTIVE in pilot")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("el modo actual no es C4_ACTIVE") == true)
        }
        verify(exactly = 0) { beerDao.insertBeer(any()) }
    }

    @Test
    fun `addBeer in pilot package succeeds when authenticated as pilot user and C4_ACTIVE`() = runTest {
        every { context.packageName } returns "com.daniel.contadordebirras.c4pilot"
        val user = mockk<FirebaseUser>(relaxed = true)
        every { user.uid } returns "pilot_user_uid"
        every { user.email } returns BeerRepository.C4_PILOT_USER_EMAIL
        currentUserFlow.value = user

        C4SyncConfig.isEnabled = true
        C4SyncConfig.engineMode = SyncEngineMode.C4_ACTIVE

        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { context.applicationContext.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } returns SyncEngineMode.C4_ACTIVE.name

        val historySlot = slot<LocalMutationHistoryEntity>()
        val beerSlot = slot<BeerEntity>()
        every { beerDao.insertHistoryRecord(capture(historySlot)) } returns 1L
        every { beerDao.insertBeer(capture(beerSlot)) } returns 55L

        val repo = createRepository()
        val id = repo.addBeer(BeerType.BOTELLIN, 2000L)

        assertEquals(55L, id)
        assertEquals(MutationStatus.PENDING, historySlot.captured.status)
        assertTrue(historySlot.captured.isLocalOnlyCreation)
        verify(exactly = 1) { syncScheduler.requestSync("pilot_user_uid") }
    }
}
