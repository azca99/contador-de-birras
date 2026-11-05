package com.example.contadordebirras.sync.c4

import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.*
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * Coordinador de Integración Android bajo el Protocolo C4 (Fase C4.4A).
 *
 * Integra:
 * - SyncProtocolPushPlanner (C3A)
 * - SyncProtocolPullPlanner (C3B)
 * - LegacyBeerClassifier (C4.1A)
 * - RoomC4SyncPersistence (Room v10)
 * - FirestoreC4SyncAdapter (Cloud Firestore SDK)
 * - StorageC4SyncAdapter (Firebase Storage SDK)
 *
 * DESCONECTADO de producción: no reemplaza a BeerSyncEngine ni interactúa con WorkManager ni la UI.
 */
class AndroidC4SyncCoordinator(
    val firestoreAdapter: FirestoreC4SyncAdapter,
    val storageAdapter: StorageC4SyncAdapter,
    val persistence: RoomC4SyncPersistence
) {

    /**
     * Simulación de caída de red después de la escritura en Firestore (ACK perdido).
     */
    var simulateAckLoss: Boolean = false

    /**
     * Ejecuta una sincronización PUSH para una entidad local concreta.
     */
    suspend fun executePush(
        syncId: String,
        ownerUid: String,
        photoBytes: ByteArray? = null,
        historicalEvidence: LegacyHistoricalEvidence = LegacyHistoricalEvidence()
    ): C4CoordinatorPushResult {
        val localBeer = persistence.beerDao.getBeerBySyncId(syncId, ownerUid)
            ?: return C4CoordinatorPushResult.NotFound

        // 1. Exigir mutationId durable (no nulo ni vacío)
        val mutationId = localBeer.lastMutationId
        if (mutationId.isNullOrBlank()) {
            return C4CoordinatorPushResult.Blocked("Mutación no durable: falta lastMutationId en la entidad local")
        }

        // 2. Exigir registro previo en el historial durable Room antes de emitir cualquier petición de red
        val historyRecord = persistence.beerDao.getHistoryRecordById(mutationId, ownerUid)
        if (historyRecord == null) {
            return C4CoordinatorPushResult.Blocked("Mutación no durable: falta registro previo en local_mutation_history para $mutationId")
        }
        if (historyRecord.syncId != syncId ||
            (historyRecord.status != MutationStatus.PENDING && historyRecord.status != MutationStatus.PARTIALLY_CONFIRMED)
        ) {
            return C4CoordinatorPushResult.Blocked("Historial durable en estado no elegible para PUSH: ${historyRecord.status}")
        }

        // 3. Clasificación preventiva si está en 0/0 (Legacy)
        var effectiveRemoteRev = localBeer.remoteRevision
        var effectiveBaseRev = localBeer.baseRevision

        if (localBeer.remoteRevision == LEGACY_REMOTE_REVISION && localBeer.baseRevision == LEGACY_REMOTE_REVISION) {
            val remoteDoc = firestoreAdapter.readServerDocument(syncId)
            val remoteSnapshot = when {
                remoteDoc == null -> LegacyRemoteSnapshot.Absent
                else -> LegacyRemoteSnapshot.Present(
                    revision = remoteDoc.revision,
                    isDeleted = remoteDoc.isDeleted,
                    lastMutationId = remoteDoc.lastMutationId,
                    fieldRevisions = remoteDoc.toRemoteSyncMetadata().fieldRevisions,
                    remoteOwnerUid = remoteDoc.userId
                )
            }

            val legacyContext = LegacyLocalEntityContext(
                syncId = localBeer.syncId,
                ownerUid = localBeer.ownerUid,
                syncStatus = localBeer.syncStatus,
                remoteRevision = localBeer.remoteRevision,
                baseRevision = localBeer.baseRevision,
                dirtyFields = BeerDirtyFieldsCodec.decode(localBeer.dirtyFields),
                lastMutationId = localBeer.lastMutationId,
                hasRemotePhotoUrl = localBeer.remotePhotoUrl != null
            )

            val classification = LegacyBeerClassifier.classify(
                local = legacyContext,
                remote = remoteSnapshot,
                readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
                evidence = historicalEvidence,
                expectedOwnerUid = ownerUid
            )

            when (classification) {
                is LegacyClassification.PromoteToLocalOnlyCreation -> {
                    effectiveRemoteRev = LOCAL_ONLY_REVISION
                    effectiveBaseRev = LOCAL_ONLY_REVISION
                }
                is LegacyClassification.NeedsReconciliation -> {
                    return C4CoordinatorPushResult.Blocked("Clasificación legacy ambigua: ${classification.reason}")
                }
                is LegacyClassification.OwnershipMismatch -> {
                    return C4CoordinatorPushResult.Blocked("Ownership mismatch detectado: ${classification.reason}")
                }
                is LegacyClassification.IgnoreIncompleteRead -> {
                    return C4CoordinatorPushResult.Blocked("Lectura incompleta ignorada")
                }
                else -> {}
            }
        }

        // 4. Lectura remota autoritativa desde SERVER
        val remoteDoc = try {
            firestoreAdapter.readServerDocument(syncId)
        } catch (e: Exception) {
            if (e is FirebaseFirestoreException && (e.code == FirebaseFirestoreException.Code.UNAVAILABLE || e.message?.contains("offline") == true)) {
                return C4CoordinatorPushResult.Offline
            }
            throw e
        }

        // 5. Aislamiento estricto de propietario (Ownership)
        if (remoteDoc != null && remoteDoc.userId != ownerUid) {
            return C4CoordinatorPushResult.Blocked(
                "Ownership mismatch: Documento $syncId pertenece a ${remoteDoc.userId} (dispositivo: $ownerUid)"
            )
        }

        val remoteMetadata = remoteDoc?.toRemoteSyncMetadata()
        val localContext = LocalMutationContext(
            syncStatus = localBeer.syncStatus,
            remoteRevision = effectiveRemoteRev,
            baseRevision = effectiveBaseRev,
            dirtyFields = BeerDirtyFieldsCodec.decode(localBeer.dirtyFields),
            lastMutationId = mutationId
        )

        // 6. Planificación pura C3A
        val plan = SyncProtocolPushPlanner.planPush(localContext, remoteMetadata)

        // 7. Ejecución del plan
        return when (plan) {
            is PushPlan.CreateActive -> {
                var uploadedPhotoUrl: String? = null

                if (plan.fieldsToWrite.contains(BeerDirtyField.PHOTO) && localBeer.photoUri != null) {
                    if (photoBytes == null) {
                        return C4CoordinatorPushResult.StorageFailed(
                            "No se pudo obtener el contenido binario de la fotografía local (${localBeer.photoUri}). Se mantiene PENDING para evitar pérdida de datos."
                        )
                    }
                    val uploadResult = storageAdapter.uploadVersionedPhoto(
                        ownerUid = ownerUid,
                        syncId = syncId,
                        mutationId = mutationId,
                        photoBytes = photoBytes
                    )
                    when (uploadResult) {
                        is C4StorageResult.Success -> {
                            val exists = storageAdapter.photoExists(uploadResult.remotePath)
                            if (!exists) {
                                return C4CoordinatorPushResult.StorageFailed("Verificación de foto en Storage falló tras subida: ${uploadResult.remotePath}")
                            }
                            uploadedPhotoUrl = uploadResult.remotePath
                        }
                        is C4StorageResult.OverwriteRejected -> {
                            return C4CoordinatorPushResult.StorageFailed("Sobrescritura rechazada: ${uploadResult.reason}")
                        }
                        is C4StorageResult.Error -> {
                            return C4CoordinatorPushResult.StorageFailed("Error en upload de foto: ${uploadResult.exception.message}")
                        }
                    }
                }

                val firestoreDoc = C4FirestoreDocument(
                    syncId = syncId,
                    userId = ownerUid,
                    type = localBeer.type.name,
                    timestamp = localBeer.timestamp,
                    latitude = localBeer.latitude,
                    longitude = localBeer.longitude,
                    comment = localBeer.comment,
                    locationName = localBeer.locationName,
                    remotePhotoUrl = uploadedPhotoUrl ?: localBeer.remotePhotoUrl,
                    photoSource = localBeer.photoSource,
                    updatedAt = localBeer.updatedAt,
                    revision = 1L,
                    isDeleted = false,
                    lastMutationId = mutationId,
                    fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
                )

                val txResult = firestoreAdapter.executeCreateActive(
                    syncId = syncId,
                    ownerUid = ownerUid,
                    newDocument = firestoreDoc,
                    mutationId = mutationId
                )

                when (txResult) {
                    is C4TransactionResult.Success -> {
                        if (simulateAckLoss) {
                            return C4CoordinatorPushResult.Blocked("ACK perdido simulado tras commit exitoso")
                        }
                        val confirmed = persistence.confirmPushFullSuccess(
                            syncId = syncId,
                            ownerUid = ownerUid,
                            expectedMutationId = mutationId,
                            newRemoteRevision = txResult.newRevision,
                            newRemotePhotoUrl = uploadedPhotoUrl
                        )
                        if (!confirmed) {
                            return C4CoordinatorPushResult.Blocked("Fallo al confirmar persistencia Room tras CreateActive para mutación $mutationId")
                        }
                        C4CoordinatorPushResult.Success(plan, txResult.newRevision)
                    }
                    is C4TransactionResult.IdempotentAck -> {
                        val confirmed = persistence.confirmPushFullSuccess(
                            syncId = syncId,
                            ownerUid = ownerUid,
                            expectedMutationId = mutationId,
                            newRemoteRevision = txResult.currentRevision,
                            newRemotePhotoUrl = uploadedPhotoUrl
                        )
                        if (!confirmed) {
                            return C4CoordinatorPushResult.Blocked("Fallo al confirmar persistencia Room tras IdempotentAck en CreateActive para mutación $mutationId")
                        }
                        C4CoordinatorPushResult.IdempotentRecovery(txResult.currentRevision, mutationId)
                    }
                    is C4TransactionResult.Conflict -> {
                        C4CoordinatorPushResult.Conflict(plan, txResult.currentRevision, txResult.reason)
                    }
                    is C4TransactionResult.TombstonePrevailed -> {
                        val applied = persistence.applyTombstonePrevailed(syncId, ownerUid, mutationId, txResult.currentRevision)
                        if (!applied) {
                            return C4CoordinatorPushResult.Blocked("Fallo al persistir tombstone prevalente en Room")
                        }
                        C4CoordinatorPushResult.Conflict(plan, txResult.currentRevision, "Tombstone remoto prevalente")
                    }
                    is C4TransactionResult.OwnershipMismatch -> {
                        C4CoordinatorPushResult.Blocked("Ownership mismatch: ${txResult.documentOwner} != ${txResult.callerOwner}")
                    }
                    is C4TransactionResult.Error -> {
                        val cause = txResult.exception
                        if (cause is FirebaseFirestoreException && (cause.code == FirebaseFirestoreException.Code.UNAVAILABLE || cause.message?.contains("offline") == true)) {
                            C4CoordinatorPushResult.Offline
                        } else {
                            C4CoordinatorPushResult.Blocked("Error en transacción CreateActive: ${cause.message}")
                        }
                    }
                    else -> {
                        C4CoordinatorPushResult.Blocked("Transacción CreateActive fallida: $txResult")
                    }
                }
            }

            is PushPlan.ApplyPatch -> {
                var uploadedPhotoUrl: String? = null

                if (plan.fieldsToWrite.contains(BeerDirtyField.PHOTO) && localBeer.photoUri != null) {
                    if (photoBytes == null) {
                        return C4CoordinatorPushResult.StorageFailed(
                            "No se pudo obtener el contenido binario de la fotografía local (${localBeer.photoUri}). Se mantiene PENDING para evitar pérdida de datos."
                        )
                    }
                    val uploadResult = storageAdapter.uploadVersionedPhoto(
                        ownerUid = ownerUid,
                        syncId = syncId,
                        mutationId = mutationId,
                        photoBytes = photoBytes
                    )
                    when (uploadResult) {
                        is C4StorageResult.Success -> {
                            val exists = storageAdapter.photoExists(uploadResult.remotePath)
                            if (!exists) {
                                return C4CoordinatorPushResult.StorageFailed("Verificación de foto en Storage falló tras subida: ${uploadResult.remotePath}")
                            }
                            uploadedPhotoUrl = uploadResult.remotePath
                        }
                        is C4StorageResult.OverwriteRejected -> {
                            return C4CoordinatorPushResult.StorageFailed("Sobrescritura de foto rechazada: ${uploadResult.reason}")
                        }
                        is C4StorageResult.Error -> {
                            return C4CoordinatorPushResult.StorageFailed("Error subiendo foto: ${uploadResult.exception.message}")
                        }
                    }
                }

                val fieldValues = mutableMapOf<String, Any?>()
                if (plan.fieldsToWrite.contains(BeerDirtyField.TYPE)) fieldValues["type"] = localBeer.type.name
                if (plan.fieldsToWrite.contains(BeerDirtyField.TIMESTAMP)) fieldValues["timestamp"] = localBeer.timestamp
                if (plan.fieldsToWrite.contains(BeerDirtyField.LOCATION)) {
                    fieldValues["latitude"] = localBeer.latitude
                    fieldValues["longitude"] = localBeer.longitude
                    fieldValues["locationName"] = localBeer.locationName
                }
                if (plan.fieldsToWrite.contains(BeerDirtyField.COMMENT)) fieldValues["comment"] = localBeer.comment
                if (plan.fieldsToWrite.contains(BeerDirtyField.PHOTO)) {
                    if (uploadedPhotoUrl != null) fieldValues["remotePhotoUrl"] = uploadedPhotoUrl
                    fieldValues["photoSource"] = localBeer.photoSource
                }

                val txResult = firestoreAdapter.executeApplyPatch(
                    syncId = syncId,
                    ownerUid = ownerUid,
                    mutationId = mutationId,
                    baseRevision = effectiveBaseRev,
                    fieldsToWrite = plan.fieldsToWrite,
                    fieldValues = fieldValues,
                    updatedPhotoUrl = uploadedPhotoUrl
                )

                when (txResult) {
                    is C4TransactionResult.Success -> {
                        if (simulateAckLoss) {
                            return C4CoordinatorPushResult.Blocked("ACK perdido simulado tras commit de patch")
                        }
                        val remainingFields = localContext.dirtyFields - plan.fieldsToWrite
                        val confirmed = if (remainingFields.isEmpty()) {
                            // Parche completo confirmado
                            persistence.confirmPushFullSuccess(
                                syncId = syncId,
                                ownerUid = ownerUid,
                                expectedMutationId = mutationId,
                                newRemoteRevision = txResult.newRevision,
                                newRemotePhotoUrl = uploadedPhotoUrl
                            )
                        } else {
                            // Parche parcial confirmado
                            val remainingCodec = BeerDirtyFieldsCodec.encode(remainingFields) ?: ""
                            val confirmedCodec = BeerDirtyFieldsCodec.encode(plan.fieldsToWrite) ?: ""
                            persistence.confirmPushPartialPatch(
                                syncId = syncId,
                                ownerUid = ownerUid,
                                expectedMutationId = mutationId,
                                newRemoteRevision = txResult.newRevision,
                                remainingDirtyFields = remainingCodec,
                                confirmedFields = confirmedCodec,
                                newRemotePhotoUrl = uploadedPhotoUrl
                            )
                        }
                        if (!confirmed) {
                            return C4CoordinatorPushResult.Blocked("Fallo al confirmar persistencia Room tras ApplyPatch para mutación $mutationId")
                        }
                        C4CoordinatorPushResult.Success(plan, txResult.newRevision)
                    }
                    is C4TransactionResult.IdempotentAck -> {
                        val remainingFields = localContext.dirtyFields - plan.fieldsToWrite
                        val confirmed = if (remainingFields.isEmpty()) {
                            persistence.confirmPushFullSuccess(
                                syncId = syncId,
                                ownerUid = ownerUid,
                                expectedMutationId = mutationId,
                                newRemoteRevision = txResult.currentRevision,
                                newRemotePhotoUrl = uploadedPhotoUrl
                            )
                        } else {
                            val remainingCodec = BeerDirtyFieldsCodec.encode(remainingFields) ?: ""
                            val confirmedCodec = BeerDirtyFieldsCodec.encode(plan.fieldsToWrite) ?: ""
                            persistence.confirmPushPartialPatch(
                                syncId = syncId,
                                ownerUid = ownerUid,
                                expectedMutationId = mutationId,
                                newRemoteRevision = txResult.currentRevision,
                                remainingDirtyFields = remainingCodec,
                                confirmedFields = confirmedCodec,
                                newRemotePhotoUrl = uploadedPhotoUrl
                            )
                        }
                        if (!confirmed) {
                            return C4CoordinatorPushResult.Blocked("Fallo al confirmar persistencia Room tras IdempotentAck en ApplyPatch para mutación $mutationId")
                        }
                        C4CoordinatorPushResult.IdempotentRecovery(txResult.currentRevision, mutationId)
                    }
                    is C4TransactionResult.Conflict -> {
                        C4CoordinatorPushResult.Conflict(plan, txResult.currentRevision, txResult.reason)
                    }
                    is C4TransactionResult.TombstonePrevailed -> {
                        val applied = persistence.applyTombstonePrevailed(syncId, ownerUid, mutationId, txResult.currentRevision)
                        if (!applied) {
                            return C4CoordinatorPushResult.Blocked("Fallo al persistir tombstone prevalente en Room tras ApplyPatch")
                        }
                        C4CoordinatorPushResult.Conflict(plan, txResult.currentRevision, "Tombstone remoto prevalente")
                    }
                    is C4TransactionResult.OwnershipMismatch -> {
                        C4CoordinatorPushResult.Blocked("Ownership mismatch: ${txResult.documentOwner} != ${txResult.callerOwner}")
                    }
                    is C4TransactionResult.Error -> {
                        val cause = txResult.exception
                        if (cause is FirebaseFirestoreException && (cause.code == FirebaseFirestoreException.Code.UNAVAILABLE || cause.message?.contains("offline") == true)) {
                            C4CoordinatorPushResult.Offline
                        } else {
                            C4CoordinatorPushResult.Blocked("Error en transacción ApplyPatch: ${cause.message}")
                        }
                    }
                    else -> {
                        C4CoordinatorPushResult.Blocked("Transacción ApplyPatch fallida: $txResult")
                    }
                }
            }

            is PushPlan.WriteTombstone -> {
                val txResult = firestoreAdapter.executeWriteTombstone(
                    syncId = syncId,
                    ownerUid = ownerUid,
                    mutationId = mutationId,
                    baseRevision = effectiveBaseRev
                )

                when (txResult) {
                    is C4TransactionResult.Success -> {
                        val confirmed = persistence.confirmTombstonePushSuccess(
                            syncId = syncId,
                            ownerUid = ownerUid,
                            expectedMutationId = mutationId,
                            newRemoteRevision = txResult.newRevision
                        )
                        if (!confirmed) {
                            return C4CoordinatorPushResult.Blocked("Fallo al confirmar persistencia de tombstone en Room para mutación $mutationId")
                        }
                        C4CoordinatorPushResult.Success(plan, txResult.newRevision)
                    }
                    is C4TransactionResult.IdempotentAck -> {
                        val confirmed = persistence.confirmTombstonePushSuccess(
                            syncId = syncId,
                            ownerUid = ownerUid,
                            expectedMutationId = mutationId,
                            newRemoteRevision = txResult.currentRevision
                        )
                        if (!confirmed) {
                            return C4CoordinatorPushResult.Blocked("Fallo al confirmar persistencia de tombstone en Room para IdempotentAck en mutación $mutationId")
                        }
                        C4CoordinatorPushResult.IdempotentRecovery(txResult.currentRevision, mutationId)
                    }
                    is C4TransactionResult.Conflict -> {
                        C4CoordinatorPushResult.Conflict(plan, txResult.currentRevision, txResult.reason)
                    }
                    is C4TransactionResult.TombstonePrevailed -> {
                        val applied = persistence.applyTombstonePrevailed(syncId, ownerUid, mutationId, txResult.currentRevision)
                        if (!applied) {
                            return C4CoordinatorPushResult.Blocked("Fallo al persistir tombstone prevalente en Room tras WriteTombstone")
                        }
                        C4CoordinatorPushResult.Success(plan, txResult.currentRevision)
                    }
                    is C4TransactionResult.Error -> {
                        val cause = txResult.exception
                        if (cause is FirebaseFirestoreException && (cause.code == FirebaseFirestoreException.Code.UNAVAILABLE || cause.message?.contains("offline") == true)) {
                            C4CoordinatorPushResult.Offline
                        } else {
                            C4CoordinatorPushResult.Blocked("Error en transacción WriteTombstone: ${cause.message}")
                        }
                    }
                    else -> {
                        C4CoordinatorPushResult.Blocked("Transacción WriteTombstone fallida: $txResult")
                    }
                }
            }

            is PushPlan.AlreadyApplied -> {
                val currentRemoteRev = remoteDoc?.revision ?: effectiveRemoteRev
                val confirmed = if (localBeer.syncStatus == SyncStatus.DELETED) {
                    persistence.confirmTombstonePushSuccess(
                        syncId = syncId,
                        ownerUid = ownerUid,
                        expectedMutationId = mutationId,
                        newRemoteRevision = currentRemoteRev
                    )
                } else {
                    persistence.confirmPushFullSuccess(
                        syncId = syncId,
                        ownerUid = ownerUid,
                        expectedMutationId = mutationId,
                        newRemoteRevision = currentRemoteRev
                    )
                }
                if (!confirmed) {
                    return C4CoordinatorPushResult.Blocked("Fallo al confirmar persistencia Room para mutación ya aplicada $mutationId")
                }
                C4CoordinatorPushResult.IdempotentRecovery(currentRemoteRev, mutationId)
            }

            is PushPlan.RemoteWinsOnly -> {
                C4CoordinatorPushResult.Conflict(plan, remoteDoc?.revision ?: 0L, "Remoto gana en todos los campos en conflicto")
            }

            is PushPlan.RemoteTombstoneWins -> {
                val tombRev = remoteDoc?.revision ?: 0L
                val applied = persistence.applyTombstonePrevailed(syncId, ownerUid, mutationId, tombRev)
                if (!applied) {
                    return C4CoordinatorPushResult.Blocked("Fallo al persistir tombstone prevalente en Room")
                }
                C4CoordinatorPushResult.Conflict(plan, tombRev, "Tombstone remoto prevalente")
            }

            is PushPlan.DeleteAlreadySatisfied -> {
                C4CoordinatorPushResult.Success(plan, remoteDoc?.revision ?: 0L)
            }

            is PushPlan.RemoteAbsentForKnownEntity -> {
                C4CoordinatorPushResult.Blocked("Documento remoto ausente para entidad previamente conocida")
            }

            is PushPlan.RemotePresentForLocalOnlyNeedsReconciliation -> {
                C4CoordinatorPushResult.Blocked("Documento remoto presente para entidad local-only (-1/-1): requiere reconciliación")
            }

            is PushPlan.InvalidMetadata -> {
                C4CoordinatorPushResult.Blocked(plan.reason)
            }
        }
    }

    /**
     * Ejecuta una sincronización PULL para una entidad concreta.
     */
    suspend fun executePull(
        syncId: String,
        ownerUid: String,
        readScope: RemoteReadScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
        provenance: PullProvenance = PullProvenance(),
        cachedRemoteDoc: C4FirestoreDocument? = null
    ): C4CoordinatorPullResult {
        // Bloquear lecturas no autoritativas
        if (readScope != RemoteReadScope.AUTHORITATIVE_CONFIRMED) {
            return C4CoordinatorPullResult.Blocked("Lectura no autoritativa: scope debe ser AUTHORITATIVE_CONFIRMED")
        }

        val remoteDoc = cachedRemoteDoc ?: firestoreAdapter.readServerDocument(syncId)
        val localBeer = persistence.beerDao.getBeerBySyncId(syncId, ownerUid)

        if (remoteDoc != null && remoteDoc.userId != ownerUid) {
            return C4CoordinatorPullResult.Blocked("Ownership mismatch en PULL: ${remoteDoc.userId} != $ownerUid")
        }

        if (remoteDoc != null) {
            if (remoteDoc.revision == 0L || remoteDoc.fieldRevisions.isEmpty()) {
                return C4CoordinatorPullResult.Blocked(
                    "Documento remoto legacy (rev 0 / sin fieldRevisions): requiere resolución"
                )
            }

            val canonicalKeys = C4SyncConstants.KNOWN_FIELD_REVISIONS_KEYS
            val fr = remoteDoc.fieldRevisions
            val isCausalValid = fr.size == 5 &&
                    fr.keys.containsAll(canonicalKeys) &&
                    fr.values.all { it in 1L..remoteDoc.revision }

            if (!isCausalValid) {
                return C4CoordinatorPullResult.Blocked(
                    "Metadata causal remota inválida: fieldRevisions ($fr) no cumple invariante de 5 claves en [1..${remoteDoc.revision}]"
                )
            }
        }

        val remoteState = when {
            remoteDoc != null -> remoteDoc.toRemotePullState()
            else -> RemotePullState.ConfirmedAbsent
        }

        val localContext = localBeer?.let {
            LocalMutationContext(
                syncStatus = it.syncStatus,
                remoteRevision = it.remoteRevision,
                baseRevision = it.baseRevision,
                dirtyFields = BeerDirtyFieldsCodec.decode(it.dirtyFields),
                lastMutationId = it.lastMutationId
            )
        }

        val plan = SyncProtocolPullPlanner.planPull(
            local = localContext,
            remoteState = remoteState,
            provenance = provenance
        )

        return when (plan) {
            is PullPlan.InsertLocal -> {
                if (remoteDoc == null) return C4CoordinatorPullResult.Blocked("Doc remoto null en InsertLocal")
                val parsedType = try {
                    BeerType.valueOf(remoteDoc.type)
                } catch (_: Exception) {
                    BeerType.LATA
                }
                val rowId = persistence.insertRemoteBeerAsSynced(
                    syncId = syncId,
                    ownerUid = ownerUid,
                    remoteRevision = plan.remoteRevision,
                    type = parsedType,
                    timestamp = remoteDoc.timestamp,
                    latitude = remoteDoc.latitude,
                    longitude = remoteDoc.longitude,
                    comment = remoteDoc.comment,
                    locationName = remoteDoc.locationName,
                    remotePhotoUrl = remoteDoc.remotePhotoUrl,
                    photoSource = remoteDoc.photoSource
                )
                if (rowId > 0) C4CoordinatorPullResult.Applied(plan) else C4CoordinatorPullResult.Blocked("Fallo al insertar cerveza en Room")
            }

            is PullPlan.UpdateLocalSynced -> {
                if (remoteDoc == null) return C4CoordinatorPullResult.Blocked("Doc remoto null en UpdateLocalSynced")
                val parsedType = try {
                    BeerType.valueOf(remoteDoc.type)
                } catch (_: Exception) {
                    BeerType.LATA
                }

                val ok = persistence.applyRemoteUpdateToSynced(
                    syncId = syncId,
                    ownerUid = ownerUid,
                    newRemoteRevision = plan.newRemoteRevision,
                    type = parsedType,
                    timestamp = remoteDoc.timestamp,
                    latitude = remoteDoc.latitude,
                    longitude = remoteDoc.longitude,
                    comment = remoteDoc.comment,
                    locationName = remoteDoc.locationName,
                    remotePhotoUrl = remoteDoc.remotePhotoUrl,
                    photoSource = remoteDoc.photoSource
                )
                if (ok) C4CoordinatorPullResult.Applied(plan) else C4CoordinatorPullResult.Blocked("Fallo al aplicar update en Room")
            }

            is PullPlan.ApplyRemoteTombstone -> {
                val pendingMutationId = localBeer?.lastMutationId
                val ok = if (localContext?.syncStatus == SyncStatus.PENDING && !pendingMutationId.isNullOrEmpty()) {
                    persistence.applyTombstonePrevailed(
                        syncId = syncId,
                        ownerUid = ownerUid,
                        displacedMutationId = pendingMutationId,
                        remoteTombstoneRevision = plan.newRemoteRevision
                    )
                } else {
                    persistence.applyRemoteTombstoneToSynced(syncId, ownerUid, plan.newRemoteRevision)
                }
                if (ok) C4CoordinatorPullResult.Applied(plan) else C4CoordinatorPullResult.Blocked("Fallo al aplicar tombstone en Room")
            }

            is PullPlan.ConfirmPendingMutation -> {
                val remainingFields = (localContext?.dirtyFields ?: emptySet()) - plan.confirmedFields - plan.remoteWinsFields
                val remainingCodec = BeerDirtyFieldsCodec.encode(remainingFields) ?: ""
                val confirmedCodec = BeerDirtyFieldsCodec.encode(plan.confirmedFields + plan.remoteWinsFields) ?: ""
                val ok = if (remainingFields.isEmpty()) {
                    persistence.confirmPushFullSuccess(
                        syncId = syncId,
                        ownerUid = ownerUid,
                        expectedMutationId = plan.confirmedMutationId,
                        newRemoteRevision = plan.newRemoteRevision,
                        newRemotePhotoUrl = remoteDoc?.remotePhotoUrl
                    )
                } else {
                    persistence.confirmPushPartialPatch(
                        syncId = syncId,
                        ownerUid = ownerUid,
                        expectedMutationId = plan.confirmedMutationId,
                        newRemoteRevision = plan.newRemoteRevision,
                        remainingDirtyFields = remainingCodec,
                        confirmedFields = confirmedCodec,
                        newRemotePhotoUrl = remoteDoc?.remotePhotoUrl
                    )
                }
                if (ok) C4CoordinatorPullResult.Applied(plan) else C4CoordinatorPullResult.Blocked("Fallo al confirmar mutación pendiente en PULL")
            }

            is PullPlan.MergeWithPending -> {
                if (remoteDoc == null) return C4CoordinatorPullResult.Blocked("Doc remoto null en MergeWithPending")
                val ok = persistence.applyMergeWithPending(
                    syncId = syncId,
                    ownerUid = ownerUid,
                    newRemoteRevision = plan.newRemoteRevision,
                    fieldsToApplyFromRemote = plan.remoteFieldsToApply + plan.remoteWinsConflictFields,
                    localFieldsToPreserve = plan.localPendingFieldsToPreserve,
                    remoteDoc = remoteDoc
                )
                if (ok) C4CoordinatorPullResult.Applied(plan) else C4CoordinatorPullResult.Blocked("Fallo al aplicar merge con cambios pendientes")
            }

            is PullPlan.NoOpAlreadyUpToDate -> {
                C4CoordinatorPullResult.NoOp
            }

            is PullPlan.PreserveLocalDelete -> {
                C4CoordinatorPullResult.NoOp
            }

            is PullPlan.DeleteAlreadySatisfied -> {
                C4CoordinatorPullResult.NoOp
            }

            is PullPlan.PreserveLocalPending -> {
                C4CoordinatorPullResult.NoOp
            }

            is PullPlan.IgnoreIncompleteRead -> {
                C4CoordinatorPullResult.IncompleteReadIgnored
            }

            is PullPlan.IgnoreRemoteTombstone -> {
                C4CoordinatorPullResult.NoOp
            }

            is PullPlan.PendingFieldOrderNeedsReconciliation -> {
                C4CoordinatorPullResult.Blocked("Orden de campos ambiguo requiere reconciliación")
            }

            is PullPlan.PendingConflictNeedsReconciliation -> {
                C4CoordinatorPullResult.Blocked("Conflicto pendiente requiere reconciliación")
            }

            is PullPlan.RemotePresentForLocalOnlyNeedsReconciliation -> {
                C4CoordinatorPullResult.Blocked("Remoto presente para entidad local-only requiere reconciliación")
            }

            is PullPlan.RemoteAbsentForKnownEntity -> {
                C4CoordinatorPullResult.Blocked("Remoto ausente para entidad conocida")
            }

            is PullPlan.ApplyRemotePurge -> {
                C4CoordinatorPullResult.Blocked("Purga remota no autorizada automáticamente")
            }

            is PullPlan.LegacyRemoteNeedsResolution -> {
                C4CoordinatorPullResult.Blocked("Documento remoto legacy requiere resolución previa")
            }

            is PullPlan.InvalidMetadata -> {
                C4CoordinatorPullResult.Blocked(plan.reason)
            }
        }
    }

    /**
     * Descubrimiento remoto por colección (PULL por colección).
     *
     * Consulta Firestore desde [Source.SERVER] para descubrir todos los documentos pertenecientes
     * a [ownerUid] (tanto activos como tombstones) y aplica el protocolo de reconciliación
     * C3B de manera granular para cada cerveza.
     *
     * INVARIANTES DE ROBUSTEZ:
     * - Si la consulta remota falla por red o permisos, NO se interpreta como colección vacía;
     *   la excepción se propaga directamente al invocador.
     * - Las cervezas nuevas se insertan mediante [PullPlan.InsertLocal].
     * - Las cervezas existentes se actualizan de forma monotónica causal.
     * - Los tombstones remotos se respetan aplicando [PullPlan.ApplyRemoteTombstone].
     * - Las mutaciones locales pendientes nunca se sobrescriben a ciegas (MergeWithPending).
     * - Se garantiza estricto aislamiento de propiedad (Ownership isolation).
     */
    suspend fun executePullCollection(
        ownerUid: String,
        readScope: RemoteReadScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED
    ): Map<String, C4CoordinatorPullResult> {
        if (readScope != RemoteReadScope.AUTHORITATIVE_CONFIRMED) {
            throw IllegalArgumentException("Lectura no autoritativa: scope debe ser AUTHORITATIVE_CONFIRMED")
        }

        val remoteDocs = firestoreAdapter.queryServerDocuments(ownerUid)
        val results = mutableMapOf<String, C4CoordinatorPullResult>()

        for (remoteDoc in remoteDocs) {
            if (remoteDoc.userId != ownerUid) {
                results[remoteDoc.syncId] = C4CoordinatorPullResult.Blocked(
                    "Ownership mismatch en PULL colección: documento ${remoteDoc.syncId} pertenece a ${remoteDoc.userId} (esperado: $ownerUid)"
                )
                continue
            }

            results[remoteDoc.syncId] = executePull(
                syncId = remoteDoc.syncId,
                ownerUid = ownerUid,
                readScope = readScope,
                cachedRemoteDoc = remoteDoc
            )
        }

        return results
    }
}
