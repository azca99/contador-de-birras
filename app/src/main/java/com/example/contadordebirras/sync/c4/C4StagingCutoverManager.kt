package com.example.contadordebirras.sync.c4

import android.content.Context
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Resultado de la ejecución de migración y transición Staging Cutover (Fase C4.5).
 *
 * Taxonomía estricta de seguridad:
 * - [Completed]: Cierre total verificado. Cero mutaciones pendientes, cero entidades legacy 0/0,
 *   todas las fotos subidas y verificadas en Storage. C4 se activa.
 * - [Incomplete]: Cutover parcial por red offline, fallo de Storage o ACK no recibido. Cero pérdida
 *   de datos; las mutaciones permanecen en PENDING en Room para ser reintentadas. C4 NO se activa.
 * - [Blocked]: Precondición fallida, error de red fatal en servidor, o conflicto/anomalía detectada
 *   (ej. documento local ausente en remoto que se preserva intacto para reconciliación). C4 NO se activa.
 */
sealed interface CutoverResult {
    data class Completed(
        val upgradedRemoteCount: Int,
        val localAlignedCount: Int,
        val localPromotedCount: Int,
        val localTombstonesCount: Int,
        val remoteDiscoveredCount: Int,
        val pushedPendingCount: Int,
        val totalActiveBeers: Int,
        val alreadyMigrated: Boolean = false,
        val details: List<String> = emptyList()
    ) : CutoverResult

    data class Incomplete(
        val pendingMutationsCount: Int,
        val pendingBeersCount: Int,
        val storageFailuresCount: Int = 0,
        val offlinePushesCount: Int = 0,
        val blockedPushesCount: Int = 0,
        val conflictsCount: Int = 0,
        val details: List<String> = emptyList()
    ) : CutoverResult

    data class Blocked(
        val reason: String,
        val details: List<String> = emptyList()
    ) : CutoverResult
}

/**
 * Resultado explícito de la operación de Rollback hacia el motor Legacy.
 */
data class RollbackResult(
    val isLocalReverted: Boolean,
    val remoteUpgradedCount: Int,
    val pointOfNoReturnReached: Boolean,
    val warningMessage: String,
    val isRemoteStateUnknown: Boolean = false,
    val isLegacyReactivated: Boolean = false
)

/**
 * Gestor de Transición y Migración Staging Cutover del motor legacy al protocolo C4 (Fase C4.5).
 *
 * GARANTÍAS FORMALES DE SEGURIDAD:
 * 1. Cero pérdida de datos: Ninguna cerveza, comentario, foto, timestamp o ubicación desaparece.
 * 2. Cero suposiciones destructivas: Las cervezas locales sin equivalente remoto NO se marcan DELETED.
 *    Se conservan intactas en Room y se bloquea la transición hasta su reconciliación segura.
 * 3. Verificación de fotografías: Las fotos pendientes se obtienen de su contenido local real,
 *    se suben a Storage versionado y se verifica su existencia antes de confirmar. Si falla,
 *    se mantienen en PENDING sin perder photoUri ni su referencia.
 * 4. Verificación estricta de post-condiciones: NUNCA devuelve Completed si quedan mutaciones PENDING,
 *    conflictos, errores de Storage o PUSH bloqueados.
 * 5. Rollback realista: Define explícitamente la frontera de irreversibilidad remota frente al
 *    rollback local, protegiendo contra la sobreescritura de clientes legacy en Firestore.
 * 6. Exclusión mutua estricta: [C4SyncConfig.syncExecutionMutex] impide ejecución concurrente.
 */
class C4StagingCutoverManager(
    private val coordinator: AndroidC4SyncCoordinator,
    private val persistence: RoomC4SyncPersistence,
    private val firestoreAdapter: FirestoreC4SyncAdapter,
    private val context: Context? = null,
    private val photoResolver: (suspend (String) -> ByteArray?)? = null
) {
    private val beerDao: BeerDao = persistence.beerDao

    /**
     * Ejecuta la transición controlada de la cuenta [ownerUid] hacia el protocolo C4.
     */
    suspend fun executeCutover(ownerUid: String): CutoverResult {
        require(ownerUid.isNotBlank() && ownerUid != "guest_local" && ownerUid != "legacy_unassigned") {
            "PROHIBIDO: Cutover requiere un UID de propietario autenticado válido ($ownerUid)"
        }

        return C4SyncConfig.syncExecutionMutex.withLock {
            val previousMode = C4SyncConfig.engineMode
            val previousEnabled = C4SyncConfig.isEnabled
            C4SyncConfig.engineMode = SyncEngineMode.CUTOVER_IN_PROGRESS

            try {
                val result = withContext(Dispatchers.IO) {
                    performCutoverInternal(ownerUid)
                }

                if (result is CutoverResult.Completed) {
                    C4SyncConfig.engineMode = SyncEngineMode.C4_ACTIVE
                    C4SyncConfig.isEnabled = true
                } else {
                    C4SyncConfig.engineMode = previousMode
                    C4SyncConfig.isEnabled = previousEnabled
                }

                result
            } catch (e: Exception) {
                C4SyncConfig.engineMode = previousMode
                C4SyncConfig.isEnabled = previousEnabled
                CutoverResult.Blocked("Excepción inesperada durante cutover: ${e.message}")
            }
        }
    }

    /**
     * Revierte de forma segura al modo legacy definiendo con transparencia la frontera de reversibilidad.
     *
     * @param ownerUid Si se proporciona, comprueba si existen documentos remotos ya modernizados (rev >= 1)
     *                 en Firestore para documentar si se alcanzó el punto de no retorno remoto.
     */
    suspend fun rollbackToLegacy(ownerUid: String? = null): RollbackResult {
        return C4SyncConfig.syncExecutionMutex.withLock {
            // 1. Consultar el estado remoto ANTES de decidir si puede volver a Legacy
            var remoteUpgradedCount = 0
            var isRemoteStateUnknown = false
            var remoteQueryError: String? = null

            if (!ownerUid.isNullOrBlank()) {
                try {
                    val remoteDocs = firestoreAdapter.queryServerDocuments(ownerUid)
                    remoteUpgradedCount = remoteDocs.count { it.revision >= 1L }
                } catch (e: Exception) {
                    isRemoteStateUnknown = true
                    remoteQueryError = e.message ?: e.javaClass.simpleName
                }
            } else {
                isRemoteStateUnknown = true
                remoteQueryError = "ownerUid no proporcionado para verificar estado remoto"
            }

            // Desactivar siempre el motor C4 local
            C4SyncConfig.isEnabled = false

            // 2. Evaluar según el estado remoto verificado
            if (isRemoteStateUnknown) {
                // Estado remoto DESCONOCIDO: No reactivar legacy y mantener el motor detenido en estado seguro
                C4SyncConfig.engineMode = SyncEngineMode.HALTED
                val warning = "ESTADO REMOTO DESCONOCIDO: No se pudo verificar el estado en Firestore ($remoteQueryError). Por seguridad para evitar inconsistencias o rechazo de reglas, NO se reactivan escrituras legacy incompatibles y el motor permanece DETENIDO en estado seguro. NO se puede afirmar que el rollback sea seguro hasta verificar el servidor."
                RollbackResult(
                    isLocalReverted = true,
                    remoteUpgradedCount = -1,
                    pointOfNoReturnReached = true,
                    isRemoteStateUnknown = true,
                    isLegacyReactivated = false,
                    warningMessage = warning
                )
            } else if (remoteUpgradedCount > 0) {
                // Existen documentos modernizados: NO reactivar escrituras legacy incompatibles
                C4SyncConfig.engineMode = SyncEngineMode.HALTED
                val warning = "PUNTO DE NO RETORNO ALCANZADO: $remoteUpgradedCount documento(s) en Firestore ya fueron ascendidos a revisión >= 1. Las reglas de Firestore impedirán que clientes legacy modifiquen o borren estos documentos modernizados. NO se reactivan escrituras legacy automáticas para evitar rechazos o inconsistencias. El motor permanece DETENIDO en estado seguro hasta resolver la cuenta o completar la transición."
                RollbackResult(
                    isLocalReverted = true,
                    remoteUpgradedCount = remoteUpgradedCount,
                    pointOfNoReturnReached = true,
                    isRemoteStateUnknown = false,
                    isLegacyReactivated = false,
                    warningMessage = warning
                )
            } else {
                // Estado remoto verificado con 0 documentos modernizados: seguro volver a Legacy
                C4SyncConfig.engineMode = SyncEngineMode.LEGACY
                val warning = "Rollback local seguro completado: Se verificó el estado remoto y ningún documento fue modernizado (0 documentos con revisión >= 1). El motor Legacy se ha reactivado con total seguridad."
                RollbackResult(
                    isLocalReverted = true,
                    remoteUpgradedCount = 0,
                    pointOfNoReturnReached = false,
                    isRemoteStateUnknown = false,
                    isLegacyReactivated = true,
                    warningMessage = warning
                )
            }
        }
    }

    private suspend fun resolvePhotoBytes(photoUriStr: String): ByteArray? {
        if (photoResolver != null) {
            return photoResolver.invoke(photoUriStr)
        }
        return withContext(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(photoUriStr)
                if (uri.scheme == "content") {
                    context?.contentResolver?.openInputStream(uri)?.use { it.readBytes() }
                } else if (uri.scheme == "file") {
                    val file = java.io.File(uri.path ?: photoUriStr)
                    if (file.exists() && file.isFile) file.readBytes()
                    else context?.contentResolver?.openInputStream(uri)?.use { it.readBytes() }
                } else {
                    val file = java.io.File(photoUriStr)
                    if (file.exists() && file.isFile) file.readBytes()
                    else context?.contentResolver?.openInputStream(uri)?.use { it.readBytes() }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun performCutoverInternal(ownerUid: String): CutoverResult {
        val details = mutableListOf<String>()

        // 1. PREVALIDACIÓN: Consultar estado local y remoto ANTES de cualquier modificación
        val localBeers = beerDao.getActiveBeersSync(ownerUid) +
                beerDao.getPendingSyncBeers(ownerUid).filter { it.syncStatus == SyncStatus.DELETED }
        val localBeersBySyncId = localBeers.associateBy { it.syncId }

        val remoteDocs = try {
            firestoreAdapter.queryServerDocuments(ownerUid)
        } catch (e: Exception) {
            return CutoverResult.Blocked("Prevalidación fallida: No se pudo consultar Firestore para cutover: ${e.message}")
        }
        val remoteDocsBySyncId = remoteDocs.associateBy { it.syncId }

        // A. Detectar cervezas SYNCED locales ausentes en Firestore (Cero pérdida de datos / Anti-Data-Loss)
        val absentSyncedLocals = localBeers.filter { localBeer ->
            localBeer.syncStatus == SyncStatus.SYNCED && !remoteDocsBySyncId.containsKey(localBeer.syncId)
        }
        if (absentSyncedLocals.isNotEmpty()) {
            absentSyncedLocals.forEach { absent ->
                details.add("Prevalidación: Local SYNCED ${absent.syncId.take(8)} ausente en remoto: conservada intacta en Room para reconciliación segura.")
            }
            return CutoverResult.Blocked(
                reason = "Prevalidación BLOQUEADA: Se detectaron ${absentSyncedLocals.size} cerveza(s) locales marcadas como SYNCED ausentes en Firestore. Fueron preservadas intactas en Room para garantizar cero pérdida de datos. Ninguna modificación fue realizada en Firestore ni en Room. Requiere reconciliación antes de migrar.",
                details = details
            )
        }

        // B. Detectar incompatibilidades conocidas en documentos remotos
        val corruptedRemoteDocs = remoteDocs.filter { it.revision > 0L && it.fieldRevisions.isEmpty() }
        if (corruptedRemoteDocs.isNotEmpty()) {
            return CutoverResult.Blocked(
                reason = "Prevalidación BLOQUEADA: Se detectaron ${corruptedRemoteDocs.size} documento(s) remotos corruptos con revisión > 0 pero fieldRevisions vacío. Ninguna modificación fue realizada.",
                details = details
            )
        }

        var upgradedRemoteCount = 0
        var localAlignedCount = 0
        var localPromotedCount = 0
        var localTombstonesCount = 0
        var pushedPendingCount = 0
        var storageFailuresCount = 0
        var offlinePushesCount = 0
        var conflictsCount = 0
        var blockedPushesCount = 0

        // 2. PASO A: Ascenso de documentos remotos legacy a revisión 1 mediante CAS autoritativo
        for (remoteDoc in remoteDocs) {
            if (remoteDoc.revision == 0L || remoteDoc.fieldRevisions.isEmpty()) {
                val migrationMutationId = "mut-cutover-upg-${remoteDoc.syncId.take(8)}-${UUID.randomUUID().toString().take(8)}"
                val txResult = firestoreAdapter.executeLegacyUpgrade(
                    syncId = remoteDoc.syncId,
                    ownerUid = ownerUid,
                    migrationMutationId = migrationMutationId
                )

                when (txResult) {
                    is C4TransactionResult.Success, is C4TransactionResult.IdempotentAck -> {
                        upgradedRemoteCount++
                        details.add("Remoto ${remoteDoc.syncId.take(8)} ascendido a rev 1 (mutationId: $migrationMutationId)")
                    }
                    else -> {
                        return CutoverResult.Blocked("Fallo al ascender documento remoto legacy ${remoteDoc.syncId}: $txResult")
                    }
                }
            }
        }

        // Mapeo de revisiones remotas actualizadas tras el ascenso del PASO A
        val updatedRemoteRevisions = remoteDocs.associate {
            it.syncId to if (it.revision >= 1L) it.revision else 1L
        }

        // 3. PASO B: Preparación de entidades locales Room que estaban en 0/0 (Legacy)
        for (localBeer in localBeers) {
            val isLegacyLocal = localBeer.remoteRevision == 0L && localBeer.baseRevision == 0L

            if (isLegacyLocal) {
                when (localBeer.syncStatus) {
                    SyncStatus.PENDING -> {
                        // Creación local offline bajo legacy: promover a local-only (-1/-1) con mutación durable
                        val initialMutationId = "mut-cutover-loc-${UUID.randomUUID().toString().take(8)}"
                        val allFields = BeerDirtyFieldsCodec.encode(BeerDirtyField.entries.toSet()) ?: ""
                        val ok = persistence.prepareLegacyPendingForLocalOnlyCreation(
                            syncId = localBeer.syncId,
                            ownerUid = ownerUid,
                            initialMutationId = initialMutationId,
                            dirtyFields = allFields
                        )
                        if (ok) {
                            localPromotedCount++
                            details.add("Local offline ${localBeer.syncId.take(8)} promovida a C4 CreateActive")
                        } else {
                            return CutoverResult.Blocked("Fallo al promover cerveza local offline ${localBeer.syncId}")
                        }
                    }

                    SyncStatus.DELETED -> {
                        // Borrado local offline bajo legacy: preparar mutación durable de tombstone con revisión remota post-ascenso
                        val baseRev = updatedRemoteRevisions[localBeer.syncId] ?: localBeer.remoteRevision
                        val tombstoneMutationId = "mut-cutover-tomb-${UUID.randomUUID().toString().take(8)}"
                        val ok = persistence.prepareLegacyDeletedForTombstone(
                            syncId = localBeer.syncId,
                            ownerUid = ownerUid,
                            tombstoneMutationId = tombstoneMutationId,
                            baseRevision = baseRev
                        )
                        if (ok) {
                            localTombstonesCount++
                            details.add("Local borrada ${localBeer.syncId.take(8)} preparada como C4 Tombstone (baseRev: $baseRev)")
                        } else {
                            return CutoverResult.Blocked("Fallo al preparar tombstone local ${localBeer.syncId}")
                        }
                    }

                    SyncStatus.SYNCED -> {
                        val remoteDoc = remoteDocsBySyncId[localBeer.syncId]
                        if (remoteDoc != null) {
                            // Cerveza presente en remoto: alinear versión local a revisión moderna remota (mínimo 1)
                            val targetRev = if (remoteDoc.revision >= 1L) remoteDoc.revision else 1L
                            val ok = persistence.alignLegacySyncedToModern(localBeer.syncId, ownerUid, targetRev)
                            if (ok) {
                                localAlignedCount++
                                details.add("Local SYNCED ${localBeer.syncId.take(8)} alineada a rev $targetRev")
                            }
                        }
                    }
                }
            }
        }

        // 4. PASO C: Descubrimiento y Reconciliación Remota por Colección (PULL C4)
        val pullResults = coordinator.executePullCollection(ownerUid)
        var remoteDiscoveredCount = 0
        var pullBlockedCount = 0
        var pullIncompleteReadCount = 0

        pullResults.forEach { (syncId, result) ->
            when (result) {
                is C4CoordinatorPullResult.Applied -> {
                    remoteDiscoveredCount++
                    details.add("PULL aplicado para ${syncId.take(8)}: ${result.plan::class.simpleName}")
                }
                is C4CoordinatorPullResult.Blocked -> {
                    pullBlockedCount++
                    details.add("PULL BLOQUEADO para ${syncId.take(8)}: ${result.reason}")
                }
                is C4CoordinatorPullResult.IncompleteReadIgnored -> {
                    pullIncompleteReadCount++
                    details.add("PULL lectura incompleta ignorada para ${syncId.take(8)}")
                }
                C4CoordinatorPullResult.NoOp -> {
                    // NoOp
                }
            }
        }

        // Si la reconciliación remota falló, bloquear antes de intentar PUSH para mantener datos y mutaciones intactos
        if (pullBlockedCount > 0 || pullIncompleteReadCount > 0) {
            return CutoverResult.Blocked(
                reason = "Cutover BLOQUEADO: La reconciliación remota (PULL) no se completó limpiamente ($pullBlockedCount bloqueado(s), $pullIncompleteReadCount lectura(s) incompleta(s)). Ningún PUSH fue ejecutado; datos y mutaciones locales se mantienen intactos.",
                details = details
            )
        }

        // 6. PASO D: PUSH de mutaciones locales pendientes (Creaciones y Borrados offline preparados)
        val pendingHistory = persistence.historyDao.getPendingMutations(ownerUid)
        for (history in pendingHistory) {
            val localBeer = persistence.beerDao.getBeerBySyncId(history.syncId, ownerUid)
            val photoBytes = if (localBeer?.photoUri != null) {
                resolvePhotoBytes(localBeer.photoUri)
            } else {
                null
            }

            val pushResult = coordinator.executePush(
                syncId = history.syncId,
                ownerUid = ownerUid,
                photoBytes = photoBytes
            )
            when (pushResult) {
                is C4CoordinatorPushResult.Success, is C4CoordinatorPushResult.IdempotentRecovery -> {
                    pushedPendingCount++
                    details.add("PUSH exitoso para ${history.syncId.take(8)} (mutación: ${history.mutationId})")
                }
                is C4CoordinatorPushResult.Offline -> {
                    offlinePushesCount++
                    details.add("PUSH aplazado para ${history.syncId.take(8)}: red offline")
                }
                is C4CoordinatorPushResult.Conflict -> {
                    conflictsCount++
                    details.add("PUSH conflicto para ${history.syncId.take(8)}: ${pushResult.reason}")
                }
                is C4CoordinatorPushResult.Blocked -> {
                    blockedPushesCount++
                    details.add("PUSH advertencia para ${history.syncId.take(8)}: ${pushResult.reason}")
                }
                is C4CoordinatorPushResult.StorageFailed -> {
                    storageFailuresCount++
                    details.add("PUSH storage falló para ${history.syncId.take(8)}: ${pushResult.reason}")
                }
                C4CoordinatorPushResult.NotFound -> {
                    blockedPushesCount++
                    details.add("PUSH entidad local no encontrada para ${history.syncId.take(8)}")
                }
            }
        }

        // 7. VERIFICACIÓN ESTRICTA DE CONDICIONES DE CIERRE POST-CUTOVER
        val remainingPendingMutations = persistence.historyDao.getPendingMutations(ownerUid)
        val remainingPendingBeers = beerDao.getPendingSyncBeers(ownerUid).filter {
            it.syncStatus == SyncStatus.PENDING || it.lastMutationId != null
        }
        val remainingLegacyBeers = (beerDao.getActiveBeersSync(ownerUid) + remainingPendingBeers)
            .filter { it.remoteRevision == 0L && it.baseRevision == 0L }


        if (remainingPendingMutations.isNotEmpty() || remainingPendingBeers.isNotEmpty() || storageFailuresCount > 0 || offlinePushesCount > 0) {
            return CutoverResult.Incomplete(
                pendingMutationsCount = remainingPendingMutations.size,
                pendingBeersCount = remainingPendingBeers.size,
                storageFailuresCount = storageFailuresCount,
                offlinePushesCount = offlinePushesCount,
                blockedPushesCount = blockedPushesCount,
                conflictsCount = conflictsCount,
                details = details
            )
        }

        if (conflictsCount > 0 || blockedPushesCount > 0 || remainingLegacyBeers.isNotEmpty()) {
            return CutoverResult.Blocked(
                reason = "Cutover BLOQUEADO: Existen anomalías pendientes: $conflictsCount conflicto(s), $blockedPushesCount bloqueo(s), o ${remainingLegacyBeers.size} entidades legacy 0/0 sin resolver.",
                details = details
            )
        }

        val totalActive = beerDao.getActiveBeersSync(ownerUid).size
        val changesMade = upgradedRemoteCount + localAlignedCount + localPromotedCount +
                localTombstonesCount + remoteDiscoveredCount + pushedPendingCount

        return CutoverResult.Completed(
            upgradedRemoteCount = upgradedRemoteCount,
            localAlignedCount = localAlignedCount,
            localPromotedCount = localPromotedCount,
            localTombstonesCount = localTombstonesCount,
            remoteDiscoveredCount = remoteDiscoveredCount,
            pushedPendingCount = pushedPendingCount,
            totalActiveBeers = totalActive,
            alreadyMigrated = (changesMade == 0),
            details = details
        )
    }
}
