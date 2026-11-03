package com.example.contadordebirras.ui.debug

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.sync.c4.C4AppSyncManager
import com.example.contadordebirras.sync.c4.C4CoordinatorPullResult
import com.example.contadordebirras.sync.c4.C4CoordinatorPushResult
import com.example.contadordebirras.sync.c4.C4SyncConfig
import com.example.contadordebirras.sync.c4.C4SyncConstants
import com.example.contadordebirras.sync.c4.C4TestEnvironmentFactory
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel para el Panel de Control y Pruebas DEBUG del Protocolo C4 (Fase C4.4B).
 *
 * AISLAMIENTO:
 * - Desactivado por defecto.
 * - Solo cuando el usuario lo activa explícitamente en la pantalla DEBUG, inicializa
 *   el entorno aislado [C4AppSyncManager] sobre una base de datos Room descartable
 *   y emuladores Firebase locales (puertos 9099, 8888, 9199).
 */
class C4SyncDebugViewModel(application: Application) : AndroidViewModel(application) {

    val isC4Active = mutableStateOf(C4SyncConfig.isEnabled)
    val statusMessage = mutableStateOf("Motor C4 inactivo. Activa el toggle para conectar al entorno aislado.")
    val beersList = mutableStateListOf<BeerEntity>()
    val mutationHistory = mutableStateListOf<LocalMutationHistoryEntity>()
    val executionLogs = mutableStateListOf<String>()

    private var syncManager: C4AppSyncManager? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        executionLogs.add(0, "[$timestamp] $message")
        if (executionLogs.size > 50) {
            executionLogs.removeAt(executionLogs.lastIndex)
        }
    }

    fun toggleC4(active: Boolean) {
        viewModelScope.launch {
            if (active) {
                C4SyncConfig.isEnabled = true
                isC4Active.value = true
                statusMessage.value = "Conectando al entorno aislado Firebase Emulator..."
                
                try {
                    val resolvedHost = withContext(Dispatchers.IO) {
                        try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_ANDROID, C4SyncConstants.FIRESTORE_PORT), 800)
                                C4SyncConstants.EMULATOR_HOST_ANDROID
                            }
                        } catch (_: Exception) {
                            C4SyncConstants.EMULATOR_HOST_LOCAL
                        }
                    }

                    val instances = C4TestEnvironmentFactory.createIsolatedInstances(
                        context = getApplication(),
                        host = resolvedHost,
                        authPort = 9099,
                        firestorePort = C4SyncConstants.FIRESTORE_PORT,
                        storagePort = C4SyncConstants.STORAGE_PORT,
                        projectId = C4SyncConstants.DEMO_PROJECT_ID
                    )

                    val userUid = withContext(Dispatchers.IO) {
                        try {
                            val userCred = instances.auth.signInAnonymously().await()
                            userCred.user?.uid ?: "debug_user_c4"
                        } catch (e: Exception) {
                            "debug_user_c4"
                        }
                    }

                    syncManager = C4AppSyncManager.createForTesting(
                        context = getApplication(),
                        ownerUid = userUid,
                        host = resolvedHost,
                        inMemory = true,
                        instances = instances
                    )
                    statusMessage.value = "Conectado a Firebase Emulator ($resolvedHost) | UID: ${userUid.take(8)}... | Base Room Descartable"
                    log("C4 inicializado con éxito en $resolvedHost (proyecto: ${C4SyncConstants.DEMO_PROJECT_ID}, authUid: $userUid)")
                    refreshState()
                } catch (e: Exception) {
                    C4SyncConfig.isEnabled = false
                    isC4Active.value = false
                    statusMessage.value = "Error al iniciar C4: ${e.message}"
                    log("ERROR: ${e.message}")
                }
            } else {
                C4SyncConfig.isEnabled = false
                isC4Active.value = false
                syncManager?.close()
                syncManager = null
                beersList.clear()
                mutationHistory.clear()
                statusMessage.value = "Motor C4 desactivado. Motor legacy activo para producción."
                log("C4 desactivado. Base descartable cerrada.")
            }
        }
    }

    fun createBeer(type: BeerType = BeerType.CANA, comment: String? = "Cerveza de prueba C4") {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                val syncId = manager.createBeer(type = type, comment = comment)
                log("Cerveza creada localmente: syncId=${syncId.take(8)}... (Mutación durable registrada)")
                refreshState()
            } catch (e: Exception) {
                log("Error al crear cerveza: ${e.message}")
            }
        }
    }

    fun editBeerComment(syncId: String, newComment: String) {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                val updated = manager.editBeerComment(syncId, newComment)
                if (updated) {
                    log("Comentario editado en syncId=${syncId.take(8)}... (Nueva mutación durable registrada)")
                } else {
                    log("No se pudo editar el comentario en syncId=${syncId.take(8)}...")
                }
                refreshState()
            } catch (e: Exception) {
                log("Error al editar comentario: ${e.message}")
            }
        }
    }

    fun deleteBeer(syncId: String) {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                val deleted = manager.deleteBeer(syncId)
                if (deleted) {
                    log("Cerveza borrada (Tombstone): syncId=${syncId.take(8)}...")
                } else {
                    log("No se pudo borrar syncId=${syncId.take(8)}...")
                }
                refreshState()
            } catch (e: Exception) {
                log("Error al borrar cerveza: ${e.message}")
            }
        }
    }

    fun executePush() {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                log("Iniciando PUSH de mutaciones pendientes hacia Firestore Emulator...")
                val results = manager.pushAllPending()
                if (results.isEmpty()) {
                    log("PUSH completado: no había mutaciones pendientes.")
                } else {
                    results.forEach { (syncId, result) ->
                        when (result) {
                            is C4CoordinatorPushResult.Success -> {
                                log("PUSH EXITOSO [${syncId.take(8)}...]: plan=${result.plan::class.simpleName}, nueva rev=${result.newRemoteRevision}")
                            }
                            is C4CoordinatorPushResult.IdempotentRecovery -> {
                                log("PUSH RECUPERACIÓN IDEMPOTENTE [${syncId.take(8)}...]: rev=${result.currentRemoteRevision}")
                            }
                            is C4CoordinatorPushResult.Conflict -> {
                                log("PUSH CONFLICTO [${syncId.take(8)}...]: rev=${result.currentRemoteRevision}, motivo=${result.reason}")
                            }
                            is C4CoordinatorPushResult.Blocked -> {
                                log("PUSH BLOQUEADO [${syncId.take(8)}...]: ${result.reason}")
                            }
                            is C4CoordinatorPushResult.StorageFailed -> {
                                log("PUSH FALLO STORAGE [${syncId.take(8)}...]: ${result.reason}")
                            }
                            C4CoordinatorPushResult.Offline -> {
                                log("PUSH OFFLINE [${syncId.take(8)}...]")
                            }
                            C4CoordinatorPushResult.NotFound -> {
                                log("PUSH NO ENCONTRADO [${syncId.take(8)}...]")
                            }
                        }
                    }
                }
                refreshState()
            } catch (e: Exception) {
                log("Error durante PUSH: ${e.message}")
            }
        }
    }

    fun executePull() {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                log("Iniciando PULL de cervezas locales para verificar reconciliación causal...")
                val currentBeers = manager.getActiveBeers()
                if (currentBeers.isEmpty()) {
                    log("PULL: No hay cervezas locales para reconciliar.")
                } else {
                    for (beer in currentBeers) {
                        val result = manager.pullBeer(beer.syncId)
                        when (result) {
                            is C4CoordinatorPullResult.Applied -> {
                                log("PULL APLICADO [${beer.syncId.take(8)}...]: plan=${result.plan::class.simpleName}")
                            }
                            is C4CoordinatorPullResult.Blocked -> {
                                log("PULL BLOQUEADO [${beer.syncId.take(8)}...]: ${result.reason}")
                            }
                            C4CoordinatorPullResult.NoOp -> {
                                log("PULL AL DÍA (NoOp) [${beer.syncId.take(8)}...]")
                            }
                            C4CoordinatorPullResult.IncompleteReadIgnored -> {
                                log("PULL LECTURA INCOMPLETA IGNORADA [${beer.syncId.take(8)}...]")
                            }
                        }
                    }
                }
                refreshState()
            } catch (e: Exception) {
                log("Error durante PULL: ${e.message}")
            }
        }
    }

    suspend fun refreshState() {
        val manager = syncManager ?: return
        withContext(Dispatchers.IO) {
            val beers = manager.getActiveBeers()
            val mutations = manager.getPendingMutations()
            withContext(Dispatchers.Main) {
                beersList.clear()
                beersList.addAll(beers)
                mutationHistory.clear()
                mutationHistory.addAll(mutations)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        syncManager?.close()
    }
}
