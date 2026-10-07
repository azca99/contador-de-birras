package com.example.contadordebirras.domain

import android.content.Context
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
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
import org.junit.Assert.*
import com.example.contadordebirras.domain.BeerType

@OptIn(ExperimentalCoroutinesApi::class)
class BeerRepositoryDeletedSyncTest {

    private lateinit var beerDao: BeerDao
    private lateinit var authRepository: AuthRepository
    private lateinit var context: Context
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var collection: CollectionReference
    private lateinit var documentReference: DocumentReference
    private lateinit var storageRef: StorageReference
    private lateinit var childRef: StorageReference
    private lateinit var transaction: Transaction
    private lateinit var documentSnapshot: DocumentSnapshot

    private lateinit var repository: BeerRepository
    private val currentUserFlow = MutableStateFlow<FirebaseUser?>(null)

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        beerDao = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        firestore = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        collection = mockk(relaxed = true)
        documentReference = mockk(relaxed = true)
        storageRef = mockk(relaxed = true)
        childRef = mockk(relaxed = true)
        transaction = mockk(relaxed = true)
        documentSnapshot = mockk(relaxed = true)

        every { authRepository.currentUser } returns currentUserFlow

        mockkStatic(FirebaseFirestore::class)
        mockkStatic(FirebaseStorage::class)
        every { FirebaseFirestore.getInstance() } returns firestore
        every { FirebaseStorage.getInstance() } returns storage

        val user = mockk<FirebaseUser>(relaxed = true)
        every { user.uid } returns "user123"
        currentUserFlow.value = user

        every { firestore.collection("beers") } returns collection
        every { collection.document(any()) } returns documentReference
        every { storage.reference } returns storageRef
        every { storageRef.child(any()) } returns childRef
        
        every { transaction.get(documentReference) } returns documentSnapshot
        
        // Mocking runTransaction
        val transactionSlot = slot<Transaction.Function<Any?>>()
        every { firestore.runTransaction(capture(transactionSlot)) } answers {
            try {
                transactionSlot.captured.apply(transaction)
                Tasks.forResult(null as Void?)
            } catch (e: Exception) {
                Tasks.forException(e)
            }
        }
        
        // Mock pull to just return empty snapshot to avoid crashes in second part of sync
        val qs = mockk<QuerySnapshot>(relaxed = true)
        every { qs.documents } returns emptyList()
        val query = mockk<Query>(relaxed = true)
        every { collection.whereEqualTo(any<String>(), any()) } returns query
        every { query.get(Source.SERVER) } returns Tasks.forResult(qs)

        repository = BeerRepository(beerDao, context, authRepository)
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
        every { childRef.delete() } returns Tasks.forResult(null as Void?)
        
        repository.syncWithCloud()
        
        // Ensure photo upload was never called because it's deleted
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
        every { childRef.delete() } returns Tasks.forResult(null as Void?)
        
        repository.syncWithCloud()
        
        verify(exactly = 0) { transaction.delete(documentReference) } // Did not try to delete because it doesn't exist
        verify { childRef.delete() } // Proceeded to storage
        verify { beerDao.hardDeleteBySyncId("sync1", "user123") } // Hard deleted
    }

    @Test
    fun `deleted sync - storage not found is treated as success`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        
        every { documentSnapshot.exists() } returns false
        
        val exception = StorageException.fromErrorStatus(com.google.android.gms.common.api.Status.RESULT_INTERNAL_ERROR)
        // We can mock the specific exception
        val notFoundException = mockk<StorageException>()
        every { notFoundException.errorCode } returns StorageException.ERROR_OBJECT_NOT_FOUND
        every { childRef.delete() } returns Tasks.forException(notFoundException)
        
        repository.syncWithCloud()
        
        verify { childRef.delete() }
        verify { beerDao.hardDeleteBySyncId("sync1", "user123") } // Hard deleted because 404 is success
    }

    @Test
    fun `deleted sync - firestore fails keeps it DELETED`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        
        every { documentSnapshot.exists() } returns true
        every { documentSnapshot.getString("userId") } returns "user123"
        
        every { firestore.runTransaction(any()) } returns Tasks.forException(Exception("Network error"))
        
        repository.syncWithCloud()
        
        verify(exactly = 0) { childRef.delete() }
        verify(exactly = 0) { beerDao.hardDeleteBySyncId(any(), any()) }
    }
    
    @Test
    fun `deleted sync - firestore mismatch user keeps it DELETED without storage delete`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, syncId = "sync1", syncStatus = SyncStatus.DELETED, ownerUid = "user123")
        every { beerDao.getPendingSyncBeers("user123") } returns listOf(beer)
        
        every { documentSnapshot.exists() } returns true
        every { documentSnapshot.getString("userId") } returns "otroUser"
        
        repository.syncWithCloud()
        
        verify(exactly = 0) { transaction.delete(documentReference) }
        verify(exactly = 0) { childRef.delete() }
        verify(exactly = 0) { beerDao.hardDeleteBySyncId(any(), any()) }
    }
}
