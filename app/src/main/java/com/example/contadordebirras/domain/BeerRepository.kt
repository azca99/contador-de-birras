package com.example.contadordebirras.domain

import android.content.Context
import android.location.Geocoder
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import java.util.Locale

class BeerRepository(
    private val beerDao: BeerDao, 
    private val context: Context, 
    private val authRepository: AuthRepository,
    private val syncScheduler: BeerSyncScheduler
) {
    
    private fun currentOwnerUid(): String = authRepository.currentUser.value?.uid ?: "guest_local"

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allBeers: Flow<List<BeerEntity>> = authRepository.currentUser
        .map { it?.uid ?: "guest_local" }
        .distinctUntilChanged()
        .flatMapLatest { uid -> beerDao.getAllBeers(uid) }

    fun observeBeers(ownerUid: String): Flow<List<BeerEntity>> = beerDao.getAllBeers(ownerUid)

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
    
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repoScope.launch {
            authRepository.currentUser.collect { user ->
                if (user != null) {
                    syncScheduler.schedulePeriodicSync(user.uid)
                    syncScheduler.requestSync(user.uid)
                }
            }
        }
    }

    fun requestSync() {
        authRepository.currentUser.value?.uid?.let { uid ->
            syncScheduler.requestSync(uid)
        }
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
            if (id > 0) {
                requestSync()
            }
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
                val updatedRows = beerDao.updateBeer(
                    id = beer.id, type = beer.type, timestamp = beer.timestamp, latitude = latitude,
                    longitude = longitude, photoUri = beer.photoUri, comment = beer.comment,
                    locationName = locationName, syncStatus = SyncStatus.PENDING, remotePhotoUrl = beer.remotePhotoUrl,
                    updatedAt = System.currentTimeMillis(), photoSource = beer.photoSource, ownerUid = ownerUid
                )
                if (updatedRows > 0) {
                    requestSync()
                }
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

}
