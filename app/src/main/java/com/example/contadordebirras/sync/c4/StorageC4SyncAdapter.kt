package com.example.contadordebirras.sync.c4

import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await

/**
 * Adaptador aislado para operaciones Firebase Storage bajo el Protocolo C4.
 *
 * RESTRICCIONES DE SEGURIDAD:
 * - Conecta exclusivamente al emulador Storage (puerto 9199).
 * - Exige nombres de fotografía versionados inmutables: users/{ownerUid}/beers/{syncId}_{mutationId}.jpg
 * - Prohibición estricta de sobrescritura: durante reintentos, verifica primero si el archivo ya existe
 *   mediante getMetadata(), evitando cualquier segundo upload ciego que violaría las reglas de inmutabilidad.
 */
class StorageC4SyncAdapter(
    private val storage: FirebaseStorage,
    val expectedBucket: String = "${C4SyncConstants.DEMO_PROJECT_ID}.appspot.com"
) {

    init {
        val app = storage.app
        val projectId = app.options.projectId
        require(projectId == C4SyncConstants.DEMO_PROJECT_ID) {
            "PROHIBIDO: Intento de inicializar StorageC4SyncAdapter con projectId no autorizado: $projectId (esperado: ${C4SyncConstants.DEMO_PROJECT_ID})"
        }
    }

    companion object {
        fun computeMd5Base64(bytes: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes)
            return try {
                android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getEncoder().encodeToString(digest)
            }
        }
    }

    /**
     * Sube una fotografía versionada respetando la inmutabilidad y protegiendo contra sobrescrituras.
     */
    suspend fun uploadVersionedPhoto(
        ownerUid: String,
        syncId: String,
        mutationId: String,
        photoBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): C4StorageResult {
        val fileName = "${syncId}_$mutationId.jpg"
        if (!C4SyncConstants.isVersionedPhotoName(fileName)) {
            return C4StorageResult.OverwriteRejected("Nombre de archivo no cumple el formato versionado C4: $fileName")
        }

        val remotePath = C4SyncConstants.buildVersionedPhotoPath(ownerUid, syncId, mutationId)
        val storageRef = storage.reference.child(remotePath)

        // 1. Detección preventiva de existencia previa (evita fallo de sobrescritura en reglas C4.3)
        try {
            val existingMeta = storageRef.metadata.await()
            if (existingMeta != null) {
                val remoteMd5 = existingMeta.md5Hash
                val remoteSize = existingMeta.sizeBytes
                val expectedSize = photoBytes.size.toLong()

                if (remoteMd5 != null) {
                    val localMd5 = computeMd5Base64(photoBytes)
                    if (remoteMd5 == localMd5 && remoteSize == expectedSize) {
                        return C4StorageResult.Success(remotePath = remotePath, alreadyExisted = true)
                    } else {
                        return C4StorageResult.OverwriteRejected(
                            "Archivo remoto existente difiere en contenido o tamaño del esperado (remoteSize=$remoteSize vs expected=$expectedSize). Sobrescritura prohibida."
                        )
                    }
                } else {
                    // Si no puede validarse la equivalencia sin hash, bloquear el reintento y documentarlo
                    return C4StorageResult.OverwriteRejected(
                        "No se puede validar la equivalencia de contenido del archivo existente (md5Hash ausente en metadatos remotos). Reintento bloqueado."
                    )
                }
            }
        } catch (e: StorageException) {
            // Solo tratar OBJECT_NOT_FOUND como autorización para una primera subida
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) {
                return C4StorageResult.Error(e)
            }
        } catch (e: Exception) {
            // Ante cualquier otro error de red o autenticación, devolver Error y detener
            return C4StorageResult.Error(e)
        }

        // 2. Subida atómica por primera vez
        val metadata = StorageMetadata.Builder()
            .setContentType(mimeType)
            .build()

        return try {
            storageRef.putBytes(photoBytes, metadata).await()
            C4StorageResult.Success(remotePath = remotePath, alreadyExisted = false)
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED) {
                C4StorageResult.OverwriteRejected("Sobrescritura denegada o falta de autorización: ${e.message}")
            } else {
                C4StorageResult.Error(e)
            }
        } catch (e: Exception) {
            C4StorageResult.Error(e)
        }
    }

    /**
     * Comprueba si una fotografía remota existe de forma autoritativa.
     */
    suspend fun photoExists(remotePath: String): Boolean {
        return try {
            storage.reference.child(remotePath).metadata.await() != null
        } catch (_: Exception) {
            false
        }
    }
}
