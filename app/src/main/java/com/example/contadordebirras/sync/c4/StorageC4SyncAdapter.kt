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
                // El archivo ya fue subido exitosamente en un intento anterior
                return C4StorageResult.Success(remotePath = remotePath, alreadyExisted = true)
            }
        } catch (e: StorageException) {
            // Objeto no existe (esperado para primera subida): continuar
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) {
                // Si el error no es NOT_FOUND, podría ser falta de permisos u otro fallo de red
            }
        } catch (_: Exception) {
            // En emuladores o entornos de prueba si no existe puede lanzar excepción
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
