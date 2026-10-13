package com.example.contadordebirras.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.domain.BeerType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BeerDaoMutationTest {

    private lateinit var beerDao: BeerDao
    private lateinit var db: BeerDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, BeerDatabase::class.java
        ).allowMainThreadQueries().build()
        beerDao = db.beerDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testUpdateBeerLocalMutation_modifiesOnlyNonDeletedOfCorrectOwner() = runBlocking {
        val beerA = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = null,
            ownerUid = "userA"
        )
        val idA = beerDao.insertBeer(beerA).toInt()

        val beerB = BeerEntity(
            type = BeerType.LATA,
            timestamp = 2000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = null,
            ownerUid = "userB"
        )
        val idB = beerDao.insertBeer(beerB).toInt()

        val deletedBeerA = BeerEntity(
            type = BeerType.BOTELLIN,
            timestamp = 3000L,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = null,
            ownerUid = "userA"
        )
        val idDeletedA = beerDao.insertBeer(deletedBeerA).toInt()

        // 1. Wrong owner targeting userA's beer
        val rowsWrongOwner = beerDao.updateBeerLocalMutation(
            id = idA,
            type = BeerType.JARRA,
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            photoUri = null,
            comment = "Hack",
            locationName = "Madrid",
            remotePhotoUrl = null,
            updatedAt = 5000L,
            photoSource = null,
            ownerUid = "userB",
            baseRevision = 2L,
            dirtyFields = "COMMENT,TYPE",
            lastMutationId = "mut-hack"
        )
        assertEquals(0, rowsWrongOwner)

        // 2. Targeting already-deleted beer of userA
        val rowsDeleted = beerDao.updateBeerLocalMutation(
            id = idDeletedA,
            type = BeerType.JARRA,
            timestamp = 3000L,
            latitude = null,
            longitude = null,
            photoUri = null,
            comment = "Cannot revive",
            locationName = null,
            remotePhotoUrl = null,
            updatedAt = 5000L,
            photoSource = null,
            ownerUid = "userA",
            baseRevision = 1L,
            dirtyFields = "COMMENT",
            lastMutationId = "mut-revive"
        )
        assertEquals(0, rowsDeleted)

        // 3. Valid update of userA's active beer
        val rowsValid = beerDao.updateBeerLocalMutation(
            id = idA,
            type = BeerType.JARRA,
            timestamp = 1000L,
            latitude = 40.0,
            longitude = -3.0,
            photoUri = null,
            comment = "Valid edit",
            locationName = "Madrid",
            remotePhotoUrl = null,
            updatedAt = 6000L,
            photoSource = null,
            ownerUid = "userA",
            baseRevision = 2L,
            dirtyFields = "COMMENT,LOCATION,TYPE",
            lastMutationId = "mut-valid-1"
        )
        assertEquals(1, rowsValid)

        val updated = beerDao.getBeerById(idA, "userA")!!
        assertEquals(BeerType.JARRA, updated.type)
        assertEquals(SyncStatus.PENDING, updated.syncStatus)
        assertEquals(2L, updated.baseRevision)
        assertEquals("COMMENT,LOCATION,TYPE", updated.dirtyFields)
        assertEquals("mut-valid-1", updated.lastMutationId)
        assertEquals(6000L, updated.updatedAt)
        assertNull(updated.deletedAt)
    }

    @Test
    fun testUpdateBeerLocationLocalMutation_modifiesOnlyNonDeletedOfCorrectOwner() = runBlocking {
        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = null,
            ownerUid = "userA"
        )
        val id = beerDao.insertBeer(beer).toInt()

        // Wrong owner
        val rowsWrongOwner = beerDao.updateBeerLocationLocalMutation(
            id = id,
            latitude = 41.0,
            longitude = -2.0,
            locationName = "Soria",
            updatedAt = 7000L,
            ownerUid = "userB",
            baseRevision = 4L,
            dirtyFields = "LOCATION",
            lastMutationId = "mut-loc-wrong"
        )
        assertEquals(0, rowsWrongOwner)

        // Valid owner
        val rowsValid = beerDao.updateBeerLocationLocalMutation(
            id = id,
            latitude = 41.0,
            longitude = -2.0,
            locationName = "Soria",
            updatedAt = 7000L,
            ownerUid = "userA",
            baseRevision = 4L,
            dirtyFields = "LOCATION",
            lastMutationId = "mut-loc-valid"
        )
        assertEquals(1, rowsValid)

        val updated = beerDao.getBeerById(id, "userA")!!
        assertEquals(41.0, updated.latitude!!, 0.0001)
        assertEquals(-2.0, updated.longitude!!, 0.0001)
        assertEquals("Soria", updated.locationName)
        assertEquals(SyncStatus.PENDING, updated.syncStatus)
        assertEquals(4L, updated.baseRevision)
        assertEquals("LOCATION", updated.dirtyFields)
        assertEquals("mut-loc-valid", updated.lastMutationId)
        assertEquals(7000L, updated.updatedAt)
        assertNull(updated.deletedAt)
    }

    @Test
    fun testSoftDeleteBeerLocalMutation_setsDeletedAndClearsDirtyFields() = runBlocking {
        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 3L,
            dirtyFields = "COMMENT,LOCATION",
            lastMutationId = "mut-edit",
            ownerUid = "userA"
        )
        val id = beerDao.insertBeer(beer).toInt()

        // Attempt soft delete with wrong owner
        val rowsWrongOwner = beerDao.softDeleteBeerLocalMutation(
            id = id,
            updatedAt = 8000L,
            deletedAt = 8000L,
            ownerUid = "userB",
            baseRevision = 3L,
            lastMutationId = "mut-del-wrong"
        )
        assertEquals(0, rowsWrongOwner)

        // Soft delete with correct owner
        val rowsValid = beerDao.softDeleteBeerLocalMutation(
            id = id,
            updatedAt = 8000L,
            deletedAt = 8000L,
            ownerUid = "userA",
            baseRevision = 3L,
            lastMutationId = "mut-del-valid"
        )
        assertEquals(1, rowsValid)

        // Query pending beers (which includes DELETED)
        val pendingList = beerDao.getPendingSyncBeers("userA")
        val deletedEntity = pendingList.find { it.id == id }!!
        assertEquals(SyncStatus.DELETED, deletedEntity.syncStatus)
        assertEquals(8000L, deletedEntity.updatedAt)
        assertEquals(8000L, deletedEntity.deletedAt)
        assertEquals(3L, deletedEntity.baseRevision)
        assertNull(deletedEntity.dirtyFields)
        assertEquals("mut-del-valid", deletedEntity.lastMutationId)

        // Attempting to delete again returns 0 (idempotent / already DELETED)
        val rowsRepeat = beerDao.softDeleteBeerLocalMutation(
            id = id,
            updatedAt = 9000L,
            deletedAt = 9000L,
            ownerUid = "userA",
            baseRevision = 3L,
            lastMutationId = "mut-del-repeat"
        )
        assertEquals(0, rowsRepeat)
    }

    @Test
    fun testMarkAsSyncedIfMutationMatches_successAndStale() = runBlocking {
        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 3L,
            dirtyFields = "COMMENT,LOCATION",
            lastMutationId = "M2",
            ownerUid = "userA"
        )
        val id = beerDao.insertBeer(beer).toInt()

        // 1. Stale ACK with expectedMutationId = "M1" should fail and change nothing
        val staleRows = beerDao.markAsSyncedIfMutationMatches(id, "https://storage/pic.jpg", "userA", "M1")
        assertEquals(0, staleRows)

        val stillPending = beerDao.getBeerById(id, "userA")!!
        assertEquals(SyncStatus.PENDING, stillPending.syncStatus)
        assertEquals(3L, stillPending.baseRevision)
        assertEquals("COMMENT,LOCATION", stillPending.dirtyFields)
        assertEquals("M2", stillPending.lastMutationId)
        assertNull(stillPending.deletedAt)

        // 2. Matching ACK with expectedMutationId = "M2" should succeed and reset metadata
        val matchedRows = beerDao.markAsSyncedIfMutationMatches(id, "https://storage/pic.jpg", "userA", "M2")
        assertEquals(1, matchedRows)

        val synced = beerDao.getBeerById(id, "userA")!!
        assertEquals(SyncStatus.SYNCED, synced.syncStatus)
        assertEquals("https://storage/pic.jpg", synced.remotePhotoUrl)
        assertEquals(6L, synced.baseRevision)
        assertNull(synced.dirtyFields)
        assertNull(synced.lastMutationId)
        assertNull(synced.deletedAt)
    }

    @Test
    fun testMarkAsSyncedIfMutationMatches_legacyNullMutationId() = runBlocking {
        val beer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = null,
            lastMutationId = null,
            ownerUid = "userA"
        )
        val id = beerDao.insertBeer(beer).toInt()

        // 1. Legacy PENDING with null lastMutationId and null expectedMutationId succeeds
        val matchedRows = beerDao.markAsSyncedIfMutationMatches(id, "https://storage/pic.jpg", "userA", null)
        assertEquals(1, matchedRows)

        val synced = beerDao.getBeerById(id, "userA")!!
        assertEquals(SyncStatus.SYNCED, synced.syncStatus)
        assertNull(synced.lastMutationId)
    }

    @Test
    fun testApplyRemoteUpdateIfSynced_onlyUpdatesSyncedRow() = runBlocking {
        val syncedBeer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = null,
            lastMutationId = null,
            ownerUid = "userA"
        )
        val idSynced = beerDao.insertBeer(syncedBeer).toInt()

        val pendingBeer = BeerEntity(
            type = BeerType.LATA,
            timestamp = 2000L,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = "COMMENT",
            lastMutationId = "M_LOCAL",
            comment = "Local edit",
            ownerUid = "userA"
        )
        val idPending = beerDao.insertBeer(pendingBeer).toInt()

        // Attempt remote update on PENDING row -> 0 rows affected, does not overwrite local changes
        val rowsPending = beerDao.applyRemoteUpdateIfSynced(
            id = idPending,
            type = BeerType.JARRA,
            timestamp = 3000L,
            latitude = 10.0,
            longitude = 10.0,
            photoUri = null,
            comment = "Remote edit",
            locationName = "Cloud",
            remotePhotoUrl = null,
            updatedAt = 9999L,
            photoSource = null,
            ownerUid = "userA"
        )
        assertEquals(0, rowsPending)

        val checkPending = beerDao.getBeerById(idPending, "userA")!!
        assertEquals(SyncStatus.PENDING, checkPending.syncStatus)
        assertEquals("Local edit", checkPending.comment)
        assertEquals("COMMENT", checkPending.dirtyFields)
        assertEquals("M_LOCAL", checkPending.lastMutationId)

        // Attempt remote update on SYNCED row -> 1 row affected
        val rowsSynced = beerDao.applyRemoteUpdateIfSynced(
            id = idSynced,
            type = BeerType.JARRA,
            timestamp = 3000L,
            latitude = 10.0,
            longitude = 10.0,
            photoUri = null,
            comment = "Remote edit",
            locationName = "Cloud",
            remotePhotoUrl = null,
            updatedAt = 9999L,
            photoSource = null,
            ownerUid = "userA"
        )
        assertEquals(1, rowsSynced)

        val checkSynced = beerDao.getBeerById(idSynced, "userA")!!
        assertEquals(BeerType.JARRA, checkSynced.type)
        assertEquals("Remote edit", checkSynced.comment)
        assertEquals(SyncStatus.SYNCED, checkSynced.syncStatus)
    }

    @Test
    fun testHardDeleteSyncedBySyncId_onlyDeletesSyncedRow() = runBlocking {
        val pendingBeer = BeerEntity(
            syncId = "sync_pending",
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.PENDING,
            ownerUid = "userA"
        )
        beerDao.insertBeer(pendingBeer)

        val syncedBeer = BeerEntity(
            syncId = "sync_synced",
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            ownerUid = "userA"
        )
        beerDao.insertBeer(syncedBeer)

        // Reconciliation deletion should NOT delete PENDING row
        val rowsPending = beerDao.hardDeleteSyncedBySyncId("sync_pending", "userA")
        assertEquals(0, rowsPending)
        assertNotNull(beerDao.getBeerBySyncId("sync_pending", "userA"))

        // Reconciliation deletion SHOULD delete SYNCED row
        val rowsSynced = beerDao.hardDeleteSyncedBySyncId("sync_synced", "userA")
        assertEquals(1, rowsSynced)
        assertNull(beerDao.getBeerBySyncId("sync_synced", "userA"))
    }

    @Test
    fun testHardDeleteDeletedBySyncId_onlyDeletesDeletedRowAndRespectsOwner() = runBlocking {
        val deletedBeerA = BeerEntity(
            syncId = "sync_del_A",
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.DELETED,
            ownerUid = "userA"
        )
        beerDao.insertBeer(deletedBeerA)

        val pendingBeerA = BeerEntity(
            syncId = "sync_pend_A",
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.PENDING,
            ownerUid = "userA"
        )
        beerDao.insertBeer(pendingBeerA)

        val syncedBeerA = BeerEntity(
            syncId = "sync_sync_A",
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            ownerUid = "userA"
        )
        beerDao.insertBeer(syncedBeerA)

        // Wrong owner cannot delete userA's DELETED beer
        val rowsWrongOwner = beerDao.hardDeleteDeletedBySyncId("sync_del_A", "userB")
        assertEquals(0, rowsWrongOwner)

        // Cannot delete PENDING or SYNCED
        assertEquals(0, beerDao.hardDeleteDeletedBySyncId("sync_pend_A", "userA"))
        assertEquals(0, beerDao.hardDeleteDeletedBySyncId("sync_sync_A", "userA"))

        // Successfully deletes DELETED beer of userA
        val rowsDeleted = beerDao.hardDeleteDeletedBySyncId("sync_del_A", "userA")
        assertEquals(1, rowsDeleted)
        assertNull(beerDao.getBeerBySyncId("sync_del_A", "userA"))
    }
}
