package com.example.contadordebirras.sync.c4

/**
 * Constantes de configuración y límites del Protocolo C4.
 *
 * Utilizado exclusivamente por los adaptadores aislados de la Fase C4.4A.
 * Desconectado de los workers y de la sincronización de producción.
 */
object C4SyncConstants {
    const val DEMO_PROJECT_ID = "demo-contador-c44"
    const val FIRESTORE_PORT = 8888
    const val STORAGE_PORT = 9199

    const val EMULATOR_HOST_LOCAL = "127.0.0.1"
    const val EMULATOR_HOST_ANDROID = "10.0.2.2"

    const val BEERS_COLLECTION = "beers"

    val KNOWN_FIELD_REVISIONS_KEYS = setOf(
        "TYPE",
        "TIMESTAMP",
        "LOCATION",
        "COMMENT",
        "PHOTO"
    )

    fun buildVersionedPhotoPath(ownerUid: String, syncId: String, mutationId: String): String {
        return "users/$ownerUid/beers/${syncId}_$mutationId.jpg"
    }

    fun isVersionedPhotoName(fileName: String): Boolean {
        val regex = Regex("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|sync-[a-zA-Z0-9-]+)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|mut-[a-zA-Z0-9-]+)\\.(jpg|jpeg|png|webp)$")
        return regex.matches(fileName)
    }
}
