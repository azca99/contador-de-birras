package com.example.contadordebirras.domain

import android.content.Context
import android.location.Geocoder
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import java.util.Locale

class BeerRepository(private val beerDao: BeerDao, private val context: Context, private val authRepository: AuthRepository) {
    
    private fun currentOwnerUid(): String = authRepository.currentUser.value?.uid ?: "guest_local"

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allBeers: Flow<List<BeerEntity>> = authRepository.currentUser
        .map { it?.uid ?: "guest_local" }
        .distinctUntilChanged()
        .flatMapLatest { uid -> beerDao.getAllBeers(uid) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val totalCount: Flow<Int> = authRepository.currentUser
        .map { it?.uid ?: "guest_local" }
        .distinctUntilChanged()
        .flatMapLatest { uid -> beerDao.getTotalCount(uid) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val lastBeer: Flow<BeerEntity?> = authRepository.currentUser
        .map { it?.uid ?: "guest_local" }
        .distinctUntilChanged()
        .flatMapLatest { uid -> beerDao.getLastBeer(uid) }
    
    private val syncMutex = Mutex()
    private val syncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repoScope.launch {
            syncTrigger.collect {
                syncWithCloud()
            }
        }
        repoScope.launch {
            authRepository.currentUser.collect { user ->
                if (user != null) {
                    requestSync()
                }
            }
        }
    }

    fun requestSync() {
        syncTrigger.tryEmit(Unit)
    }

    suspend fun addBeer(type: BeerType, timestamp: Long, latitude: Double? = null, longitude: Double? = null, photoUri: String? = null, comment: String? = null, photoSource: String? = null): Long {
        return withContext(Dispatchers.IO) {
            val beer = BeerEntity(
                type = type, timestamp = timestamp, latitude = latitude, longitude = longitude, 
                photoUri = photoUri, comment = comment, locationName = null, photoSource = photoSource,
                syncStatus = SyncStatus.PENDING, updatedAt = System.currentTimeMillis(),
                ownerUid = currentOwnerUid()
            )
            val id = beerDao.insertBeer(beer)
            id
        }
    }

    suspend fun updateBeerLocation(beerId: Long, latitude: Double, longitude: Double) {
        withContext(Dispatchers.IO) {
            var locationName: String? = "Ubicación desconocida"
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    locationName = address.locality ?: address.subAdminArea ?: address.adminArea ?: address.countryName ?: "Ubicación desconocida"
                }
            } catch (e: Exception) {}
            
            val ownerUid = currentOwnerUid()
            val beer = beerDao.getBeerById(beerId.toInt(), ownerUid)
            if (beer != null) {
                beerDao.updateBeer(
                    id = beer.id, type = beer.type, timestamp = beer.timestamp, latitude = latitude,
                    longitude = longitude, photoUri = beer.photoUri, comment = beer.comment,
                    locationName = locationName, syncStatus = SyncStatus.PENDING, remotePhotoUrl = beer.remotePhotoUrl,
                    updatedAt = System.currentTimeMillis(), photoSource = beer.photoSource, ownerUid = ownerUid
                )
            }
        }
    }

    suspend fun deleteBeer(beer: BeerEntity) {
        withContext(Dispatchers.IO) {
            val ownerUid = currentOwnerUid()
            if (beer.ownerUid == ownerUid) {
                beerDao.softDeleteBeer(beer.id, ownerUid)
                requestSync()
            }
        }
    }

    suspend fun updateBeer(beer: BeerEntity) {
        withContext(Dispatchers.IO) {
            val ownerUid = currentOwnerUid()
            if (beer.ownerUid == ownerUid) {
                beerDao.updateBeer(
                    id = beer.id, type = beer.type, timestamp = beer.timestamp, latitude = beer.latitude,
                    longitude = beer.longitude, photoUri = beer.photoUri, comment = beer.comment,
                    locationName = beer.locationName, syncStatus = SyncStatus.PENDING, remotePhotoUrl = beer.remotePhotoUrl,
                    updatedAt = System.currentTimeMillis(), photoSource = beer.photoSource, ownerUid = ownerUid
                )
                requestSync()
            }
        }
    }

    suspend fun syncWithCloud() {
        val user = authRepository.currentUser.value ?: return
        val syncUid = user.uid
        val firestore = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance()

        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                if (authRepository.currentUser.value?.uid != syncUid) return@withContext

                // 1. PUSH local PENDING/DELETED
                val pendingBeers = beerDao.getPendingSyncBeers(user.uid)
                for (beer in pendingBeers) {
                    if (authRepository.currentUser.value?.uid != syncUid) return@withContext
                    if (beer.ownerUid != syncUid) continue // Extra safety check
                    var remoteUrl = beer.remotePhotoUrl
                    var photoUploadFailed = false
                    if (beer.photoUri != null && remoteUrl == null) {
                        try {
                            val storageRef = storage.reference.child("users/${user.uid}/beers/${beer.syncId}.jpg")
                            val uri = android.net.Uri.parse(beer.photoUri)
                            storageRef.putFile(uri).await()
                            if (authRepository.currentUser.value?.uid != syncUid) return@withContext
                            remoteUrl = "users/${user.uid}/beers/${beer.syncId}.jpg"
                        } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                            android.util.Log.e("SyncDebug", "Error uploading photo", e)
                            photoUploadFailed = true
                        }
                    }

                    if (beer.syncStatus == SyncStatus.DELETED) {
                        try {
                            firestore.runTransaction { transaction ->
                                val docRef = firestore.collection("beers").document(beer.syncId)
                                val doc = transaction.get(docRef)
                                if (doc.exists()) {
                                    if (doc.getString("userId") == syncUid) {
                                        transaction.delete(docRef)
                                    }
                                }
                            }.await()
                            if (authRepository.currentUser.value?.uid != syncUid) return@withContext
                            beerDao.hardDeleteBySyncId(beer.syncId, syncUid) // Borrado fisico local real
                        } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
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
                            if (authRepository.currentUser.value?.uid != syncUid) return@withContext
                            if (!photoUploadFailed) {
                                beerDao.markAsSynced(beer.id, remoteUrl, syncUid)
                            }
                        } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                            android.util.Log.e("SyncDebug", "Error al actualizar en Firestore", e)
                        }
                    }
                }

                // 2. PULL / RECONCILE from Cloud
                try {
                    if (authRepository.currentUser.value?.uid != syncUid) return@withContext
                    val snapshot = firestore.collection("beers").whereEqualTo("userId", syncUid).get(Source.SERVER).await()
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

                        if (authRepository.currentUser.value?.uid != syncUid) return@withContext
                        val localBeer = beerDao.getBeerBySyncId(syncId, syncUid)
                        val decision = SyncResolver.resolvePullConflict(localBeer, updatedAt)

                        if (decision == SyncResolver.SyncDecision.INSERT_LOCAL) {
                            val newBeer = BeerEntity(
                                type = type, timestamp = timestamp, latitude = lat, longitude = lng,
                                comment = comment, locationName = locName, remotePhotoUrl = remotePhotoUrl,
                                photoSource = photoSource, syncId = syncId, syncStatus = SyncStatus.SYNCED, updatedAt = updatedAt,
                                ownerUid = syncUid
                            )
                            beerDao.insertBeer(newBeer)
                        } else if (decision == SyncResolver.SyncDecision.UPDATE_LOCAL) {
                            val updatedBeer = localBeer!!.copy(
                                type = type, timestamp = timestamp, latitude = lat, longitude = lng,
                                comment = comment, locationName = locName, remotePhotoUrl = remotePhotoUrl,
                                photoSource = photoSource, updatedAt = updatedAt
                            )
                            if (updatedBeer.ownerUid == syncUid) {
                                beerDao.updateBeer(
                                    id = updatedBeer.id, type = updatedBeer.type, timestamp = updatedBeer.timestamp,
                                    latitude = updatedBeer.latitude, longitude = updatedBeer.longitude,
                                    photoUri = updatedBeer.photoUri, comment = updatedBeer.comment,
                                    locationName = updatedBeer.locationName, syncStatus = updatedBeer.syncStatus,
                                    remotePhotoUrl = updatedBeer.remotePhotoUrl, updatedAt = updatedBeer.updatedAt,
                                    photoSource = updatedBeer.photoSource, ownerUid = syncUid
                                )
                            }
                        }
                    }

                    // 3. RECONCILIACION DE BORRADOS
                    if (authRepository.currentUser.value?.uid != syncUid) return@withContext
                    val localSyncedIds = beerDao.getAllSyncedIds(syncUid)
                    val toDelete = SyncResolver.resolveDeletions(localSyncedIds, Result.success(remoteIds))
                    for (id in toDelete) {
                        beerDao.hardDeleteBySyncId(id, syncUid)
                    }

                } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                    android.util.Log.e("SyncDebug", "Error pulling from Firestore. Skip reconciliation.", e)
                }
            }
        }
    }
}
