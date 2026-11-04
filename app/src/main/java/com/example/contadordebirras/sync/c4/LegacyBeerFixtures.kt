package com.example.contadordebirras.sync.c4

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import java.util.UUID

/**
 * Fixtures ficticios y utilidades para representar de forma auténtica las estructuras
 * de datos del sistema de sincronización legacy (Fase C4.5).
 *
 * En concordancia exacta con:
 * - [com.example.contadordebirras.domain.BeerSyncEngine.kt]
 * - [com.example.contadordebirras.data.BeerEntity.kt]
 * - Esquema de Firestore previo a C4 (sin revision, isDeleted, lastMutationId ni fieldRevisions).
 * - Esquema de Storage previo a C4 (`users/{userId}/beers/{syncId}.jpg`).
 */
object LegacyBeerFixtures {

    /**
     * Crea un mapa representativo de un documento Firestore legacy auténtico tal como
     * era generado por [com.example.contadordebirras.domain.BeerSyncEngine.pushPendingBeer].
     *
     * IMPORTANTE: No incluye los campos C4 (revision, isDeleted, lastMutationId, fieldRevisions).
     */
    fun createLegacyFirestorePayload(
        userId: String,
        syncId: String = UUID.randomUUID().toString(),
        type: String = "CANA",
        timestamp: Long = System.currentTimeMillis() - 86400000L,
        latitude: Double? = 40.4168,
        longitude: Double? = -3.7038,
        comment: String? = "Cerveza legacy de prueba",
        locationName: String? = "Bar Legacy Ficticio",
        hasPhoto: Boolean = false,
        photoSource: String? = if (hasPhoto) "CAMERA" else null,
        updatedAt: Long = timestamp
    ): Map<String, Any?> {
        val remotePhotoUrl = if (hasPhoto) "users/$userId/beers/$syncId.jpg" else null
        return hashMapOf(
            "userId" to userId,
            "type" to type,
            "timestamp" to timestamp,
            "latitude" to latitude,
            "longitude" to longitude,
            "comment" to comment,
            "locationName" to locationName,
            "remotePhotoUrl" to remotePhotoUrl,
            "photoSource" to photoSource,
            "updatedAt" to updatedAt
        )
    }

    /**
     * Crea una entidad local Room representativa de una fila legacy auténtica en Room v10.
     *
     * Invariantes legacy:
     * - [remoteRevision] = 0L
     * - [baseRevision] = 0L
     * - [dirtyFields] = null
     * - [lastMutationId] = null
     * - [deletedAt] = null (o timestamp si estaba en DELETED)
     */
    fun createLegacyRoomBeer(
        ownerUid: String,
        syncId: String = UUID.randomUUID().toString(),
        syncStatus: String = SyncStatus.SYNCED,
        type: BeerType = BeerType.CANA,
        comment: String? = "Cerveza Room legacy",
        timestamp: Long = System.currentTimeMillis() - 86400000L,
        latitude: Double? = 40.4168,
        longitude: Double? = -3.7038,
        locationName: String? = "Bar Local Legacy",
        photoUri: String? = null,
        hasRemotePhoto: Boolean = false,
        photoSource: String? = if (hasRemotePhoto) "CAMERA" else null,
        updatedAt: Long = timestamp
    ): BeerEntity {
        val remoteUrl = if (hasRemotePhoto) "users/$ownerUid/beers/$syncId.jpg" else null
        val deletedAt = if (syncStatus == SyncStatus.DELETED) System.currentTimeMillis() else null

        return BeerEntity(
            id = 0,
            type = type,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            photoUri = photoUri,
            comment = comment,
            locationName = locationName,
            syncId = syncId,
            syncStatus = syncStatus,
            remotePhotoUrl = remoteUrl,
            updatedAt = updatedAt,
            photoSource = photoSource,
            ownerUid = ownerUid,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = deletedAt
        )
    }
}
