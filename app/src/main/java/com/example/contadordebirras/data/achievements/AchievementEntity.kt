package com.example.contadordebirras.data.achievements

import androidx.room.Entity

@Entity(
    tableName = "achievements",
    primaryKeys = ["ownerUid", "achievementId"]
)
data class AchievementEntity(
    val ownerUid: String,
    val achievementId: String,
    val unlockedAt: Long? = null,
    val claimed: Boolean = false,
    val claimedAt: Long? = null,
    val progressAtUnlock: Int = 0,
    val points: Int = 0
)
