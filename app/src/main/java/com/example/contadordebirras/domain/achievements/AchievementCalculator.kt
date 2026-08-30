package com.example.contadordebirras.domain.achievements

import com.example.contadordebirras.data.achievements.AchievementEntity

class AchievementCalculator {

    fun calculateLevels(points: Int): UserLevel {
        val levels = listOf(
            UserLevel(1, "Recién servido", 0),
            UserLevel(2, "Espuma inicial", 50),
            UserLevel(3, "Cliente habitual", 150),
            UserLevel(4, "Cañero local", 300),
            UserLevel(5, "Coleccionista de barra", 500),
            UserLevel(6, "Catador de formatos", 750),
            UserLevel(7, "Cronista cervecero", 1000),
            UserLevel(8, "Señor de la espuma", 1500),
            UserLevel(9, "Leyenda de barra", 2250),
            UserLevel(10, "Mito cervecero", 3000)
        )
        var current = levels.first()
        for (lvl in levels) {
            if (points >= lvl.requiredPoints) {
                current = lvl
            } else {
                break
            }
        }
        return current
    }

    fun getNextLevelRequiredPoints(points: Int): Int {
        val levels = listOf(0, 50, 150, 300, 500, 750, 1000, 1500, 2250, 3000)
        return levels.firstOrNull { it > points } ?: 3000
    }

    fun calculateProgress(
        input: AchievementStatsInput,
        savedEntities: List<AchievementEntity>
    ): List<AchievementProgress> {
        return AchievementCatalog.achievements.map { def ->
            val saved = savedEntities.find { it.achievementId == def.id }
            
            // If it's already unlocked in DB, return that state
            if (saved?.unlockedAt != null) {
                return@map AchievementProgress(
                    id = def.id,
                    currentProgress = def.target,
                    target = def.target,
                    isUnlocked = true,
                    unlockedAt = saved.unlockedAt,
                    isClaimed = saved.claimed
                )
            }

            // Calculate current progress dynamically
            val currentProgress = evaluateProgress(def, input)
            val isUnlocked = currentProgress >= def.target
            
            AchievementProgress(
                id = def.id,
                currentProgress = currentProgress.coerceAtMost(def.target),
                target = def.target,
                isUnlocked = isUnlocked,
                unlockedAt = if (isUnlocked) System.currentTimeMillis() else null,
                isClaimed = false
            )
        }
    }

    private fun evaluateProgress(def: AchievementDefinition, input: AchievementStatsInput): Int {
        // Fallback progress evaluation based on ID prefixes
        return when {
            def.id.startsWith("GEN_") -> input.totalBeers
            def.id.startsWith("CAN_") -> input.countByType[com.example.contadordebirras.domain.BeerType.CANA] ?: 0
            def.id.startsWith("LAT_") -> input.countByType[com.example.contadordebirras.domain.BeerType.LATA] ?: 0
            def.id.startsWith("BOT_") -> input.countByType[com.example.contadordebirras.domain.BeerType.BOTELLIN] ?: 0
            def.id.startsWith("COP_") -> input.countByType[com.example.contadordebirras.domain.BeerType.COPA] ?: 0
            def.id.startsWith("JAR_") -> input.countByType[com.example.contadordebirras.domain.BeerType.JARRA] ?: 0
            def.id.startsWith("PIN_") -> input.countByType[com.example.contadordebirras.domain.BeerType.PINTA] ?: 0
            def.id.startsWith("LIT_") -> input.countByType[com.example.contadordebirras.domain.BeerType.LITRO] ?: 0
            def.id.startsWith("FOT_") -> input.photosAdded
            def.id.startsWith("SOC_") -> input.friendsAdded
            def.id.startsWith("UBI_") -> input.distinctLocations
            def.id.startsWith("TMP_") -> input.recordsByDay.size
            def.id.startsWith("EST_") -> input.statsUses
            def.category == "Colección" -> 0 // Requires complex evaluation of other achievements
            else -> 0 // Default to 0 for responsible, hidden, favorite, variety
        }
    }

    fun buildUiModels(
        progresses: List<AchievementProgress>
    ): List<AchievementUiModel> {
        return AchievementCatalog.achievements.map { def ->
            val prog = progresses.find { it.id == def.id } ?: AchievementProgress(def.id, 0, def.target, false, null, false)
            val state = when {
                prog.isClaimed -> AchievementState.CLAIMED
                prog.isUnlocked -> AchievementState.UNLOCKED
                prog.currentProgress > 0 -> AchievementState.IN_PROGRESS
                else -> AchievementState.LOCKED
            }
            AchievementUiModel(
                id = def.id,
                category = def.category,
                name = def.name,
                description = def.description,
                difficulty = def.difficulty,
                points = def.points,
                iconKey = def.iconKey,
                currentProgress = prog.currentProgress,
                target = def.target,
                progressPercent = if (def.target > 0) {
                    val p = prog.currentProgress.toFloat() / def.target
                    if (p.isNaN()) 0f else p.coerceIn(0f, 1f)
                } else 0f,
                state = state,
                isHidden = def.isHidden
            )
        }
    }
}
