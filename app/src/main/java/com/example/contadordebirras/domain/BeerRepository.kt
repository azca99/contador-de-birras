package com.example.contadordebirras.domain

import android.content.Context
import android.location.Geocoder
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import java.util.Locale
import java.util.UUID

class BeerRepository(
    private val beerDao: BeerDao, 
    private val context: Context, 
    private val authRepository: AuthRepository,
    private val syncScheduler: BeerSyncScheduler,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val mutationIdProvider: () -> String = { UUID.randomUUID().toString() }
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
            val now = nowProvider()
            val beer = BeerEntity(
                type = type, timestamp = timestamp, latitude = latitude, longitude = longitude, 
                photoUri = photoUri, comment = comment, locationName = null, photoSource = photoSource,
                syncStatus = SyncStatus.PENDING, updatedAt = now,
                ownerUid = currentOwnerUid(),
                remoteRevision = 0L,
                baseRevision = 0L,
                dirtyFields = BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS),
                lastMutationId = mutationIdProvider(),
                deletedAt = null
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
            val current = beerDao.getBeerById(beerId.toInt(), ownerUid) ?: return@withContext
            if (current.syncStatus == SyncStatus.DELETED) return@withContext

            val locationChanged = current.latitude != latitude ||
                    current.longitude != longitude ||
                    current.locationName != locationName
            if (!locationChanged) {
                return@withContext
            }

            val changedFields = setOf(BeerDirtyField.LOCATION)
            val baseRevision = if (current.syncStatus == SyncStatus.PENDING) current.baseRevision else current.remoteRevision
            val dirtyFields = if (current.syncStatus == SyncStatus.PENDING) {
                BeerDirtyFieldsCodec.merge(current.dirtyFields, changedFields)
            } else {
                BeerDirtyFieldsCodec.encode(changedFields)
            }
            val mutationId = mutationIdProvider()
            val now = nowProvider()

            val updatedRows = beerDao.updateBeerLocationLocalMutation(
                id = current.id,
                latitude = latitude,
                longitude = longitude,
                locationName = locationName,
                updatedAt = now,
                ownerUid = ownerUid,
                baseRevision = baseRevision,
                dirtyFields = dirtyFields,
                lastMutationId = mutationId
            )
            if (updatedRows > 0) {
                requestSync()
            }
        }
    }

    suspend fun deleteBeer(beer: BeerEntity) {
        withContext(Dispatchers.IO) {
            val ownerUid = currentOwnerUid()
            if (beer.ownerUid != ownerUid) return@withContext

            val current = beerDao.getBeerById(beer.id, ownerUid) ?: return@withContext
            if (current.syncStatus == SyncStatus.DELETED) return@withContext

            val baseRevision = if (current.syncStatus == SyncStatus.PENDING) current.baseRevision else current.remoteRevision
            val now = nowProvider()
            val mutationId = mutationIdProvider()

            val updatedRows = beerDao.softDeleteBeerLocalMutation(
                id = current.id,
                updatedAt = now,
                deletedAt = now,
                ownerUid = ownerUid,
                baseRevision = baseRevision,
                lastMutationId = mutationId
            )
            if (updatedRows > 0) {
                requestSync()
            }
        }
    }

    suspend fun updateBeer(beer: BeerEntity) {
        withContext(Dispatchers.IO) {
            val ownerUid = currentOwnerUid()
            if (beer.ownerUid != ownerUid) return@withContext

            val current = beerDao.getBeerById(beer.id, ownerUid) ?: return@withContext
            if (current.syncStatus == SyncStatus.DELETED) return@withContext

            val changedFields = mutableSetOf<BeerDirtyField>()
            if (current.type != beer.type) {
                changedFields.add(BeerDirtyField.TYPE)
            }
            if (current.timestamp != beer.timestamp) {
                changedFields.add(BeerDirtyField.TIMESTAMP)
            }
            if (current.latitude != beer.latitude || current.longitude != beer.longitude || current.locationName != beer.locationName) {
                changedFields.add(BeerDirtyField.LOCATION)
            }
            if (current.comment != beer.comment) {
                changedFields.add(BeerDirtyField.COMMENT)
            }
            if (current.photoUri != beer.photoUri || current.photoSource != beer.photoSource || current.remotePhotoUrl != beer.remotePhotoUrl) {
                changedFields.add(BeerDirtyField.PHOTO)
            }

            if (changedFields.isEmpty()) {
                return@withContext
            }

            val baseRevision = if (current.syncStatus == SyncStatus.PENDING) current.baseRevision else current.remoteRevision
            val dirtyFields = if (current.syncStatus == SyncStatus.PENDING) {
                BeerDirtyFieldsCodec.merge(current.dirtyFields, changedFields)
            } else {
                BeerDirtyFieldsCodec.encode(changedFields)
            }
            val mutationId = mutationIdProvider()
            val now = nowProvider()

            val updatedRows = beerDao.updateBeerLocalMutation(
                id = current.id,
                type = beer.type,
                timestamp = beer.timestamp,
                latitude = beer.latitude,
                longitude = beer.longitude,
                photoUri = beer.photoUri,
                comment = beer.comment,
                locationName = beer.locationName,
                remotePhotoUrl = beer.remotePhotoUrl,
                updatedAt = now,
                photoSource = beer.photoSource,
                ownerUid = ownerUid,
                baseRevision = baseRevision,
                dirtyFields = dirtyFields,
                lastMutationId = mutationId
            )
            if (updatedRows > 0) {
                requestSync()
            }
        }
    }

}
