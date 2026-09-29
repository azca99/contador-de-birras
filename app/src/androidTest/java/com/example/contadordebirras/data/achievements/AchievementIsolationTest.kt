package com.example.contadordebirras.data.achievements

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.contadordebirras.data.BeerDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AchievementIsolationTest {

    private lateinit var database: BeerDatabase
    private lateinit var dao: AchievementDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java).build()
        dao = database.achievementDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun testIsolation_A_and_B_have_same_achievementId_without_conflicts() = runBlocking {
        // 1. A y B pueden tener simultáneamente el mismo achievementId
        dao.insertOrUpdate(AchievementEntity("userA", "ach1", points = 10))
        dao.insertOrUpdate(AchievementEntity("userB", "ach1", points = 20))
        
        val aAch = dao.getAchievementById("userA", "ach1")
        val bAch = dao.getAchievementById("userB", "ach1")
        
        assertEquals(10, aAch?.points)
        assertEquals(20, bAch?.points)
    }

    @Test
    fun testIsolation_getAllAchievements_respects_owner() = runBlocking {
        dao.insertOrUpdate(AchievementEntity("userA", "ach1", points = 10))
        dao.insertOrUpdate(AchievementEntity("userB", "ach2", points = 20))
        
        // 2. getAllAchievements(A) no devuelve B
        val aList = dao.getAllAchievements("userA").first()
        assertEquals(1, aList.size)
        assertEquals("ach1", aList[0].achievementId)

        // 3. getAllAchievements(B) no devuelve A
        val bList = dao.getAllAchievements("userB").first()
        assertEquals(1, bList.size)
        assertEquals("ach2", bList[0].achievementId)
    }

    @Test
    fun testIsolation_guest_and_legacy_separation() = runBlocking {
        dao.insertOrUpdate(AchievementEntity("userA", "ach1", points = 10))
        dao.insertOrUpdate(AchievementEntity("guest_local", "ach2", points = 20))
        dao.insertOrUpdate(AchievementEntity("legacy_unassigned", "ach3", points = 30))

        val aList = dao.getAllAchievements("userA").first()
        val guestList = dao.getAllAchievements("guest_local").first()
        val legacyList = dao.getAllAchievements("legacy_unassigned").first()

        // 4, 5, 6. Separación de guest, legacy y userA
        assertEquals(1, aList.size)
        assertEquals("ach1", aList[0].achievementId)
        
        assertEquals(1, guestList.size)
        assertEquals("ach2", guestList[0].achievementId)

        assertEquals(1, legacyList.size)
        assertEquals("ach3", legacyList[0].achievementId)
    }

    @Test
    fun testIsolation_getAchievementById_isolation() = runBlocking {
        dao.insertOrUpdate(AchievementEntity("userB", "achX", points = 10))
        
        // 7. getAchievementById(A, X) nunca devuelve B+X
        val aAch = dao.getAchievementById("userA", "achX")
        assertNull(aAch)
    }

    @Test
    fun testIsolation_replace_keeps_B_intact() = runBlocking {
        dao.insertOrUpdate(AchievementEntity("userA", "achX", points = 10))
        dao.insertOrUpdate(AchievementEntity("userB", "achX", points = 20))

        // 8. REPLACE de A+X modifica únicamente A+X y conserva B+X
        dao.insertOrUpdate(AchievementEntity("userA", "achX", points = 15)) // update A

        val aAch = dao.getAchievementById("userA", "achX")
        val bAch = dao.getAchievementById("userB", "achX")

        assertEquals(15, aAch?.points)
        assertEquals(20, bAch?.points) // B conserved
    }

    @Test
    fun testIsolation_insertAll_preserves_ownerUid() = runBlocking {
        // 9. insertAll de varias entidades A conserva ownerUid A
        val list = listOf(
            AchievementEntity("userA", "ach1", points = 10),
            AchievementEntity("userA", "ach2", points = 20)
        )
        dao.insertAll(list)

        val aList = dao.getAllAchievements("userA").first()
        assertEquals(2, aList.size)
        assertEquals("userA", aList[0].ownerUid)
        assertEquals("userA", aList[1].ownerUid)
    }

    @Test
    fun testIsolation_claimed_does_not_affect_B() = runBlocking {
        dao.insertOrUpdate(AchievementEntity("userA", "achX", claimed = false))
        dao.insertOrUpdate(AchievementEntity("userB", "achX", claimed = false))

        // 10. claimed=true / claimedAt de A no modifican la fila equivalente B
        dao.insertOrUpdate(AchievementEntity("userA", "achX", claimed = true, claimedAt = 1000L))

        val aAch = dao.getAchievementById("userA", "achX")
        val bAch = dao.getAchievementById("userB", "achX")

        assertEquals(true, aAch?.claimed)
        assertEquals(false, bAch?.claimed)
        assertNull(bAch?.claimedAt)
    }
}
