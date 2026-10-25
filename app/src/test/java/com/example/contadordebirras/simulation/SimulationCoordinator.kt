package com.example.contadordebirras.simulation

import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.*
import java.io.IOException

/**
 * Resultado semántico de la ejecución de una operación PUSH simulada.
 */
sealed interface PushExecutionResult {
    data class Success(val plan: PushPlan, val newRemoteRevision: Long) : PushExecutionResult
    data class Conflict(val plan: PushPlan, val currentRemoteRevision: Long, val reason: String) : PushExecutionResult
    data class Blocked(val reason: String) : PushExecutionResult
    data class AckLost(val remoteRevision: Long, val mutationId: String) : PushExecutionResult
    data class StorageUploadFailed(val reason: String) : PushExecutionResult
    data object Offline : PushExecutionResult
    data object NotFound : PushExecutionResult
}

/**
 * Resultado semántico de la ejecución de una operación PULL simulada.
 */
sealed interface PullExecutionResult {
    data class Applied(val plan: PullPlan) : PullExecutionResult
    data class Blocked(val reason: String) : PullExecutionResult
    data object NoOp : PullExecutionResult
    data object Offline : PullExecutionResult
    data object IncompleteReadIgnored : PullExecutionResult
}

/**
 * Coordinador de simulación multidispositivo (Fase C4.2).
 *
 * Conecta de forma pura y hermética:
 * - [SyncProtocolPushPlanner] (C3A)
 * - [SyncProtocolPullPlanner] (C3B)
 * - [LegacyBeerClassifier] (C4.1A)
 * - [FakeFirestore]
 * - [FakeStorage]
 * - [SimulatedDevice]
 *
 * No contiene ninguna dependencia con BeerSyncEngine, BeerRepository ni Workers de producción.
 */
class SimulationCoordinator(
    val firestore: FakeFirestore,
    val storage: FakeStorage
) {
    /** Simulación de pérdida de ACK en la respuesta de red (Firestore confirma, el cliente no recibe ACK) */
    var dropPushAck: Boolean = false

    /**
     * Ejecuta una sincronización PUSH para una cerveza concreta de un dispositivo.
     */
    fun executePush(
        device: SimulatedDevice,
        syncId: String,
        historicalEvidence: LegacyHistoricalEvidence = LegacyHistoricalEvidence()
    ): PushExecutionResult {
        if (!device.isOnline) return PushExecutionResult.Offline

        val localBeer = device.getBeer(syncId) ?: return PushExecutionResult.NotFound

        // 1. Clasificación Legacy preventiva si está en 0/0
        if (localBeer.remoteRevision == LEGACY_REMOTE_REVISION && localBeer.baseRevision == LEGACY_REMOTE_REVISION) {
            val remoteDoc = try {
                firestore.getDocument(syncId)
            } catch (e: Exception) {
                return PushExecutionResult.Blocked("Lectura remota falló durante clasificación legacy: ${e.message}")
            }

            val remoteSnapshot = when {
                remoteDoc == null -> LegacyRemoteSnapshot.Absent
                else -> LegacyRemoteSnapshot.Present(
                    revision = remoteDoc.revision,
                    isDeleted = remoteDoc.isDeleted,
                    lastMutationId = remoteDoc.lastMutationId,
                    fieldRevisions = remoteDoc.fieldRevisions,
                    remoteOwnerUid = remoteDoc.userId
                )
            }

            val legacyContext = LegacyLocalEntityContext(
                syncId = localBeer.syncId,
                ownerUid = localBeer.ownerUid,
                syncStatus = localBeer.syncStatus,
                remoteRevision = localBeer.remoteRevision,
                baseRevision = localBeer.baseRevision,
                dirtyFields = localBeer.dirtyFields,
                lastMutationId = localBeer.lastMutationId,
                hasRemotePhotoUrl = localBeer.remotePhotoUrl != null
            )

            val classification = LegacyBeerClassifier.classify(
                local = legacyContext,
                remote = remoteSnapshot,
                readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
                evidence = historicalEvidence,
                expectedOwnerUid = device.ownerUid
            )

            when (classification) {
                is LegacyClassification.PromoteToLocalOnlyCreation -> {
                    // Promoción segura a sentinelas local-only (-1/-1)
                    localBeer.remoteRevision = LOCAL_ONLY_REVISION
                    localBeer.baseRevision = LOCAL_ONLY_REVISION
                    device.insertBeerDirect(localBeer)
                }
                is LegacyClassification.NeedsReconciliation -> {
                    return PushExecutionResult.Blocked("Clasificación legacy ambigua: ${classification.reason}")
                }
                is LegacyClassification.OwnershipMismatch -> {
                    return PushExecutionResult.Blocked("Ownership mismatch detectado: ${classification.reason}")
                }
                is LegacyClassification.IgnoreIncompleteRead -> {
                    return PushExecutionResult.Blocked("Lectura incompleta ignorada")
                }
                is LegacyClassification.LocalDeleteWins -> {
                    // Fila local DELETED prevalece; continuará a planificar WriteTombstone
                }
                else -> {
                    // Otros estados no bloqueantes o delegados a planners
                }
            }
        }

        // 2. Consulta de estado remoto para el Push Planner
        val remoteDoc = try {
            firestore.getDocument(syncId)
        } catch (e: Exception) {
            return PushExecutionResult.Blocked("Error de red al consultar remoto: ${e.message}")
        }

        val remoteMetadata = remoteDoc?.toRemoteSyncMetadata()
        val localContext = localBeer.toLocalMutationContext()

        // 3. Planificación pura C3A
        val plan = SyncProtocolPushPlanner.planPush(localContext, remoteMetadata)

        // 4. Ejecución del plan
        return when (plan) {
            is PushPlan.CreateActive -> {
                val mutationId = localBeer.lastMutationId ?: "mut_create_${System.currentTimeMillis()}"
                var uploadedRemotePhotoUrl: String? = null

                // Manejo de fotografía si está marcada como dirty y existe URI local
                if (plan.fieldsToWrite.contains(BeerDirtyField.PHOTO) && localBeer.photoUri != null) {
                    val photoPath = "users/${device.ownerUid}/beers/${syncId}_$mutationId.jpg"
                    val uploadResult = storage.uploadPhoto(
                        path = photoPath,
                        ownerUid = device.ownerUid,
                        bytes = "dummy_photo_bytes".toByteArray()
                    )
                    if (uploadResult.isFailure) {
                        return PushExecutionResult.StorageUploadFailed(
                            "Fallo al subir foto a Storage: ${uploadResult.exceptionOrNull()?.message}"
                        )
                    }
                    uploadedRemotePhotoUrl = uploadResult.getOrNull()
                }

                val newDoc = FakeFirestoreBeerDocument(
                    syncId = syncId,
                    userId = device.ownerUid,
                    revision = plan.nextRevision,
                    isDeleted = false,
                    lastMutationId = mutationId,
                    fieldRevisions = plan.fieldsToWrite.associateWith { plan.nextRevision },
                    type = localBeer.type,
                    timestamp = localBeer.timestamp,
                    comment = localBeer.comment,
                    photoUrl = uploadedRemotePhotoUrl,
                    latitude = localBeer.latitude,
                    longitude = localBeer.longitude
                )

                val txResult = firestore.executeCreateActiveTransaction(
                    syncId = syncId,
                    callerUid = device.ownerUid,
                    newDocument = newDoc
                )

                when (txResult) {
                    is TransactionResult.Success -> {
                        if (dropPushAck) {
                            PushExecutionResult.AckLost(plan.nextRevision, mutationId)
                        } else {
                            device.applyFullPushConfirmation(
                                syncId = syncId,
                                expectedMutationId = mutationId,
                                newRemoteRevision = plan.nextRevision,
                                remotePhotoUrl = uploadedRemotePhotoUrl
                            )
                            PushExecutionResult.Success(plan, plan.nextRevision)
                        }
                    }
                    is TransactionResult.Conflict -> {
                        PushExecutionResult.Conflict(plan, txResult.currentRevision, txResult.reason)
                    }
                    is TransactionResult.PreconditionFailed -> {
                        PushExecutionResult.Blocked("Precondición fallida en CreateActive: ${txResult.reason}")
                    }
                    is TransactionResult.Error -> {
                        PushExecutionResult.Blocked("Error en CreateActive: ${txResult.exception.message}")
                    }
                }
            }

            is PushPlan.ApplyPatch -> {
                val mutationId = localBeer.lastMutationId ?: "mut_patch_${System.currentTimeMillis()}"
                var uploadedRemotePhotoUrl: String? = null

                if (plan.fieldsToWrite.contains(BeerDirtyField.PHOTO) && localBeer.photoUri != null) {
                    val photoPath = "users/${device.ownerUid}/beers/${syncId}_$mutationId.jpg"
                    val uploadResult = storage.uploadPhoto(
                        path = photoPath,
                        ownerUid = device.ownerUid,
                        bytes = "dummy_patch_photo_bytes".toByteArray()
                    )
                    if (uploadResult.isFailure) {
                        return PushExecutionResult.StorageUploadFailed(
                            "Fallo al subir foto para parche: ${uploadResult.exceptionOrNull()?.message}"
                        )
                    }
                    uploadedRemotePhotoUrl = uploadResult.getOrNull()
                }

                val fieldsToUpdate = mutableMapOf<String, Any?>()
                if (plan.fieldsToWrite.contains(BeerDirtyField.TYPE)) fieldsToUpdate["type"] = localBeer.type
                if (plan.fieldsToWrite.contains(BeerDirtyField.TIMESTAMP)) fieldsToUpdate["timestamp"] = localBeer.timestamp
                if (plan.fieldsToWrite.contains(BeerDirtyField.COMMENT)) fieldsToUpdate["comment"] = localBeer.comment
                if (plan.fieldsToWrite.contains(BeerDirtyField.PHOTO)) fieldsToUpdate["photoUrl"] = uploadedRemotePhotoUrl
                if (plan.fieldsToWrite.contains(BeerDirtyField.LOCATION)) {
                    fieldsToUpdate["latitude"] = localBeer.latitude
                    fieldsToUpdate["longitude"] = localBeer.longitude
                }

                val newFieldRevisions = plan.fieldsToWrite.associateWith { plan.nextRevision }

                val txResult = firestore.executeApplyPatchTransaction(
                    syncId = syncId,
                    callerUid = device.ownerUid,
                    expectedRevision = remoteMetadata?.revision ?: localBeer.baseRevision,
                    mutationId = mutationId,
                    fieldsToUpdate = fieldsToUpdate,
                    newFieldRevisions = newFieldRevisions
                )

                when (txResult) {
                    is TransactionResult.Success -> {
                        if (dropPushAck) {
                            PushExecutionResult.AckLost(plan.nextRevision, mutationId)
                        } else {
                            if (plan.remoteWinsFields.isEmpty()) {
                                device.applyFullPushConfirmation(
                                    syncId = syncId,
                                    expectedMutationId = mutationId,
                                    newRemoteRevision = plan.nextRevision,
                                    remotePhotoUrl = uploadedRemotePhotoUrl
                                )
                            } else {
                                device.applyPartialConfirmation(
                                    syncId = syncId,
                                    expectedMutationId = mutationId,
                                    newRemoteRevision = plan.nextRevision,
                                    confirmedFields = plan.fieldsToWrite,
                                    remainingDirtyFields = emptySet(),
                                    remotePhotoUrl = uploadedRemotePhotoUrl
                                )
                            }
                            PushExecutionResult.Success(plan, plan.nextRevision)
                        }
                    }
                    is TransactionResult.Conflict -> {
                        PushExecutionResult.Conflict(plan, txResult.currentRevision, txResult.reason)
                    }
                    is TransactionResult.PreconditionFailed -> {
                        PushExecutionResult.Blocked("Precondición fallida en ApplyPatch: ${txResult.reason}")
                    }
                    is TransactionResult.Error -> {
                        PushExecutionResult.Blocked("Error en ApplyPatch: ${txResult.exception.message}")
                    }
                }
            }

            is PushPlan.WriteTombstone -> {
                val mutationId = localBeer.lastMutationId ?: "mut_del_${System.currentTimeMillis()}"
                val txResult = firestore.executeWriteTombstoneTransaction(
                    syncId = syncId,
                    callerUid = device.ownerUid,
                    expectedRevision = remoteMetadata?.revision ?: localBeer.baseRevision,
                    mutationId = mutationId
                )

                when (txResult) {
                    is TransactionResult.Success -> {
                        if (dropPushAck) {
                            PushExecutionResult.AckLost(plan.nextRevision, mutationId)
                        } else {
                            device.applyDeleteConfirmation(syncId, mutationId, plan.nextRevision)
                            PushExecutionResult.Success(plan, plan.nextRevision)
                        }
                    }
                    is TransactionResult.Conflict -> {
                        PushExecutionResult.Conflict(plan, txResult.currentRevision, txResult.reason)
                    }
                    is TransactionResult.PreconditionFailed -> {
                        PushExecutionResult.Blocked("Precondición fallida en WriteTombstone: ${txResult.reason}")
                    }
                    is TransactionResult.Error -> {
                        PushExecutionResult.Blocked("Error en WriteTombstone: ${txResult.exception.message}")
                    }
                }
            }

            is PushPlan.AlreadyApplied -> {
                remoteDoc?.let {
                    device.applyFullPushConfirmation(syncId, it.lastMutationId ?: "", it.revision)
                }
                PushExecutionResult.Success(plan, remoteDoc?.revision ?: 0L)
            }

            is PushPlan.RemoteTombstoneWins -> {
                remoteDoc?.let {
                    device.applyDeleteConfirmation(syncId, localBeer.lastMutationId ?: "", it.revision)
                }
                PushExecutionResult.Success(plan, remoteDoc?.revision ?: 0L)
            }

            is PushPlan.DeleteAlreadySatisfied -> {
                PushExecutionResult.Success(plan, remoteDoc?.revision ?: 0L)
            }

            is PushPlan.RemoteWinsOnly -> {
                PushExecutionResult.Conflict(plan, remoteDoc?.revision ?: 0L, "Remote wins on all modified fields")
            }

            is PushPlan.RemoteAbsentForKnownEntity -> {
                PushExecutionResult.Blocked("Anti-resurrección: Entidad conocida pero ausente en remoto")
            }

            is PushPlan.RemotePresentForLocalOnlyNeedsReconciliation -> {
                PushExecutionResult.Blocked("Bloqueo de seguridad: Local-only encuentra remoto activo con distinto mutationId")
            }

            is PushPlan.InvalidMetadata -> {
                PushExecutionResult.Blocked("Metadata inválida: ${plan.reason}")
            }
        }
    }

    /**
     * Ejecuta una sincronización PULL para una cerveza concreta de un dispositivo.
     */
    fun executePull(
        device: SimulatedDevice,
        syncId: String,
        scope: RemoteReadScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
        overrideOrigin: RemoteOrigin? = null,
        fieldEvidence: Map<BeerDirtyField, FieldOriginEvidence> = emptyMap()
    ): PullExecutionResult {
        if (!device.isOnline) return PullExecutionResult.Offline

        val localBeer = device.getBeer(syncId)
        val localContext = localBeer?.toLocalMutationContext()

        // 1. Lectura remota
        val remoteDoc = try {
            firestore.getDocument(syncId)
        } catch (e: Exception) {
            // Un timeout o error de transporte equivale a lectura incompleta/no confirmada
            return PullExecutionResult.IncompleteReadIgnored
        }

        // 2. Aislamiento estricto de propietario (Ownership)
        if (remoteDoc != null && remoteDoc.userId != device.ownerUid) {
            return PullExecutionResult.Blocked(
                "Ownership Mismatch: Documento remoto pertenece a ${remoteDoc.userId}, dispositivo es ${device.ownerUid}"
            )
        }

        val remoteState = when {
            remoteDoc != null -> RemotePullState.Present(remoteDoc.toRemoteSyncMetadata())
            scope == RemoteReadScope.AUTHORITATIVE_CONFIRMED -> RemotePullState.ConfirmedAbsent
            else -> RemotePullState.IncompleteOrUnconfirmed
        }

        if (remoteState is RemotePullState.IncompleteOrUnconfirmed) {
            return PullExecutionResult.IncompleteReadIgnored
        }

        // 3. Determinación de procedencia (Provenance)
        val pastMutations = device.getPastLocalMutationIds(syncId)
        val computedOrigin = when {
            overrideOrigin != null -> overrideOrigin
            remoteDoc == null -> RemoteOrigin.INDETERMINATE
            pastMutations.contains(remoteDoc.lastMutationId) -> RemoteOrigin.LOCAL_LOST_ACK
            remoteDoc.lastMutationId != null && remoteDoc.lastMutationId != localBeer?.lastMutationId -> RemoteOrigin.EXTERNAL_CONFIRMED
            else -> RemoteOrigin.INDETERMINATE
        }

        val provenance = PullProvenance(
            pastLocalMutationIds = pastMutations,
            origin = computedOrigin,
            fieldEvidence = fieldEvidence
        )

        // 4. Planificación pura C3B
        val plan = SyncProtocolPullPlanner.planPull(
            local = localContext,
            remoteState = remoteState,
            provenance = provenance
        )

        // 5. Aplicación del plan al dispositivo
        return when (plan) {
            is PullPlan.InsertLocal -> {
                val newBeer = SimulatedBeer(
                    syncId = syncId,
                    ownerUid = device.ownerUid,
                    type = remoteDoc?.type ?: "LATA",
                    timestamp = remoteDoc?.timestamp ?: 0L,
                    comment = remoteDoc?.comment,
                    photoUri = null, // Estrictamente local; no existe en remoto
                    remotePhotoUrl = remoteDoc?.photoUrl,
                    latitude = remoteDoc?.latitude,
                    longitude = remoteDoc?.longitude,
                    syncStatus = SyncStatus.SYNCED,
                    remoteRevision = plan.remoteRevision,
                    baseRevision = plan.remoteRevision,
                    dirtyFields = emptySet(),
                    lastMutationId = remoteDoc?.lastMutationId
                )
                device.insertBeerDirect(newBeer)
                PullExecutionResult.Applied(plan)
            }

            is PullPlan.UpdateLocalSynced -> {
                if (localBeer != null && remoteDoc != null) {
                    if (plan.fieldsToApply.contains(BeerDirtyField.TYPE)) localBeer.type = remoteDoc.type
                    if (plan.fieldsToApply.contains(BeerDirtyField.TIMESTAMP)) localBeer.timestamp = remoteDoc.timestamp
                    if (plan.fieldsToApply.contains(BeerDirtyField.COMMENT)) localBeer.comment = remoteDoc.comment
                    if (plan.fieldsToApply.contains(BeerDirtyField.PHOTO)) localBeer.remotePhotoUrl = remoteDoc.photoUrl
                    if (plan.fieldsToApply.contains(BeerDirtyField.LOCATION)) {
                        localBeer.latitude = remoteDoc.latitude
                        localBeer.longitude = remoteDoc.longitude
                    }
                    localBeer.remoteRevision = plan.newRemoteRevision
                    localBeer.baseRevision = plan.newRemoteRevision
                    localBeer.lastMutationId = remoteDoc.lastMutationId
                    device.insertBeerDirect(localBeer)
                }
                PullExecutionResult.Applied(plan)
            }

            is PullPlan.ConfirmPendingMutation -> {
                device.applyFullPushConfirmation(
                    syncId = syncId,
                    expectedMutationId = plan.confirmedMutationId,
                    newRemoteRevision = plan.newRemoteRevision,
                    remotePhotoUrl = remoteDoc?.photoUrl
                )
                PullExecutionResult.Applied(plan)
            }

            is PullPlan.MergeWithPending -> {
                if (localBeer != null && remoteDoc != null) {
                    // Aplicar campos remotos ortogonales o ganados por remoto
                    if (plan.remoteFieldsToApply.contains(BeerDirtyField.TYPE)) localBeer.type = remoteDoc.type
                    if (plan.remoteFieldsToApply.contains(BeerDirtyField.TIMESTAMP)) localBeer.timestamp = remoteDoc.timestamp
                    if (plan.remoteFieldsToApply.contains(BeerDirtyField.COMMENT)) localBeer.comment = remoteDoc.comment
                    if (plan.remoteFieldsToApply.contains(BeerDirtyField.PHOTO)) localBeer.remotePhotoUrl = remoteDoc.photoUrl
                    if (plan.remoteFieldsToApply.contains(BeerDirtyField.LOCATION)) {
                        localBeer.latitude = remoteDoc.latitude
                        localBeer.longitude = remoteDoc.longitude
                    }

                    // Actualizar revisiones manteniendo baseRevision congelada si quedan pendientes
                    localBeer.remoteRevision = plan.newRemoteRevision
                    localBeer.baseRevision = plan.newBaseRevision
                    localBeer.dirtyFields = plan.localPendingFieldsToPreserve
                    if (plan.localPendingFieldsToPreserve.isEmpty()) {
                        localBeer.syncStatus = SyncStatus.SYNCED
                    }
                    device.insertBeerDirect(localBeer)
                }
                PullExecutionResult.Applied(plan)
            }

            is PullPlan.ApplyRemoteTombstone -> {
                if (localBeer != null) {
                    localBeer.syncStatus = SyncStatus.DELETED
                    localBeer.remoteRevision = plan.newRemoteRevision
                    localBeer.baseRevision = plan.newRemoteRevision
                    localBeer.dirtyFields = emptySet()
                    device.insertBeerDirect(localBeer)
                }
                PullExecutionResult.Applied(plan)
            }

            is PullPlan.PreserveLocalDelete -> {
                PullExecutionResult.Applied(plan)
            }

            is PullPlan.DeleteAlreadySatisfied -> {
                PullExecutionResult.NoOp
            }

            is PullPlan.PreserveLocalPending -> {
                PullExecutionResult.NoOp
            }

            is PullPlan.NoOpAlreadyUpToDate -> {
                PullExecutionResult.NoOp
            }

            is PullPlan.RemotePresentForLocalOnlyNeedsReconciliation -> {
                PullExecutionResult.Blocked("Bloqueo seguro: Local-only encuentra documento remoto activo")
            }

            is PullPlan.PendingConflictNeedsReconciliation -> {
                PullExecutionResult.Blocked("Bloqueo seguro: Conflicto pendiente en campos ${plan.conflictingFields}")
            }

            is PullPlan.PendingFieldOrderNeedsReconciliation -> {
                PullExecutionResult.Blocked("Bloqueo seguro: Orden ambiguo en campos ${plan.ambiguousFields}")
            }

            is PullPlan.RemoteAbsentForKnownEntity -> {
                PullExecutionResult.Blocked("Anti-resurrección: Ausente en remoto para entidad conocida")
            }

            is PullPlan.ApplyRemotePurge -> {
                if (localBeer != null) {
                    localBeer.syncStatus = SyncStatus.DELETED
                    device.insertBeerDirect(localBeer)
                }
                PullExecutionResult.Applied(plan)
            }

            is PullPlan.IgnoreIncompleteRead -> {
                PullExecutionResult.IncompleteReadIgnored
            }

            is PullPlan.IgnoreRemoteTombstone -> {
                PullExecutionResult.NoOp
            }

            is PullPlan.LegacyRemoteNeedsResolution -> {
                PullExecutionResult.Blocked("Remoto legacy requiere resolución")
            }

            is PullPlan.InvalidMetadata -> {
                PullExecutionResult.Blocked("Metadata inválida: ${plan.reason}")
            }
        }
    }
}
