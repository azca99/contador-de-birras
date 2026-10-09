package com.example.contadordebirras.domain

import android.content.Context
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
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

@OptIn(ExperimentalCoroutinesApi::class)
class BeerRepositorySyncTest {

    private lateinit var beerDao: BeerDao
    private lateinit var authRepository: AuthRepository
    private lateinit var context: Context
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    
    private lateinit var repository: BeerRepository
    private val currentUserFlow = MutableStateFlow<FirebaseUser?>(null)

    private lateinit var syncScheduler: BeerSyncScheduler

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        beerDao = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        firestore = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)

        every { authRepository.currentUser } returns currentUserFlow
        
        mockkStatic(FirebaseFirestore::class)
        mockkStatic(FirebaseStorage::class)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { FirebaseFirestore.getInstance() } returns firestore
        every { FirebaseStorage.getInstance() } returns storage

        val user = mockk<FirebaseUser>(relaxed = true)
        every { user.uid } returns "user123"
        currentUserFlow.value = user
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `addBeer successful insert triggers syncRequest`() = runTest {
        every { beerDao.insertBeer(any()) } returns 1L
        
        repository = BeerRepository(beerDao, context, authRepository, syncScheduler)
        
        repository.addBeer(BeerType.LATA, 12345L)
        
        verify(atLeast = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `addBeer failed insert does not trigger syncRequest`() = runTest {
        every { beerDao.insertBeer(any()) } returns 0L
        
        repository = BeerRepository(beerDao, context, authRepository, syncScheduler)
        
        // requestSync is called in init block, clear it


        
        repository.addBeer(BeerType.LATA, 12345L)
        
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }

    @Test
    fun `updateBeerLocation with rows updated triggers syncRequest`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, ownerUid = "user123")
        every { beerDao.getBeerById(1, "user123") } returns beer
        every { beerDao.updateBeer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        
        repository = BeerRepository(beerDao, context, authRepository, syncScheduler)


        repository.updateBeerLocation(1L, 10.0, 10.0)
        
        verify { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerLocation with zero rows updated does not trigger syncRequest`() = runTest {
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, ownerUid = "user123")
        every { beerDao.getBeerById(1, "user123") } returns beer
        every { beerDao.updateBeer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        
        repository = BeerRepository(beerDao, context, authRepository, syncScheduler)
        repository.updateBeerLocation(1L, 10.0, 10.0)
        
        // init block triggers 1 call. updateBeerLocation should not trigger a second.
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }
}
