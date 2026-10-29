package com.example.contadordebirras.sync.c4

import com.example.contadordebirras.domain.BeerDirtyField
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Adaptador aislado para operaciones Cloud Firestore bajo el Protocolo C4.
 *
 * RESTRICCIONES DE SEGURIDAD:
 * - Conecta exclusivamente a emuladores locales (puerto 8888).
 * - Rechaza terminantemente cualquier proyecto o conexión que no sea el emulador ficticio.
 * - Exige lecturas de servidor (Source.SERVER) para evitar lecturas de caché no autoritativas.
 * - Implementa transacciones CAS atómicas para garantizar monotonicidad de revisión (+1).
 */
class FirestoreC4SyncAdapter(
    private val firestore: FirebaseFirestore,
    val expectedProjectId: String = C4SyncConstants.DEMO_PROJECT_ID
) {

    init {
        val app = firestore.app
        val projectId = app.options.projectId
        require(projectId == expectedProjectId) {
            "PROHIBIDO: Intento de inicializar FirestoreC4SyncAdapter con projectId no autorizado: $projectId (esperado: $expectedProjectId)"
        }
    }

    private val beersCollection = firestore.collection(C4SyncConstants.BEERS_COLLECTION)

    fun getDocRef(syncId: String): DocumentReference = beersCollection.document(syncId)

    /**
     * Lectura autoritativa remota desde SERVER, omitiendo caché local.
     */
    suspend fun readServerDocument(syncId: String): C4FirestoreDocument? {
        val snapshot = try {
            getDocRef(syncId).get(Source.SERVER).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.NOT_FOUND) return null
            throw e
        }
        return C4FirestoreDocument.fromDocumentSnapshot(syncId, snapshot)
    }

    /**
     * Transacción de creación: CreateActive.
     * Exige que el documento no exista previamente, o detecta reintento idempotente.
     * Inicia estrictamente en revisión 1 con las 5 claves de fieldRevisions en 1.
     */
    suspend fun executeCreateActive(
        syncId: String,
        ownerUid: String,
        newDocument: C4FirestoreDocument,
        mutationId: String
    ): C4TransactionResult {
        val docRef = getDocRef(syncId)

        return try {
            firestore.runTransaction { tx ->
                val snapshot = tx.get(docRef)
                if (snapshot.exists()) {
                    val remoteOwner = snapshot.getString("userId")
                    if (remoteOwner != ownerUid) {
                        return@runTransaction C4TransactionResult.OwnershipMismatch(
                            documentOwner = remoteOwner ?: "unknown",
                            callerOwner = ownerUid
                        )
                    }

                    val remoteMutationId = snapshot.getString("lastMutationId")
                    val remoteRevision = snapshot.getLong("revision") ?: 1L
                    val isDeleted = snapshot.getBoolean("isDeleted") ?: false

                    if (remoteMutationId == mutationId && !isDeleted) {
                        return@runTransaction C4TransactionResult.IdempotentAck(
                            currentRevision = remoteRevision,
                            mutationId = mutationId
                        )
                    }

                    if (isDeleted) {
                        return@runTransaction C4TransactionResult.TombstonePrevailed(remoteRevision)
                    }

                    return@runTransaction C4TransactionResult.Conflict(
                        currentRevision = remoteRevision,
                        reason = "Documento ya existe en el servidor con revisión $remoteRevision"
                    )
                }

                // Documento no existe: Creación limpia en rev 1
                val creationDoc = newDocument.copy(
                    syncId = syncId,
                    userId = ownerUid,
                    revision = 1L,
                    isDeleted = false,
                    lastMutationId = mutationId,
                    fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
                )

                tx.set(docRef, creationDoc.toFirestoreMap())
                C4TransactionResult.Success(newRevision = 1L, mutationId = mutationId)
            }.await()
        } catch (e: Exception) {
            C4TransactionResult.Error(e)
        }
    }

    /**
     * Transacción de actualización: ApplyPatch.
     * Exige correspondencia causal: remoteRevision == baseRevision.
     * Incrementa la revisión global en +1 y avanza fieldRevisions únicamente de los campos modificados.
     */
    suspend fun executeApplyPatch(
        syncId: String,
        ownerUid: String,
        mutationId: String,
        baseRevision: Long,
        fieldsToWrite: Set<BeerDirtyField>,
        fieldValues: Map<String, Any?>,
        updatedPhotoUrl: String? = null
    ): C4TransactionResult {
        val docRef = getDocRef(syncId)

        return try {
            firestore.runTransaction { tx ->
                val snapshot = tx.get(docRef)
                if (!snapshot.exists()) {
                    return@runTransaction C4TransactionResult.NotFound
                }

                val remoteOwner = snapshot.getString("userId")
                if (remoteOwner != ownerUid) {
                    return@runTransaction C4TransactionResult.OwnershipMismatch(
                        documentOwner = remoteOwner ?: "unknown",
                        callerOwner = ownerUid
                    )
                }

                val remoteRevision = snapshot.getLong("revision") ?: 0L
                val remoteMutationId = snapshot.getString("lastMutationId")
                val isDeleted = snapshot.getBoolean("isDeleted") ?: false

                if (isDeleted) {
                    return@runTransaction C4TransactionResult.TombstonePrevailed(remoteRevision)
                }

                // Detección de idempotencia
                if (remoteMutationId == mutationId) {
                    return@runTransaction C4TransactionResult.IdempotentAck(
                        currentRevision = remoteRevision,
                        mutationId = mutationId
                    )
                }

                // Comprobación de precondición CAS
                if (remoteRevision != baseRevision) {
                    return@runTransaction C4TransactionResult.Conflict(
                        currentRevision = remoteRevision,
                        reason = "Revisión remota ($remoteRevision) difiere de baseRevision local ($baseRevision)"
                    )
                }

                val nextRevision = baseRevision + 1L

                // Actualización causal de fieldRevisions
                @Suppress("UNCHECKED_CAST")
                val oldFr = (snapshot.get("fieldRevisions") as? Map<String, Any>)?.mapValues {
                    (it.value as? Number)?.toLong() ?: 1L
                } ?: C4FirestoreDocument.canonicalCreationFieldRevisions(1L)

                val newFr = oldFr.toMutableMap()
                // Garantizar presencia de las 5 claves canónicas
                for (key in C4SyncConstants.KNOWN_FIELD_REVISIONS_KEYS) {
                    if (!newFr.containsKey(key)) {
                        newFr[key] = 1L
                    }
                }

                // Avanzar únicamente los campos modificados
                for (field in fieldsToWrite) {
                    newFr[field.name] = nextRevision
                }

                val updates = mutableMapOf<String, Any?>(
                    "revision" to nextRevision,
                    "lastMutationId" to mutationId,
                    "updatedAt" to System.currentTimeMillis(),
                    "fieldRevisions" to newFr
                )

                // Aplicar valores de campos modificados
                fieldValues.forEach { (k, v) ->
                    updates[k] = v
                }

                if (fieldsToWrite.contains(BeerDirtyField.PHOTO) && updatedPhotoUrl != null) {
                    updates["remotePhotoUrl"] = updatedPhotoUrl
                }

                tx.update(docRef, updates)
                C4TransactionResult.Success(newRevision = nextRevision, mutationId = mutationId)
            }.await()
        } catch (e: Exception) {
            C4TransactionResult.Error(e)
        }
    }

    /**
     * Transacción de tombstone: WriteTombstone.
     * Exige remoteRevision == baseRevision.
     * Avanza revisión en +1, establece isDeleted = true, preserva íntegras las fieldRevisions anteriores.
     */
    suspend fun executeWriteTombstone(
        syncId: String,
        ownerUid: String,
        mutationId: String,
        baseRevision: Long
    ): C4TransactionResult {
        val docRef = getDocRef(syncId)

        return try {
            firestore.runTransaction { tx ->
                val snapshot = tx.get(docRef)
                if (!snapshot.exists()) {
                    return@runTransaction C4TransactionResult.NotFound
                }

                val remoteOwner = snapshot.getString("userId")
                if (remoteOwner != ownerUid) {
                    return@runTransaction C4TransactionResult.OwnershipMismatch(
                        documentOwner = remoteOwner ?: "unknown",
                        callerOwner = ownerUid
                    )
                }

                val remoteRevision = snapshot.getLong("revision") ?: 0L
                val remoteMutationId = snapshot.getString("lastMutationId")
                val isDeleted = snapshot.getBoolean("isDeleted") ?: false

                if (isDeleted) {
                    if (remoteMutationId == mutationId) {
                        return@runTransaction C4TransactionResult.IdempotentAck(remoteRevision, mutationId)
                    }
                    return@runTransaction C4TransactionResult.TombstonePrevailed(remoteRevision)
                }

                if (remoteMutationId == mutationId) {
                    return@runTransaction C4TransactionResult.IdempotentAck(remoteRevision, mutationId)
                }

                if (remoteRevision != baseRevision) {
                    return@runTransaction C4TransactionResult.Conflict(
                        currentRevision = remoteRevision,
                        reason = "Revisión remota ($remoteRevision) difiere de baseRevision local ($baseRevision) para tombstone"
                    )
                }

                val nextRevision = baseRevision + 1L

                @Suppress("UNCHECKED_CAST")
                val oldFr = (snapshot.get("fieldRevisions") as? Map<String, Any>)?.mapValues {
                    (it.value as? Number)?.toLong() ?: 1L
                } ?: C4FirestoreDocument.canonicalCreationFieldRevisions(1L)

                val updates = mapOf(
                    "isDeleted" to true,
                    "revision" to nextRevision,
                    "lastMutationId" to mutationId,
                    "updatedAt" to System.currentTimeMillis(),
                    "fieldRevisions" to oldFr
                )

                tx.update(docRef, updates)
                C4TransactionResult.Success(newRevision = nextRevision, mutationId = mutationId)
            }.await()
        } catch (e: Exception) {
            C4TransactionResult.Error(e)
        }
    }
}
