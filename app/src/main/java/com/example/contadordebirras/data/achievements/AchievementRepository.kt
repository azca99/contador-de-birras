package com.example.contadordebirras.data.achievements

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AchievementRepository {
    fun getAllAchievements(ownerUid: String): Flow<List<AchievementEntity>>
    suspend fun getAchievementById(ownerUid: String, id: String): AchievementEntity?
    suspend fun insertOrUpdate(ownerUid: String, achievement: AchievementEntity)
    suspend fun insertAll(ownerUid: String, achievements: List<AchievementEntity>)
    suspend fun deleteAchievements(ownerUid: String, ids: List<String>)
}

class DefaultAchievementRepository(private val dao: AchievementDao) : AchievementRepository {
    override fun getAllAchievements(ownerUid: String): Flow<List<AchievementEntity>> = dao.getAllAchievements(ownerUid)

    override suspend fun getAchievementById(ownerUid: String, id: String): AchievementEntity? = withContext(Dispatchers.IO) {
        dao.getAchievementById(ownerUid, id)
    }

    override suspend fun insertOrUpdate(ownerUid: String, achievement: AchievementEntity) {
        require(achievement.ownerUid == ownerUid) { "Mismatch in ownerUid" }
        withContext(Dispatchers.IO) {
            dao.insertOrUpdate(achievement)
        }
    }

    override suspend fun insertAll(ownerUid: String, achievements: List<AchievementEntity>) {
        require(achievements.all { it.ownerUid == ownerUid }) { "Mismatch in ownerUid in list" }
        withContext(Dispatchers.IO) {
            dao.insertAll(achievements)
        }
    }

    override suspend fun deleteAchievements(ownerUid: String, ids: List<String>) {
        withContext(Dispatchers.IO) {
            dao.deleteAchievements(ownerUid, ids)
        }
    }
}
