package com.example.contadordebirras

import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.data.achievements.AchievementDao
import com.example.contadordebirras.data.achievements.AchievementEntity
import com.example.contadordebirras.data.achievements.DefaultAchievementRepository
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.ui.achievements.AchievementsViewModel
import com.example.contadordebirras.ui.stats.StatsViewModel
import com.google.firebase.auth.FirebaseUser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testHistoryAndAchievementsDivergence() = runTest {
        val beerDao = mockk<BeerDao>()
        val achievementDao = mockk<AchievementDao>()
        
        val userFlow = MutableStateFlow(mockk<FirebaseUser>(relaxed = true) { every { uid } returns "userA" })
        val authRepo = mockk<AuthRepository>(relaxed = true)
        every { authRepo.currentUser } returns userFlow
        
        val beers = listOf(
            BeerEntity(id = 1, type = BeerType.LATA, timestamp = 1000L, photoUri = "local.jpg", ownerUid = "userA"),
            BeerEntity(id = 2, type = BeerType.LATA, timestamp = 2000L, remotePhotoUrl = "remote.jpg", ownerUid = "userA"),
            BeerEntity(id = 3, type = BeerType.LATA, timestamp = 3000L, photoUri = "local2.jpg", remotePhotoUrl = "remote2.jpg", ownerUid = "userA")
        )
        
        every { beerDao.getAllBeers("userA") } returns flowOf(beers)
        every { achievementDao.getAllAchievements("userA") } returns flowOf(emptyList())

        val context = mockk<android.content.Context>(relaxed = true)
        val beerRepo = BeerRepository(beerDao, context, authRepo)
        val achievementRepo = DefaultAchievementRepository(achievementDao)

        // StatsViewModel (HistoryScreen)
        val statsViewModel = StatsViewModel(beerRepo)
        
        // Advance time to allow stateIn to collect
        val job = backgroundScope.launch { statsViewModel.allBeers.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        
        val emittedBeers = statsViewModel.allBeers.value
        val beersWithPhotos = emittedBeers.filter { it.hasPhoto() }
        assertEquals(3, beersWithPhotos.size)
        job.cancel()

        // AchievementsViewModel
        val achViewModel = AchievementsViewModel(beerRepo, achievementRepo, authRepo)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = achViewModel.uiState.value
        
        val fot001 = state.achievements.find { it.id == "FOT_001" }
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED, fot001?.state)
        assertEquals(1, fot001?.currentProgress)

        val fot002 = state.achievements.find { it.id == "FOT_002" }
        assertEquals(3, fot002?.currentProgress)
    }
}
