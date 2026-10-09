package com.example.contadordebirras.worker

import android.content.Context
import androidx.work.*
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class WorkManagerBeerSyncSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerBeerSyncScheduler

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager
        
        scheduler = WorkManagerBeerSyncScheduler(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `requestSync schedules OneTimeWorkRequest correctly`() {
        val expectedUid = "user123"
        
        val workRequestSlot = slot<OneTimeWorkRequest>()
        every { workManager.enqueueUniqueWork(
            eq("beer-sync-user-$expectedUid"),
            eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
            capture(workRequestSlot)
        ) } returns mockk()
        
        scheduler.requestSync(expectedUid)
        
        val capturedRequest = workRequestSlot.captured
        val workSpec = capturedRequest.workSpec
        
        // Assert input data
        val inputExpectedUid = workSpec.input.keyValueMap[BeerSyncWorker.KEY_EXPECTED_UID] as String?
        assertEquals(expectedUid, inputExpectedUid)
        
        // Assert constraints
        val constraints = workSpec.constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        
        // Assert backoff policy
        assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
        assertEquals(30_000L, workSpec.backoffDelayDuration) // 30 seconds
    }
    
    @Test
    fun `schedulePeriodicSync schedules PeriodicWorkRequest correctly`() {
        val expectedUid = "user123"
        
        val workRequestSlot = slot<PeriodicWorkRequest>()
        every { workManager.enqueueUniquePeriodicWork(
            eq("beer-periodic-sync-user-$expectedUid"),
            eq(ExistingPeriodicWorkPolicy.KEEP),
            capture(workRequestSlot)
        ) } returns mockk()
        
        scheduler.schedulePeriodicSync(expectedUid)
        
        val capturedRequest = workRequestSlot.captured
        val workSpec = capturedRequest.workSpec
        
        // Assert input data
        val inputExpectedUid = workSpec.input.keyValueMap[BeerSyncWorker.KEY_EXPECTED_UID] as String?
        assertEquals(expectedUid, inputExpectedUid)
        
        // Assert constraints
        val constraints = workSpec.constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        
        // Interval is 6 hours (21600000 ms)
        assertEquals(21600000L, workSpec.intervalDuration)
    }
}
