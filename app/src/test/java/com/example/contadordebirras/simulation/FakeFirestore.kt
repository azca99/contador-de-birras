package com.example.contadordebirras.simulation

import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.FIRST_PROTOCOL_REVISION
import com.example.contadordebirras.domain.RemoteBeerSyncMetadata
import java.io.IOException

/**
 * Representación pura de un documento remoto en Firestore dentro de la colección `/beers/{syncId}`.
 */
data class FakeFirestoreBeerDocument(
    val syncId: String,
    val userId: String,
    val revision: Long,
    val isDeleted: Boolean = false,
    val lastMutationId: String? = null,
    val fieldRevisions: Map<BeerDirtyField, Long> = emptyMap(),
    // Campos del payload de la cerveza
    val type: String = "LATA",
    val timestamp: Long = 0L,
    val comment: String? = null,
    val photoUrl: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    fun toRemoteSyncMetadata(): RemoteBeerSyncMetadata =
        RemoteBeerSyncMetadata(
            revision = revision,
            isDeleted = isDeleted,
            lastMutationId = lastMutationId,
            fieldRevisions = fieldRevisions
        )
}

/**
 * Precondición atómica CAS exigida para transacciones de Firestore simuladas.
 */
sealed interface TransactionPrecondition {
    /** Se exige que el documento no exista previamente en Firestore (CreateActive) */
    data object ExpectAbsent : TransactionPrecondition

    /** Se exige que el documento exista con la revisión exacta indicada (ApplyPatch / WriteTombstone) */
    data class ExpectRevision(val revision: Long) : TransactionPrecondition
}

/**
 * Resultado de una transacción atómica contra [FakeFirestore].
 */
sealed interface TransactionResult {
    data class Success(val document: FakeFirestoreBeerDocument) : TransactionResult
    data class Conflict(val currentRevision: Long, val reason: String) : TransactionResult
    data class PreconditionFailed(val reason: String) : TransactionResult
    data class Error(val exception: Throwable) : TransactionResult
}

/**
 * Simulador hermético en memoria de Firestore para pruebas multidispositivo JVM (Fase C4.2).
 *
 * Simula:
 * - Comprobación atómica de precondiciones (CAS).
 * - Revisiones monotónicas estrictas (incremento de 1 en 1).
 * - Protección de tombstones: no permite resucitaciones sin protocolo.
 * - Prohibición de borrado físico (hard delete) para documentos modernos con revision >= 1.
 * - Inmutabilidad estricta del propietario (userId).
 * - Integridad de fieldRevisions (ningún campo puede tener revisión superior a la del documento).
 * - Inyección de fallos de red y timeouts diferenciados de respuestas autoritativas vacías.
 */
class FakeFirestore {

    private val documents = mutableMapOf<String, FakeFirestoreBeerDocument>()

    /** Inyección de fallo de transporte (ej. timeout de red) */
    var simulateNetworkError: Boolean = false

    /** Inyección de timeout de servidor */
    var simulateTimeout: Boolean = false

    /**
     * Lectura directa simulada de un documento por su ID.
     */
    @Synchronized
    fun getDocument(syncId: String): FakeFirestoreBeerDocument? {
        if (simulateNetworkError) throw IOException("FakeFirestore: Simulación de fallo de red de transporte")
        if (simulateTimeout) throw IOException("FakeFirestore: Simulación de timeout de servidor")
        return documents[syncId]?.copy()
    }

    /**
     * Inserta directamente un documento para configurar el estado inicial del test.
     */
    @Synchronized
    fun seedDocument(document: FakeFirestoreBeerDocument) {
        documents[document.syncId] = document.copy()
    }

    /**
     * Transacción atómica simulada para crear un documento nuevo activo en Firestore.
     */
    @Synchronized
    fun executeCreateActiveTransaction(
        syncId: String,
        callerUid: String,
        newDocument: FakeFirestoreBeerDocument
    ): TransactionResult {
        if (simulateNetworkError) return TransactionResult.Error(IOException("Fallo de red en CreateActive"))
        if (simulateTimeout) return TransactionResult.Error(IOException("Timeout en CreateActive"))

        // 1. Verificación de ownership
        if (newDocument.userId != callerUid) {
            return TransactionResult.PreconditionFailed(
                "Ownership mismatch: caller $callerUid cannot create document owned by ${newDocument.userId}"
            )
        }

        // 2. Comprobación de precondición: debe estar ausente
        val existing = documents[syncId]
        if (existing != null) {
            return if (existing.lastMutationId == newDocument.lastMutationId) {
                // Idempotencia: ya aplicado con el mismo mutationId
                TransactionResult.Success(existing.copy())
            } else {
                TransactionResult.Conflict(
                    currentRevision = existing.revision,
                    reason = "Document $syncId already exists with revision ${existing.revision}"
                )
            }
        }

        // 3. Verificación de primera revisión monotónica
        if (newDocument.revision != FIRST_PROTOCOL_REVISION) {
            return TransactionResult.PreconditionFailed(
                "Initial protocol document must have revision $FIRST_PROTOCOL_REVISION, found ${newDocument.revision}"
            )
        }

        // 4. Verificación de integridad de fieldRevisions
        for ((field, rev) in newDocument.fieldRevisions) {
            if (rev > newDocument.revision) {
                return TransactionResult.PreconditionFailed(
                    "Field $field revision ($rev) cannot exceed document revision (${newDocument.revision})"
                )
            }
        }

        documents[syncId] = newDocument.copy()
        return TransactionResult.Success(newDocument.copy())
    }

    /**
     * Transacción atómica simulada para aplicar un parche (ApplyPatch) con comprobación CAS.
     */
    @Synchronized
    fun executeApplyPatchTransaction(
        syncId: String,
        callerUid: String,
        expectedRevision: Long,
        mutationId: String,
        fieldsToUpdate: Map<String, Any?>,
        newFieldRevisions: Map<BeerDirtyField, Long>
    ): TransactionResult {
        if (simulateNetworkError) return TransactionResult.Error(IOException("Fallo de red en ApplyPatch"))
        if (simulateTimeout) return TransactionResult.Error(IOException("Timeout en ApplyPatch"))

        val existing = documents[syncId]
            ?: return TransactionResult.PreconditionFailed("Document $syncId does not exist for patch")

        // 1. Verificación de ownership
        if (existing.userId != callerUid) {
            return TransactionResult.PreconditionFailed(
                "Ownership mismatch: caller $callerUid cannot patch document owned by ${existing.userId}"
            )
        }

        // 2. Verificación de idempotencia por mutationId
        if (existing.lastMutationId == mutationId) {
            return TransactionResult.Success(existing.copy())
        }

        // 3. Verificación de tombstone: no se puede parchar un documento borrado
        if (existing.isDeleted) {
            return TransactionResult.Conflict(
                currentRevision = existing.revision,
                reason = "Cannot patch remote tombstone for $syncId"
            )
        }

        // 4. Verificación CAS de revisión esperada
        if (existing.revision != expectedRevision) {
            return TransactionResult.Conflict(
                currentRevision = existing.revision,
                reason = "CAS mismatch: expected revision $expectedRevision but current is ${existing.revision}"
            )
        }

        val nextRevision = existing.revision + 1L

        // 5. Verificación de integridad de nuevas fieldRevisions
        val mergedFieldRevisions = existing.fieldRevisions.toMutableMap()
        for ((field, rev) in newFieldRevisions) {
            if (rev > nextRevision) {
                return TransactionResult.PreconditionFailed(
                    "Field $field revision ($rev) cannot exceed next document revision ($nextRevision)"
                )
            }
            mergedFieldRevisions[field] = rev
        }

        // 6. Aplicación de campos
        val patched = existing.copy(
            revision = nextRevision,
            lastMutationId = mutationId,
            fieldRevisions = mergedFieldRevisions,
            type = fieldsToUpdate["type"] as? String ?: existing.type,
            timestamp = fieldsToUpdate["timestamp"] as? Long ?: existing.timestamp,
            comment = if (fieldsToUpdate.containsKey("comment")) fieldsToUpdate["comment"] as? String else existing.comment,
            photoUrl = if (fieldsToUpdate.containsKey("photoUrl")) fieldsToUpdate["photoUrl"] as? String else existing.photoUrl,
            latitude = if (fieldsToUpdate.containsKey("latitude")) fieldsToUpdate["latitude"] as? Double else existing.latitude,
            longitude = if (fieldsToUpdate.containsKey("longitude")) fieldsToUpdate["longitude"] as? Double else existing.longitude
        )

        documents[syncId] = patched
        return TransactionResult.Success(patched.copy())
    }

    /**
     * Transacción atómica simulada para escribir un tombstone (WriteTombstone) con comprobación CAS.
     */
    @Synchronized
    fun executeWriteTombstoneTransaction(
        syncId: String,
        callerUid: String,
        expectedRevision: Long,
        mutationId: String
    ): TransactionResult {
        if (simulateNetworkError) return TransactionResult.Error(IOException("Fallo de red en WriteTombstone"))
        if (simulateTimeout) return TransactionResult.Error(IOException("Timeout en WriteTombstone"))

        val existing = documents[syncId]
            ?: return TransactionResult.PreconditionFailed("Document $syncId does not exist for tombstone")

        if (existing.userId != callerUid) {
            return TransactionResult.PreconditionFailed("Ownership mismatch for tombstone")
        }

        // Idempotencia
        if (existing.isDeleted && existing.lastMutationId == mutationId) {
            return TransactionResult.Success(existing.copy())
        }

        if (existing.isDeleted) {
            // Ya es tombstone con otra mutación
            return TransactionResult.Success(existing.copy())
        }

        if (existing.revision != expectedRevision) {
            return TransactionResult.Conflict(
                currentRevision = existing.revision,
                reason = "CAS mismatch: expected revision $expectedRevision but current is ${existing.revision}"
            )
        }

        val nextRevision = existing.revision + 1L
        val tombstone = existing.copy(
            revision = nextRevision,
            isDeleted = true,
            lastMutationId = mutationId
        )

        documents[syncId] = tombstone
        return TransactionResult.Success(tombstone.copy())
    }

    /**
     * Intento de borrado físico (hard delete).
     * En el protocolo moderno está PROHIBIDO para cualquier documento con revision >= 1.
     */
    @Synchronized
    fun executePhysicalDelete(syncId: String, callerUid: String): TransactionResult {
        val existing = documents[syncId]
            ?: return TransactionResult.Success(FakeFirestoreBeerDocument(syncId, callerUid, 0L, isDeleted = true))

        if (existing.userId != callerUid) {
            return TransactionResult.PreconditionFailed("Ownership mismatch for delete")
        }

        if (existing.revision >= FIRST_PROTOCOL_REVISION) {
            return TransactionResult.PreconditionFailed(
                "HARD DELETE PROHIBITED: Document $syncId has modern revision ${existing.revision}. Tombstones are mandatory."
            )
        }

        // Solo legacy revision 0 podría ser borrado físico
        documents.remove(syncId)
        return TransactionResult.Success(existing.copy(isDeleted = true))
    }

    /**
     * Cuenta total de documentos en la colección simulada.
     */
    @Synchronized
    fun getDocumentCount(): Int = documents.size

    /**
     * Lista todos los documentos activos (no tombstones).
     */
    @Synchronized
    fun getActiveDocuments(): List<FakeFirestoreBeerDocument> =
        documents.values.filter { !it.isDeleted }.map { it.copy() }

    /**
     * Lista todos los tombstones.
     */
    @Synchronized
    fun getTombstones(): List<FakeFirestoreBeerDocument> =
        documents.values.filter { it.isDeleted }.map { it.copy() }
}
