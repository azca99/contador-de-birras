package com.example.contadordebirras.domain

import android.content.Context
import android.location.Geocoder
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID

class BeerRepository(
    private val beerDao: BeerDao, 
    private val context: Context, 
    private val authRepository: AuthRepository,
    private val syncScheduler: BeerSyncScheduler,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val mutationIdProvider: () -> String = { UUID.randomUUID().toString() }
) {
    private val localMutationMutex = Mutex()
    
    private fun currentOwnerUid(): String = authRepository.currentUser.value?.uid ?: "guest_local"

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allBeers: Flow<List<BeerEntity>> = authRepository.currentUser
        .map { it?.uid ?: "guest_local" }
        .distinctUntilChanged()
        .flatMapLatest { uid -> beerDao.getAllBeers(uid) }

    fun observeBeers(ownerUid: String): Flow<List<BeerEntity>> = beerDao.getAllBeers(ownerUid)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val totalCount: Flow<Int> = authRepository.currentUser
        .map { it?.uid ?: "guest_local" }
        .distinctUntilChanged()
        .flatMapLatest { uid -> beerDao.getTotalCount(uid) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val lastBeer: Flow<BeerEntity?> = authRepository.currentUser
        .map { it?.uid ?: "guest_local" }
        .distinctUntilChanged()
        .flatMapLatest { uid -> beerDao.getLastBeer(uid) }
    
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repoScope.launch {
            authRepository.currentUser.collect { user ->
                if (user != null) {
                    val activationMgr = com.example.contadordebirras.sync.c4.C4ControlledActivationManager(context)
                    val protocolResult = activationMgr.detectAndEnforceAccountProtocol(user.uid)
                    when (protocolResult) {
                        is com.example.contadordebirras.sync.c4.ProtocolResult.C4Active,
                        is com.example.contadordebirras.sync.c4.ProtocolResult.Legacy -> {
                            syncScheduler.schedulePeriodicSync(user.uid)
                            syncScheduler.requestSync(user.uid)
                        }
                        is com.example.contadordebirras.sync.c4.ProtocolResult.Blocked -> {
                            android.util.Log.w("C4Protocol", "Sincronización bloqueada para ${user.uid}: ${protocolResult.reason}")
                        }
                        is com.example.contadordebirras.sync.c4.ProtocolResult.Unverified -> {
                            android.util.Log.w("C4Protocol", "Sincronización retenida para ${user.uid}: protocolo remoto no verificado y sin estado local fiable.")
                        }
                    }
                }
            }
        }
    }

    fun requestSync() {
        authRepository.currentUser.value?.uid?.let { uid ->
            syncScheduler.requestSync(uid)
        }
    }

    companion object {
        const val C4_PILOT_USER_EMAIL = "c4_pilot_user@demo-contador-c44.test"
    }

    private suspend fun ensureSafeExecutionMode(ownerUid: String): Boolean {
        val isPilotPackage = context.packageName?.endsWith(".c4pilot") == true
        if (isPilotPackage) {
            val currentUser = authRepository.currentUser.value
            if (currentUser == null || ownerUid.isBlank() || ownerUid == "guest_local") {
                throw IllegalStateException("Operación bloqueada en piloto C4: la sesión aún no está lista.")
            }
            if (currentUser.email != C4_PILOT_USER_EMAIL) {
                throw IllegalStateException(
                    "Operación bloqueada en piloto C4: la cuenta autenticada (${currentUser.email}) no corresponde a la cuenta piloto autorizada ($C4_PILOT_USER_EMAIL)."
                )
            }
            val persistedMode = try {
                com.example.contadordebirras.sync.c4.C4SyncStateStore.getEngineMode(context, ownerUid)
            } catch (e: Exception) {
                throw IllegalStateException("Operación bloqueada en piloto C4: fallo al leer el estado persistente de C4: ${e.message}", e)
            }
            if (persistedMode != com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE ||
                !com.example.contadordebirras.sync.c4.C4SyncConfig.isC4Active()
            ) {
                throw IllegalStateException(
                    "Operación bloqueada en piloto C4: el modo actual no es C4_ACTIVE (persistido=$persistedMode, enMemoria=${com.example.contadordebirras.sync.c4.C4SyncConfig.engineMode})."
                )
            }
            return true
        } else {
            val persistedMode = try {
                com.example.contadordebirras.sync.c4.C4SyncStateStore.getEngineMode(context, ownerUid)
            } catch (_: Exception) {
                com.example.contadordebirras.sync.c4.SyncEngineMode.LEGACY
            }
            val isCurrentUid = com.example.contadordebirras.sync.c4.C4SyncConfig.currentActiveOwnerUid == ownerUid
            val effectiveMode = if (persistedMode == com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED ||
                (isCurrentUid && com.example.contadordebirras.sync.c4.C4SyncConfig.engineMode == com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED)
            ) {
                com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED
            } else if (persistedMode == com.example.contadordebirras.sync.c4.SyncEngineMode.CUTOVER_IN_PROGRESS ||
                (isCurrentUid && com.example.contadordebirras.sync.c4.C4SyncConfig.engineMode == com.example.contadordebirras.sync.c4.SyncEngineMode.CUTOVER_IN_PROGRESS)
            ) {
                com.example.contadordebirras.sync.c4.SyncEngineMode.CUTOVER_IN_PROGRESS
            } else {
                persistedMode
            }

            if (effectiveMode == com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED ||
                effectiveMode == com.example.contadordebirras.sync.c4.SyncEngineMode.CUTOVER_IN_PROGRESS
            ) {
                throw IllegalStateException("Operación bloqueada: la cuenta está en estado $effectiveMode para proteger los datos C4.")
            }
            val isAccountC4 = persistedMode == com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE ||
                    (isCurrentUid && com.example.contadordebirras.sync.c4.C4SyncConfig.engineMode == com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE)

            if (isAccountC4) {
                val isRealProductionApp = context.packageName?.endsWith(".c4pilot") == false &&
                        !context.packageName.isNullOrBlank()
                val isOperational = if (isRealProductionApp) {
                    com.example.contadordebirras.sync.c4.C4SyncConfig.isProductionC4FeatureEnabled &&
                            com.example.contadordebirras.sync.c4.C4SyncConfig.isUserAuthorizedForC4(ownerUid) &&
                            (if (isCurrentUid) com.example.contadordebirras.sync.c4.C4SyncConfig.isC4Active() else persistedMode == com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE)
                } else {
                    com.example.contadordebirras.sync.c4.C4SyncConfig.isC4Active()
                }
                if (!isOperational) {
                    throw IllegalStateException(
                        "Operación bloqueada: la cuenta está migrada a C4 pero el protocolo C4 está deshabilitado en este entorno. Se impiden nuevas escrituras para evitar mutaciones sin sincronización."
                    )
                }
                if (ownerUid.isBlank() || ownerUid == "guest_local") {
                    throw IllegalStateException("Operación bloqueada: el protocolo C4 requiere una sesión de usuario autenticada.")
                }
                return true
            }

            if (!isAccountC4 && ownerUid.isNotBlank() && ownerUid != "guest_local") {
                val isSessionVerified = com.example.contadordebirras.sync.c4.C4SyncConfig.isSessionProtocolVerified(ownerUid)
                if (!isSessionVerified) {
                    val activationMgr = com.example.contadordebirras.sync.c4.C4ControlledActivationManager(context)
                    val protocolResult = activationMgr.detectAndEnforceAccountProtocol(ownerUid)
                    when (protocolResult) {
                        is com.example.contadordebirras.sync.c4.ProtocolResult.C4Active -> return true
                        is com.example.contadordebirras.sync.c4.ProtocolResult.Legacy -> return false
                        is com.example.contadordebirras.sync.c4.ProtocolResult.Blocked -> {
                            throw IllegalStateException("Operación bloqueada: ${protocolResult.reason}")
                        }
                        is com.example.contadordebirras.sync.c4.ProtocolResult.Unverified -> {
                            throw IllegalStateException(
                                "Operación bloqueada: No se puede verificar el protocolo de la cuenta con el servidor y no existe un estado local fiable en esta sesión. Conéctate a la red para verificar el estado de la cuenta."
                            )
                        }
                    }
                }
            }

            return false
        }
    }

    suspend fun addBeer(type: BeerType, timestamp: Long, latitude: Double? = null, longitude: Double? = null, photoUri: String? = null, comment: String? = null, photoSource: String? = null): Long {
        return withContext(Dispatchers.IO) {
            val ownerUid = currentOwnerUid()
            val isC4 = ensureSafeExecutionMode(ownerUid)

            val now = nowProvider()
            val syncId = UUID.randomUUID().toString()
            val mutationId = mutationIdProvider()
            val allFields = BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS) ?: ""

            if (isC4) {
                val record = com.example.contadordebirras.data.LocalMutationHistoryEntity(
                    mutationId = mutationId,
                    syncId = syncId,
                    ownerUid = ownerUid,
                    timestamp = now,
                    dirtyFields = allFields,
                    baseRevision = 0L,
                    status = com.example.contadordebirras.data.MutationStatus.PENDING,
                    isLocalOnlyCreation = true
                )
                beerDao.supersedePriorPendingHistoryRecords(syncId, ownerUid, mutationId)
                beerDao.insertHistoryRecord(record)
            }

            val beer = BeerEntity(
                syncId = syncId,
                type = type, timestamp = timestamp, latitude = latitude, longitude = longitude, 
                photoUri = photoUri, comment = comment, locationName = null, photoSource = photoSource,
                syncStatus = SyncStatus.PENDING, updatedAt = now,
                ownerUid = ownerUid,
                remoteRevision = 0L,
                baseRevision = 0L,
                dirtyFields = allFields,
                lastMutationId = mutationId,
                deletedAt = null
            )
            val id = beerDao.insertBeer(beer)
            if (id > 0) {
                requestSync()
            }
            id
        }
    }

    suspend fun updateBeerLocation(beerId: Long, latitude: Double, longitude: Double) {
        withContext(Dispatchers.IO) {
            var locationName: String? = "Ubicación desconocida"
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    locationName = address.locality ?: address.subAdminArea ?: address.adminArea ?: address.countryName ?: "Ubicación desconocida"
                }
            } catch (e: Exception) {}
            
            val updated = localMutationMutex.withLock {
                val ownerUid = currentOwnerUid()
                val current = beerDao.getBeerById(beerId.toInt(), ownerUid) ?: return@withLock false
                if (current.syncStatus == SyncStatus.DELETED) return@withLock false

                val isC4 = ensureSafeExecutionMode(ownerUid)

                val locationChanged = current.latitude != latitude ||
                        current.longitude != longitude ||
                        current.locationName != locationName
                if (!locationChanged) {
                    return@withLock false
                }

                val changedFields = setOf(BeerDirtyField.LOCATION)
                val baseRevision = if (current.syncStatus == SyncStatus.PENDING) current.baseRevision else current.remoteRevision
                val dirtyFields = if (current.syncStatus == SyncStatus.PENDING) {
                    BeerDirtyFieldsCodec.merge(current.dirtyFields, changedFields)
                } else {
                    BeerDirtyFieldsCodec.encode(changedFields)
                }
                val mutationId = mutationIdProvider()
                val now = nowProvider()

                if (isC4) {
                    val isCreation = current.remoteRevision == 0L
                    val historyDirtyFields = if (isCreation) {
                        BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS) ?: ""
                    } else {
                        dirtyFields ?: ""
                    }
                    val record = com.example.contadordebirras.data.LocalMutationHistoryEntity(
                        mutationId = mutationId,
                        syncId = current.syncId,
                        ownerUid = ownerUid,
                        timestamp = now,
                        dirtyFields = historyDirtyFields,
                        baseRevision = baseRevision,
                        status = com.example.contadordebirras.data.MutationStatus.PENDING,
                        isLocalOnlyCreation = isCreation
                    )
                    beerDao.supersedePriorPendingHistoryRecords(current.syncId, ownerUid, mutationId)
                    beerDao.insertHistoryRecord(record)
                }

                val updatedRows = beerDao.updateBeerLocationLocalMutation(
                    id = current.id,
                    latitude = latitude,
                    longitude = longitude,
                    locationName = locationName,
                    updatedAt = now,
                    ownerUid = ownerUid,
                    baseRevision = baseRevision,
                    dirtyFields = dirtyFields,
                    lastMutationId = mutationId
                )
                updatedRows > 0
            }
            if (updated) {
                requestSync()
            }
        }
    }

    suspend fun deleteBeer(beer: BeerEntity) {
        withContext(Dispatchers.IO) {
            val updated = localMutationMutex.withLock {
                val ownerUid = currentOwnerUid()
                if (beer.ownerUid != ownerUid) return@withLock false

                val current = beerDao.getBeerById(beer.id, ownerUid) ?: return@withLock false
                if (current.syncStatus == SyncStatus.DELETED) return@withLock false

                val isC4 = ensureSafeExecutionMode(ownerUid)

                val baseRevision = if (current.syncStatus == SyncStatus.PENDING) current.baseRevision else current.remoteRevision
                val now = nowProvider()
                val mutationId = mutationIdProvider()

                if (isC4) {
                    val record = com.example.contadordebirras.data.LocalMutationHistoryEntity(
                        mutationId = mutationId,
                        syncId = current.syncId,
                        ownerUid = ownerUid,
                        timestamp = now,
                        dirtyFields = "",
                        baseRevision = baseRevision,
                        status = com.example.contadordebirras.data.MutationStatus.PENDING,
                        isLocalOnlyCreation = false
                    )
                    beerDao.supersedePriorPendingHistoryRecords(current.syncId, ownerUid, mutationId)
                    beerDao.insertHistoryRecord(record)
                }

                val updatedRows = beerDao.softDeleteBeerLocalMutation(
                    id = current.id,
                    updatedAt = now,
                    deletedAt = now,
                    ownerUid = ownerUid,
                    baseRevision = baseRevision,
                    lastMutationId = mutationId
                )
                updatedRows > 0
            }
            if (updated) {
                requestSync()
            }
        }
    }

    suspend fun updateBeerDetails(
        beerId: Long,
        type: BeerType,
        comment: String?,
        typeChanged: Boolean,
        commentChanged: Boolean
    ) {
        withContext(Dispatchers.IO) {
            val updated = localMutationMutex.withLock {
                val ownerUid = currentOwnerUid()
                val current = beerDao.getBeerById(beerId.toInt(), ownerUid) ?: return@withLock false
                if (current.syncStatus == SyncStatus.DELETED) return@withLock false

                val isC4 = ensureSafeExecutionMode(ownerUid)

                val changedFields = mutableSetOf<BeerDirtyField>()
                if (typeChanged && current.type != type) {
                    changedFields.add(BeerDirtyField.TYPE)
                }
                if (commentChanged && current.comment.orEmpty() != comment.orEmpty()) {
                    changedFields.add(BeerDirtyField.COMMENT)
                }

                if (changedFields.isEmpty()) {
                    return@withLock false
                }

                val typeToPersist = if (changedFields.contains(BeerDirtyField.TYPE)) type else current.type
                val commentToPersist = if (changedFields.contains(BeerDirtyField.COMMENT)) comment else current.comment

                val baseRevision = if (current.syncStatus == SyncStatus.PENDING) current.baseRevision else current.remoteRevision
                val dirtyFields = if (current.syncStatus == SyncStatus.PENDING) {
                    BeerDirtyFieldsCodec.merge(current.dirtyFields, changedFields)
                } else {
                    BeerDirtyFieldsCodec.encode(changedFields)
                }
                val mutationId = mutationIdProvider()
                val now = nowProvider()

                if (isC4) {
                    val isCreation = current.remoteRevision == 0L
                    val historyDirtyFields = if (isCreation) {
                        BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS) ?: ""
                    } else {
                        dirtyFields ?: ""
                    }
                    val record = com.example.contadordebirras.data.LocalMutationHistoryEntity(
                        mutationId = mutationId,
                        syncId = current.syncId,
                        ownerUid = ownerUid,
                        timestamp = now,
                        dirtyFields = historyDirtyFields,
                        baseRevision = baseRevision,
                        status = com.example.contadordebirras.data.MutationStatus.PENDING,
                        isLocalOnlyCreation = isCreation
                    )
                    beerDao.supersedePriorPendingHistoryRecords(current.syncId, ownerUid, mutationId)
                    beerDao.insertHistoryRecord(record)
                }

                val updatedRows = beerDao.updateBeerDetailsLocalMutation(
                    id = current.id,
                    type = typeToPersist,
                    comment = commentToPersist,
                    updatedAt = now,
                    ownerUid = ownerUid,
                    baseRevision = baseRevision,
                    dirtyFields = dirtyFields,
                    lastMutationId = mutationId
                )
                updatedRows > 0
            }
            if (updated) {
                requestSync()
            }
        }
    }

    suspend fun updateBeerDetails(
        beerId: Int,
        type: BeerType,
        comment: String?,
        typeChanged: Boolean,
        commentChanged: Boolean
    ) {
        updateBeerDetails(beerId.toLong(), type, comment, typeChanged, commentChanged)
    }


}
