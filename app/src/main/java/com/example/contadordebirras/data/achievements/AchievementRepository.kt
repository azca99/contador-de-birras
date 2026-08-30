package com.example.contadordebirras.data.achievements

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AchievementRepository {
    fun getAllAchievements(): Flow<List<AchievementEntity>>
    suspend fun getAchievementById(id: String): AchievementEntity?
    suspend fun insertOrUpdate(achievement: AchievementEntity)
    suspend fun insertAll(achievements: List<AchievementEntity>)
}

class DefaultAchievementRepository(private val dao: AchievementDao) : AchievementRepository {
    override fun getAllAchievements(): Flow<List<AchievementEntity>> = dao.getAllAchievements()

    override suspend fun getAchievementById(id: String): AchievementEntity? = withContext(Dispatchers.IO) {
        dao.getAchievementById(id)
    }

    override suspend fun insertOrUpdate(achievement: AchievementEntity) {
        withContext(Dispatchers.IO) {
            dao.insertOrUpdate(achievement)
        }
    }

    override suspend fun insertAll(achievements: List<AchievementEntity>) {
        withContext(Dispatchers.IO) {
            dao.insertAll(achievements)
        }
    }
}
