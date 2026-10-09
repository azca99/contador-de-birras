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
        beerDatabase = androidx.room.Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java).allowMainThreadQueries().build()
        beerDao = mockk(relaxed = true)
        auth = mockk(relaxed = true)
        user = mockk(relaxed = true)

        mockkStatic(FirebaseAuth::class)
        every { FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns user
    }

    @After
    fun tearDown() {
        beerDatabase.close()
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
        
        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync() } returns SyncEngineResult.SUCCESS
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.success(), result)
        coVerify { anyConstructed<BeerSyncEngine>().sync() }
    }
    
    @Test
    fun `doWork returns success when engine returns AUTH_CHANGED`() = runTest {
        every { user.uid } returns "user_A"
        
        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync() } returns SyncEngineResult.AUTH_CHANGED
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.success(), result)
    }

    @Test
    fun `doWork calls engine and returns retry when engine returns RETRY`() = runTest {
        every { user.uid } returns "user_A"
        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync() } returns SyncEngineResult.RETRY
        
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
        coEvery { anyConstructed<BeerSyncEngine>().sync() } returns SyncEngineResult.RETRY
        
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
        coEvery { anyConstructed<BeerSyncEngine>().sync() } returns SyncEngineResult.PERMANENT_FAILURE
        
        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker = TestListenableWorkerBuilder<BeerSyncWorker>(context)
            .setInputData(inputData)
            .build()
            
        val result = worker.doWork()
        
        assertEquals(Result.failure(), result)
    }
}
