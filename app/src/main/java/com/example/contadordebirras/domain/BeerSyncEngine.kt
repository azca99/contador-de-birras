package com.example.contadordebirras.domain

import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import android.util.Log

enum class SyncEngineResult {
    SUCCESS,
    RETRY,
    AUTH_CHANGED,
    PERMANENT_FAILURE
}

class BeerSyncEngine(
    private val beerDao: BeerDao,
    private val expectedUid: String
) {
    suspend fun sync(currentUid: String?): SyncEngineResult {
        if (currentUid != expectedUid) {
            return SyncEngineResult.AUTH_CHANGED
        }
        
        return try {
            val pendingBeers = beerDao.getPendingSyncBeers(expectedUid)
            for (beer in pendingBeers) {
                if (currentUid != expectedUid) {
                    return SyncEngineResult.AUTH_CHANGED
                }
                
                try {
                    when (beer.syncStatus) {
                        SyncStatus.PENDING -> {
                            pushPendingBeer(beer)
                        }
                        SyncStatus.DELETED -> {
                            val success = pushDeletedBeer(beer)
                            if (success) {
                                beerDao.hardDeleteBySyncId(beer.syncId, expectedUid)
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("BeerSyncEngine", "Error syncing beer ${beer.id}: ${e.message}", e)
                    return SyncEngineResult.RETRY
                }
            }
            
            pullRemoteBeers()
            
            SyncEngineResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("BeerSyncEngine", "Fatal sync error: ${e.message}", e)
            SyncEngineResult.RETRY
        }
    }
    
    private suspend fun pushPendingBeer(beer: BeerEntity) {
        val firestore = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance()
        
        var remoteUrl = beer.remotePhotoUrl
        var photoUploadFailed = false
        if (beer.photoUri != null && remoteUrl == null) {
            try {
                val storageRef = storage.reference.child("users/${expectedUid}/beers/${beer.syncId}.jpg")
                val uri = android.net.Uri.parse(beer.photoUri)
                storageRef.putFile(uri).await()
                remoteUrl = "users/${expectedUid}/beers/${beer.syncId}.jpg"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e("BeerSyncEngine", "Error uploading photo", e)
                photoUploadFailed = true
            }
        }

        val map = hashMapOf(
            "userId" to expectedUid,
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

        firestore.collection("beers").document(beer.syncId).set(map).await()
        if (!photoUploadFailed) {
            beerDao.markAsSynced(beer.id, remoteUrl, expectedUid)
        } else {
            // throw to trigger retry for the photo upload if it failed
            throw Exception("Photo upload failed for ${beer.syncId}")
        }
    }
    
    private suspend fun pushDeletedBeer(beer: BeerEntity): Boolean {
        val firestore = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance()
        
        val docRef = firestore.collection("beers").document(beer.syncId)
        
        var transactionResult = "SUCCESS"
        
        try {
            transactionResult = firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                if (snapshot.exists()) {
                    val ownerId = snapshot.getString("userId")
                    if (ownerId != expectedUid) {
                        "OWNERSHIP_MISMATCH"
                    } else {
                        transaction.delete(docRef)
                        "DELETED"
                    }
                } else {
                    "ALREADY_ABSENT"
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e // Let it fail, it will be caught and trigger RETRY
        }

        if (transactionResult == "OWNERSHIP_MISMATCH") {
            Log.e("BeerSyncEngine", "UserId mismatch en doc de Firestore para ${beer.syncId}")
            return false
        }
        
        var storageSuccess = false
        try {
            val storageRef = storage.reference.child("users/${expectedUid}/beers/${beer.syncId}.jpg")
            storageRef.delete().await()
            storageSuccess = true
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                storageSuccess = true
            } else {
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
        
        return storageSuccess
    }
    
    private suspend fun pullRemoteBeers() {
        val firestore = FirebaseFirestore.getInstance()
        
        val snapshot = firestore.collection("beers").whereEqualTo("userId", expectedUid).get(Source.SERVER).await()
        val remoteIds = mutableSetOf<String>()

        for (doc in snapshot.documents) {
            val syncId = doc.id
            remoteIds.add(syncId)

            val typeStr = doc.getString("type")
            val type = SyncResolver.parseRemoteBeerType(typeStr)
            if (type == null) {
                Log.w("BeerSyncEngine", "Skipping remote beer document due to invalid or missing type.")
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

            val localBeer = beerDao.getBeerBySyncId(syncId, expectedUid)
            val decision = SyncResolver.resolvePullConflict(localBeer, updatedAt)

            if (decision == SyncResolver.SyncDecision.INSERT_LOCAL) {
                val newBeer = BeerEntity(
                    type = type, timestamp = timestamp, latitude = lat, longitude = lng,
                    comment = comment, locationName = locName, remotePhotoUrl = remotePhotoUrl,
                    photoSource = photoSource, syncId = syncId, syncStatus = SyncStatus.SYNCED, updatedAt = updatedAt,
                    ownerUid = expectedUid
                )
                beerDao.insertBeer(newBeer)
            } else if (decision == SyncResolver.SyncDecision.UPDATE_LOCAL) {
                val updatedBeer = localBeer!!.copy(
                    type = type, timestamp = timestamp, latitude = lat, longitude = lng,
                    comment = comment, locationName = locName, remotePhotoUrl = remotePhotoUrl,
                    photoSource = photoSource, updatedAt = updatedAt
                )
                if (updatedBeer.ownerUid == expectedUid) {
                    beerDao.updateBeer(
                        id = updatedBeer.id, type = updatedBeer.type, timestamp = updatedBeer.timestamp,
                        latitude = updatedBeer.latitude, longitude = updatedBeer.longitude,
                        photoUri = updatedBeer.photoUri, comment = updatedBeer.comment,
                        locationName = updatedBeer.locationName, syncStatus = updatedBeer.syncStatus,
                        remotePhotoUrl = updatedBeer.remotePhotoUrl, updatedAt = updatedBeer.updatedAt,
                        photoSource = updatedBeer.photoSource, ownerUid = expectedUid
                    )
                }
            }
        }

        val localSyncedIds = beerDao.getAllSyncedIds(expectedUid)
        val toDelete = SyncResolver.resolveDeletions(localSyncedIds, Result.success(remoteIds))
        for (id in toDelete) {
            beerDao.hardDeleteBySyncId(id, expectedUid)
        }
    }
}
