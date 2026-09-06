package com.example.contadordebirras.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface BeerDao {
    @Insert
    fun insertBeer(beer: BeerEntity): Long

    @Delete
    fun deleteBeer(beer: BeerEntity): Int

    @androidx.room.Update
    fun updateBeer(beer: BeerEntity): Int

    @Query("UPDATE beers SET syncStatus = 'DELETED' WHERE id = :id")
    fun softDeleteBeer(id: Int): Int

    @Query("UPDATE beers SET syncStatus = 'SYNCED', remotePhotoUrl = :remoteUrl WHERE id = :id AND syncStatus != 'DELETED'")
    fun markAsSynced(id: Int, remoteUrl: String?): Int

    @Query("UPDATE beers SET syncStatus = 'DELETED' WHERE id = (SELECT id FROM beers WHERE syncStatus != 'DELETED' ORDER BY timestamp DESC LIMIT 1)")
    fun deleteLastBeer(): Int

    @Query("SELECT * FROM beers WHERE syncStatus != 'DELETED' ORDER BY timestamp DESC")
    fun getAllBeers(): Flow<List<BeerEntity>>

    @Query("SELECT COUNT(*) FROM beers WHERE syncStatus != 'DELETED'")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT * FROM beers WHERE syncStatus != 'DELETED' ORDER BY timestamp DESC LIMIT 1")
    fun getLastBeer(): Flow<BeerEntity?>

    @Query("SELECT * FROM beers WHERE syncStatus = 'PENDING' OR syncStatus = 'DELETED'")
    fun getPendingSyncBeers(): List<BeerEntity>
    @Query("SELECT * FROM beers WHERE syncId = :syncId LIMIT 1")
    fun getBeerBySyncId(syncId: String): BeerEntity?

    @Query("DELETE FROM beers WHERE syncId = :syncId")
    fun hardDeleteBySyncId(syncId: String): Int
    @Query("SELECT syncId FROM beers WHERE syncStatus = 'SYNCED'")
    fun getAllSyncedIds(): List<String>
}
