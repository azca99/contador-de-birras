package com.example.contadordebirras.data.migration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.data.AchievementEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoRecoveryHelperTest {

    private lateinit var db: BeerDatabase
    private lateinit var dao: BeerDao

    private val legacyUid = "legacy_unassigned"
    private val activeUid = "auth_user_123"
    private val otherUid = "other_user_456"

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BeerDatabase::class.java
        ).build()
        dao = db.beerDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private suspend fun insertBeer(
        uid: String,
        syncId: String,
        hasPhoto: Boolean,
        type: BeerType = BeerType.LATA,
        timestamp: Long = 1000L,
        status: SyncStatus = SyncStatus.SYNCED
    ) {
        val beer = BeerEntity(
            type = type,
            timestamp = timestamp,
            photoUri = if (hasPhoto) "file://photo_$syncId.jpg" else null,
            photoSource = if (hasPhoto) "CAMERA" else null,
            syncStatus = status,
            ownerUid = uid,
            updatedAt = 100L
        ).apply { this.syncId = syncId }
        dao.insertBeer(beer)
    }

    @Test
    fun testRecoverySuccessAndIdempotency() = runBlocking {
        // Setup 5 correct matches
        val syncIds = (1..5).map { UUID.randomUUID().toString() }
        for (syncId in syncIds) {
            insertBeer(legacyUid, syncId, hasPhoto = true)
            insertBeer(activeUid, syncId, hasPhoto = false)
        }
        
        // Add one achievement
        db.achievementDao().insertAchievement(AchievementEntity("FOT_001", "FOT", legacyUid, 10, true, 1000L, 1000L))

        // 1. Run Recovery
        val supportDb = db.openHelper.writableDatabase
        val success = PhotoRecoveryHelper.recoverPhotos(supportDb, activeUid)
        
        assertTrue("Recovery should succeed", success)
        
        val activeBeers = dao.getAllBeersForSync(activeUid)
        assertEquals(5, activeBeers.size)
        assertTrue(activeBeers.all { it.photoUri != null && it.photoUri!!.startsWith("file://photo_") })
        assertTrue(activeBeers.all { it.syncStatus == SyncStatus.SYNCED }) // syncStatus unchanged
        assertTrue(activeBeers.all { it.updatedAt == 100L }) // updatedAt unchanged

        val legacyBeers = dao.getAllBeersForSync(legacyUid)
        assertTrue(legacyBeers.all { it.photoUri != null }) // Legacy unchanged
        
        val achievements = db.achievementDao().getAchievements(legacyUid)
        assertEquals(1, achievements.size)

        // 2. Second execution (Idempotency)
        val success2 = PhotoRecoveryHelper.recoverPhotos(supportDb, activeUid)
        assertTrue("Second execution should succeed (idempotent)", success2)
    }

    @Test
    fun testRecoveryRollbackWhenNotExactlyFive() = runBlocking {
        // Setup only 4 correct matches
        val syncIds = (1..4).map { UUID.randomUUID().toString() }
        for (syncId in syncIds) {
            insertBeer(legacyUid, syncId, hasPhoto = true)
            insertBeer(activeUid, syncId, hasPhoto = false)
        }

        val supportDb = db.openHelper.writableDatabase
        val success = PhotoRecoveryHelper.recoverPhotos(supportDb, activeUid)
        
        assertFalse("Recovery should fail because not exactly 5 or 0 rows were updated", success)
        
        // Verify rollback: active beers should STILL have null photos
        val activeBeers = dao.getAllBeersForSync(activeUid)
        assertTrue(activeBeers.all { it.photoUri == null })
    }

    @Test
    fun testIgnoredConditions() = runBlocking {
        // We will insert 5 correct matches so the transaction succeeds.
        val correctSyncIds = (1..5).map { UUID.randomUUID().toString() }
        for (syncId in correctSyncIds) {
            insertBeer(legacyUid, syncId, hasPhoto = true)
            insertBeer(activeUid, syncId, hasPhoto = false)
        }
        
        // Missing match: Legacy has it, active doesn't.
        insertBeer(legacyUid, "missing_match", hasPhoto = true)
        
        // Ambiguous match: Legacy has 1, active has 2.
        insertBeer(legacyUid, "ambiguous", hasPhoto = true)
        insertBeer(activeUid, "ambiguous", hasPhoto = false)
        insertBeer(activeUid, "ambiguous", hasPhoto = false)
        
        // Incorrect owner
        insertBeer(legacyUid, "wrong_owner", hasPhoto = true)
        insertBeer(otherUid, "wrong_owner", hasPhoto = false)
        
        // Target already has photo
        insertBeer(legacyUid, "has_photo", hasPhoto = true)
        insertBeer(activeUid, "has_photo", hasPhoto = true) // Already has photo
        
        // Target deleted
        insertBeer(legacyUid, "deleted", hasPhoto = true)
        insertBeer(activeUid, "deleted", hasPhoto = false, status = SyncStatus.DELETED)

        val supportDb = db.openHelper.writableDatabase
        val success = PhotoRecoveryHelper.recoverPhotos(supportDb, activeUid)
        
        assertTrue("Recovery should succeed for the 5 correct matches", success)
        
        // Validate ignored conditions were NOT updated
        val activeBeers = dao.getAllBeersForSync(activeUid)
        
        assertEquals(0, activeBeers.count { it.syncId == "ambiguous" && it.photoUri != null })
        
        val otherBeers = dao.getAllBeersForSync(otherUid)
        assertTrue(otherBeers.all { it.photoUri == null })
        
        val deletedBeer = activeBeers.find { it.syncId == "deleted" }
        assertNull(deletedBeer?.photoUri)
    }
}
