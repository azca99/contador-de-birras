package com.example.contadordebirras.domain

import com.example.contadordebirras.data.SyncStatus

/**
 * Ámbito o garantía disponible sobre una lectura remota en operaciones PULL.
 */
enum class RemoteReadScope {
    /**
     * Lectura autoritativa y confirmada: una consulta directa (por ej. get() por ID o snapshot completo)
     * certifica de forma autoritativa la presencia o ausencia del documento en Firestore.
     */
    AUTHORITATIVE_CONFIRMED,

    /**
     * Lectura no autoritativa o incompleta: resultados de paginación parcial, lecturas condicionadas,
     * errores de red o consultas filtradas donde la ausencia de un ID no implica su eliminación remota.
     */
    INCOMPLETE_OR_UNCONFIRMED
}

/**
 * Estado remoto puro para operaciones PULL del protocolo multidispositivo (Fase C3B).
 */
sealed interface RemotePullState {
    /**
     * El documento remoto existe en Firestore con su metadata de sincronización.
     */
    data class Present(val metadata: RemoteBeerSyncMetadata) : RemotePullState

    /**
     * El documento remoto está ausente de forma confirmada y autoritativa en Firestore.
     */
    data object ConfirmedAbsent : RemotePullState

    /**
     * La lectura remota fue incompleta o no autoritativa; no permite inferir ausencias.
     */
    data object IncompleteOrUnconfirmed : RemotePullState
}

/**
 * Jerarquía sellada pura que representa las decisiones semánticas de incorporación de cambios remotos (PULL).
 */
sealed interface PullPlan {

    /**
     * Entidad remota activa no existe localmente en Room: debe insertarse como SYNCED.
     * NOTA SOBRE FOTOS: photoUri es estrictamente local y no existe en remoto; la inserción
     * local poblará remotePhotoUrl y photoSource, dejando photoUri como null hasta descarga local.
     */
    data class InsertLocal(
        val remoteRevision: Long,
        val fieldsToApply: Set<BeerDirtyField> = BeerDirtyFieldsCodec.ALL_FIELDS
    ) : PullPlan

    /**
     * Entidad local SYNCED recibe una actualización remota más reciente (revision monotónica superior).
     * Los datos puramente locales (como photoUri) se preservan y no se sobrescriben.
     */
    data class UpdateLocalSynced(
        val newRemoteRevision: Long,
        val fieldsToApply: Set<BeerDirtyField> = BeerDirtyFieldsCodec.ALL_FIELDS
    ) : PullPlan

    /**
     * Entidad local PENDING incorpora cambios remotos ortogonales o ganados por conflicto.
     *
     * @property remoteFieldsToApply Conjunto de campos remotos que deben aplicarse a la fila local
     * (campos ortogonales modificados remotamente + campos en conflicto donde remoto ganó).
     * @property remoteWinsConflictFields Subconjunto de campos dirty locales donde remoto ganó
     * el conflicto concurrente (REMOTE CONFIRMED WINS). Estos campos dejan de ser dirty en local.
     * @property localPendingFieldsToPreserve Campos dirty locales donde remoto NO tuvo cambios
     * concurrentes. Estos campos continúan pendientes localmente y no se tocan.
     * @property newRemoteRevision Nueva revisión remota observada (remote.revision).
     * @property newBaseRevision Base histórica sobre la que se asientan las mutaciones pendientes.
     * PERMANECE CONGELADA en local.baseRevision si aún existen campos pendientes locales por subir,
     * o avanza a remote.revision si todos los campos dirty se rindieron a remoto.
     */
    data class MergeWithPending(
        val remoteFieldsToApply: Set<BeerDirtyField>,
        val remoteWinsConflictFields: Set<BeerDirtyField>,
        val localPendingFieldsToPreserve: Set<BeerDirtyField>,
        val newRemoteRevision: Long,
        val newBaseRevision: Long
    ) : PullPlan

    /**
     * La entidad local ya está sincronizada con la misma revisión o no existen campos nuevos por aplicar.
     * Garantiza idempotencia en la sincronización.
     */
    data object NoOpAlreadyUpToDate : PullPlan

    /**
     * Entidad local DELETED frente a remoto activo: DELETE WINS.
     * La fila local permanece en estado DELETED para emitir WriteTombstone en el siguiente push.
     * Jamás se resucita localmente por recibir un documento remoto activo.
     */
    data object PreserveLocalDelete : PullPlan

    /**
     * Documento remoto es un tombstone frente a una entidad local activa (SYNCED o PENDING).
     * DELETE WINS: la eliminación remota prevalece e impide resurrecciones de cervezas borradas.
     */
    data class ApplyRemoteTombstone(
        val newRemoteRevision: Long
    ) : PullPlan

    /**
     * La intención de borrado ya está satisfecha tanto localmente como en remoto.
     */
    data object DeleteAlreadySatisfied : PullPlan

    /**
     * Entidad local PENDING local-only (-1/-1) que aún no existe en remoto.
     * Se preserva intacta en espera de su primer push (CreateActive).
     */
    data object PreserveLocalPending : PullPlan

    /**
     * Estado bloqueado de forma segura: entidad local PENDING local-only (-1/-1) encuentra un documento
     * remoto activo con distinto lastMutationId (ACK de creación perdido o colisión).
     * Compatible con C3A; no se reconcilia automáticamente en el pull para evitar pérdida de datos.
     */
    data object RemotePresentForLocalOnlyNeedsReconciliation : PullPlan

    /**
     * Estado seguro de conflicto ambiguo: uno o más campos dirty locales presentan una revisión
     * remota que es superior a baseRevision pero menor o igual a local.remoteRevision.
     *
     * CAUSA RAÍZ:
     * baseRevision permanece congelada mientras existan campos locales pendientes para que el siguiente
     * PUSH mantenga una base de OCC estricta. Sin embargo, remoteRevision avanza con los PULLs previos.
     * Si tras un PULL previo el usuario modifica localmente un campo cuya revisión remota ya se encontraba
     * entre baseRevision y remoteRevision, la metadata escalar actual no puede demostrar si la edición
     * local ocurrió antes o después de incorporar dicha actualización remota.
     *
     * DECISIÓN SEMÁNTICA:
     * Para evitar la pérdida silenciosa de modificaciones locales (por ej. sobrescribir una nueva foto
     * local con una foto remota previa), se devuelve este resultado seguro que preserva los cambios locales
     * y remite la entidad a reconciliación. No se sobrescriben otros campos ni se borran fotos.
     *
     * DOCUMENTACIÓN DE DISEÑO PARA FASE C4:
     * 1. La Fase C4 necesitará historial o metadata adicional por campo (por ej. timestamps locales por campo
     *    o un mapa de revisiones base por campo `Map<BeerDirtyField, Long>`) para resolver estos casos
     *    automáticamente sin bloquear indefinidamente.
     * 2. LIMITACIÓN COMPARTIDA CON FASE C3A:
     *    `SyncProtocolPushPlanner` en la Fase C3A comparte conceptualmente esta misma limitación al evaluar
     *    `remoteFieldRev > local.baseRevision` para clasificar campos conflictivos. Si un campo se edita
     *    localmente tras un PULL previo mientras otro campo mantiene congelada baseRevision, C3A podría
     *    clasificarlo como conflicto remoto perdiendo la edición local en un push directo. La Fase C4
     *    deberá proteger simétricamente ambas rutas (PUSH y PULL). C3A no se modifica en esta fase.
     *
     * @property ambiguousFields Conjunto de campos dirty locales cuyo orden relativo con el remoto es ambiguo.
     * @property localPendingFields Conjunto completo de campos dirty locales que se preservan intactos.
     * @property remoteRevision Revisión actual del documento remoto.
     * @property localRemoteRevision Revisión remota previamente conocida por el cliente.
     * @property localBaseRevision Revisión base histórica sobre la que se congeló el estado local.
     */
    data class PendingFieldOrderNeedsReconciliation(
        val ambiguousFields: Set<BeerDirtyField>,
        val localPendingFields: Set<BeerDirtyField>,
        val remoteRevision: Long,
        val localRemoteRevision: Long,
        val localBaseRevision: Long
    ) : PullPlan

    /**
     * Entidad previamente conocida en remoto (remoteRevision >= 0L) que no aparece en una lectura
     * autoritativa pero tiene cambios locales PENDING. Protección anti-resurrección / conflicto.
     */
    data object RemoteAbsentForKnownEntity : PullPlan

    /**
     * Entidad SYNCED ausente en consulta remota autoritativa: purga local (hard delete o tombstone local).
     */
    data object ApplyRemotePurge : PullPlan

    /**
     * Lectura remota incompleta o no autoritativa: no se asumen eliminaciones ni se altera el estado local.
     */
    data object IgnoreIncompleteRead : PullPlan

    /**
     * Documento remoto tombstone frente a local ausente: no hay nada que crear ni borrar localmente.
     */
    data object IgnoreRemoteTombstone : PullPlan

    /**
     * Documento remoto legacy (revision = 0, sin fieldRevisions) frente a edición local PENDING.
     * Al no disponer de metadata suficiente para determinar cambios por campo, requiere resolución explícita.
     */
    data object LegacyRemoteNeedsResolution : PullPlan

    /**
     * Metadata inconsistente o corrupta detectada. Evita fallos en tiempo de ejecución.
     */
    data class InvalidMetadata(val reason: String) : PullPlan
}

/**
 * Planificador puro y determinista de conflictos para operaciones PULL del protocolo multidispositivo (Fase C3B).
 *
 * Responde a: "Dado el estado local actual de una entidad y el estado remoto leído en Firestore,
 * ¿cómo deben incorporarse los cambios remotos sin perder mutaciones locales pendientes?"
 *
 * No realiza accesos a base de datos, red, WorkManager ni operaciones reales.
 */
object SyncProtocolPullPlanner {

    /**
     * Planifica la acción a tomar durante un pull a partir del estado local y el estado remoto leído.
     */
    fun planPull(
        local: LocalMutationContext?,
        remoteState: RemotePullState
    ): PullPlan {
        // 1. Validación de metadata local
        if (local != null) {
            if (local.baseRevision < LOCAL_ONLY_REVISION) {
                return PullPlan.InvalidMetadata(
                    "Local baseRevision cannot be less than $LOCAL_ONLY_REVISION: ${local.baseRevision}"
                )
            }
            if (local.remoteRevision < LOCAL_ONLY_REVISION) {
                return PullPlan.InvalidMetadata(
                    "Local remoteRevision cannot be less than $LOCAL_ONLY_REVISION: ${local.remoteRevision}"
                )
            }
            if (local.baseRevision > local.remoteRevision) {
                return PullPlan.InvalidMetadata(
                    "Local baseRevision (${local.baseRevision}) cannot exceed local remoteRevision (${local.remoteRevision})"
                )
            }
            if ((local.remoteRevision == LOCAL_ONLY_REVISION) != (local.baseRevision == LOCAL_ONLY_REVISION)) {
                return PullPlan.InvalidMetadata(
                    "Incoherent local revisions: remoteRevision=${local.remoteRevision} and baseRevision=${local.baseRevision} cannot mix $LOCAL_ONLY_REVISION with known revisions"
                )
            }
            if (local.syncStatus != SyncStatus.PENDING &&
                local.syncStatus != SyncStatus.SYNCED &&
                local.syncStatus != SyncStatus.DELETED
            ) {
                return PullPlan.InvalidMetadata(
                    "Unsupported syncStatus for pull: '${local.syncStatus}'"
                )
            }
            if (local.syncStatus == SyncStatus.PENDING && local.dirtyFields.isEmpty()) {
                return PullPlan.InvalidMetadata(
                    "PENDING local mutation must have at least one dirty field."
                )
            }
        }

        // 2. Validación de metadata remota
        val remote = (remoteState as? RemotePullState.Present)?.metadata
        if (remote != null) {
            if (remote.revision < LEGACY_REMOTE_REVISION) {
                return PullPlan.InvalidMetadata(
                    "Remote revision cannot be negative: ${remote.revision}"
                )
            }
            for ((field, rev) in remote.fieldRevisions) {
                if (rev < 0L) {
                    return PullPlan.InvalidMetadata(
                        "Field revision for $field cannot be negative: $rev"
                    )
                }
                if (rev > remote.revision) {
                    return PullPlan.InvalidMetadata(
                        "Field revision for $field ($rev) cannot exceed remote revision (${remote.revision})"
                    )
                }
            }
            // Invariante de monotonicidad: remoto no puede retroceder respecto a lo ya conocido por local
            if (local != null && local.remoteRevision >= LEGACY_REMOTE_REVISION && remote.revision < local.remoteRevision) {
                return PullPlan.InvalidMetadata(
                    "Remote revision (${remote.revision}) cannot be less than local remoteRevision (${local.remoteRevision})"
                )
            }
        }

        // 3. Remoto ausente
        if (remoteState is RemotePullState.IncompleteOrUnconfirmed) {
            return PullPlan.IgnoreIncompleteRead
        }

        if (remoteState is RemotePullState.ConfirmedAbsent) {
            if (local == null) {
                return PullPlan.NoOpAlreadyUpToDate
            }
            return when (local.syncStatus) {
                SyncStatus.DELETED -> PullPlan.DeleteAlreadySatisfied
                SyncStatus.SYNCED -> PullPlan.ApplyRemotePurge
                SyncStatus.PENDING -> {
                    if (local.remoteRevision == LOCAL_ONLY_REVISION) {
                        PullPlan.PreserveLocalPending
                    } else {
                        PullPlan.RemoteAbsentForKnownEntity
                    }
                }
                else -> PullPlan.InvalidMetadata("Unknown syncStatus: ${local.syncStatus}")
            }
        }

        // A partir de aquí: remote != null (Documento remoto PRESENTE)

        // 4. Local ausente
        if (local == null) {
            return if (remote!!.isDeleted) {
                PullPlan.IgnoreRemoteTombstone
            } else {
                PullPlan.InsertLocal(remoteRevision = remote.revision)
            }
        }

        // 5. Remoto es Tombstone (isDeleted == true)
        if (remote!!.isDeleted) {
            return when (local.syncStatus) {
                SyncStatus.DELETED -> PullPlan.DeleteAlreadySatisfied
                SyncStatus.SYNCED, SyncStatus.PENDING -> PullPlan.ApplyRemoteTombstone(
                    newRemoteRevision = remote.revision
                )
                else -> PullPlan.InvalidMetadata("Unknown syncStatus: ${local.syncStatus}")
            }
        }

        // A partir de aquí: remote != null && !remote.isDeleted (Documento remoto ACTIVO)

        // 6. Local DELETED frente a remoto activo: DELETE WINS
        if (local.syncStatus == SyncStatus.DELETED) {
            return PullPlan.PreserveLocalDelete
        }

        // 7. Local SYNCED frente a remoto activo
        if (local.syncStatus == SyncStatus.SYNCED) {
            return when {
                remote.revision > local.remoteRevision -> PullPlan.UpdateLocalSynced(
                    newRemoteRevision = remote.revision,
                    fieldsToApply = BeerDirtyFieldsCodec.ALL_FIELDS
                )
                remote.revision == local.remoteRevision -> PullPlan.NoOpAlreadyUpToDate
                else -> PullPlan.InvalidMetadata(
                    "Remote revision (${remote.revision}) cannot be less than local remoteRevision (${local.remoteRevision})"
                )
            }
        }

        // 8. Local PENDING frente a remoto activo
        // Caso local-only con remoto activo: posible ACK de creación perdido o colisión (C3A compatible)
        if (local.remoteRevision == LOCAL_ONLY_REVISION) {
            return PullPlan.RemotePresentForLocalOnlyNeedsReconciliation
        }

        // Caso legacy sin metadata de campos
        if (remote.revision == LEGACY_REMOTE_REVISION && local.baseRevision == LEGACY_REMOTE_REVISION) {
            return PullPlan.LegacyRemoteNeedsResolution
        }

        // 9. Fusión campo por campo para PENDING
        val orthogonalRemoteFields = LinkedHashSet<BeerDirtyField>()
        val remoteWinsConflictFields = LinkedHashSet<BeerDirtyField>()
        val localPendingFieldsToPreserve = LinkedHashSet<BeerDirtyField>()
        val ambiguousFields = LinkedHashSet<BeerDirtyField>()

        for (field in BeerDirtyFieldsCodec.ALL_FIELDS) {
            val isLocalDirty = field in local.dirtyFields
            val remoteFieldRev = remote.fieldRevisions[field] ?: LEGACY_REMOTE_REVISION
            val remoteChangedSinceBase = remoteFieldRev > local.baseRevision
            val remoteChangedSinceLocalRemote = remoteFieldRev > local.remoteRevision

            when {
                isLocalDirty -> {
                    when {
                        // A1: Remoto NO ha modificado el campo desde la base histórica local ->
                        // mutación local pura sin cambio remoto concurrente. Se preserva intacto.
                        !remoteChangedSinceBase -> {
                            localPendingFieldsToPreserve.add(field)
                        }
                        // A2: Remoto modificó el campo después de la última revisión remota conocida localmente ->
                        // conflicto verdaderamente nuevo y concurrente demostrable: REMOTE CONFIRMED WINS.
                        remoteChangedSinceLocalRemote -> {
                            remoteWinsConflictFields.add(field)
                        }
                        // A3: local.baseRevision < remoteFieldRev <= local.remoteRevision ->
                        // El campo fue modificado en una revisión ya conocida localmente, pero baseRevision
                        // permanecía congelada por otro campo pendiente. El orden temporal entre la edición
                        // local y la remota es indeterminado con la metadata escalar actual.
                        else -> {
                            ambiguousFields.add(field)
                        }
                    }
                }
                else -> {
                    // Campo NO dirty en local
                    when {
                        // B1: Remoto modificó el campo de forma verdaderamente nueva (después de local.remoteRevision) ->
                        // cambio ortogonal nuevo que debe incorporarse a la fila local.
                        remoteChangedSinceLocalRemote -> {
                            orthogonalRemoteFields.add(field)
                        }
                        // B2: remoteFieldRev <= local.remoteRevision ->
                        // Ya fue incorporado previamente o coincide con el estado base conocido.
                        else -> {
                            // No-op
                        }
                    }
                }
            }
        }

        // Si existen campos cuyo orden relativo es ambiguo, no sobrescribir ningún campo ni borrar fotos:
        // preservar el estado local completo y solicitar reconciliación.
        if (ambiguousFields.isNotEmpty()) {
            return PullPlan.PendingFieldOrderNeedsReconciliation(
                ambiguousFields = ambiguousFields,
                localPendingFields = local.dirtyFields,
                remoteRevision = remote.revision,
                localRemoteRevision = local.remoteRevision,
                localBaseRevision = local.baseRevision
            )
        }

        val remoteFieldsToApply = orthogonalRemoteFields + remoteWinsConflictFields

        if (remoteFieldsToApply.isEmpty() && remote.revision == local.remoteRevision) {
            return PullPlan.NoOpAlreadyUpToDate
        }

        val newBaseRevision = if (localPendingFieldsToPreserve.isNotEmpty()) {
            local.baseRevision // Frozen: no avanzar baseRevision mientras haya cambios pendientes
        } else {
            remote.revision // Si se rindieron todos los campos dirty, avanza a remote.revision
        }

        return PullPlan.MergeWithPending(
            remoteFieldsToApply = remoteFieldsToApply,
            remoteWinsConflictFields = remoteWinsConflictFields,
            localPendingFieldsToPreserve = localPendingFieldsToPreserve,
            newRemoteRevision = remote.revision,
            newBaseRevision = newBaseRevision
        )
    }

    /**
     * Sobrecarga de conveniencia que acepta [RemoteBeerSyncMetadata] opcional y [RemoteReadScope].
     */
    fun planPull(
        local: LocalMutationContext?,
        remoteMetadata: RemoteBeerSyncMetadata?,
        readScope: RemoteReadScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED
    ): PullPlan {
        val state = when {
            remoteMetadata != null -> RemotePullState.Present(remoteMetadata)
            readScope == RemoteReadScope.AUTHORITATIVE_CONFIRMED -> RemotePullState.ConfirmedAbsent
            else -> RemotePullState.IncompleteOrUnconfirmed
        }
        return planPull(local, state)
    }
}
