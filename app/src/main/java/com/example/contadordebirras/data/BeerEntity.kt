package com.example.contadordebirras.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.contadordebirras.domain.BeerType

import java.util.UUID

@Entity(tableName = "beers", indices = [androidx.room.Index(value = ["syncId"], unique = true)])
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
    val photoSource: String? = null
)
