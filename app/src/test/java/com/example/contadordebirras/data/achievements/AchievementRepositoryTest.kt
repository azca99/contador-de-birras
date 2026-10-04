package com.example.contadordebirras.data.achievements

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

    override fun deleteAchievements(ownerUid: String, ids: List<String>) {
        data.removeAll { it.ownerUid == ownerUid && it.achievementId in ids }
        state.value = data.toList()
    }
}

class AchievementRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @org.junit.Before
    fun setUpDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @org.junit.After
    fun tearDownDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun testRepositoryIsolation_ownerA_entityA_allowed() = runTest {
        val dao = FakeAchievementDao()
        val repo = DefaultAchievementRepository(dao)

        repo.insertOrUpdate("userA", AchievementEntity("userA", "ach1", points = 10))
        val list = repo.getAllAchievements("userA").first()
        assertEquals(1, list.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRepositoryIsolation_ownerA_entityB_throws() = runTest {
        val dao = FakeAchievementDao()
        val repo = DefaultAchievementRepository(dao)

        repo.insertOrUpdate("userA", AchievementEntity("userB", "ach1", points = 10))
    }
    
    @Test
    fun testRepositoryIsolation_batchA_pure_allowed() = runTest {
        val dao = FakeAchievementDao()
        val repo = DefaultAchievementRepository(dao)

        repo.insertAll("userA", listOf(
            AchievementEntity("userA", "ach1"),
            AchievementEntity("userA", "ach2")
        ))
        val list = repo.getAllAchievements("userA").first()
        assertEquals(2, list.size)
    }

    @Test
    fun testRepositoryIsolation_batchMixed_throws_and_no_partial_write() = runTest {
        val dao = FakeAchievementDao()
        val repo = DefaultAchievementRepository(dao)

        try {
            repo.insertAll("userA", listOf(
                AchievementEntity("userA", "ach1"),
                AchievementEntity("userB", "ach2")
            ))
            fail("Should throw exception")
        } catch (e: IllegalArgumentException) {
            // Success
        }

        val list = repo.getAllAchievements("userA").first()
        assertEquals("No debe haber escritura parcial", 0, list.size)
    }
}


