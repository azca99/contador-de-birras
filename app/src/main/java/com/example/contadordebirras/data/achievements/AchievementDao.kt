package com.example.contadordebirras.data.achievements

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements WHERE ownerUid = :ownerUid")
    fun getAllAchievements(ownerUid: String): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements WHERE ownerUid = :ownerUid AND achievementId = :id")
    fun getAchievementById(ownerUid: String, id: String): AchievementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(achievement: AchievementEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(achievements: List<AchievementEntity>)
}
