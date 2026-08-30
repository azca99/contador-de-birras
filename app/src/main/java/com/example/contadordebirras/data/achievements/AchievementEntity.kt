package com.example.contadordebirras.data.achievements

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val achievementId: String,
    val unlockedAt: Long? = null,
    val claimed: Boolean = false,
    val claimedAt: Long? = null,
    val progressAtUnlock: Int = 0,
    val points: Int = 0
)
