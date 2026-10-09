package com.example.contadordebirras.domain

import android.content.Context
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import com.example.contadordebirras.domain.BeerType

@OptIn(ExperimentalCoroutinesApi::class)
class BeerRepositoryDeletedSyncTest {

    private lateinit var beerDao: BeerDao
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var collection: CollectionReference
    private lateinit var documentReference: DocumentReference
    private lateinit var storageRef: StorageReference
    private lateinit var childRef: StorageReference
    private lateinit var transaction: Transaction
    private lateinit var documentSnapshot: DocumentSnapshot

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        beerDao = mockk(relaxed = true)
        firestore = mockk(relaxed = true)
        storage = mockk(relaxed = true)

        collection = mockk(relaxed = true)
        documentReference = mockk(relaxed = true)
        storageRef = mockk(relaxed = true)
        childRef = mockk(relaxed = true)
        transaction = mockk(relaxed = true)
        documentSnapshot = mockk(relaxed = true)

        mockkStatic(FirebaseFirestore::class)
        mockkStatic(FirebaseStorage::class)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { FirebaseFirestore.getInstance() } returns firestore
        every { FirebaseStorage.getInstance() } returns storage

        every { firestore.collection("beers") } returns collection
        every { collection.document(any()) } returns documentReference
        every { storage.reference } returns storageRef
        every { storageRef.child(any()) } returns childRef
        
        every { transaction.get(documentReference) } returns documentSnapshot
        
        val transactionSlot = slot<Transaction.Function<Any?>>()
        every { firestore.runTransaction(capture(transactionSlot)) } answers {
            try {
                val result = transactionSlot.captured.apply(transaction)
                Tasks.forResult<Any?>(result)
            } catch (e: Exception) {
                Tasks.forException<Any?>(e)
            }
        }
        
        val qs = mockk<QuerySnapshot>(relaxed = true)
        every { qs.documents } returns emptyList()
        val query = mockk<Query>(relaxed = true)
        every { collection.whereEqualTo(any<String>(), any()) } returns query
        every { query.get(Source.SERVER) } returns Tasks.forResult(qs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `deleted sync - successful firestore and storage allows hard delete`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, photoUri = "local.jpg", ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        
        every { documentSnapshot.exists() } returns true
        every { documentSnapshot.getString("userId") } returns "user123"
        every { childRef.delete() } returns Tasks.forResult<Void>(null)
        
        val engine = BeerSyncEngine(beerDao, "user123") { "user123" }
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.SUCCESS, result)
        verify(exactly = 0) { childRef.putFile(any()) }
        verify { transaction.delete(documentReference) }
        verify { childRef.delete() }
        verify { beerDao.hardDeleteBySyncId("sync1", "user123") }
    }

    @Test
    fun `deleted sync - firestore document missing is treated as success`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        
        every { documentSnapshot.exists() } returns false
        every { childRef.delete() } returns Tasks.forResult<Void>(null)
        
        val engine = BeerSyncEngine(beerDao, "user123") { "user123" }
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.SUCCESS, result)
        verify(exactly = 0) { transaction.delete(documentReference) }
        verify { childRef.delete() }
        verify { beerDao.hardDeleteBySyncId("sync1", "user123") }
    }

    @Test
    fun `deleted sync - storage not found is treated as success`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        every { documentSnapshot.exists() } returns false
        
        val notFoundException = mockk<StorageException>()
        every { notFoundException.errorCode } returns StorageException.ERROR_OBJECT_NOT_FOUND
        every { childRef.delete() } returns Tasks.forException(notFoundException)
        
        val engine = BeerSyncEngine(beerDao, "user123") { "user123" }
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.SUCCESS, result)
        verify { childRef.delete() }
        verify { beerDao.hardDeleteBySyncId("sync1", "user123") }
    }

    @Test
    fun `deleted sync - firestore fails keeps it DELETED`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        
        every { documentSnapshot.exists() } returns true
        every { documentSnapshot.getString("userId") } returns "user123"
        
        every { transaction.get(documentReference) } throws Exception("Network error")
        
        val engine = BeerSyncEngine(beerDao, "user123") { "user123" }
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.RETRY, result)
        verify(exactly = 0) { childRef.delete() }
        verify(exactly = 0) { beerDao.hardDeleteBySyncId(any(), any()) }
    }
    
    @Test
    fun `deleted sync - firestore mismatch user keeps it DELETED and returns PERMANENT_FAILURE`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        
        every { documentSnapshot.exists() } returns true
        every { documentSnapshot.getString("userId") } returns "otroUser"
        
        val engine = BeerSyncEngine(beerDao, "user123") { "user123" }
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.PERMANENT_FAILURE, result)
        verify(exactly = 0) { transaction.delete(documentReference) }
        verify(exactly = 0) { childRef.delete() }
        verify(exactly = 0) { beerDao.hardDeleteBySyncId(any(), any()) }
    }

    @Test
    fun `deleted sync - firestore absent but storage real error keeps it DELETED`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        
        every { documentSnapshot.exists() } returns false
        every { childRef.delete() } returns Tasks.forException<Void>(Exception("Real storage error"))
        
        val engine = BeerSyncEngine(beerDao, "user123") { "user123" }
        val result = engine.sync()
        
        assertEquals(SyncEngineResult.RETRY, result)
        verify(exactly = 0) { transaction.delete(documentReference) }
        verify { childRef.delete() }
        verify(exactly = 0) { beerDao.hardDeleteBySyncId("sync1", "user123") }
    }
}
