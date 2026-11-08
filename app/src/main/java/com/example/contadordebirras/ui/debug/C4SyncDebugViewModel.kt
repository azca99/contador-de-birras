package com.example.contadordebirras.ui.debug

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.sync.c4.C4AppSyncManager
import com.example.contadordebirras.sync.c4.C4CoordinatorPullResult
import com.example.contadordebirras.sync.c4.C4CoordinatorPushResult
import com.example.contadordebirras.sync.c4.C4StagingCutoverManager
import com.example.contadordebirras.sync.c4.C4SyncConfig
import com.example.contadordebirras.sync.c4.C4SyncConstants
import com.example.contadordebirras.sync.c4.C4TestEnvironmentFactory
import com.example.contadordebirras.sync.c4.CutoverResult
import com.example.contadordebirras.sync.c4.LegacyBeerFixtures
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
    val authEmail = mutableStateOf("c4_pilot_user@demo-contador-c44.test")
    val statusMessage = mutableStateOf("Motor C4 inactivo. Activa el toggle para conectar al entorno aislado.")
    val beersList = mutableStateListOf<BeerEntity>()
    val mutationHistory = mutableStateListOf<LocalMutationHistoryEntity>()
    val executionLogs = mutableStateListOf<String>()

    private var syncManager: C4AppSyncManager? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        if (C4SyncConfig.isEnabled && C4SyncConfig.engineMode == com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE) {
            toggleC4(true)
        }
    }

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

                    val targetEmail = authEmail.value.trim().ifBlank { "c4_shared_user@demo-contador-c44.test" }
                    val targetPassword = "password123456"

                    val defaultAuth = com.google.firebase.auth.FirebaseAuth.getInstance()
                    val userCred = withContext(Dispatchers.IO) {
                        try {
                            defaultAuth.signInWithEmailAndPassword(targetEmail, targetPassword).await()
                        } catch (_: Exception) {
                            defaultAuth.createUserWithEmailAndPassword(targetEmail, targetPassword).await()
                        }
                    }
                    val userUid = userCred.user?.uid
                        ?: throw IllegalStateException("Firebase Auth Emulator no devolvió un usuario autenticado válido.")

                    // Reutilizar la sesión autenticada del usuario piloto en demo-contador-c44
                    val instances = C4TestEnvironmentFactory.getAuthenticatedPilotInstances(
                        context = getApplication(),
                        expectedUid = userUid
                    )

                    syncManager = C4AppSyncManager.createForTesting(
                        context = getApplication(),
                        ownerUid = userUid,
                        host = resolvedHost,
                        inMemory = false,
                        dbName = "c4_debug_disposable.db",
                        instances = instances
                    )

                    val persistedMode = com.example.contadordebirras.sync.c4.C4SyncStateStore.getEngineMode(
                        getApplication(),
                        userUid
                    )

                    // 5. El panel de diagnóstico tampoco debe reactivar una cuenta HALTED simplemente por activar el interruptor.
                    // Cualquier recuperación debe ser explícita y segura.
                    if (persistedMode == com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED ||
                        persistedMode == com.example.contadordebirras.sync.c4.SyncEngineMode.CUTOVER_IN_PROGRESS
                    ) {
                        C4SyncConfig.isEnabled = false
                        C4SyncConfig.engineMode = persistedMode
                        isC4Active.value = false
                        statusMessage.value = "Cuenta en estado $persistedMode. Reactivación automática mediante interruptor bloqueada. Usa la acción explícita de recuperación."
                        log("SEGURIDAD: La cuenta $userUid está en $persistedMode. Reactivación automática rechazada.")
                        refreshState()
                        return@launch
                    }

                    // Activar el modo C4 persistido para este usuario piloto
                    C4SyncConfig.isEnabled = true
                    C4SyncConfig.engineMode = com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE
                    com.example.contadordebirras.sync.c4.C4SyncStateStore.setEngineMode(
                        getApplication(),
                        userUid,
                        com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE
                    )

                    statusMessage.value = "Conectado a Firebase Emulator ($resolvedHost) | UID: ${userUid.take(8)}... | Email: $targetEmail"
                    log("C4 inicializado con éxito en $resolvedHost (proyecto: ${C4SyncConstants.DEMO_PROJECT_ID}, authUid: $userUid, user: $targetEmail)")
                    refreshState()
                } catch (e: Exception) {
                    C4SyncConfig.isEnabled = false
                    C4SyncConfig.engineMode = com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED
                    isC4Active.value = false
                    syncManager?.close()
                    syncManager = null
                    statusMessage.value = "Error al iniciar C4: ${e.message} (Estado: HALTED)"
                    log("ERROR de autenticación / conexión: ${e.message}. Motor detenido en HALTED.")
                }
            } else {
                val oldUid = syncManager?.ownerUid
                C4SyncConfig.isEnabled = false
                isC4Active.value = false

                if (!oldUid.isNullOrBlank()) {
                    val currentPersisted = com.example.contadordebirras.sync.c4.C4SyncStateStore.getEngineMode(
                        getApplication(),
                        oldUid
                    )
                    // Si ya estaba en C4_ACTIVE o CUTOVER, NUNCA volver a LEGACY:
                    // mantener el bloqueo del motor Legacy persistiendo HALTED y conservando las mutaciones pendientes.
                    val targetMode = if (currentPersisted == com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE ||
                        currentPersisted == com.example.contadordebirras.sync.c4.SyncEngineMode.CUTOVER_IN_PROGRESS
                    ) {
                        com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED
                    } else {
                        currentPersisted
                    }
                    com.example.contadordebirras.sync.c4.C4SyncStateStore.setEngineMode(
                        getApplication(),
                        oldUid,
                        targetMode
                    )
                    C4SyncConfig.engineMode = targetMode
                } else {
                    C4SyncConfig.engineMode = com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED
                }

                syncManager?.close()
                syncManager = null
                statusMessage.value = "Motor C4 desactivado en UI. Cuenta en HALTED (Legacy bloqueado; mutaciones locales conservadas)."
                log("C4 desactivado. Cuenta en estado HALTED (Legacy bloqueado; mutaciones pendientes conservadas).")
            }
        }
    }

    /**
     * Recuperación explícita y consciente de una cuenta en estado HALTED.
     */
    fun recoverHaltedAccountExplicitly() {
        viewModelScope.launch {
            val defaultAuth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val userUid = defaultAuth.currentUser?.uid ?: syncManager?.ownerUid
            if (userUid.isNullOrBlank()) {
                statusMessage.value = "No se puede recuperar: no hay usuario autenticado en Firebase Emulator."
                log("Recuperación fallida: UID no disponible.")
                return@launch
            }
            log("Ejecutando recuperación explícita para UID: $userUid a C4_ACTIVE...")
            com.example.contadordebirras.sync.c4.C4SyncStateStore.setEngineMode(
                getApplication(),
                userUid,
                com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE
            )
            C4SyncConfig.isEnabled = true
            C4SyncConfig.engineMode = com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE
            isC4Active.value = true
            statusMessage.value = "Cuenta recuperada explícitamente a C4_ACTIVE."
            log("Recuperación completada con éxito. C4_ACTIVE establecido.")
            toggleC4(true)
        }
    }

    fun triggerWorkManagerSync() {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                log("Encolando BeerSyncWorker en WorkManager para ${manager.ownerUid.take(8)}...")
                val scheduler = com.example.contadordebirras.worker.WorkManagerBeerSyncScheduler(getApplication())
                scheduler.requestSync(manager.ownerUid)
                log("BeerSyncWorker encolado con éxito. Se ejecutará en segundo plano contra Firebase Emulator.")
            } catch (e: Exception) {
                log("Error al encolar WorkManager: ${e.message}")
            }
        }
    }

    fun clearLocalDatabase() {
        val manager = syncManager ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                manager.database.clearAllTables()
            }
            log("Base Room local reiniciada (tablas vaciadas).")
            refreshState()
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
                log("Iniciando PULL por colección (Descubrimiento remoto desde SERVER)...")
                val results = manager.pullRemoteCollection()
                if (results.isEmpty()) {
                    log("PULL Colección: No se encontraron documentos remotos para el propietario.")
                } else {
                    log("PULL Colección: Descubiertos y procesados ${results.size} documentos remotos.")
                    for ((syncId, result) in results) {
                        when (result) {
                            is C4CoordinatorPullResult.Applied -> {
                                log("PULL APLICADO [${syncId.take(8)}...]: plan=${result.plan::class.simpleName}")
                            }
                            is C4CoordinatorPullResult.Blocked -> {
                                log("PULL BLOQUEADO [${syncId.take(8)}...]: ${result.reason}")
                            }
                            C4CoordinatorPullResult.NoOp -> {
                                log("PULL AL DÍA (NoOp) [${syncId.take(8)}...]")
                            }
                            C4CoordinatorPullResult.IncompleteReadIgnored -> {
                                log("PULL LECTURA INCOMPLETA IGNORADA [${syncId.take(8)}...]")
                            }
                        }
                    }
                }
                refreshState()
            } catch (e: Exception) {
                log("Error durante PULL por colección: ${e.message}")
            }
        }
    }

    fun seedLegacyScenario() {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                log("Sembrando escenario legacy (Fase C4.5)...")
                withContext(Dispatchers.IO) {
                    val uid = manager.ownerUid
                    manager.database.clearAllTables()

                    // 1. Cerveza 1: SYNCED 0/0 en Room y en Firestore (con foto)
                    val beer1SyncId = UUID.randomUUID().toString()
                    val beer1 = LegacyBeerFixtures.createLegacyRoomBeer(
                        ownerUid = uid,
                        syncId = beer1SyncId,
                        syncStatus = SyncStatus.SYNCED,
                        comment = "Legacy Birra 1 Sincronizada",
                        hasRemotePhoto = true
                    )
                    manager.database.beerDao().insertBeer(beer1)

                    val doc1Payload = LegacyBeerFixtures.createLegacyFirestorePayload(
                        userId = uid,
                        syncId = beer1SyncId,
                        comment = "Legacy Birra 1 Sincronizada",
                        hasPhoto = true
                    )
                    manager.coordinator.firestoreAdapter.getDocRef(beer1SyncId).set(doc1Payload).await()

                    // 2. Cerveza 2: PENDING 0/0 en Room (creada offline bajo legacy)
                    val beer2SyncId = UUID.randomUUID().toString()
                    val beer2 = LegacyBeerFixtures.createLegacyRoomBeer(
                        ownerUid = uid,
                        syncId = beer2SyncId,
                        syncStatus = SyncStatus.PENDING,
                        comment = "Legacy Birra 2 Offline"
                    )
                    manager.database.beerDao().insertBeer(beer2)

                    // 3. Cerveza 3: DELETED 0/0 en Room (borrada offline bajo legacy)
                    val beer3SyncId = UUID.randomUUID().toString()
                    val beer3 = LegacyBeerFixtures.createLegacyRoomBeer(
                        ownerUid = uid,
                        syncId = beer3SyncId,
                        syncStatus = SyncStatus.DELETED,
                        comment = "Legacy Birra 3 Borrada"
                    )
                    manager.database.beerDao().insertBeer(beer3)
                    val doc3Payload = LegacyBeerFixtures.createLegacyFirestorePayload(
                        userId = uid,
                        syncId = beer3SyncId,
                        comment = "Legacy Birra 3 Borrada"
                    )
                    manager.coordinator.firestoreAdapter.getDocRef(beer3SyncId).set(doc3Payload).await()

                    // 4. Cerveza 4: En Firestore solamente (creada en otro dispositivo bajo legacy)
                    val beer4SyncId = UUID.randomUUID().toString()
                    val doc4Payload = LegacyBeerFixtures.createLegacyFirestorePayload(
                        userId = uid,
                        syncId = beer4SyncId,
                        comment = "Legacy Birra 4 Remota Otro Dispositivo"
                    )
                    manager.coordinator.firestoreAdapter.getDocRef(beer4SyncId).set(doc4Payload).await()
                }
                log("Escenario legacy sembrado con éxito:")
                log(" • Room: 1 SYNCED (con foto), 1 PENDING offline, 1 DELETED offline (todos 0/0)")
                log(" • Firestore: 3 docs legacy sin revisión (Beer 1, Beer 3, Beer 4)")
                refreshState()
            } catch (e: Exception) {
                log("Error al sembrar escenario legacy: ${e.message}")
            }
        }
    }

    fun executeCutover() {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                log("Iniciando Staging Cutover a Protocolo C4...")
                val cutoverManager = C4StagingCutoverManager(
                    coordinator = manager.coordinator,
                    persistence = manager.persistence,
                    firestoreAdapter = manager.coordinator.firestoreAdapter,
                    context = getApplication()
                )
                val result = cutoverManager.executeCutover(manager.ownerUid)
                when (result) {
                    is CutoverResult.Completed -> {
                        if (result.alreadyMigrated) {
                            log("Cutover COMPLETADO (ya estaba 100% migrado e idempotente, total activas: ${result.totalActiveBeers})")
                        } else {
                            log("Cutover COMPLETADO con éxito:")
                            log(" • Remotos ascendidos a rev 1: ${result.upgradedRemoteCount}")
                            log(" • Locales alineadas a moderna: ${result.localAlignedCount}")
                            log(" • Locales offline promovidas a C4: ${result.localPromotedCount}")
                            log(" • Tombstones locales procesados: ${result.localTombstonesCount}")
                            log(" • Remotas descubiertas: ${result.remoteDiscoveredCount}")
                            log(" • PUSH pendientes confirmados: ${result.pushedPendingCount}")
                        }
                        result.details.forEach { log("   $it") }
                    }
                    is CutoverResult.Incomplete -> {
                        log("Cutover INCOMPLETO (mutaciones preservadas en Room):")
                        log(" • Mutaciones pendientes: ${result.pendingMutationsCount}")
                        log(" • Cervezas pendientes: ${result.pendingBeersCount}")
                        log(" • Fallos Storage: ${result.storageFailuresCount}")
                        log(" • PUSH offline: ${result.offlinePushesCount}")
                        result.details.forEach { log("   $it") }
                    }
                    is CutoverResult.Blocked -> {
                        log("Cutover BLOQUEADO: ${result.reason}")
                        result.details.forEach { log("   $it") }
                    }
                }
                refreshState()
            } catch (e: Exception) {
                log("Excepción durante cutover: ${e.message}")
            }
        }
    }

    fun rollbackToLegacy() {
        val manager = syncManager ?: return
        viewModelScope.launch {
            try {
                val cutoverManager = C4StagingCutoverManager(
                    coordinator = manager.coordinator,
                    persistence = manager.persistence,
                    firestoreAdapter = manager.coordinator.firestoreAdapter,
                    context = getApplication()
                )
                val rollbackResult = cutoverManager.rollbackToLegacy(manager.ownerUid)
                statusMessage.value = if (rollbackResult.isLegacyReactivated) {
                    "Motor C4 desactivado. Motor legacy reactivado de forma segura."
                } else {
                    "Motor C4 desactivado. Motor DETENIDO en estado seguro (no reactiva legacy)."
                }
                log("Rollback ejecutado: ${rollbackResult.warningMessage}")
                refreshState()
            } catch (e: Exception) {
                log("Error al revertir a legacy: ${e.message}")
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
