package com.example.contadordebirras.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Estados del ciclo de vida de una mutación local en el historial durable.
 */
object MutationStatus {
    /** La mutación está registrada localmente y pendiente de envío/confirmación en Firestore. */
    const val PENDING = "PENDING"
    /** La mutación fue confirmada exitosamente por Firestore (ACK recibido para todos los campos). */
    const val CONFIRMED = "CONFIRMED"
    /** La mutación fue confirmada parcialmente (algunos campos confirmados en Firestore, otros siguen pendientes). */
    const val PARTIALLY_CONFIRMED = "PARTIALLY_CONFIRMED"
    /** La mutación fue sustituida localmente por una edición posterior del usuario antes de recibir ACK. */
    const val SUPERSEDED = "SUPERSEDED"
    /** La mutación fue rechazada de forma explícita o descartada por conflicto terminal. */
    const val REJECTED = "REJECTED"
}

/**
 * Entidad de persistencia Room que almacena el historial durable de mutaciones locales (Fase C4.1B).
 *
 * Permite:
 * 1. Registrar mutaciones sucesivas para una misma cerveza identificada por [syncId] y [ownerUid].
 * 2. Reconocer ACKs perdidos en operaciones PULL mediante [mutationId].
 * 3. Proporcionar evidencia causal de qué campos ([dirtyFields]) fueron modificados y sobre qué
 *    revisión remota base ([baseRevision]) se originó la mutación.
 * 4. Almacenar evidencia causal por campo ([fieldRevisions], [causalEvidence]) si existe.
 * 5. Conservar el historial sin purgas automáticas prematuras para permitir reconciliaciones seguras.
 *
 * @property mutationId Identificador único universal (UUID) de la mutación. Clave primaria.
 * @property syncId Identificador de sincronización de la entidad cerveza asociada.
 * @property ownerUid Identificador del propietario de la entidad (aislamiento estricto por cuenta).
 * @property timestamp Marca de tiempo local (epoch millis) en que se generó la mutación.
 * @property dirtyFields Campos afectados por la mutación (formato serializado con [BeerDirtyFieldsCodec]).
 * @property baseRevision Revisión remota sobre la que se originó esta mutación.
 * @property status Estado de confirmación/persistencia ([MutationStatus]).
 * @property fieldRevisions Serialización opcional de revisiones conocidas por campo al momento de la mutación.
 * @property causalEvidence Evidencia causal opcional por campo o dispositivo para resolución de procedencia.
 * @property confirmedRevision Revisión asignada por Firestore cuando la mutación fue confirmada.
 * @property confirmedAt Marca de tiempo local de recepción del ACK de confirmación.
 * @property isLocalOnlyCreation Indica si la mutación corresponde a la creación local-only inicial (-1L/-1L).
 */
@Entity(
    tableName = "local_mutation_history",
    indices = [
        Index(value = ["syncId", "ownerUid"], name = "index_local_mutation_history_syncId_ownerUid"),
        Index(value = ["ownerUid", "status"], name = "index_local_mutation_history_ownerUid_status")
    ]
)
data class LocalMutationHistoryEntity(
    @PrimaryKey
    val mutationId: String,
    val syncId: String,
    val ownerUid: String,
    val timestamp: Long,
    val dirtyFields: String,
    val baseRevision: Long = 0L,
    val status: String = MutationStatus.PENDING,
    val fieldRevisions: String? = null,
    val causalEvidence: String? = null,
    val confirmedRevision: Long? = null,
    val confirmedAt: Long? = null,
    val isLocalOnlyCreation: Boolean = false
)
