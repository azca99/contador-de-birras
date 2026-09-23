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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BeerIsolationTest {

    private lateinit var db: BeerDatabase
    private lateinit var beerDao: BeerDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java).build()
        beerDao = db.beerDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun createBeer(owner: String, type: BeerType = BeerType.LATA): BeerEntity {
        return BeerEntity(
            type = type,
            timestamp = System.currentTimeMillis(),
            syncId = UUID.randomUUID().toString(),
            ownerUid = owner
        )
    }

    @Test
    fun testIsolation_getAllBeers() = runBlocking {
        beerDao.insertBeer(createBeer("userA"))
        beerDao.insertBeer(createBeer("userB"))
        beerDao.insertBeer(createBeer("userA"))

        val beersA = beerDao.getAllBeers("userA").first()
        val beersB = beerDao.getAllBeers("userB").first()

        assertEquals(2, beersA.size)
        assertEquals(1, beersB.size)
        
        assert(beersA.all { it.ownerUid == "userA" })
        assert(beersB.all { it.ownerUid == "userB" })
    }

    @Test
    fun testIsolation_getTotalCount() = runBlocking {
        beerDao.insertBeer(createBeer("userA"))
        beerDao.insertBeer(createBeer("userA"))
        beerDao.insertBeer(createBeer("userB"))

        val countA = beerDao.getTotalCount("userA").first()
        val countB = beerDao.getTotalCount("userB").first()

        assertEquals(2, countA)
        assertEquals(1, countB)
    }

    @Test
    fun testIsolation_getLastBeer() = runBlocking {
        beerDao.insertBeer(createBeer("userB", BeerType.BOTELLA).copy(timestamp = 100))
        beerDao.insertBeer(createBeer("userA", BeerType.LATA).copy(timestamp = 200))

        val lastA = beerDao.getLastBeer("userA").first()
        val lastB = beerDao.getLastBeer("userB").first()

        assertEquals(BeerType.LATA, lastA?.type)
        assertEquals(BeerType.BOTELLA, lastB?.type)
    }

    @Test
    fun testIsolation_getPendingSyncBeers() = runBlocking {
        beerDao.insertBeer(createBeer("userA").copy(syncStatus = SyncStatus.PENDING))
        beerDao.insertBeer(createBeer("userB").copy(syncStatus = SyncStatus.PENDING))

        val pendingA = beerDao.getPendingSyncBeers("userA")
        val pendingB = beerDao.getPendingSyncBeers("userB")

        assertEquals(1, pendingA.size)
        assertEquals("userA", pendingA[0].ownerUid)
        assertEquals(1, pendingB.size)
        assertEquals("userB", pendingB[0].ownerUid)
    }

    @Test
    fun testIsolation_getById_and_BySyncId() = runBlocking {
        val beer = createBeer("userA")
        val id = beerDao.insertBeer(beer).toInt()

        val foundA = beerDao.getBeerById(id, "userA")
        val notFoundB = beerDao.getBeerById(id, "userB")

        assertEquals("userA", foundA?.ownerUid)
        assertNull(notFoundB)

        val syncFoundA = beerDao.getBeerBySyncId(beer.syncId, "userA")
        val syncNotFoundB = beerDao.getBeerBySyncId(beer.syncId, "userB")

        assertEquals("userA", syncFoundA?.ownerUid)
        assertNull(syncNotFoundB)
    }

    @Test
    fun testIsolation_softDeleteBeer() = runBlocking {
        val beer = createBeer("userA")
        val id = beerDao.insertBeer(beer).toInt()

        // B tries to delete A's beer -> should fail (return 0 rows affected)
        val rowsAffected = beerDao.softDeleteBeer(id, "userB")
        assertEquals(0, rowsAffected)

        val check1 = beerDao.getBeerById(id, "userA")
        assertEquals(SyncStatus.PENDING, check1?.syncStatus)

        // A deletes A's beer -> should succeed
        val rowsAffected2 = beerDao.softDeleteBeer(id, "userA")
        assertEquals(1, rowsAffected2)

        val check2 = beerDao.getBeerById(id, "userA")
        assertEquals(SyncStatus.DELETED, check2?.syncStatus)
    }

    @Test
    fun testIsolation_hardDeleteBySyncId() = runBlocking {
        val beer = createBeer("userA")
        beerDao.insertBeer(beer)

        // B tries to hard delete A's beer
        val rows = beerDao.hardDeleteBySyncId(beer.syncId, "userB")
        assertEquals(0, rows)

        // A hard deletes A's beer
        val rows2 = beerDao.hardDeleteBySyncId(beer.syncId, "userA")
        assertEquals(1, rows2)
    }
}
