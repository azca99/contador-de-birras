package com.example.contadordebirras.simulation

import java.io.IOException

/**
 * Representación de un objeto fotográfico almacenado en Firebase Storage.
 */
data class FakeStoragePhotoObject(
    val path: String,
    val ownerUid: String,
    val bytes: ByteArray,
    val contentType: String = "image/jpeg"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FakeStoragePhotoObject
        return path == other.path && ownerUid == other.ownerUid && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + ownerUid.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * Simulador hermético en memoria de Firebase Storage para pruebas multidispositivo JVM (Fase C4.2).
 *
 * Simula:
 * - Rutas versionadas inmutables (ej. `users/{uid}/beers/{syncId}_{mutationId}.jpg`).
 * - Invariante de inmutabilidad: rechaza cualquier sobrescritura de una ruta versionada existente.
 * - Rechazo estricto de rutas que contengan URIs locales del dispositivo (`file://`, `content://`).
 * - Inyección controlada de errores de upload (500 interno, timeout de red).
 */
class FakeStorage {

    private val objects = mutableMapOf<String, FakeStoragePhotoObject>()

    /** Inyección de fallo de subida (ej. error 500 del servidor) */
    var failUpload: Boolean = false

    /** Inyección de fallo de red de transporte */
    var simulateNetworkError: Boolean = false

    /**
     * Sube un archivo fotográfico a una ruta versionada de Storage.
     */
    @Synchronized
    fun uploadPhoto(
        path: String,
        ownerUid: String,
        bytes: ByteArray,
        contentType: String = "image/jpeg"
    ): Result<String> {
        if (simulateNetworkError) {
            return Result.failure(IOException("FakeStorage: Timeout de red durante subida"))
        }
        if (failUpload) {
            return Result.failure(IOException("FakeStorage: Error 500 al persistir fotografía en Storage"))
        }

        // 1. Verificación de seguridad: no admitir URIs locales como ruta de Storage
        if (path.startsWith("file:") || path.startsWith("content:")) {
            return Result.failure(
                IllegalArgumentException("FakeStorage: La ruta de Storage no puede ser una URI local del dispositivo: $path")
            )
        }

        // 2. Invariante de inmutabilidad y soporte de reintento idempotente:
        val existing = objects[path]
        if (existing != null) {
            if (existing.ownerUid != ownerUid) {
                return Result.failure(
                    SecurityException("FakeStorage: La ruta existente $path pertenece a otro usuario")
                )
            }
            return if (existing.bytes.contentEquals(bytes)) {
                // Reintento idempotente de la misma mutación fotográfica
                val downloadUrl = "https://firebasestorage.googleapis.com/v0/b/contador-de-birras.appspot.com/o/${path.replace("/", "%2F")}?alt=media"
                Result.success(downloadUrl)
            } else {
                // Infracción de inmutabilidad: contenido diferente en la misma ruta
                Result.failure(
                    IllegalStateException("FakeStorage: Infracción de inmutabilidad. La ruta versionada $path ya existe con contenido diferente y no puede sobrescribirse.")
                )
            }
        }

        // 3. Verificación de pertenencia del path al usuario
        if (!path.startsWith("users/$ownerUid/beers/")) {
            return Result.failure(
                SecurityException("FakeStorage: El path $path no pertenece al espacio del usuario $ownerUid")
            )
        }

        objects[path] = FakeStoragePhotoObject(path, ownerUid, bytes, contentType)
        val downloadUrl = "https://firebasestorage.googleapis.com/v0/b/contador-de-birras.appspot.com/o/${path.replace("/", "%2F")}?alt=media"
        return Result.success(downloadUrl)
    }

    /**
     * Consulta si existe un objeto en la ruta indicada.
     */
    @Synchronized
    fun hasObject(path: String): Boolean = objects.containsKey(path)

    /**
     * Obtiene el objeto fotográfico si existe.
     */
    @Synchronized
    fun getObject(path: String): FakeStoragePhotoObject? = objects[path]?.copy()

    /**
     * Cantidad de fotografías almacenadas.
     */
    @Synchronized
    fun getObjectCount(): Int = objects.size
}
