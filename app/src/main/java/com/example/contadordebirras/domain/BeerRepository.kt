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

    suspend fun addBeer(type: BeerType, timestamp: Long, latitude: Double? = null, longitude: Double? = null, photoUri: String? = null, comment: String? = null, photoSource: String? = null) {
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
                photoUri = photoUri, comment = comment, locationName = locationName, photoSource = photoSource,
                syncStatus = SyncStatus.PENDING, updatedAt = System.currentTimeMillis()
            )
            beerDao.insertBeer(beer)
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
                // 1. PUSH local PENDING/DELETED
                val pendingBeers = beerDao.getPendingSyncBeers()
                for (beer in pendingBeers) {
                    var remoteUrl = beer.remotePhotoUrl
                    var photoUploadFailed = false
                    if (beer.photoUri != null && remoteUrl == null) {
                        try {
                            val storageRef = storage.reference.child("users/${user.uid}/beers/${beer.syncId}.jpg")
                            val uri = android.net.Uri.parse(beer.photoUri)
                            storageRef.putFile(uri).await()
                            remoteUrl = "users/${user.uid}/beers/${beer.syncId}.jpg"
                        } catch (e: Exception) {
                            android.util.Log.e("SyncDebug", "Error uploading photo", e)
                            photoUploadFailed = true
                        }
                    }

                    if (beer.syncStatus == SyncStatus.DELETED) {
                        try {
                            firestore.collection("beers").document(beer.syncId).delete().await()
                            beerDao.hardDeleteBySyncId(beer.syncId) // Borrado fsico local real
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
                            "photoSource" to beer.photoSource,
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

                // 2. PULL / RECONCILE from Cloud
                try {
                    val snapshot = firestore.collection("beers").whereEqualTo("userId", user.uid).get().await()
                    val remoteIds = mutableSetOf<String>()

                    for (doc in snapshot.documents) {
                        val syncId = doc.id
                        remoteIds.add(syncId)

                        val typeStr = doc.getString("type")
                        val type = SyncResolver.parseRemoteBeerType(typeStr)
                        if (type == null) {
                            android.util.Log.w("BeerRepository", "Skipping remote beer document due to invalid or missing type.")
                            continue
                        }
                        val timestamp = doc.getLong("timestamp") ?: 0L
                        val lat = doc.getDouble("latitude")
                        val lng = doc.getDouble("longitude")
                        val comment = doc.getString("comment")
                        val locName = doc.getString("locationName")
                        val remotePhotoUrl = doc.getString("remotePhotoUrl")
                        val photoSource = doc.getString("photoSource")
                        val updatedAt = doc.getLong("updatedAt") ?: 0L

                        val localBeer = beerDao.getBeerBySyncId(syncId)
                        val decision = SyncResolver.resolvePullConflict(localBeer, updatedAt)

                        if (decision == SyncResolver.SyncDecision.INSERT_LOCAL) {
                            val newBeer = BeerEntity(
                                type = type, timestamp = timestamp, latitude = lat, longitude = lng,
                                comment = comment, locationName = locName, remotePhotoUrl = remotePhotoUrl,
                                photoSource = photoSource, syncId = syncId, syncStatus = SyncStatus.SYNCED, updatedAt = updatedAt
                            )
                            beerDao.insertBeer(newBeer)
                        } else if (decision == SyncResolver.SyncDecision.UPDATE_LOCAL) {
                            val updatedBeer = localBeer!!.copy(
                                type = type, timestamp = timestamp, latitude = lat, longitude = lng,
                                comment = comment, locationName = locName, remotePhotoUrl = remotePhotoUrl,
                                photoSource = photoSource, updatedAt = updatedAt
                            )
                            beerDao.updateBeer(updatedBeer)
                        }
                    }

                    // 3. RECONCILIACION DE BORRADOS
                    val localSyncedIds = beerDao.getAllSyncedIds()
                    val toDelete = SyncResolver.resolveDeletions(localSyncedIds, remoteIds)
                    for (id in toDelete) {
                        beerDao.hardDeleteBySyncId(id)
                    }

                } catch (e: Exception) {
                    android.util.Log.e("SyncDebug", "Error pulling from Firestore. Skip reconciliation.", e)
                }
            }
        }
    }
}
