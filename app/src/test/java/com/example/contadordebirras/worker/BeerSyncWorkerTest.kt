package com.example.contadordebirras.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.domain.BeerSyncEngine
import com.example.contadordebirras.domain.SyncEngineResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BeerSyncWorkerTest {

    private lateinit var beerDao: BeerDao
    private lateinit var beerDatabase: BeerDatabase
    private lateinit var auth: FirebaseAuth
    private lateinit var user: FirebaseUser
    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        beerDao = mockk(relaxed = true)
        beerDatabase = mockk(relaxed = true)
        auth = mockk(relaxed = true)
        user = mockk(relaxed = true)

        mockkStatic(BeerDatabase::class)
        every { BeerDatabase.getDatabase(any()) } returns beerDatabase
        every { beerDatabase.beerDao() } returns beerDao

        mockkStatic(FirebaseAuth::class)
        every { FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns user
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `doWork returns success when expectedUid does not match currentUid`() = runTest {
        every { user.uid } returns "user_A"
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_B").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.success(), result)
        verify(exactly = 0) { BeerDatabase.getDatabase(any()) }
    }

    @Test
    fun `doWork returns success when user is null`() = runTest {
        every { auth.currentUser } returns null
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.success(), result)
    }

    @Test
    fun `doWork calls engine and returns success when engine returns SUCCESS`() = runTest {
        every { user.uid } returns "user_A"
        
        // Mocking the engine constructor inside the worker is hard without a factory,
        // but we can mock the engine class itself since we use mockk.
        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync(any()) } returns SyncEngineResult.SUCCESS
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.success(), result)
        coVerify { anyConstructed<BeerSyncEngine>().sync("user_A") }
    }

    @Test
    fun `doWork calls engine and returns retry when engine returns RETRY`() = runTest {
        every { user.uid } returns "user_A"
        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync(any()) } returns SyncEngineResult.RETRY
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .setRunAttemptCount(1)
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.retry(), result)
    }

    @Test
    fun `doWork returns failure when engine returns RETRY but max retries reached`() = runTest {
        every { user.uid } returns "user_A"
        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync(any()) } returns SyncEngineResult.RETRY
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .setRunAttemptCount(6) // MAX is 5
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.failure(), result)
    }

    @Test
    fun `doWork returns failure when engine returns PERMANENT_FAILURE`() = runTest {
        every { user.uid } returns "user_A"
        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync(any()) } returns SyncEngineResult.PERMANENT_FAILURE
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.failure(), result)
    }
}
