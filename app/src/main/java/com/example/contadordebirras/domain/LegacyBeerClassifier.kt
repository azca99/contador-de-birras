package com.example.contadordebirras.domain

import com.example.contadordebirras.data.SyncStatus

/**
 * Contexto de entidad local para la evaluación pura del clasificador legacy (Fase C4.1A).
 *
 * @property syncId Identificador global único de sincronización (UUID).
 * @property ownerUid Identificador del propietario de la fila en Room (ej. UID autenticado, "guest_local").
 * @property syncStatus Estado de sincronización en Room (PENDING, SYNCED, DELETED).
 * @property remoteRevision Última revisión remota registrada en local (0L para entidades legacy).
 * @property baseRevision Revisión base registrada en local (0L para entidades legacy).
 * @property dirtyFields Conjunto de campos modificados localmente.
 * @property lastMutationId Identificador de mutación de la última modificación local.
 * @property hasRemotePhotoUrl Indica si la fila local tiene registrada una URL remota de foto en Storage.
 */
data class LegacyLocalEntityContext(
    val syncId: String,
    val ownerUid: String,
    val syncStatus: String,
    val remoteRevision: Long,
    val baseRevision: Long,
    val dirtyFields: Set<BeerDirtyField>,
    val lastMutationId: String?,
    val hasRemotePhotoUrl: Boolean = false
)

/**
 * Representación pura del estado remoto observado en Firestore para la clasificación legacy.
 */
sealed interface LegacyRemoteSnapshot {
    /** El documento remoto no existe en Firestore */
    data object Absent : LegacyRemoteSnapshot

    /** El documento remoto existe en Firestore con su metadata observada */
    data class Present(
        val revision: Long,
        val isDeleted: Boolean = false,
        val lastMutationId: String? = null,
        val fieldRevisions: Map<BeerDirtyField, Long> = emptyMap()
    ) : LegacyRemoteSnapshot {
        val isModern: Boolean get() = revision >= FIRST_PROTOCOL_REVISION
        val isLegacy: Boolean get() = revision == LEGACY_REMOTE_REVISION
    }
}

/**
 * Evidencia histórica y causal verificable disponible para la entidad.
 *
 * ADVERTENCIA CRÍTICA:
 * Ningún timestamp, sourceDeviceId o lastMutationId aislado constituye evidencia causal suficiente
 * de ordenación temporal. La evidencia debe sustentarse en registros locales continuos o
 * historiales de mutaciones durables.
 *
 * @property isProvenNeverSyncedCreation Prueba fidedigna y positiva de que esta fila fue creada
 * en este dispositivo localmente y jamás ha sido sincronizada ni confirmada en remoto.
 * Solo puede ser true ante prueba concluyente.
 * @property pastLocalMutationIds Historial verificable de mutationIds pasados generados por este
 * dispositivo para esta entidad (para identificar ACKs perdidos).
 * @property hasPriorSyncHistory Prueba verificable de que la entidad tuvo sincronizaciones previas
 * exitosas en el pasado (logs locales, presencia de remotePhotoUrl, etc.).
 */
data class LegacyHistoricalEvidence(
    val isProvenNeverSyncedCreation: Boolean = false,
    val pastLocalMutationIds: Set<String> = emptySet(),
    val hasPriorSyncHistory: Boolean = false
)

/**
 * Jerarquía sellada pura que representa las decisiones de clasificación legacy (Fase C4.1A).
 */
sealed interface LegacyClassification {

    /**
     * Creación local validada de forma segura:
     * - Remoto ausente bajo lectura autoritativa confirmada.
     * - Evidencia positiva verificable de que nunca fue sincronizada (isProvenNeverSyncedCreation = true).
     * - dirtyFields == ALL_FIELDS.
     * - local.syncStatus == PENDING.
     * - Coherencia estricta de ownerUid.
     *
     * Autoriza la transición segura a sentinelas local-only (-1L/-1L) para emitir CreateActive en C3A.
     */
    data class PromoteToLocalOnlyCreation(
        val initialMutationId: String
    ) : LegacyClassification

    /**
     * Posible ACK de mutación propia identificada por coincidencia de lastMutationId
     * (o mutación previa en pastLocalMutationIds).
     *
     * IMPORTANTE: La coincidencia de mutationId NO demuestra por sí sola que todos los campos
     * hayan sido escritos. Se delega la evaluación granular a SyncProtocolPullPlanner (C3B),
     * que verifica revisiones por campo (fieldRevisions) y campos confirmados vs ganados por remoto.
     */
    data class PotentialOwnMutationAck(
        val matchedMutationId: String,
        val isModern: Boolean,
        val remoteRevision: Long
    ) : LegacyClassification

    /**
     * Documento remoto moderno activo (revision >= 1) con mutationId diferente.
     * La entidad local debe ser procesada por los planificadores C3A/C3B sin inventar revisiones.
     */
    data class ModernRemoteActive(
        val remoteRevision: Long,
        val remoteLastMutationId: String?
    ) : LegacyClassification

    /**
     * Documento remoto legacy existente en Firestore (revision == 0 o sin metadata moderna).
     * Debe conservarse sin asumir revisiones ficticias ni forzar conversiones incompatibles.
     */
    data class LegacyRemoteActive(
        val remoteLastMutationId: String?
    ) : LegacyClassification

    /**
     * Documento remoto es un tombstone (isDeleted == true).
     * DELETE WINS: la eliminación remota prevalece sobre cualquier estado local activo.
     */
    data class RemoteTombstonePrevailed(
        val remoteRevision: Long,
        val isModern: Boolean
    ) : LegacyClassification

    /**
     * La entidad local está marcada como DELETED frente a un remoto activo.
     * DELETE WINS: jamás se clasifica como creación activa; requiere emitir WriteTombstone.
     */
    data class LocalDeleteWins(
        val remoteExists: Boolean,
        val remoteRevision: Long
    ) : LegacyClassification

    /**
     * Intención de borrado satisfecha: la fila local está en DELETED y el remoto
     * ya es tombstone o está ausente confirmado.
     */
    data object DeleteAlreadySatisfied : LegacyClassification

    /**
     * Entidad previamente conocida / sincronizada que ahora está ausente en Firestore
     * bajo lectura autoritativa confirmada.
     * Anti-resurrección: no se debe recrear automáticamente; requiere reconciliación o purga local.
     */
    data class RemoteAbsentForPreviouslySyncedEntity(
        val reason: String
    ) : LegacyClassification

    /**
     * Lectura remota incompleta o no autoritativa: no se pueden inferir ausencias ni confirmar eliminaciones.
     * Se preserva el estado local intacto sin aplicar cambios destructivos.
     */
    data object IgnoreIncompleteRead : LegacyClassification

    /**
     * Aislamiento estricto de propietario: la entidad local pertenece a guest_local o legacy_unassigned,
     * no coincidiendo con la cuenta autenticada activa. Se preserva localmente sin cruzarla al usuario remoto.
     */
    data class PreserveIsolatedGuestOrUnassigned(
        val ownerUid: String,
        val reason: String
    ) : LegacyClassification

    /**
     * Inconsistencia o colisión de ownership detectada (ej. pertenece a otro UID autenticado distinto).
     * Se bloquea la operación para evitar fuga de información entre usuarios.
     */
    data class OwnershipMismatch(
        val localOwnerUid: String,
        val expectedOwnerUid: String,
        val reason: String
    ) : LegacyClassification

    /**
     * Situación ambigua o con evidencia insuficiente:
     * - PENDING 0/0 ALL_FIELDS con remoto ausente PERO sin evidencia positiva de creación no sincronizada.
     * - PENDING 0/0 con dirtyFields parciales y remoto ausente.
     * - Datos contradictorios o estados indeterminados.
     *
     * DECISIÓN DE SEGURIDAD:
     * La fila se preserva íntegramente en Room. Se bloquea cualquier mutación a -1/-1 y se remite a reconciliación.
     */
    data class NeedsReconciliation(
        val reason: String,
        val localSyncStatus: String,
        val remoteObserved: Boolean
    ) : LegacyClassification

    /**
     * Metadata corrupta o incoherente detectada en los datos puros.
     */
    data class InvalidMetadata(
        val reason: String
    ) : LegacyClassification
}

/**
 * Clasificador determinista y puro de entidades legacy para la Fase C4.1A.
 *
 * Responsabilidad única:
 * Evaluar el estado de entidades creadas o migradas bajo esquemas anteriores (revisiones 0/0)
 * distinguiendo con seguridad creaciones legítimas, documentos preexistentes, ACKs perdidos
 * y situaciones ambiguas que deben bloquearse para impedir pérdidas de datos.
 *
 * No realiza consultas a base de datos, red, Firebase ni WorkManager.
 */
object LegacyBeerClassifier {

    const val GUEST_OWNER_UID = "guest_local"
    const val LEGACY_UNASSIGNED_UID = "legacy_unassigned"

    /**
     * Clasifica una entidad a partir de sus contextos puros.
     */
    fun classify(
        local: LegacyLocalEntityContext,
        remote: LegacyRemoteSnapshot,
        readScope: RemoteReadScope,
        expectedOwnerUid: String,
        evidence: LegacyHistoricalEvidence = LegacyHistoricalEvidence()
    ): LegacyClassification {
        // 1. Validación de metadata local
        if (local.baseRevision < LOCAL_ONLY_REVISION) {
            return LegacyClassification.InvalidMetadata(
                "Local baseRevision cannot be less than $LOCAL_ONLY_REVISION: ${local.baseRevision}"
            )
        }
        if (local.remoteRevision < LOCAL_ONLY_REVISION) {
            return LegacyClassification.InvalidMetadata(
                "Local remoteRevision cannot be less than $LOCAL_ONLY_REVISION: ${local.remoteRevision}"
            )
        }
        if (local.baseRevision > local.remoteRevision) {
            return LegacyClassification.InvalidMetadata(
                "Local baseRevision (${local.baseRevision}) cannot exceed local remoteRevision (${local.remoteRevision})"
            )
        }
        if ((local.remoteRevision == LOCAL_ONLY_REVISION) != (local.baseRevision == LOCAL_ONLY_REVISION)) {
            return LegacyClassification.InvalidMetadata(
                "Incoherent local revisions: remoteRevision=${local.remoteRevision} and baseRevision=${local.baseRevision} cannot mix $LOCAL_ONLY_REVISION with known revisions"
            )
        }
        if (local.syncStatus != SyncStatus.PENDING &&
            local.syncStatus != SyncStatus.SYNCED &&
            local.syncStatus != SyncStatus.DELETED
        ) {
            return LegacyClassification.InvalidMetadata(
                "Unsupported syncStatus for legacy classification: '${local.syncStatus}'"
            )
        }
        if (local.syncStatus == SyncStatus.PENDING && local.dirtyFields.isEmpty()) {
            return LegacyClassification.InvalidMetadata(
                "PENDING local entity must have at least one dirty field."
            )
        }

        // 2. Validación de metadata remota
        if (remote is LegacyRemoteSnapshot.Present) {
            if (remote.revision < LEGACY_REMOTE_REVISION) {
                return LegacyClassification.InvalidMetadata(
                    "Remote revision cannot be negative: ${remote.revision}"
                )
            }
            for ((field, rev) in remote.fieldRevisions) {
                if (rev < 0L) {
                    return LegacyClassification.InvalidMetadata(
                        "Remote fieldRevision cannot be negative for field $field: $rev"
                    )
                }
                if (rev > remote.revision) {
                    return LegacyClassification.InvalidMetadata(
                        "Remote fieldRevision ($rev) cannot exceed remote revision (${remote.revision}) for field $field"
                    )
                }
            }
        }

        // 3. Aislamiento estricto de OwnerUid (Regla 6)
        if (local.ownerUid != expectedOwnerUid) {
            return if (local.ownerUid == GUEST_OWNER_UID || local.ownerUid == LEGACY_UNASSIGNED_UID) {
                LegacyClassification.PreserveIsolatedGuestOrUnassigned(
                    ownerUid = local.ownerUid,
                    reason = "Entity belongs to '${local.ownerUid}' and must remain isolated from active user '$expectedOwnerUid'."
                )
            } else {
                LegacyClassification.OwnershipMismatch(
                    localOwnerUid = local.ownerUid,
                    expectedOwnerUid = expectedOwnerUid,
                    reason = "Ownership mismatch: entity owned by '${local.ownerUid}' cannot be synced under '$expectedOwnerUid'."
                )
            }
        }

        // 4. Garantía de lectura remota para documento ausente (Regla 2)
        if (remote is LegacyRemoteSnapshot.Absent) {
            if (readScope != RemoteReadScope.AUTHORITATIVE_CONFIRMED) {
                return LegacyClassification.IgnoreIncompleteRead
            }

            // Remoto ausente bajo lectura autoritativa confirmada:
            // 4a. Entidad local DELETED (Regla 7)
            if (local.syncStatus == SyncStatus.DELETED) {
                return LegacyClassification.DeleteAlreadySatisfied
            }

            // 4b. Entidad con evidencia de haber sido sincronizada previamente (Regla 2)
            if (evidence.hasPriorSyncHistory || local.hasRemotePhotoUrl) {
                return LegacyClassification.RemoteAbsentForPreviouslySyncedEntity(
                    reason = "Entity has prior sync history but is absent from authoritative remote read."
                )
            }

            // 4c. Entidad PENDING con dirtyFields parciales (Regla 1 y 2)
            // Si solo tiene modificados algunos campos, existió previamente en Firestore y se editaron campos locales.
            if (local.dirtyFields != BeerDirtyFieldsCodec.ALL_FIELDS) {
                return LegacyClassification.RemoteAbsentForPreviouslySyncedEntity(
                    reason = "Entity with partial dirty fields was previously known but is absent from authoritative remote read."
                )
            }

            // 4d. Entidad PENDING con ALL_FIELDS
            // Regla 1 y 3: ALL_FIELDS NO demuestra creación nueva a menos que haya prueba positiva fidedigna.
            if (evidence.isProvenNeverSyncedCreation) {
                return LegacyClassification.PromoteToLocalOnlyCreation(
                    initialMutationId = local.lastMutationId ?: "CREATION_INITIAL"
                )
            }

            // Sin prueba positiva de creación no sincronizada: BLOQUEO SEGURO (Regla 1)
            return LegacyClassification.NeedsReconciliation(
                reason = "Legacy PENDING 0/0 entity with ALL_FIELDS lacks positive evidence of un-synced creation.",
                localSyncStatus = local.syncStatus,
                remoteObserved = false
            )
        }

        // A partir de aquí: remote is LegacyRemoteSnapshot.Present
        check(remote is LegacyRemoteSnapshot.Present)

        // 5. Remoto es Tombstone (isDeleted == true) (Regla 7: DELETE WINS)
        if (remote.isDeleted) {
            return if (local.syncStatus == SyncStatus.DELETED) {
                LegacyClassification.DeleteAlreadySatisfied
            } else {
                LegacyClassification.RemoteTombstonePrevailed(
                    remoteRevision = remote.revision,
                    isModern = remote.isModern
                )
            }
        }

        // 6. Local DELETED frente a remoto activo (Regla 7: DELETE WINS)
        if (local.syncStatus == SyncStatus.DELETED) {
            return LegacyClassification.LocalDeleteWins(
                remoteExists = true,
                remoteRevision = remote.revision
            )
        }

        // 7. ACK de mutación propia conocida (Regla 4)
        // Coincidencia con lastMutationId activo o con mutationIds pasados en el historial
        val matchedMutationId = when {
            local.lastMutationId != null && remote.lastMutationId == local.lastMutationId -> local.lastMutationId
            remote.lastMutationId != null && evidence.pastLocalMutationIds.contains(remote.lastMutationId) -> remote.lastMutationId
            else -> null
        }
        if (matchedMutationId != null) {
            return LegacyClassification.PotentialOwnMutationAck(
                matchedMutationId = matchedMutationId,
                isModern = remote.isModern,
                remoteRevision = remote.revision
            )
        }

        // 8. Remoto activo con mutación diferente
        return if (remote.isLegacy) {
            // Regla 5: Documento legacy activo sin revisión moderna (revision = 0)
            LegacyClassification.LegacyRemoteActive(
                remoteLastMutationId = remote.lastMutationId
            )
        } else {
            // Documento moderno activo (revision >= 1)
            LegacyClassification.ModernRemoteActive(
                remoteRevision = remote.revision,
                remoteLastMutationId = remote.lastMutationId
            )
        }
    }

    /**
     * Sobrecarga de conveniencia que acepta [RemoteBeerSyncMetadata] opcional.
     */
    fun classify(
        local: LegacyLocalEntityContext,
        remoteMetadata: RemoteBeerSyncMetadata?,
        readScope: RemoteReadScope,
        expectedOwnerUid: String,
        evidence: LegacyHistoricalEvidence = LegacyHistoricalEvidence()
    ): LegacyClassification {
        val snapshot = when {
            remoteMetadata == null -> LegacyRemoteSnapshot.Absent
            else -> LegacyRemoteSnapshot.Present(
                revision = remoteMetadata.revision,
                isDeleted = remoteMetadata.isDeleted,
                lastMutationId = remoteMetadata.lastMutationId,
                fieldRevisions = remoteMetadata.fieldRevisions
            )
        }
        return classify(local, snapshot, readScope, expectedOwnerUid, evidence)
    }
}
