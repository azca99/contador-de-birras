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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

    @Test
    fun `two workers are serialized by syncExecutionMutex`() = runTest {
        every { user.uid } returns "user_A"

        val w1Entered = CompletableDeferred<Unit>()
        val w1Release = CompletableDeferred<Unit>()
        val w2Entered = CompletableDeferred<Unit>()
        var syncCount = 0

        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync() } coAnswers {
            syncCount++
            if (syncCount == 1) {
                w1Entered.complete(Unit)
                w1Release.await()
                SyncEngineResult.SUCCESS
            } else {
                w2Entered.complete(Unit)
                SyncEngineResult.SUCCESS
            }
        }

        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker1 = TestListenableWorkerBuilder<BeerSyncWorker>(context).setInputData(inputData).build()
        val worker2 = TestListenableWorkerBuilder<BeerSyncWorker>(context).setInputData(inputData).build()

        var result1: Result? = null
        var result2: Result? = null

        val job1 = launch(Dispatchers.Default) {
            result1 = worker1.doWork()
        }

        w1Entered.await()
        assertEquals(1, syncCount)

        val job2 = launch(Dispatchers.Default) {
            result2 = worker2.doWork()
        }

        // Verify that while worker1 holds the mutex, worker2 cannot enter sync()
        assertEquals(false, w2Entered.isCompleted)
        assertEquals(1, syncCount)

        // Release worker1
        w1Release.complete(Unit)
        job1.join()
        assertEquals(Result.success(), result1)

        // Worker 2 can now enter sync()
        w2Entered.await()
        job2.join()
        assertEquals(Result.success(), result2)
        assertEquals(2, syncCount)
        coVerify(exactly = 2) { anyConstructed<BeerSyncEngine>().sync() }
    }

    @Test
    fun `auth change while waiting for mutex skips engine execution and returns success`() = runTest {
        every { user.uid } returns "user_A"

        val w1Entered = CompletableDeferred<Unit>()
        val w1Release = CompletableDeferred<Unit>()

        mockkConstructor(BeerSyncEngine::class)
        coEvery { anyConstructed<BeerSyncEngine>().sync() } coAnswers {
            w1Entered.complete(Unit)
            w1Release.await()
            SyncEngineResult.SUCCESS
        }

        val inputData = Data.Builder().putString(BeerSyncWorker.KEY_EXPECTED_UID, "user_A").build()
        val worker1 = TestListenableWorkerBuilder<BeerSyncWorker>(context).setInputData(inputData).build()
        val worker2 = TestListenableWorkerBuilder<BeerSyncWorker>(context).setInputData(inputData).build()

        var result1: Result? = null
        var result2: Result? = null

        val job1 = launch(Dispatchers.Default) {
            result1 = worker1.doWork()
        }

        w1Entered.await()

        val job2 = launch(Dispatchers.Default) {
            result2 = worker2.doWork()
        }

        // While worker2 is waiting on the mutex, auth changes: userA -> userB
        val userB = mockk<FirebaseUser>(relaxed = true)
        every { userB.uid } returns "user_B"
        every { auth.currentUser } returns userB

        // Release worker1
        w1Release.complete(Unit)
        job1.join()
        assertEquals(Result.success(), result1)

        // Worker2 obtains the mutex, revalidates auth, sees user_B != user_A, returns success without executing engine
        job2.join()
        assertEquals(Result.success(), result2)
        coVerify(exactly = 1) { anyConstructed<BeerSyncEngine>().sync() }
    }
}
