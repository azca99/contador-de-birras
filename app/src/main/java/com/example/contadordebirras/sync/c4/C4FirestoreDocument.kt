package com.example.contadordebirras.sync.c4

import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.RemoteBeerSyncMetadata
import com.example.contadordebirras.domain.RemotePullState
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Representación inmutable del documento Firestore conforme al esquema auténtico
 * validado en las reglas C4.3 y en concordancia con BeerEntity y BeerDirtyField.
 */
data class C4FirestoreDocument(
    val syncId: String,
    val userId: String,
    val type: String,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val comment: String? = null,
    val locationName: String? = null,
    val remotePhotoUrl: String? = null,
    val photoSource: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val revision: Long = 1L,
    val isDeleted: Boolean = false,
    val lastMutationId: String,
    val fieldRevisions: Map<String, Long> = canonicalCreationFieldRevisions()
) {

    fun toFirestoreMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "syncId" to syncId,
            "userId" to userId,
            "type" to type,
            "timestamp" to timestamp,
            "latitude" to latitude,
            "longitude" to longitude,
            "comment" to comment,
            "locationName" to locationName,
            "remotePhotoUrl" to remotePhotoUrl,
            "photoSource" to photoSource,
            "updatedAt" to updatedAt,
            "revision" to revision,
            "isDeleted" to isDeleted,
            "lastMutationId" to lastMutationId,
            "fieldRevisions" to fieldRevisions
        )
        return map
    }

    fun toRemoteSyncMetadata(): RemoteBeerSyncMetadata {
        val enumMap = fieldRevisions.mapNotNull { (k, v) ->
            try {
                BeerDirtyField.valueOf(k) to v
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toMap()

        return RemoteBeerSyncMetadata(
            revision = revision,
            isDeleted = isDeleted,
            lastMutationId = lastMutationId,
            fieldRevisions = enumMap
        )
    }

    fun toRemotePullState(): RemotePullState {
        return RemotePullState.Present(toRemoteSyncMetadata())
    }

    companion object {
        fun canonicalCreationFieldRevisions(revision: Long = 1L): Map<String, Long> {
            return mapOf(
                "TYPE" to revision,
                "TIMESTAMP" to revision,
                "LOCATION" to revision,
                "COMMENT" to revision,
                "PHOTO" to revision
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun fromDocumentSnapshot(syncId: String, snapshot: DocumentSnapshot): C4FirestoreDocument? {
            if (!snapshot.exists()) return null

            val userId = snapshot.getString("userId") ?: return null
            val revision = snapshot.getLong("revision") ?: 0L
            val isDeleted = snapshot.getBoolean("isDeleted") ?: false
            val lastMutationId = snapshot.getString("lastMutationId") ?: ""
            val type = snapshot.getString("type") ?: "LATA"
            val timestamp = snapshot.getLong("timestamp") ?: 0L
            val latitude = snapshot.getDouble("latitude")
            val longitude = snapshot.getDouble("longitude")
            val comment = snapshot.getString("comment")
            val locationName = snapshot.getString("locationName")
            val remotePhotoUrl = snapshot.getString("remotePhotoUrl")
            val photoSource = snapshot.getString("photoSource")
            val updatedAt = snapshot.getLong("updatedAt") ?: 0L

            val rawFr = snapshot.get("fieldRevisions") as? Map<String, Any>
            val parsedFr = mutableMapOf<String, Long>()
            rawFr?.forEach { (k, v) ->
                val num = (v as? Number)?.toLong()
                if (num != null) {
                    parsedFr[k] = num
                }
            }

            return C4FirestoreDocument(
                syncId = syncId,
                userId = userId,
                type = type,
                timestamp = timestamp,
                latitude = latitude,
                longitude = longitude,
                comment = comment,
                locationName = locationName,
                remotePhotoUrl = remotePhotoUrl,
                photoSource = photoSource,
                updatedAt = updatedAt,
                revision = revision,
                isDeleted = isDeleted,
                lastMutationId = lastMutationId,
                fieldRevisions = parsedFr
            )
        }
    }
}
