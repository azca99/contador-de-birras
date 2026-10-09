package com.example.contadordebirras.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.domain.BeerSyncEngine
import com.example.contadordebirras.domain.SyncEngineResult
import com.google.firebase.auth.FirebaseAuth
import kotlin.coroutines.cancellation.CancellationException

class BeerSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_EXPECTED_UID = "expected_uid"
        private const val MAX_RETRIES = 5
    }

    override suspend fun doWork(): Result {
        val expectedUid = inputData.getString(KEY_EXPECTED_UID) ?: return Result.success()
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        
        if (currentUid != expectedUid) {
            // Work belongs to a previous session, do not process
            return Result.success()
        }
        
        val beerDao = BeerDatabase.getDatabase(applicationContext).beerDao()
        val auth = FirebaseAuth.getInstance()
        val engine = BeerSyncEngine(beerDao, expectedUid) { auth.currentUser?.uid }
        
        return try {
            val result = engine.sync()
            when (result) {
                SyncEngineResult.SUCCESS -> Result.success()
                SyncEngineResult.AUTH_CHANGED -> Result.success() // Same as above, just stop
                SyncEngineResult.RETRY -> {
                    if (runAttemptCount < MAX_RETRIES) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                SyncEngineResult.PERMANENT_FAILURE -> Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
