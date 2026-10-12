package com.example.contadordebirras.domain

import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class BeerSyncEngineTest {

    private lateinit var beerDao: BeerDao
    private lateinit var firestore: FirebaseFirestore
    private lateinit var collection: CollectionReference
    private lateinit var documentReference: DocumentReference
    private lateinit var storage: FirebaseStorage
    private lateinit var storageRef: StorageReference
    private lateinit var childRef: StorageReference
    private var currentUid: String? = "user_A"

    @Before
    fun setup() {
        beerDao = mockk(relaxed = true)
        currentUid = "user_A"
        
        mockkStatic(FirebaseFirestore::class)
        mockkStatic(FirebaseStorage::class)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        
        firestore = mockk(relaxed = true)
        collection = mockk(relaxed = true)
        documentReference = mockk(relaxed = true)
        val query = mockk<Query>(relaxed = true)
        val qs = mockk<QuerySnapshot>(relaxed = true)
        every { qs.documents } returns emptyList()
        every { firestore.collection(any()) } returns collection
        every { collection.document(any()) } returns documentReference
        every { collection.whereEqualTo(any<String>(), any()) } returns query
        every { query.get(Source.SERVER) } returns Tasks.forResult(qs)
        every { FirebaseFirestore.getInstance() } returns firestore

        storage = mockk(relaxed = true)
        storageRef = mockk(relaxed = true)
        childRef = mockk(relaxed = true)
        every { storage.reference } returns storageRef
        every { storageRef.child(any()) } returns childRef
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
    fun `pushPendingBeer with photo returns AUTH_CHANGED and skips firestore and markAsSynced if user changes right after putFile`() = runTest {
        val engine = BeerSyncEngine(beerDao, "user_A") { currentUid }
        
        val mockBeer = BeerEntity(
            id = 1,
            type = BeerType.LATA,
            timestamp = 123L,
            latitude = 0.0,
            longitude = 0.0,
            syncId = "sync_photo",
            syncStatus = SyncStatus.PENDING,
            photoUri = "content://photo",
            ownerUid = "user_A"
        )
        coEvery { beerDao.getPendingSyncBeers("user_A") } returns listOf(mockBeer)
        
        val uploadTask = mockk<UploadTask>(relaxed = true)
        val snapshot = mockk<UploadTask.TaskSnapshot>(relaxed = true)
        every { uploadTask.isComplete } returns true
        every { uploadTask.isSuccessful } returns true
        every { uploadTask.isCanceled } returns false
        every { uploadTask.exception } returns null
        every { uploadTask.result } returns snapshot
        
        every { childRef.putFile(any()) } answers {
            currentUid = "user_B"
            uploadTask
        }
        
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.AUTH_CHANGED, result)
        verify(exactly = 0) { documentReference.set(any()) }
        coVerify(exactly = 0) { beerDao.markAsSynced(any(), any(), any()) }
    }

    @Test
    fun `pushPendingBeer without photo returns AUTH_CHANGED and skips firestore set if user changes immediately before set`() = runTest {
        val engine = BeerSyncEngine(
            beerDao = beerDao,
            expectedUid = "user_A",
            currentUidProvider = { currentUid }
        ).apply {
            onBeforeFirestoreSet = { currentUid = "user_B" }
        }
        
        val mockBeer = BeerEntity(
            id = 2,
            type = BeerType.LATA,
            timestamp = 123L,
            latitude = 0.0,
            longitude = 0.0,
            syncId = "sync_no_photo",
            syncStatus = SyncStatus.PENDING,
            photoUri = null,
            ownerUid = "user_A"
        )
        coEvery { beerDao.getPendingSyncBeers("user_A") } returns listOf(mockBeer)
        
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.AUTH_CHANGED, result)
        verify(exactly = 0) { documentReference.set(any()) }
        coVerify(exactly = 0) { beerDao.markAsSynced(any(), any(), any()) }
    }

    @Test
    fun `deleted sync returns AUTH_CHANGED and skips local hardDelete if user changes after remote delete`() = runTest {
        val engine = BeerSyncEngine(beerDao, "user_A") { currentUid }
        
        val mockBeer = BeerEntity(
            id = 1,
            type = BeerType.LATA,
            timestamp = 123L,
            latitude = 0.0,
            longitude = 0.0,
            syncId = "sync_del",
            syncStatus = SyncStatus.DELETED,
            photoUri = "photo.jpg",
            ownerUid = "user_A"
        )
        coEvery { beerDao.getPendingSyncBeers("user_A") } returns listOf(mockBeer)
        
        val transactionSlot = slot<Transaction.Function<Any?>>()
        val transaction = mockk<Transaction>(relaxed = true)
        val documentSnapshot = mockk<DocumentSnapshot>(relaxed = true)
        every { documentSnapshot.exists() } returns true
        every { documentSnapshot.getString("userId") } returns "user_A"
        every { transaction.get(documentReference) } returns documentSnapshot
        
        every { firestore.runTransaction(capture(transactionSlot)) } answers {
            val res = transactionSlot.captured.apply(transaction)
            Tasks.forResult<Any?>(res)
        }
        
        every { childRef.delete() } answers {
            currentUid = "user_B"
            Tasks.forResult<Void>(null)
        }
        
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.AUTH_CHANGED, result)
        verify { transaction.delete(documentReference) }
        verify { childRef.delete() }
        coVerify(exactly = 0) { beerDao.hardDeleteBySyncId(any(), any()) }
    }

    @Test
    fun `pullRemoteBeers multi deletion reconciliation stops immediately on live auth change`() = runTest {
        val engine = BeerSyncEngine(beerDao, "user_A") { currentUid }
        
        coEvery { beerDao.getPendingSyncBeers("user_A") } returns emptyList()
        coEvery { beerDao.getAllSyncedIds("user_A") } returns listOf("del_1", "del_2")
        
        coEvery { beerDao.hardDeleteBySyncId("del_1", "user_A") } answers {
            currentUid = "user_B"
            1
        }
        
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.AUTH_CHANGED, result)
        coVerify(exactly = 1) { beerDao.hardDeleteBySyncId("del_1", "user_A") }
        coVerify(exactly = 0) { beerDao.hardDeleteBySyncId("del_2", any()) }
    }

    @Test
    fun `sync returns AUTH_CHANGED if user changes during pull`() = runTest {
        val engine = BeerSyncEngine(beerDao, "user_A") { currentUid }
        
        coEvery { beerDao.getPendingSyncBeers("user_A") } answers {
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
