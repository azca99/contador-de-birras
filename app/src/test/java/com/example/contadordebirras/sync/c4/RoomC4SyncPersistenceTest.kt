package com.example.contadordebirras.sync.c4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomC4SyncPersistenceTest {

    private lateinit var database: BeerDatabase
    private lateinit var beerDao: BeerDao
    private lateinit var persistence: RoomC4SyncPersistence

    private val ownerAlice = "user_alice_123"
    private val ownerBob = "user_bob_456"
    private val syncId = "beer_test_001"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        beerDao = database.beerDao()
        persistence = RoomC4SyncPersistence(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun preNetworkRegistration_createsPendingHistoryRecord() {
        val beer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.4168,
            longitude = -3.7038,
            photoUri = "content://photo/1.jpg",
            comment = "Initial",
            locationName = "Madrid",
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = ownerAlice,
            remoteRevision = -1L,
            baseRevision = -1L,
            dirtyFields = "ALL",
            lastMutationId = "mut_create_1"
        )
        beerDao.insertBeer(beer)

        val recorded = persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = ownerAlice,
            mutationId = "mut_create_1",
            dirtyFields = "ALL",
            baseRevision = -1L,
            isLocalOnlyCreation = true
        )

        assertTrue("Durable history record must be inserted successfully", recorded)
        val history = beerDao.getHistoryRecordById("mut_create_1", ownerAlice)
        assertNotNull(history)
        assertEquals(syncId, history!!.syncId)
        assertEquals(ownerAlice, history.ownerUid)
        assertEquals("mut_create_1", history.mutationId)
        assertEquals(MutationStatus.PENDING, history.status)
        assertEquals(-1L, history.baseRevision)
        assertNull(history.confirmedRevision)
    }

    @Test
    fun confirmPushFullSuccess_cleansDirtyFieldsAndAdvancesRevisions() {
        val beer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.4168,
            longitude = -3.7038,
            photoUri = "content://photo/local.jpg",
            comment = "Initial",
            locationName = "Madrid",
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = ownerAlice,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = "TYPE,COMMENT",
            lastMutationId = "mut_patch_1"
        )
        beerDao.insertBeer(beer)
        persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = ownerAlice,
            mutationId = "mut_patch_1",
            dirtyFields = "TYPE,COMMENT",
            baseRevision = 0L,
            isLocalOnlyCreation = false
        )

        val confirmed = persistence.confirmPushFullSuccess(
            syncId = syncId,
            ownerUid = ownerAlice,
            expectedMutationId = "mut_patch_1",
            newRemoteRevision = 1L,
            newRemotePhotoUrl = "https://storage.googleapis.com/test.jpg"
        )

        assertTrue(confirmed)
        val updatedBeer = beerDao.getBeerBySyncId(syncId, ownerAlice)
        assertNotNull(updatedBeer)
        assertEquals(SyncStatus.SYNCED, updatedBeer!!.syncStatus)
        assertEquals(1L, updatedBeer.remoteRevision)
        assertEquals(1L, updatedBeer.baseRevision)
        assertNull(updatedBeer.dirtyFields)
        assertNull(updatedBeer.lastMutationId)
        assertEquals("content://photo/local.jpg", updatedBeer.photoUri)
        assertEquals("https://storage.googleapis.com/test.jpg", updatedBeer.remotePhotoUrl)

        val history = beerDao.getHistoryRecordById("mut_patch_1", ownerAlice)
        assertNotNull(history)
        assertEquals(MutationStatus.CONFIRMED, history!!.status)
        assertEquals(1L, history.confirmedRevision)
    }

    @Test
    fun confirmPushFullSuccess_mismatchedMutationId_failsAndPreservesPending() {
        val beer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = null,
            longitude = null,
            photoUri = null,
            comment = "Initial",
            locationName = null,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = ownerAlice,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = "COMMENT",
            lastMutationId = "mut_patch_2"
        )
        beerDao.insertBeer(beer)

        val confirmed = persistence.confirmPushFullSuccess(
            syncId = syncId,
            ownerUid = ownerAlice,
            expectedMutationId = "mut_patch_1", // Discrepancia
            newRemoteRevision = 1L
        )

        assertFalse(confirmed)
        val currentBeer = beerDao.getBeerBySyncId(syncId, ownerAlice)
        assertNotNull(currentBeer)
        assertEquals(SyncStatus.PENDING, currentBeer!!.syncStatus)
        assertEquals("mut_patch_2", currentBeer.lastMutationId)
        assertEquals("COMMENT", currentBeer.dirtyFields)
    }

    @Test
    fun confirmPushPartialPatch_validPartition_advancesRemoteRevisionAndPreservesBaseRevisionAndPhotoUri() {
        val beer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = null,
            longitude = null,
            photoUri = "content://photo/protected_local.jpg",
            comment = "Updated Comment",
            locationName = null,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = ownerAlice,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "TYPE,COMMENT",
            lastMutationId = "mut_partial_1"
        )
        beerDao.insertBeer(beer)
        persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = ownerAlice,
            mutationId = "mut_partial_1",
            dirtyFields = "TYPE,COMMENT",
            baseRevision = 1L,
            isLocalOnlyCreation = false
        )

        val ok = persistence.confirmPushPartialPatch(
            syncId = syncId,
            ownerUid = ownerAlice,
            expectedMutationId = "mut_partial_1",
            newRemoteRevision = 2L,
            remainingDirtyFields = "COMMENT",
            confirmedFields = "TYPE"
        )

        assertTrue(ok)
        val updatedBeer = beerDao.getBeerBySyncId(syncId, ownerAlice)
        assertNotNull(updatedBeer)
        assertEquals(SyncStatus.PENDING, updatedBeer!!.syncStatus)
        assertEquals(2L, updatedBeer.remoteRevision)
        // INVARIANTE CAUSAL: baseRevision permanece congelada en 1L
        assertEquals(1L, updatedBeer.baseRevision)
        assertEquals("COMMENT", updatedBeer.dirtyFields)
        assertEquals("content://photo/protected_local.jpg", updatedBeer.photoUri)

        val history = beerDao.getHistoryRecordById("mut_partial_1", ownerAlice)
        assertNotNull(history)
        assertEquals(MutationStatus.PARTIALLY_CONFIRMED, history!!.status)
        assertEquals(2L, history.confirmedRevision)
    }

    @Test
    fun confirmPushPartialPatch_invalidPartition_failsAndModifiesNothing() {
        val beer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = null,
            longitude = null,
            photoUri = null,
            comment = "Comment",
            locationName = null,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = ownerAlice,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "TYPE",
            lastMutationId = "mut_partial_invalid"
        )
        beerDao.insertBeer(beer)
        persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = ownerAlice,
            mutationId = "mut_partial_invalid",
            dirtyFields = "TYPE",
            baseRevision = 1L,
            isLocalOnlyCreation = false
        )

        // Invalid partition: remaining + confirmed != original dirtyFields
        val ok = persistence.confirmPushPartialPatch(
            syncId = syncId,
            ownerUid = ownerAlice,
            expectedMutationId = "mut_partial_invalid",
            newRemoteRevision = 2L,
            remainingDirtyFields = "COMMENT", // COMMENT no estaba en original
            confirmedFields = "TYPE"
        )

        assertFalse(ok)
        val currentBeer = beerDao.getBeerBySyncId(syncId, ownerAlice)
        assertNotNull(currentBeer)
        assertEquals("TYPE", currentBeer!!.dirtyFields)
        assertEquals(1L, currentBeer.remoteRevision)
        assertEquals(1L, currentBeer.baseRevision)
    }

    @Test
    fun applyTombstonePrevailed_marksDeletedAndHistorySuperseded() {
        val beer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = null,
            longitude = null,
            photoUri = "content://photo/keep_local.jpg",
            comment = "Local pending edit",
            locationName = null,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = ownerAlice,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = "COMMENT",
            lastMutationId = "mut_displaced_1"
        )
        beerDao.insertBeer(beer)
        persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = ownerAlice,
            mutationId = "mut_displaced_1",
            dirtyFields = "COMMENT",
            baseRevision = 1L,
            isLocalOnlyCreation = false
        )

        val ok = persistence.applyTombstonePrevailed(
            syncId = syncId,
            ownerUid = ownerAlice,
            displacedMutationId = "mut_displaced_1",
            remoteTombstoneRevision = 3L
        )

        assertTrue(ok)
        val updatedBeer = beerDao.getBeerBySyncId(syncId, ownerAlice)
        assertNotNull(updatedBeer)
        assertEquals(SyncStatus.DELETED, updatedBeer!!.syncStatus)
        assertEquals(3L, updatedBeer.remoteRevision)
        assertEquals(3L, updatedBeer.baseRevision)
        assertNull(updatedBeer.dirtyFields)
        assertNull(updatedBeer.lastMutationId)
        assertNotNull(updatedBeer.deletedAt)

        val history = beerDao.getHistoryRecordById("mut_displaced_1", ownerAlice)
        assertNotNull(history)
        // INVARIANTE: SUPERSEDED, nunca CONFIRMED
        assertEquals(MutationStatus.SUPERSEDED, history!!.status)
    }

    @Test
    fun ownerIsolation_rejectsCrossUserModifications() {
        val beer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = null,
            longitude = null,
            photoUri = null,
            comment = "Alice Beer",
            locationName = null,
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = ownerAlice,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = "ALL",
            lastMutationId = "mut_alice_1"
        )
        beerDao.insertBeer(beer)

        // Bob intenta confirmar mutación de Alice
        val confirmedByBob = persistence.confirmPushFullSuccess(
            syncId = syncId,
            ownerUid = ownerBob,
            expectedMutationId = "mut_alice_1",
            newRemoteRevision = 1L
        )

        assertFalse("Cross-user confirmation must fail", confirmedByBob)
        val aliceBeer = beerDao.getBeerBySyncId(syncId, ownerAlice)
        assertNotNull(aliceBeer)
        assertEquals(SyncStatus.PENDING, aliceBeer!!.syncStatus)
        assertEquals("mut_alice_1", aliceBeer.lastMutationId)
    }

    @Test
    fun preventionOfPhysicalHardDelete() {
        val hardDeleteResult = beerDao.confirmDeletePushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerAlice,
            expectedMutationId = "mut_any",
            newRemoteRevision = 10L
        )
        assertFalse("Monolithic hard deletion must be blocked in Room", hardDeleteResult)
    }
}
