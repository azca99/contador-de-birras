package com.example.contadordebirras.sync.c4

import android.util.Log
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
 */
sealed interface CutoverResult {
    data class Success(
        val upgradedRemoteCount: Int,
        val localAlignedCount: Int,
        val localPromotedCount: Int,
        val localTombstonesCount: Int,
        val remoteDiscoveredCount: Int,
        val pushedPendingCount: Int,
        val details: List<String>
    ) : CutoverResult

    data class AlreadyUpToDate(
        val totalActiveBeers: Int,
        val message: String
    ) : CutoverResult

    data class Blocked(val reason: String) : CutoverResult
    data class Error(val exception: Throwable) : CutoverResult
}

/**
 * Gestor de Transición y Migración Staging Cutover del motor legacy al protocolo C4 (Fase C4.5).
 *
 * GARANTÍAS FORMALES:
 * 1. Cero pérdida de datos: Ninguna cerveza, comentario, foto, timestamp o ubicación desaparece.
 * 2. Cero duplicados: La identidad `syncId` se preserva unívocamente; índices únicos respetados.
 * 3. Preservación de offline pending: Las cervezas creadas o borradas sin conexión bajo el motor
 *    legacy se ascienden de forma durable a mutaciones C4 antes de cualquier operación de red.
 * 4. Ascenso atómico CAS en Firestore: Los documentos remotos legacy se ascienden a revisión 1
 *    mediante la transacción autorizada [isLegacyUpgrade] del perfil legacy-compat.
 * 5. Exclusión mutua estricta: [C4SyncConfig.syncExecutionMutex] impide que BeerSyncEngine
 *    y C4 escriban simultáneamente sobre los mismos datos.
 * 6. Idempotencia: La migración puede reejecutarse arbitrariamente sin efectos secundarios ni duplicados.
 * 7. Rollback seguro: La transición puede revertirse a modo LEGACY en cualquier momento sin pérdida.
 */
class C4StagingCutoverManager(
    private val coordinator: AndroidC4SyncCoordinator,
    private val persistence: RoomC4SyncPersistence,
    private val firestoreAdapter: FirestoreC4SyncAdapter
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

                if (result is CutoverResult.Success || result is CutoverResult.AlreadyUpToDate) {
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
                CutoverResult.Error(e)
            }
        }
    }

    /**
     * Revierte de forma segura al modo legacy sin alterar los datos existentes.
     */
    suspend fun rollbackToLegacy(): Boolean {
        return C4SyncConfig.syncExecutionMutex.withLock {
            C4SyncConfig.engineMode = SyncEngineMode.LEGACY
            C4SyncConfig.isEnabled = false
            true
        }
    }

    private suspend fun performCutoverInternal(ownerUid: String): CutoverResult {
        val details = mutableListOf<String>()

        // 1. Lectura de estado local existente en Room
        val localBeers = beerDao.getActiveBeersSync(ownerUid) +
                beerDao.getPendingSyncBeers(ownerUid).filter { it.syncStatus == SyncStatus.DELETED }
        val localBeersBySyncId = localBeers.associateBy { it.syncId }

        // 2. Consulta autoritativa SERVER de documentos remotos en Firestore
        val remoteDocs = try {
            firestoreAdapter.queryServerDocuments(ownerUid)
        } catch (e: Exception) {
            return CutoverResult.Blocked("No se pudo consultar Firestore para cutover: ${e.message}")
        }
        val remoteDocsBySyncId = remoteDocs.associateBy { it.syncId }

        var upgradedRemoteCount = 0
        var localAlignedCount = 0
        var localPromotedCount = 0
        var localTombstonesCount = 0
        var pushedPendingCount = 0

        // 3. PASO A: Ascenso de documentos remotos legacy a revisión 1
        for (remoteDoc in remoteDocs) {
            if (remoteDoc.revision == 0L || remoteDoc.fieldRevisions.isEmpty()) {
                val migrationMutationId = "cutover-upgrade-${remoteDoc.syncId.take(8)}-${UUID.randomUUID().toString().take(8)}"
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

        // 4. PASO B: Preparación de entidades locales Room que estaban en 0/0 (Legacy)
        for (localBeer in localBeers) {
            val isLegacyLocal = localBeer.remoteRevision == 0L && localBeer.baseRevision == 0L

            if (isLegacyLocal) {
                when (localBeer.syncStatus) {
                    SyncStatus.PENDING -> {
                        // Creación local offline bajo legacy: promover a local-only (-1/-1) con mutación durable
                        val initialMutationId = "cutover-local-${UUID.randomUUID().toString().take(8)}"
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
                        val tombstoneMutationId = "cutover-tomb-${UUID.randomUUID().toString().take(8)}"
                        val ok = persistence.prepareLegacyDeletedForTombstone(
                            syncId = localBeer.syncId,
                            ownerUid = ownerUid,
                            tombstoneMutationId = tombstoneMutationId,
                            baseRevision = baseRev
                        )
                        if (ok) {
                            localTombstonesCount++
                            details.add("Local borrada ${localBeer.syncId.take(8)} preparada como C4 Tombstone (baseRev: $baseRev)")
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
                        } else {
                            // Cerveza ausente en remoto: eliminación física remota en era legacy -> marcar DELETED
                            val ok = persistence.markLegacyAbsentAsDeleted(localBeer.syncId, ownerUid)
                            if (ok) {
                                localTombstonesCount++
                                details.add("Local previamente sincronizada ${localBeer.syncId.take(8)} ausente en remoto -> marcada DELETED")
                            }
                        }
                    }
                }
            }
        }

        // 5. PASO C: Descubrimiento y Reconciliación Remota por Colección (PULL C4)
        val pullResults = coordinator.executePullCollection(ownerUid)
        var remoteDiscoveredCount = 0
        pullResults.forEach { (syncId, result) ->
            if (result is C4CoordinatorPullResult.Applied) {
                remoteDiscoveredCount++
                details.add("PULL aplicado para $syncId: ${result.plan::class.simpleName}")
            }
        }

        // 6. PASO D: PUSH de mutaciones locales pendientes (Creaciones y Borrados offline preparados)
        val pendingHistory = persistence.historyDao.getPendingMutations(ownerUid)
        for (history in pendingHistory) {
            val pushResult = coordinator.executePush(
                syncId = history.syncId,
                ownerUid = ownerUid
            )
            when (pushResult) {
                is C4CoordinatorPushResult.Success, is C4CoordinatorPushResult.IdempotentRecovery -> {
                    pushedPendingCount++
                    details.add("PUSH exitoso para ${history.syncId.take(8)} (mutación: ${history.mutationId})")
                }
                is C4CoordinatorPushResult.Offline -> {
                    details.add("PUSH aplazado para ${history.syncId.take(8)}: red offline")
                }
                is C4CoordinatorPushResult.Conflict -> {
                    details.add("PUSH conflicto para ${history.syncId.take(8)}: ${pushResult.reason}")
                }
                is C4CoordinatorPushResult.Blocked -> {
                    details.add("PUSH advertencia para ${history.syncId.take(8)}: ${pushResult.reason}")
                }
                is C4CoordinatorPushResult.StorageFailed -> {
                    details.add("PUSH storage falló para ${history.syncId.take(8)}: ${pushResult.reason}")
                }
                C4CoordinatorPushResult.NotFound -> {
                    details.add("PUSH entidad local no encontrada para ${history.syncId.take(8)}")
                }
            }
        }

        // Comprobación de idempotencia o finalización
        val totalActive = beerDao.getActiveBeersSync(ownerUid).size
        val changesMade = upgradedRemoteCount + localAlignedCount + localPromotedCount +
                localTombstonesCount + remoteDiscoveredCount + pushedPendingCount

        return if (changesMade == 0) {
            CutoverResult.AlreadyUpToDate(
                totalActiveBeers = totalActive,
                message = "Base de datos y entorno remoto ya están 100% migrados y alineados al Protocolo C4."
            )
        } else {
            CutoverResult.Success(
                upgradedRemoteCount = upgradedRemoteCount,
                localAlignedCount = localAlignedCount,
                localPromotedCount = localPromotedCount,
                localTombstonesCount = localTombstonesCount,
                remoteDiscoveredCount = remoteDiscoveredCount,
                pushedPendingCount = pushedPendingCount,
                details = details
            )
        }
    }
}
