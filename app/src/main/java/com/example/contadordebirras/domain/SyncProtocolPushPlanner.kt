package com.example.contadordebirras.domain

import com.example.contadordebirras.data.SyncStatus

/**
 * Constantes conceptuales de revisión para el protocolo de sincronización multidispositivo (Fase C3A).
 *
 * LOCAL_ONLY_REVISION (-1): Entidad creada localmente y nunca confirmada en remoto.
 * LEGACY_REMOTE_REVISION (0): Documento legacy remoto que aún no tiene campo de revisión.
 * FIRST_PROTOCOL_REVISION (1): Primera revisión para documentos gestionados por el nuevo protocolo.
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

        // 3. Idempotencia: si mutationId coincide, la mutación ya fue aplicada en remoto
        if (remote != null && local.lastMutationId != null && remote.lastMutationId == local.lastMutationId) {
            return PushPlan.AlreadyApplied
        }

        // 4. Remoto es Tombstone
        if (remote != null && remote.isDeleted) {
            return if (local.syncStatus == SyncStatus.DELETED) {
                PushPlan.DeleteAlreadySatisfied
            } else {
                PushPlan.RemoteTombstoneWins
            }
        }

        // 5. Remoto ausente (remote == null)
        if (remote == null) {
            if (local.syncStatus == SyncStatus.DELETED) {
                return PushPlan.DeleteAlreadySatisfied
            }
            // local.syncStatus == PENDING
            return if (local.remoteRevision == LOCAL_ONLY_REVISION && local.baseRevision == LOCAL_ONLY_REVISION) {
                PushPlan.CreateActive(
                    nextRevision = FIRST_PROTOCOL_REVISION,
                    fieldsToWrite = local.dirtyFields
                )
            } else if (local.remoteRevision >= LEGACY_REMOTE_REVISION || local.baseRevision >= LEGACY_REMOTE_REVISION) {
                PushPlan.RemoteAbsentForKnownEntity
            } else {
                PushPlan.InvalidMetadata(
                    "Inconsistent revision state for absent remote: remoteRevision=${local.remoteRevision}, baseRevision=${local.baseRevision}"
                )
            }
        }

        // 6. Remoto activo: comprobar desbordamiento de revisión antes de incrementar
        if (remote.revision == Long.MAX_VALUE) {
            // Si local está DELETED o PENDING con campos seguros, requeriría incrementar revision
            if (local.syncStatus == SyncStatus.DELETED) {
                return PushPlan.InvalidMetadata(
                    "Revision overflow: remote revision is Long.MAX_VALUE and cannot be incremented for tombstone."
                )
            }
            // Para PENDING, comprobar si tiene safeFields antes de fallar por overflow
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

        // 7. Local DELETED sobre remoto activo -> DELETE WINS
        if (local.syncStatus == SyncStatus.DELETED) {
            return PushPlan.WriteTombstone(nextRevision = nextRevision)
        }

        // 8. Local PENDING sobre remoto activo -> Evaluación de conflictos por campo
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
