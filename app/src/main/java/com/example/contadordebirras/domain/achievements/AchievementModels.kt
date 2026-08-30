package com.example.contadordebirras.domain.achievements

enum class AchievementDifficulty(val points: Int) {
    COMUN(5),
    POCO_COMUN(10),
    RARO(20),
    EPICO(35),
    LEGENDARIO(50),
    RESPONSABLE(15)
}

enum class AchievementState {
    LOCKED,
    IN_PROGRESS,
    UNLOCKED,
    CLAIMED
}

data class UserLevel(
    val level: Int,
    val name: String,
    val requiredPoints: Int
)

data class AchievementDefinition(
    val id: String,
    val category: String,
    val name: String,
    val description: String,
    val difficulty: AchievementDifficulty,
    val points: Int,
    val iconKey: String,
    val isHidden: Boolean,
    val target: Int
)

data class AchievementProgress(
    val id: String,
    val currentProgress: Int,
    val target: Int,
    val isUnlocked: Boolean,
    val unlockedAt: Long?,
    val isClaimed: Boolean
)

data class AchievementUiModel(
    val id: String,
    val category: String,
    val name: String,
    val description: String,
    val difficulty: AchievementDifficulty,
    val points: Int,
    val iconKey: String,
    val currentProgress: Int,
    val target: Int,
    val progressPercent: Float,
    val state: AchievementState,
    val isHidden: Boolean
)
