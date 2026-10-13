package com.example.contadordebirras.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow
import com.example.contadordebirras.domain.BeerType

@Dao
interface BeerDao {
    @Insert
    fun insertBeer(beer: BeerEntity): Long

    @Query("""
        UPDATE beers SET
            type = :type, comment = :comment,
            syncStatus = 'PENDING', updatedAt = :updatedAt, baseRevision = :baseRevision,
            dirtyFields = :dirtyFields, lastMutationId = :lastMutationId, deletedAt = NULL
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus != 'DELETED'
    """)
    fun updateBeerDetailsLocalMutation(
        id: Int, type: BeerType, comment: String?,
        updatedAt: Long, ownerUid: String,
        baseRevision: Long, dirtyFields: String?, lastMutationId: String
    ): Int


    @Query("""
        UPDATE beers SET
            syncStatus = 'SYNCED',
            remotePhotoUrl = :remoteUrl,
            baseRevision = remoteRevision,
            dirtyFields = NULL,
            lastMutationId = NULL,
            deletedAt = NULL
        WHERE id = :id
          AND ownerUid = :ownerUid
          AND syncStatus = 'PENDING'
          AND (
              (lastMutationId IS NULL AND :expectedMutationId IS NULL)
              OR lastMutationId = :expectedMutationId
          )
    """)
    fun markAsSyncedIfMutationMatches(id: Int, remoteUrl: String?, ownerUid: String, expectedMutationId: String?): Int

    @Query("""
        UPDATE beers SET
            type = :type, timestamp = :timestamp, latitude = :latitude, longitude = :longitude,
            photoUri = :photoUri, comment = :comment, locationName = :locationName,
            remotePhotoUrl = :remotePhotoUrl, updatedAt = :updatedAt,
            photoSource = :photoSource
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus = 'SYNCED'
    """)
    fun applyRemoteUpdateIfSynced(
        id: Int, type: BeerType, timestamp: Long, latitude: Double?, longitude: Double?,
        photoUri: String?, comment: String?, locationName: String?,
        remotePhotoUrl: String?, updatedAt: Long, photoSource: String?, ownerUid: String
    ): Int

    @Query("""
        UPDATE beers SET 
            type = :type, timestamp = :timestamp, latitude = :latitude, longitude = :longitude, 
            photoUri = :photoUri, comment = :comment, locationName = :locationName, 
            syncStatus = 'PENDING', remotePhotoUrl = :remotePhotoUrl, updatedAt = :updatedAt, 
            photoSource = :photoSource, baseRevision = :baseRevision, dirtyFields = :dirtyFields, 
            lastMutationId = :lastMutationId, deletedAt = NULL 
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus != 'DELETED'
    """)
    fun updateBeerLocalMutation(
        id: Int, type: BeerType, timestamp: Long, latitude: Double?, longitude: Double?,
        photoUri: String?, comment: String?, locationName: String?, remotePhotoUrl: String?,
        updatedAt: Long, photoSource: String?, ownerUid: String,
        baseRevision: Long, dirtyFields: String?, lastMutationId: String
    ): Int

    @Query("""
        UPDATE beers SET 
            latitude = :latitude, longitude = :longitude, locationName = :locationName, 
            syncStatus = 'PENDING', updatedAt = :updatedAt, baseRevision = :baseRevision, 
            dirtyFields = :dirtyFields, lastMutationId = :lastMutationId, deletedAt = NULL 
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus != 'DELETED'
    """)
    fun updateBeerLocationLocalMutation(
        id: Int, latitude: Double, longitude: Double, locationName: String?,
        updatedAt: Long, ownerUid: String,
        baseRevision: Long, dirtyFields: String?, lastMutationId: String
    ): Int

    @Query("""
        UPDATE beers SET 
            syncStatus = 'DELETED', updatedAt = :updatedAt, deletedAt = :deletedAt, 
            baseRevision = :baseRevision, dirtyFields = NULL, lastMutationId = :lastMutationId 
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus != 'DELETED'
    """)
    fun softDeleteBeerLocalMutation(
        id: Int, updatedAt: Long, deletedAt: Long, ownerUid: String,
        baseRevision: Long, lastMutationId: String
    ): Int

    @Query("UPDATE beers SET syncStatus = 'DELETED' WHERE id = (SELECT id FROM beers WHERE syncStatus != 'DELETED' AND ownerUid = :ownerUid ORDER BY timestamp DESC LIMIT 1) AND ownerUid = :ownerUid")
    fun deleteLastBeer(ownerUid: String): Int

    @Query("SELECT * FROM beers WHERE syncStatus != 'DELETED' AND ownerUid = :ownerUid ORDER BY timestamp DESC")
    fun getAllBeers(ownerUid: String): Flow<List<BeerEntity>>

    @Query("SELECT COUNT(*) FROM beers WHERE syncStatus != 'DELETED' AND ownerUid = :ownerUid")
    fun getTotalCount(ownerUid: String): Flow<Int>

    @Query("SELECT * FROM beers WHERE syncStatus != 'DELETED' AND ownerUid = :ownerUid ORDER BY timestamp DESC LIMIT 1")
    fun getLastBeer(ownerUid: String): Flow<BeerEntity?>

    @Query("SELECT * FROM beers WHERE (syncStatus = 'PENDING' OR syncStatus = 'DELETED') AND ownerUid = :ownerUid")
    fun getPendingSyncBeers(ownerUid: String): List<BeerEntity>
    @Query("SELECT * FROM beers WHERE syncId = :syncId AND ownerUid = :ownerUid LIMIT 1")
    fun getBeerBySyncId(syncId: String, ownerUid: String): BeerEntity?

    @Query("DELETE FROM beers WHERE syncId = :syncId AND ownerUid = :ownerUid AND syncStatus = 'DELETED'")
    fun hardDeleteDeletedBySyncId(syncId: String, ownerUid: String): Int

    @Query("DELETE FROM beers WHERE syncId = :syncId AND ownerUid = :ownerUid AND syncStatus = 'SYNCED'")
    fun hardDeleteSyncedBySyncId(syncId: String, ownerUid: String): Int


    @Query("SELECT * FROM beers WHERE id = :id AND ownerUid = :ownerUid LIMIT 1")
    fun getBeerById(id: Int, ownerUid: String): BeerEntity?

    @Query("SELECT syncId FROM beers WHERE syncStatus = 'SYNCED' AND ownerUid = :ownerUid")
    fun getAllSyncedIds(ownerUid: String): List<String>
}
