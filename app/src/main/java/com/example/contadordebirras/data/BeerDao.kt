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
            type = :type, timestamp = :timestamp, latitude = :latitude, longitude = :longitude, 
            photoUri = :photoUri, comment = :comment, locationName = :locationName, 
            syncStatus = :syncStatus, remotePhotoUrl = :remotePhotoUrl, updatedAt = :updatedAt, 
            photoSource = :photoSource 
        WHERE id = :id AND ownerUid = :ownerUid
    """)
    fun updateBeer(
        id: Int, type: BeerType, timestamp: Long, latitude: Double?, longitude: Double?,
        photoUri: String?, comment: String?, locationName: String?, syncStatus: String,
        remotePhotoUrl: String?, updatedAt: Long, photoSource: String?, ownerUid: String
    ): Int

    @Query("UPDATE beers SET syncStatus = 'DELETED' WHERE id = :id AND ownerUid = :ownerUid")
    fun softDeleteBeer(id: Int, ownerUid: String): Int

    @Query("UPDATE beers SET syncStatus = 'SYNCED', remotePhotoUrl = :remoteUrl WHERE id = :id AND syncStatus != 'DELETED' AND ownerUid = :ownerUid")
    fun markAsSynced(id: Int, remoteUrl: String?, ownerUid: String): Int

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

    @Query("DELETE FROM beers WHERE syncId = :syncId AND ownerUid = :ownerUid")
    fun hardDeleteBySyncId(syncId: String, ownerUid: String): Int

    @Query("SELECT * FROM beers WHERE id = :id AND ownerUid = :ownerUid LIMIT 1")
    fun getBeerById(id: Int, ownerUid: String): BeerEntity?

    @Query("SELECT syncId FROM beers WHERE syncStatus = 'SYNCED' AND ownerUid = :ownerUid")
    fun getAllSyncedIds(ownerUid: String): List<String>
}
