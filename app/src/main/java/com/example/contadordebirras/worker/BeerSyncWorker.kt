package com.example.contadordebirras.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.domain.BeerSyncEngine
import com.example.contadordebirras.domain.SyncEngineResult
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class BeerSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_EXPECTED_UID = "expected_uid"
        private const val MAX_RETRIES = 5
        private val syncExecutionMutex = Mutex()
    }

    override suspend fun doWork(): Result {
        val expectedUid = inputData.getString(KEY_EXPECTED_UID) ?: return Result.success()
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        
        if (currentUid != expectedUid) {
            // Work belongs to a previous session, do not process
            return Result.success()
        }

        // 1. Verificación persistente del estado del motor antes de cualquier sincronización
        val persistedMode = com.example.contadordebirras.sync.c4.C4SyncStateStore.getEngineMode(applicationContext, expectedUid)

        if (persistedMode == com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE) {
            // Ejecutar sincronización C4 mediante el proveedor inyectado bajo exclusión mutua
            return com.example.contadordebirras.sync.c4.C4SyncConfig.syncExecutionMutex.withLock {
                val provider = com.example.contadordebirras.sync.c4.C4SyncProviderRegistry.getProvider()
                when (val syncResult = provider.syncUser(applicationContext, expectedUid)) {
                    is com.example.contadordebirras.sync.c4.C4ProviderSyncResult.Success -> {
                        Result.success()
                    }
                    is com.example.contadordebirras.sync.c4.C4ProviderSyncResult.InactiveOrBlocked -> {
                        // Si el estado es C4_ACTIVE pero el proveedor está inactivo o bloqueado:
                        // 1. NO ejecutar Legacy (se respeta la exclusión mutua y el modo C4_ACTIVE).
                        // 2. NO comunicar sincronización completada (no devolver Result.success()).
                        // 3. Dejar un error explícito sin reintentos infinitos (Result.failure()).
                        Result.failure()
                    }
                    is com.example.contadordebirras.sync.c4.C4ProviderSyncResult.TransientError -> {
                        // Fallo transitorio con proveedor operativo: reintentar
                        Result.retry()
                    }
                }
            }
        }

        // Si el estado es CUTOVER_IN_PROGRESS o HALTED, o C4 está activo en memoria, NO ejecutar escrituras legacy
        if (persistedMode != com.example.contadordebirras.sync.c4.SyncEngineMode.LEGACY ||
            com.example.contadordebirras.sync.c4.C4SyncConfig.isEnabled ||
            com.example.contadordebirras.sync.c4.C4SyncConfig.engineMode != com.example.contadordebirras.sync.c4.SyncEngineMode.LEGACY
        ) {
            return Result.success()
        }

        return com.example.contadordebirras.sync.c4.C4SyncConfig.syncExecutionMutex.withLock {
            syncExecutionMutex.withLock {
                val validUid = FirebaseAuth.getInstance().currentUser?.uid
                if (validUid != expectedUid) {
                    // Auth changed or became null while waiting for the mutex
                    return@withLock Result.success()
                }

                val recheckedMode = com.example.contadordebirras.sync.c4.C4SyncStateStore.getEngineMode(applicationContext, expectedUid)
                if (recheckedMode != com.example.contadordebirras.sync.c4.SyncEngineMode.LEGACY ||
                    com.example.contadordebirras.sync.c4.C4SyncConfig.isEnabled ||
                    com.example.contadordebirras.sync.c4.C4SyncConfig.engineMode != com.example.contadordebirras.sync.c4.SyncEngineMode.LEGACY
                ) {
                    return@withLock Result.success()
                }

            val beerDao = BeerDatabase.getDatabase(applicationContext).beerDao()
            val auth = FirebaseAuth.getInstance()
            val engine = BeerSyncEngine(beerDao, expectedUid) { auth.currentUser?.uid }

            try {
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
}
}
