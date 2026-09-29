package com.example.contadordebirras.data.achievements

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.flow.first

class FakeAchievementDao : AchievementDao {
    private val data = mutableListOf<AchievementEntity>()
    private val state = MutableStateFlow(data.toList())

    override fun getAllAchievements(ownerUid: String): Flow<List<AchievementEntity>> =
        state.map { list -> list.filter { it.ownerUid == ownerUid } }

    override fun getAchievementById(ownerUid: String, id: String): AchievementEntity? =
        data.find { it.ownerUid == ownerUid && it.achievementId == id }

    override fun insertOrUpdate(achievement: AchievementEntity) {
        data.removeAll { it.ownerUid == achievement.ownerUid && it.achievementId == achievement.achievementId }
        data.add(achievement)
        state.value = data.toList()
    }

    override fun insertAll(achievements: List<AchievementEntity>) {
        achievements.forEach { insertOrUpdate(it) }
    }
}

class AchievementIsolationTest {

    @Test
    fun testRepositoryIsolation_A_cannot_read_B() = runTest {
        val dao = FakeAchievementDao()
        val repo = DefaultAchievementRepository(dao)

        repo.insertOrUpdate("userA", AchievementEntity("userA", "ach1", points = 10))
        repo.insertOrUpdate("userB", AchievementEntity("userB", "ach1", points = 20))

        val achievementsA = repo.getAllAchievements("userA").first()
        assertEquals(1, achievementsA.size)
        assertEquals("userA", achievementsA[0].ownerUid)
        assertEquals(10, achievementsA[0].points)

        val singleB = repo.getAchievementById("userA", "ach1")
        assertEquals(10, singleB?.points)
        
        val missingB = repo.getAchievementById("userA", "ach_not_exist")
        assertNull(missingB)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRepositoryIsolation_writeMismatchedOwner_throwsException() = runTest {
        val dao = FakeAchievementDao()
        val repo = DefaultAchievementRepository(dao)

        repo.insertOrUpdate("userA", AchievementEntity("userB", "ach1", points = 10))
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testRepositoryIsolation_insertAllMismatchedOwner_throwsException() = runTest {
        val dao = FakeAchievementDao()
        val repo = DefaultAchievementRepository(dao)

        repo.insertAll("userA", listOf(AchievementEntity("userB", "ach1", points = 10)))
    }
}
