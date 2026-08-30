package com.example.contadordebirras.domain

import android.content.Context
import android.location.Geocoder
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class BeerRepository(private val beerDao: BeerDao, private val context: Context) {
    val allBeers: Flow<List<BeerEntity>> = beerDao.getAllBeers()
    val totalCount: Flow<Int> = beerDao.getTotalCount()
    val lastBeer: Flow<BeerEntity?> = beerDao.getLastBeer()
    
    private val syncMutex = Mutex()

    suspend fun addBeer(type: BeerType, timestamp: Long, latitude: Double? = null, longitude: Double? = null, photoUri: String? = null, comment: String? = null) {
        withContext(Dispatchers.IO) {
            var locationName: String? = null
            if (latitude != null && longitude != null) {
                locationName = "Ubicación desconocida"
                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        locationName = address.locality ?: address.subAdminArea ?: address.adminArea ?: address.countryName ?: "Ubicación desconocida"
                    }
                } catch (e: Exception) {}
            }
            val beer = BeerEntity(
                type = type, timestamp = timestamp, latitude = latitude, longitude = longitude, 
                photoUri = photoUri, comment = comment, locationName = locationName,
                syncStatus = SyncStatus.PENDING, updatedAt = System.currentTimeMillis()
            )
            beerDao.insertBeer(beer)
            syncWithCloud()
        }
    }

    suspend fun deleteLastBeer() {
        withContext(Dispatchers.IO) {
            beerDao.deleteLastBeer()
            syncWithCloud()
        }
    }

    suspend fun deleteBeer(beer: BeerEntity) {
        withContext(Dispatchers.IO) {
            beerDao.softDeleteBeer(beer.id)
            syncWithCloud()
        }
    }

    suspend fun updateBeer(beer: BeerEntity) {
        withContext(Dispatchers.IO) {
            beerDao.updateBeer(beer.copy(syncStatus = SyncStatus.PENDING, updatedAt = System.currentTimeMillis()))
            syncWithCloud()
        }
    }

    suspend fun syncWithCloud() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val firestore = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance()

        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val pendingBeers = beerDao.getPendingSyncBeers()
                for (beer in pendingBeers) {
                    var remoteUrl = beer.remotePhotoUrl
                    var photoUploadFailed = false
                    if (beer.photoUri != null && remoteUrl == null) {
                        try {
                            val storageRef = storage.reference.child("users/${user.uid}/beers/${beer.syncId}.jpg")
                            val uri = android.net.Uri.parse(beer.photoUri)
                            storageRef.putFile(uri).await()
                            remoteUrl = storageRef.downloadUrl.await().toString()
                        } catch (e: Exception) {
                            android.util.Log.e("SyncDebug", "Error uploading photo, continuing sync without it", e)
                            photoUploadFailed = true
                        }
                    }

                    if (beer.syncStatus == SyncStatus.DELETED) {
                        try {
                            firestore.collection("beers").document(beer.syncId).delete().await()
                            beerDao.deleteBeer(beer) // Borrado fsico local
                        } catch (e: Exception) {
                            android.util.Log.e("SyncDebug", "Error al borrar en Firestore", e)
                        }
                    } else {
                        val map = hashMapOf(
                            "userId" to user.uid,
                            "type" to beer.type.name,
                            "timestamp" to beer.timestamp,
                            "latitude" to beer.latitude,
                            "longitude" to beer.longitude,
                            "comment" to beer.comment,
                            "locationName" to beer.locationName,
                            "remotePhotoUrl" to remoteUrl,
                            "updatedAt" to beer.updatedAt
                        )
                        try {
                            firestore.collection("beers").document(beer.syncId).set(map).await()
                            if (!photoUploadFailed) {
                                beerDao.markAsSynced(beer.id, remoteUrl)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SyncDebug", "Error al actualizar en Firestore", e)
                        }
                    }
                }
            }
        }
    }
}
