package com.example.contadordebirras.domain

import android.content.Context
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BeerRepositoryMutationTest {

    private lateinit var beerDao: BeerDao
    private lateinit var authRepository: AuthRepository
    private lateinit var context: Context
    private lateinit var syncScheduler: BeerSyncScheduler
    private val currentUserFlow = MutableStateFlow<FirebaseUser?>(null)

    private var currentTime = 1000L
    private var nextMutationId = "mutation-1"

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
        every { user.uid } returns "user123"
        currentUserFlow.value = user
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createRepositoryAndClearMocks(
        customNowProvider: (() -> Long)? = null,
        customMutationIdProvider: (() -> String)? = null
    ): BeerRepository {
        val repo = BeerRepository(
            beerDao = beerDao,
            context = context,
            authRepository = authRepository,
            syncScheduler = syncScheduler,
            nowProvider = customNowProvider ?: { currentTime },
            mutationIdProvider = customMutationIdProvider ?: { nextMutationId }
        )
        verify(timeout = 2000, exactly = 1) { syncScheduler.requestSync("user123") }
        clearMocks(syncScheduler, answers = false)
        return repo
    }

    @Test
    fun `addBeer initializes full local mutation metadata`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 5000L
        nextMutationId = "mut-add-123"

        val entitySlot = slot<BeerEntity>()
        every { beerDao.insertBeer(capture(entitySlot)) } returns 1L

        val id = repo.addBeer(BeerType.BOTELLIN, 2000L, 40.0, -3.0, "photo.jpg", "rico", "camera")
        assertEquals(1L, id)

        val entity = entitySlot.captured
        assertEquals(BeerType.BOTELLIN, entity.type)
        assertEquals(2000L, entity.timestamp)
        assertEquals(SyncStatus.PENDING, entity.syncStatus)
        assertEquals(0L, entity.remoteRevision)
        assertEquals(0L, entity.baseRevision)
        assertEquals("COMMENT,LOCATION,PHOTO,TIMESTAMP,TYPE", entity.dirtyFields)
        assertEquals("mut-add-123", entity.lastMutationId)
        assertNull(entity.deletedAt)
        assertEquals(5000L, entity.updatedAt)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerDetails on SYNCED entity sets baseRevision to remoteRevision and encodes only changed fields`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 6000L
        nextMutationId = "mut-edit-1"

        val syncedBeer = BeerEntity(
            id = 42,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "Initial comment",
            photoUri = "photo.jpg",
            remotePhotoUrl = "https://storage/pic.jpg",
            photoSource = "gallery",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(42, "user123") } returns syncedBeer

        val updateSlot = slot<String>()
        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 42,
                type = BeerType.LATA,
                comment = "Updated comment",
                updatedAt = 6000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = capture(updateSlot)
            )
        } returns 1

        repo.updateBeerDetails(42L, BeerType.LATA, "Updated comment", typeChanged = false, commentChanged = true)

        assertEquals(5L, baseRevSlot.captured)
        assertEquals("COMMENT", dirtyFieldsSlot.captured)
        assertEquals("mut-edit-1", updateSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerDetails on PENDING entity preserves baseRevision and merges dirtyFields`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 7000L
        nextMutationId = "mut-edit-2"

        val pendingBeer = BeerEntity(
            id = 42,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "Updated comment",
            photoUri = "photo.jpg",
            remotePhotoUrl = "https://storage/pic.jpg",
            photoSource = "gallery",
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 3L,
            dirtyFields = "COMMENT",
            lastMutationId = "mut-edit-1",
            deletedAt = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(42, "user123") } returns pendingBeer

        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 42,
                type = BeerType.JARRA,
                comment = "Updated comment",
                updatedAt = 7000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = "mut-edit-2"
            )
        } returns 1

        repo.updateBeerDetails(42L, BeerType.JARRA, "Updated comment", typeChanged = true, commentChanged = false)

        assertEquals(3L, baseRevSlot.captured)
        assertEquals("COMMENT,TYPE", dirtyFieldsSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerDetails with identical data is a clean no-op`() = runTest {
        var mutationIdCallCount = 0
        val repo = createRepositoryAndClearMocks(
            customMutationIdProvider = {
                mutationIdCallCount++
                "unexpected-mut"
            }
        )

        val syncedBeer = BeerEntity(
            id = 42,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "Initial",
            photoUri = "photo.jpg",
            remotePhotoUrl = null,
            photoSource = "camera",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(42, "user123") } returns syncedBeer

        // Try updating with identical data
        repo.updateBeerDetails(42L, syncedBeer.type, syncedBeer.comment, typeChanged = true, commentChanged = true)

        assertEquals(0, mutationIdCallCount)
        verify(exactly = 0) { beerDao.updateBeerDetailsLocalMutation(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }

    @Test
    fun `updateBeerLocation with identical coordinates and name is no-op`() = runTest {
        var mutationIdCallCount = 0
        val repo = createRepositoryAndClearMocks(
            customMutationIdProvider = {
                mutationIdCallCount++
                "unexpected-mut"
            }
        )

        val beer = BeerEntity(
            id = 10,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.4168,
            longitude = -3.7038,
            locationName = "Ubicación desconocida",
            ownerUid = "user123",
            syncStatus = SyncStatus.SYNCED
        )
        every { beerDao.getBeerById(10, "user123") } returns beer

        repo.updateBeerLocation(10L, 40.4168, -3.7038)

        assertEquals(0, mutationIdCallCount)
        verify(exactly = 0) { beerDao.updateBeerLocationLocalMutation(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }

    @Test
    fun `updateBeerLocation with changed coordinates records LOCATION dirty field`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 8000L
        nextMutationId = "mut-loc-1"

        val syncedBeer = BeerEntity(
            id = 10,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 4L,
            baseRevision = 4L,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(10, "user123") } returns syncedBeer
        every {
            beerDao.updateBeerLocationLocalMutation(
                id = 10,
                latitude = 41.0,
                longitude = -2.0,
                locationName = any(),
                updatedAt = 8000L,
                ownerUid = "user123",
                baseRevision = 4L,
                dirtyFields = "LOCATION",
                lastMutationId = "mut-loc-1"
            )
        } returns 1

        repo.updateBeerLocation(10L, 41.0, -2.0)

        verify(exactly = 1) {
            beerDao.updateBeerLocationLocalMutation(
                id = 10,
                latitude = 41.0,
                longitude = -2.0,
                locationName = any(),
                updatedAt = 8000L,
                ownerUid = "user123",
                baseRevision = 4L,
                dirtyFields = "LOCATION",
                lastMutationId = "mut-loc-1"
            )
        }
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `deleteBeer on SYNCED entity sets baseRevision to remoteRevision and assigns deletedAt`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 9000L
        nextMutationId = "mut-del-1"

        val syncedBeer = BeerEntity(
            id = 15,
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 8L,
            baseRevision = 8L,
            dirtyFields = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(15, "user123") } returns syncedBeer
        every {
            beerDao.softDeleteBeerLocalMutation(
                id = 15,
                updatedAt = 9000L,
                deletedAt = 9000L,
                ownerUid = "user123",
                baseRevision = 8L,
                lastMutationId = "mut-del-1"
            )
        } returns 1

        repo.deleteBeer(syncedBeer)

        verify(exactly = 1) {
            beerDao.softDeleteBeerLocalMutation(
                id = 15,
                updatedAt = 9000L,
                deletedAt = 9000L,
                ownerUid = "user123",
                baseRevision = 8L,
                lastMutationId = "mut-del-1"
            )
        }
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `deleteBeer on PENDING entity preserves baseRevision`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 9500L
        nextMutationId = "mut-del-2"

        val pendingBeer = BeerEntity(
            id = 15,
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 8L,
            baseRevision = 3L,
            dirtyFields = "COMMENT",
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(15, "user123") } returns pendingBeer
        every {
            beerDao.softDeleteBeerLocalMutation(
                id = 15,
                updatedAt = 9500L,
                deletedAt = 9500L,
                ownerUid = "user123",
                baseRevision = 3L,
                lastMutationId = "mut-del-2"
            )
        } returns 1

        repo.deleteBeer(pendingBeer)

        verify(exactly = 1) {
            beerDao.softDeleteBeerLocalMutation(
                id = 15,
                updatedAt = 9500L,
                deletedAt = 9500L,
                ownerUid = "user123",
                baseRevision = 3L,
                lastMutationId = "mut-del-2"
            )
        }
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `deleteBeer on already DELETED entity is a no-op`() = runTest {
        val repo = createRepositoryAndClearMocks()

        val deletedBeer = BeerEntity(
            id = 15,
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.DELETED,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(15, "user123") } returns deletedBeer

        repo.deleteBeer(deletedBeer)

        verify(exactly = 0) { beerDao.softDeleteBeerLocalMutation(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }

    @Test
    fun `updateBeerDetails same field twice does not duplicate dirty field, preserves baseRevision, and gets new mutationId`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 10000L
        nextMutationId = "mut-comment-2"

        val pendingBeer = BeerEntity(
            id = 42,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "First edit",
            photoUri = "photo.jpg",
            remotePhotoUrl = "https://storage/pic.jpg",
            photoSource = "gallery",
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 3L,
            dirtyFields = "COMMENT",
            lastMutationId = "mut-comment-1",
            deletedAt = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(42, "user123") } returns pendingBeer

        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        val mutationIdSlot = slot<String>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 42,
                type = BeerType.LATA,
                comment = "Second edit",
                updatedAt = 10000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = capture(mutationIdSlot)
            )
        } returns 1

        repo.updateBeerDetails(42L, BeerType.LATA, "Second edit", typeChanged = false, commentChanged = true)

        assertEquals(3L, baseRevSlot.captured)
        assertEquals("COMMENT", dirtyFieldsSlot.captured)
        assertEquals("mut-comment-2", mutationIdSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerDetails preserves location when UI had stale location snapshot`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 12000L
        nextMutationId = "mut-stale-loc"

        // Estado ACTUAL Room:
        // type = LATA, comment = "old", latitude = 40.0, longitude = -3.0, locationName = "Madrid"
        val roomBeer = BeerEntity(
            id = 100,
            type = BeerType.LATA,
            timestamp = 5000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "old",
            photoUri = null,
            remotePhotoUrl = null,
            photoSource = null,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(100, "user123") } returns roomBeer

        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        val mutationIdSlot = slot<String>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 100,
                type = BeerType.LATA,
                comment = "new",
                updatedAt = 12000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = capture(mutationIdSlot)
            )
        } returns 1

        repo.updateBeerDetails(100L, BeerType.LATA, "new", typeChanged = false, commentChanged = true)

        // Esperado: dirtyFields = COMMENT (NO LOCATION)
        assertEquals(4L, baseRevSlot.captured)
        assertEquals("COMMENT", dirtyFieldsSlot.captured)
        assertEquals("mut-stale-loc", mutationIdSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
        verify(exactly = 0) { beerDao.updateBeerLocationLocalMutation(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateBeerDetails preserves photo when UI had stale photo snapshot`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 13000L
        nextMutationId = "mut-stale-photo"

        // Estado ACTUAL Room:
        // remotePhotoUrl = "users/u/beers/x.jpg", photoUri = "content://local", photoSource = "CAMERA"
        val roomBeer = BeerEntity(
            id = 200,
            type = BeerType.LATA,
            timestamp = 5000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "some comment",
            photoUri = "content://local",
            remotePhotoUrl = "users/u/beers/x.jpg",
            photoSource = "CAMERA",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(200, "user123") } returns roomBeer

        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        val mutationIdSlot = slot<String>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 200,
                type = BeerType.JARRA,
                comment = "some comment",
                updatedAt = 13000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = capture(mutationIdSlot)
            )
        } returns 1

        repo.updateBeerDetails(200L, BeerType.JARRA, "some comment", typeChanged = true, commentChanged = false)

        // Esperado: dirtyFields = TYPE (NO PHOTO)
        assertEquals(5L, baseRevSlot.captured)
        assertEquals("TYPE", dirtyFieldsSlot.captured)
        assertEquals("mut-stale-photo", mutationIdSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerDetails multi-field update marks only TYPE and COMMENT in canonical order`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 14000L
        nextMutationId = "mut-multi-details"

        val roomBeer = BeerEntity(
            id = 300,
            type = BeerType.LATA,
            timestamp = 5000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "old comment",
            photoUri = "photo.jpg",
            remotePhotoUrl = "users/u/beers/x.jpg",
            photoSource = "CAMERA",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(300, "user123") } returns roomBeer

        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        val mutationIdSlot = slot<String>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 300,
                type = BeerType.JARRA,
                comment = "new comment",
                updatedAt = 14000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = capture(mutationIdSlot)
            )
        } returns 1

        repo.updateBeerDetails(300L, BeerType.JARRA, "new comment", typeChanged = true, commentChanged = true)

        // Esperado exactamente: dirtyFields = COMMENT,TYPE en orden canónico
        // NO LOCATION, PHOTO, TIMESTAMP
        assertEquals(2L, baseRevSlot.captured)
        assertEquals("COMMENT,TYPE", dirtyFieldsSlot.captured)
        assertEquals("mut-multi-details", mutationIdSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerDetails with identical type and comment results in 0 writes 0 mutationId and 0 sync`() = runTest {
        var mutationIdCallCount = 0
        val repo = createRepositoryAndClearMocks(
            customMutationIdProvider = {
                mutationIdCallCount++
                "unexpected-mut"
            }
        )

        val roomBeer = BeerEntity(
            id = 400,
            type = BeerType.LATA,
            timestamp = 5000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "same comment",
            photoUri = "photo.jpg",
            remotePhotoUrl = "users/u/beers/x.jpg",
            photoSource = "CAMERA",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(400, "user123") } returns roomBeer

        repo.updateBeerDetails(400L, BeerType.LATA, "same comment", typeChanged = true, commentChanged = true)

        assertEquals(0, mutationIdCallCount)
        verify(exactly = 0) { beerDao.updateBeerDetailsLocalMutation(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }

    @Test
    fun `updateBeerDetails preserves remote comment when user changed only type from stale snapshot`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 15000L
        nextMutationId = "mut-stale-comment"

        // snapshot al abrir: type = LATA, comment = "old"
        // estado ACTUAL Room al guardar: type = LATA, comment = "remote"
        val roomBeer = BeerEntity(
            id = 500,
            type = BeerType.LATA,
            timestamp = 5000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "remote",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 3L,
            baseRevision = 3L,
            dirtyFields = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(500, "user123") } returns roomBeer

        // usuario solo cambió TYPE: requested type = JARRA, requested comment = "old"
        // typeChanged = true, commentChanged = false
        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        val mutationIdSlot = slot<String>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 500,
                type = BeerType.JARRA,
                comment = "remote", // comment ACTUAL de Room conservado
                updatedAt = 15000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = capture(mutationIdSlot)
            )
        } returns 1

        repo.updateBeerDetails(500L, BeerType.JARRA, "old", typeChanged = true, commentChanged = false)

        // Esperado: type = JARRA, comment = "remote", dirtyFields = TYPE (NO COMMENT)
        assertEquals(3L, baseRevSlot.captured)
        assertEquals("TYPE", dirtyFieldsSlot.captured)
        assertEquals("mut-stale-comment", mutationIdSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerDetails preserves remote type when user changed only comment from stale snapshot`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 16000L
        nextMutationId = "mut-stale-type"

        // Snapshot al abrir: type = LATA, comment = "old"
        // estado ACTUAL Room: type = JARRA, comment = "old"
        val roomBeer = BeerEntity(
            id = 600,
            type = BeerType.JARRA,
            timestamp = 5000L,
            latitude = 40.0,
            longitude = -3.0,
            locationName = "Madrid",
            comment = "old",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = null,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(600, "user123") } returns roomBeer

        // usuario cambia SOLO comentario: requested type = LATA, requested comment = "new"
        // typeChanged = false, commentChanged = true
        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        val mutationIdSlot = slot<String>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 600,
                type = BeerType.JARRA, // type ACTUAL de Room conservado
                comment = "new",
                updatedAt = 16000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = capture(mutationIdSlot)
            )
        } returns 1

        repo.updateBeerDetails(600L, BeerType.LATA, "new", typeChanged = false, commentChanged = true)

        // Esperado: type sigue siendo JARRA, comment = "new", dirtyFields = COMMENT (NO TYPE)
        assertEquals(4L, baseRevSlot.captured)
        assertEquals("COMMENT", dirtyFieldsSlot.captured)
        assertEquals("mut-stale-type", mutationIdSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerDetails convergence before save results in clean no-op`() = runTest {
        var mutationIdCallCount = 0
        val repo = createRepositoryAndClearMocks(
            customMutationIdProvider = {
                mutationIdCallCount++
                "unexpected-mut"
            }
        )

        // usuario cambió comment a "new", commentChanged = true
        // pero antes de guardar Room ya contiene comment = "new"
        val roomBeer = BeerEntity(
            id = 700,
            type = BeerType.LATA,
            timestamp = 5000L,
            comment = "new",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 5L,
            baseRevision = 5L,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(700, "user123") } returns roomBeer

        repo.updateBeerDetails(700L, BeerType.LATA, "new", typeChanged = false, commentChanged = true)

        // Esperado: si no hay otro cambio real: NO write, NO mutationId, NO requestSync
        assertEquals(0, mutationIdCallCount)
        verify(exactly = 0) { beerDao.updateBeerDetailsLocalMutation(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }

    @Test
    fun `updateBeerDetails multi-field update marks only TYPE and COMMENT in canonical order when both changed`() = runTest {
        val repo = createRepositoryAndClearMocks()
        currentTime = 17000L
        nextMutationId = "mut-multi-canonical"

        val roomBeer = BeerEntity(
            id = 800,
            type = BeerType.LATA,
            timestamp = 5000L,
            comment = "old",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 2L,
            baseRevision = 2L,
            ownerUid = "user123"
        )
        every { beerDao.getBeerById(800, "user123") } returns roomBeer

        val baseRevSlot = slot<Long>()
        val dirtyFieldsSlot = slot<String?>()
        val mutationIdSlot = slot<String>()
        every {
            beerDao.updateBeerDetailsLocalMutation(
                id = 800,
                type = BeerType.JARRA,
                comment = "fresh",
                updatedAt = 17000L,
                ownerUid = "user123",
                baseRevision = capture(baseRevSlot),
                dirtyFields = captureNullable(dirtyFieldsSlot),
                lastMutationId = capture(mutationIdSlot)
            )
        } returns 1

        repo.updateBeerDetails(800L, BeerType.JARRA, "fresh", typeChanged = true, commentChanged = true)

        // Esperado exactamente: dirtyFields = COMMENT,TYPE en orden canónico
        assertEquals(2L, baseRevSlot.captured)
        assertEquals("COMMENT,TYPE", dirtyFieldsSlot.captured)
        assertEquals("mut-multi-canonical", mutationIdSlot.captured)
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }
}
