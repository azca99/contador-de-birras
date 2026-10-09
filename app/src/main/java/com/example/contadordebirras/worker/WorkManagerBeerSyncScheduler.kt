package com.example.contadordebirras.worker

import android.content.Context
import androidx.work.*
import com.example.contadordebirras.domain.BeerSyncScheduler
import java.util.concurrent.TimeUnit

class WorkManagerBeerSyncScheduler(
    private val context: Context
) : BeerSyncScheduler {

    override fun requestSync(expectedUid: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        val workRequest = OneTimeWorkRequestBuilder<BeerSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(BeerSyncWorker.KEY_EXPECTED_UID to expectedUid))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
            
        WorkManager.getInstance(context).enqueueUniqueWork(
            "beer-sync-user-$expectedUid",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
    }

    override fun schedulePeriodicSync(expectedUid: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        val periodicRequest = PeriodicWorkRequestBuilder<BeerSyncWorker>(
            6, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(workDataOf(BeerSyncWorker.KEY_EXPECTED_UID to expectedUid))
            .build()
            
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "beer-periodic-sync-user-$expectedUid",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }
}
