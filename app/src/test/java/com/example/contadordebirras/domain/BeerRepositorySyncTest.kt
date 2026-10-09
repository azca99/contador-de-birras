package com.example.contadordebirras.domain

import android.content.Context
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
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
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.delay

@OptIn(ExperimentalCoroutinesApi::class)
class BeerRepositorySyncTest {

    private lateinit var beerDao: BeerDao
    private lateinit var authRepository: AuthRepository
    private lateinit var context: Context
    private lateinit var repository: BeerRepository
    private val currentUserFlow = MutableStateFlow<FirebaseUser?>(null)

    private lateinit var syncScheduler: BeerSyncScheduler

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

    private suspend fun initRepositoryAndClearMocks() {
        repository = BeerRepository(beerDao, context, authRepository, syncScheduler)
        // Wait for the init block coroutine to complete its first syncRequest
        verify(timeout = 2000, exactly = 1) { syncScheduler.requestSync("user123") }
        clearMocks(syncScheduler, answers = false)
    }

    @Test
    fun `addBeer successful insert triggers syncRequest`() = runTest {
        initRepositoryAndClearMocks()
        
        every { beerDao.insertBeer(any()) } returns 1L
        repository.addBeer(BeerType.LATA, 12345L)
        
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `addBeer failed insert does not trigger syncRequest`() = runTest {
        initRepositoryAndClearMocks()
        
        every { beerDao.insertBeer(any()) } returns 0L
        repository.addBeer(BeerType.LATA, 12345L)
        
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }
    
    @Test
    fun `updateBeer triggers syncRequest`() = runTest {
        initRepositoryAndClearMocks()
        
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, ownerUid = "user123")
        every { beerDao.getBeerById(1, "user123") } returns beer
        every { beerDao.updateBeer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        
        repository.updateBeer(beer)
        
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }
    
    @Test
    fun `deleteBeer triggers syncRequest`() = runTest {
        initRepositoryAndClearMocks()
        
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, ownerUid = "user123")
        every { beerDao.getBeerById(1, "user123") } returns beer
        
        repository.deleteBeer(beer)
        
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerLocation with rows updated triggers syncRequest`() = runTest {
        initRepositoryAndClearMocks()
        
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, ownerUid = "user123")
        every { beerDao.getBeerById(1, "user123") } returns beer
        every { beerDao.updateBeer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        
        repository.updateBeerLocation(1L, 10.0, 10.0)
        
        verify(exactly = 1) { syncScheduler.requestSync("user123") }
    }

    @Test
    fun `updateBeerLocation with zero rows updated does not trigger syncRequest`() = runTest {
        initRepositoryAndClearMocks()
        
        val beer = BeerEntity(id = 1, type = BeerType.LATA, timestamp = 0L, ownerUid = "user123")
        every { beerDao.getBeerById(1, "user123") } returns beer
        every { beerDao.updateBeer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        
        repository.updateBeerLocation(1L, 10.0, 10.0)
        
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }
    
    @Test
    fun `no authenticated user means no syncRequest in init or operations`() = runTest {
        currentUserFlow.value = null
        repository = BeerRepository(beerDao, context, authRepository, syncScheduler)
        
        // Let any init coroutines run
        delay(100)
        
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
        
        every { beerDao.insertBeer(any()) } returns 1L
        repository.addBeer(BeerType.LATA, 12345L)
        
        verify(exactly = 0) { syncScheduler.requestSync(any()) }
    }
}
