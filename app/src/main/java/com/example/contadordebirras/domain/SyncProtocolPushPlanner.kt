package com.example.contadordebirras.domain

import com.example.contadordebirras.data.SyncStatus

/**
 * Constantes conceptuales de revisión para el protocolo de sincronización multidispositivo (Fase C3A).
 *
 * LOCAL_ONLY_REVISION (-1): Entidad creada localmente y nunca confirmada en remoto.
 * LEGACY_REMOTE_REVISION (0): Documento legacy remoto que aún no tiene campo de revisión.
 * FIRST_PROTOCOL_REVISION (1): Primera revisión para documentos gestionados por el nuevo protocolo.
 *
 * DEUDA TÉCNICA (Integración posterior / Fase C4):
 * Actualmente `BeerRepository.addBeer()` inicializa filas locales nuevas con remoteRevision = 0L y baseRevision = 0L.
 * En la fase de integración remota (C4), `BeerRepository.addBeer()` deberá actualizarse para usar
 * LOCAL_ONLY_REVISION (-1L) tanto para remoteRevision como para baseRevision.
 * En esta fase (C3A), BeerRepository permanece 100% intacto.
 *
 * RECONCILIACIÓN EN FASE C4 (Documentación de diseño para RemotePresentForLocalOnlyNeedsReconciliation):
 * Cuando una entidad PENDING local-only (-1/-1) encuentra un documento remoto activo con un lastMutationId distinto,
 * el planner emite `PushPlan.RemotePresentForLocalOnlyNeedsReconciliation`.
 * Para resolver esto sin perder cambios, la capa C4 necesitará:
 * 1. Opción recomendada: Reconciliación mediante pull: Room adopta `remoteRevision = remote.revision` y
 *    `baseRevision = remote.revision`, preservando los `dirtyFields` acumulados localmente por la edición posterior (M2).
 *    En el siguiente intento de push, el planner tratará la mutación como un `ApplyPatch` estándar (3-way merge).
 * 2. Alternativa con tracking: Si Room persistiera el `creationMutationId` original (M1) separado del
 *    `lastMutationId` actual (M2), C4 podría certificar que el documento remoto activo fue creado por este
 *    mismo dispositivo, reconciliando la baseRevision de forma determinista y segura.
 */
const val LOCAL_ONLY_REVISION = -1L
const val LEGACY_REMOTE_REVISION = 0L
const val FIRST_PROTOCOL_REVISION = 1L

/**
 * Metadata remota de sincronización para un documento en Firestore.
 *
 * Si el documento no existe en remoto, se representa como `null` en lugar de una instancia ficticia.
 */
data class RemoteBeerSyncMetadata(
    val revision: Long,
    val isDeleted: Boolean,
    val lastMutationId: String?,
    val fieldRevisions: Map<BeerDirtyField, Long> = emptyMap()
)

/**
 * Contexto local puro requerido para planificar una mutación push.
 */
data class LocalMutationContext(
    val syncStatus: String,
    val remoteRevision: Long,
    val baseRevision: Long,
    val dirtyFields: Set<BeerDirtyField>,
    val lastMutationId: String?
)

/**
 * Resultado semántico puro del planificador de conflictos de push.
 */
sealed interface PushPlan {

    /**
     * Creación de un documento activo nuevo en Firestore.
     */
    data class CreateActive(
        val nextRevision: Long,
        val fieldsToWrite: Set<BeerDirtyField>
    ) : PushPlan

    /**
     * Aplicación de un parche sobre un documento existente.
     * Puede incluir campos que se escribirán y campos donde remoto gana y no se sobrescriben.
     */
    data class ApplyPatch(
        val nextRevision: Long,
        val fieldsToWrite: Set<BeerDirtyField>,
        val remoteWinsFields: Set<BeerDirtyField> = emptySet()
    ) : PushPlan

    /**
     * Todos los campos locales modificados entraron en conflicto con revisiones remotas más recientes.
     * Remoto gana en todos; no hay campos seguros para escribir.
     */
    data class RemoteWinsOnly(
        val remoteWinsFields: Set<BeerDirtyField>
    ) : PushPlan

    /**
     * Escritura de un nuevo tombstone para marcar el documento como borrado en remoto.
     */
    data class WriteTombstone(
        val nextRevision: Long
    ) : PushPlan

    /**
     * La mutación local ya fue confirmada previamente en remoto (idempotencia garantizada por mutationId).
     */
    data object AlreadyApplied : PushPlan

    /**
     * El documento remoto ya es un tombstone; la eliminación remota gana sobre cualquier edición local pendiente.
     */
    data object RemoteTombstoneWins : PushPlan

    /**
     * La intención local de borrado ya está satisfecha porque el documento remoto ya es tombstone o no existe.
     */
    data object DeleteAlreadySatisfied : PushPlan

    /**
     * La entidad afirma haber conocido previamente una versión remota (remoteRevision >= 0),
     * pero el documento remoto no existe. Protección anti-resurrección.
     */
    data object RemoteAbsentForKnownEntity : PushPlan

    /**
     * Entidad local-only (remoteRevision = -1) que encuentra un documento remoto activo con un lastMutationId
     * distinto. Ocurre legítimamente si Firestore confirmó CreateActive(M1) pero el proceso murió antes del
     * ACK en Room, y luego el usuario editó la entidad generando M2. También puede indicar colisión de identidad.
     * Requiere que la capa de sincronización (Fase C4) concilie los estados antes de continuar.
     */
    data object RemotePresentForLocalOnlyNeedsReconciliation : PushPlan

    /**
     * Metadata inconsistente o inválida detectada. Evita fallos y previene estados corruptos.
     */
    data class InvalidMetadata(val reason: String) : PushPlan
}

/**
 * Planificador puro y determinista de conflictos para operaciones PUSH del protocolo multidispositivo (Fase C3A).
 *
 * Responde a: "Dada una mutación local y el estado remoto actual, ¿qué debería hacer una futura transacción Firestore?"
 * No ejecuta operaciones ni contiene dependencias de Firebase, Room, Android o WorkManager.
 */
object SyncProtocolPushPlanner {

    /**
     * Planifica la acción a tomar durante un push basándose en el estado local y la metadata remota actual.
     */
    fun planPush(
        local: LocalMutationContext,
        remote: RemoteBeerSyncMetadata?
    ): PushPlan {
        // 1. Validación de metadata local
        if (local.baseRevision < LOCAL_ONLY_REVISION) {
            return PushPlan.InvalidMetadata(
                "Local baseRevision cannot be less than $LOCAL_ONLY_REVISION: ${local.baseRevision}"
            )
        }
        if (local.remoteRevision < LOCAL_ONLY_REVISION) {
            return PushPlan.InvalidMetadata(
                "Local remoteRevision cannot be less than $LOCAL_ONLY_REVISION: ${local.remoteRevision}"
            )
        }
        if (local.baseRevision > local.remoteRevision) {
            return PushPlan.InvalidMetadata(
                "Local baseRevision (${local.baseRevision}) cannot exceed local remoteRevision (${local.remoteRevision})"
            )
        }
        if ((local.remoteRevision == LOCAL_ONLY_REVISION) != (local.baseRevision == LOCAL_ONLY_REVISION)) {
            return PushPlan.InvalidMetadata(
                "Incoherent local revisions: remoteRevision=${local.remoteRevision} and baseRevision=${local.baseRevision} cannot mix $LOCAL_ONLY_REVISION with known revisions"
            )
        }
        if (local.syncStatus != SyncStatus.PENDING && local.syncStatus != SyncStatus.DELETED) {
            return PushPlan.InvalidMetadata(
                "Unsupported syncStatus for push: '${local.syncStatus}'. Only PENDING and DELETED can be pushed."
            )
        }
        if (local.syncStatus == SyncStatus.PENDING && local.dirtyFields.isEmpty()) {
            return PushPlan.InvalidMetadata(
                "PENDING local mutation must have at least one dirty field."
            )
        }

        // 2. Validación de metadata remota
        if (remote != null) {
            if (remote.revision < LEGACY_REMOTE_REVISION) {
                return PushPlan.InvalidMetadata(
                    "Remote revision cannot be negative: ${remote.revision}"
                )
            }
            if (local.remoteRevision >= LEGACY_REMOTE_REVISION && remote.revision < local.remoteRevision) {
                return PushPlan.InvalidMetadata(
                    "Remote revision (${remote.revision}) cannot be less than local remoteRevision (${local.remoteRevision})"
                )
            }
            for ((field, rev) in remote.fieldRevisions) {
                if (rev < 0L) {
                    return PushPlan.InvalidMetadata(
                        "Field revision for $field cannot be negative: $rev"
                    )
                }
                if (rev > remote.revision) {
                    return PushPlan.InvalidMetadata(
                        "Field revision for $field ($rev) cannot exceed remote revision (${remote.revision})"
                    )
                }
            }
        }

        // 3. Remoto ausente (remote == null)
        if (remote == null) {
            if (local.syncStatus == SyncStatus.DELETED) {
                return PushPlan.DeleteAlreadySatisfied
            }
            // local.syncStatus == PENDING
            return if (local.remoteRevision == LOCAL_ONLY_REVISION && local.baseRevision == LOCAL_ONLY_REVISION) {
                if (local.dirtyFields != BeerDirtyFieldsCodec.ALL_FIELDS) {
                    PushPlan.InvalidMetadata(
                        "Cannot create active entity with incomplete dirty fields: expected ${BeerDirtyFieldsCodec.ALL_FIELDS}, got ${local.dirtyFields}"
                    )
                } else {
                    PushPlan.CreateActive(
                        nextRevision = FIRST_PROTOCOL_REVISION,
                        fieldsToWrite = BeerDirtyFieldsCodec.ALL_FIELDS
                    )
                }
            } else if (local.remoteRevision >= LEGACY_REMOTE_REVISION && local.baseRevision >= LEGACY_REMOTE_REVISION) {
                PushPlan.RemoteAbsentForKnownEntity
            } else {
                PushPlan.InvalidMetadata(
                    "Inconsistent revision state for absent remote: remoteRevision=${local.remoteRevision}, baseRevision=${local.baseRevision}"
                )
            }
        }

        // 4. Remoto es Tombstone (isDeleted == true)
        if (remote.isDeleted) {
            return if (local.syncStatus == SyncStatus.DELETED) {
                // Si la mutación de borrado coincide exactamente, fue aplicada por este dispositivo
                if (local.lastMutationId != null && remote.lastMutationId == local.lastMutationId) {
                    PushPlan.AlreadyApplied
                } else {
                    PushPlan.DeleteAlreadySatisfied
                }
            } else {
                // Local PENDING + remoto tombstone: REMOTO BORRADO GANA.
                // Semánticamente nunca se debe responder AlreadyApplied a una edición activa
                // frente a un tombstone remoto, pues produciría un ACK falso en Room marcándola
                // como SYNCED activa cuando en Firestore está eliminada.
                PushPlan.RemoteTombstoneWins
            }
        }

        // A partir de aquí: remote != null && !remote.isDeleted (Documento remoto ACTIVO)

        // 5. Idempotencia sobre documento remoto activo:
        // Si el mutationId coincide, la mutación local ya fue confirmada en remoto
        // (aplica tanto para reintento de CreateActive con -1/-1 como para ApplyPatch).
        if (local.lastMutationId != null && remote.lastMutationId == local.lastMutationId) {
            return PushPlan.AlreadyApplied
        }

        // 6. DELETE WINS sobre documento remoto activo:
        // Local DELETED + remoto activo debe permitir WriteTombstone incluso si local
        // conserva -1/-1 debido a un ACK de creación perdido antes de que el usuario borrara.
        // La protección de ownership deberá validarse en la capa transaccional.
        if (local.syncStatus == SyncStatus.DELETED) {
            if (remote.revision == Long.MAX_VALUE) {
                return PushPlan.InvalidMetadata(
                    "Revision overflow: remote revision is Long.MAX_VALUE and cannot be incremented for tombstone."
                )
            }
            return PushPlan.WriteTombstone(nextRevision = remote.revision + 1L)
        }

        // 7. Local PENDING local-only (-1/-1) frente a documento remoto activo con distinta mutación:
        // Puede tratarse de un Create previo propio cuyo ACK se perdió antes de una edición local M2,
        // o de una colisión de identidad / inconsistencia.
        // No se debe sobrescribir a ciegas ni clasificar como metadata corrupta.
        // Se emite RemotePresentForLocalOnlyNeedsReconciliation para que la capa de sincronización
        // concilie los estados (por ej. reconciliando baseRevision al remoto y preservando los dirtyFields de M2).
        if (local.remoteRevision == LOCAL_ONLY_REVISION) {
            return PushPlan.RemotePresentForLocalOnlyNeedsReconciliation
        }

        // 8. Local PENDING sobre documento remoto activo conocido (remoteRevision >= 0L)
        // Comprobar desbordamiento de revisión antes de incrementar
        if (remote.revision == Long.MAX_VALUE) {
            val conflictingFields = local.dirtyFields.filter { field ->
                val remoteFieldRev = remote.fieldRevisions[field] ?: LEGACY_REMOTE_REVISION
                remoteFieldRev > local.baseRevision
            }.toSet()
            val safeFields = local.dirtyFields - conflictingFields
            if (safeFields.isNotEmpty()) {
                return PushPlan.InvalidMetadata(
                    "Revision overflow: remote revision is Long.MAX_VALUE and cannot be incremented for patch."
                )
            } else {
                return PushPlan.RemoteWinsOnly(remoteWinsFields = conflictingFields)
            }
        }

        val nextRevision = remote.revision + 1L

        val conflictingFields = local.dirtyFields.filter { field ->
            val remoteFieldRev = remote.fieldRevisions[field] ?: LEGACY_REMOTE_REVISION
            remoteFieldRev > local.baseRevision
        }.toSet()

        val safeFields = local.dirtyFields - conflictingFields

        return when {
            safeFields.isEmpty() -> PushPlan.RemoteWinsOnly(remoteWinsFields = conflictingFields)
            else -> PushPlan.ApplyPatch(
                nextRevision = nextRevision,
                fieldsToWrite = safeFields,
                remoteWinsFields = conflictingFields
            )
        }
    }

    /**
     * Calcula el nuevo mapa de fieldRevisions tras escribir exitosamente los campos especificados.
     * Los campos no modificados conservan su revisión previa.
     */
    fun advanceFieldRevisions(
        current: Map<BeerDirtyField, Long>,
        writtenFields: Set<BeerDirtyField>,
        nextRevision: Long
    ): Map<BeerDirtyField, Long> {
        if (writtenFields.isEmpty()) return current.toMap()
        val updated = LinkedHashMap(current)
        for (field in writtenFields) {
            updated[field] = nextRevision
        }
        return updated
    }
}
