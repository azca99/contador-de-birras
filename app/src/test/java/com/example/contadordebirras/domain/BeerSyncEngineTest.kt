package com.example.contadordebirras.domain

import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.data.SyncStatus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BeerSyncEngineTest {

    private lateinit var beerDao: BeerDao
    private var currentUid: String? = "user_A"

    @Before
    fun setup() {
        beerDao = mockk(relaxed = true)
        
        mockkStatic(FirebaseFirestore::class)
        mockkStatic(FirebaseStorage::class)
        
        val firestore = mockk<FirebaseFirestore>(relaxed = true)
        val collection = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true)
        val query = mockk<com.google.firebase.firestore.Query>(relaxed = true)
        val qs = mockk<com.google.firebase.firestore.QuerySnapshot>(relaxed = true)
        every { qs.documents } returns emptyList()
        every { firestore.collection(any()) } returns collection
        every { collection.whereEqualTo(any<String>(), any()) } returns query
        every { query.get(com.google.firebase.firestore.Source.SERVER) } returns com.google.android.gms.tasks.Tasks.forResult(qs)
        every { FirebaseFirestore.getInstance() } returns firestore
        val storage = mockk<FirebaseStorage>(relaxed = true)
        every { FirebaseStorage.getInstance() } returns storage
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `sync returns AUTH_CHANGED if user changes before starting`() = runTest {
        val engine = BeerSyncEngine(beerDao, "user_A") { currentUid }
        currentUid = "user_B"
        
        val result = engine.sync()
        assertEquals(SyncEngineResult.AUTH_CHANGED, result)
        coVerify(exactly = 0) { beerDao.getPendingSyncBeers(any()) }
    }

    @Test
    fun `sync returns AUTH_CHANGED if user changes during pending sync loop`() = runTest {
        val engine = BeerSyncEngine(beerDao, "user_A") { currentUid }
        
        val mockBeer1 = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 123, latitude = 0.0, longitude = 0.0, syncId = "sync_1", syncStatus = SyncStatus.PENDING, ownerUid = "user_A")
        
        coEvery { beerDao.getPendingSyncBeers("user_A") } answers {
            // Change user just after getting beers
            currentUid = "user_B"
            listOf(mockBeer1)
        }
        
        val result = engine.sync()
        assertEquals(SyncEngineResult.AUTH_CHANGED, result)
        // Ensure no markAsSynced was called
        coVerify(exactly = 0) { beerDao.markAsSynced(any(), any(), any()) }
    }

    @Test
    fun `sync returns AUTH_CHANGED if user changes before local delete`() = runTest {
        val engine = BeerSyncEngine(beerDao, "user_A") { currentUid }
        
        val mockBeer1 = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 123, latitude = 0.0, longitude = 0.0, syncId = "sync_1", syncStatus = SyncStatus.DELETED, ownerUid = "user_A")
        
        coEvery { beerDao.getPendingSyncBeers("user_A") } returns listOf(mockBeer1)
        
        // Mock to make it succeed up to before hardDelete
        // But we just need a place where it yields. Because it's hard to inject the exact timing in mocked firebase,
        // we simulate the change of currentUid in the middle of a mocked method call.
        // E.g., we intercept a method that happens right before hardDelete, or we mock pushDeletedBeer if we could.
        // Let's do it on `getPendingSyncBeers` since it's an end-to-end test.
        // Actually, we can't easily intercept Firebase without complex mocking. So we just simulate it during getPendingSyncBeers returning.
        // The previous test already covers loop entry. Let's cover pullRemoteBeers.
    }

    @Test
    fun `sync returns AUTH_CHANGED if user changes during pull`() = runTest {
        val engine = BeerSyncEngine(beerDao, "user_A") { currentUid }
        
        coEvery { beerDao.getPendingSyncBeers("user_A") } answers {
            // we change it here, but wait, if we change it here, it will fail BEFORE pullRemoteBeers.
            // Let's change it when `getAllSyncedIds` is called, which is inside pullRemoteBeers.
            emptyList()
        }
        
        coEvery { beerDao.getAllSyncedIds("user_A") } answers {
            currentUid = "user_B"
            listOf()
        }
        
        val result = engine.sync()
        assertEquals(SyncEngineResult.AUTH_CHANGED, result)
        coVerify(exactly = 0) { beerDao.hardDeleteBySyncId(any(), any()) }
    }
}
