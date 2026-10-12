package com.example.contadordebirras.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.contadordebirras.domain.BeerType

import java.util.UUID

@Entity(tableName = "beers", indices = [androidx.room.Index(value = ["ownerUid", "syncId"], unique = true)])
data class BeerEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: BeerType,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photoUri: String? = null,
    val comment: String? = null,
    val locationName: String? = null,
    val syncId: String = UUID.randomUUID().toString(),
    val syncStatus: String = SyncStatus.PENDING,
    val remotePhotoUrl: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val photoSource: String? = null,
    val ownerUid: String = "guest_local",
    // Metadata para el protocolo de sincronización multidispositivo (Fase C1)
    // remoteRevision: última revision remota conocida
    val remoteRevision: Long = 0L,
    // baseRevision: revision remota sobre la que comenzó el conjunto actual de cambios locales
    val baseRevision: Long = 0L,
    // dirtyFields: representación de los campos modificados localmente (en C1 permanece null y no se interpreta)
    val dirtyFields: String? = null,
    // lastMutationId: UUID de mutación futura para idempotencia (en C1 permanece null)
    val lastMutationId: String? = null,
    // deletedAt: timestamp local auxiliar del borrado pendiente futuro (en C1 permanece null)
    val deletedAt: Long? = null
) {
    fun hasPhoto(): Boolean {
        return !photoUri.isNullOrBlank() || !remotePhotoUrl.isNullOrBlank()
    }
}
