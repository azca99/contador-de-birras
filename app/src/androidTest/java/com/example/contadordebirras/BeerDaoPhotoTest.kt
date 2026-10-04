package com.example.contadordebirras

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BeerDaoPhotoTest {
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
    fun testPhotosInDao() = runBlocking {
        // User A
        val beer1 = BeerEntity(type = BeerType.LATA, timestamp = 1000L, photoUri = "local.jpg", ownerUid = "userA")
        val beer2 = BeerEntity(type = BeerType.LATA, timestamp = 2000L, remotePhotoUrl = "remote.jpg", ownerUid = "userA")
        val beer3 = BeerEntity(type = BeerType.LATA, timestamp = 3000L, photoUri = "local2.jpg", remotePhotoUrl = "remote2.jpg", ownerUid = "userA")
        val beer4 = BeerEntity(type = BeerType.LATA, timestamp = 4000L, photoUri = null, remotePhotoUrl = null, ownerUid = "userA")
        
        // User B
        val beer5 = BeerEntity(type = BeerType.LATA, timestamp = 5000L, photoUri = "other.jpg", ownerUid = "userB")

        beerDao.insertBeer(beer1)
        beerDao.insertBeer(beer2)
        beerDao.insertBeer(beer3)
        beerDao.insertBeer(beer4)
        beerDao.insertBeer(beer5)

        val userABeers = beerDao.getAllBeers("userA").first()
        val photosAdded = userABeers.count { it.hasPhoto() }
        
        // Debe devolver exactamente 3 fotos, no 4 ni 5
        assertEquals(3, photosAdded)
        assertEquals(4, userABeers.size)
        
        val userBBeers = beerDao.getAllBeers("userB").first()
        val photosB = userBBeers.count { it.hasPhoto() }
        assertEquals(1, photosB)
    }
}
