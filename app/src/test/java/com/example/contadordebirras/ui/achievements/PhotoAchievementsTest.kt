package com.example.contadordebirras.ui.achievements

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.data.achievements.AchievementEntity
import com.example.contadordebirras.data.achievements.AchievementRepository
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.google.firebase.auth.FirebaseUser
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoAchievementsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createBeer(
        id: Int,
        owner: String = "userA",
        photoUri: String? = null,
        remotePhotoUrl: String? = null,
        photoSource: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ) = BeerEntity(
        id = id,
        ownerUid = owner,
        type = BeerType.LATA,
        timestamp = timestamp,
        photoUri = photoUri,
        remotePhotoUrl = remotePhotoUrl,
        photoSource = photoSource
    )

    private fun timestampForMonth(year: Int, month: Int): Long {
        return ZonedDateTime.of(year, month, 1, 12, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    @Test
    fun `test 1 and 2 - first local photo and first remote photo`() = runTest {
        val beerLocal = createBeer(1, photoUri = "local.jpg")
        val beerRemote = createBeer(2, remotePhotoUrl = "remote.jpg")
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(mockk(relaxed = true) { every { uid } returns "userA" })
        every { authRepo.currentUser } returns userFlow
        
        val beerRepo = mockk<BeerRepository>(relaxed = true)
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow(listOf(beerLocal, beerRemote))
        
        val achRepo = mockk<AchievementRepository>(relaxed = true)
        every { achRepo.getAllAchievements("userA") } returns MutableStateFlow(emptyList())

        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val fot001 = state.achievements.find { it.id == "FOT_001" }
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED, fot001?.state)
        assertEquals(1, fot001?.currentProgress)
    }

    @Test
    fun `test 4 and 5 - double references count once, no photo counts zero`() = runTest {
        val beerDouble = createBeer(1, photoUri = "local.jpg", remotePhotoUrl = "remote.jpg")
        val beerNone = createBeer(2, photoUri = null, remotePhotoUrl = null)
        val beerBlank = createBeer(3, photoUri = "", remotePhotoUrl = " ")
        
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(mockk(relaxed = true) { every { uid } returns "userA" })
        every { authRepo.currentUser } returns userFlow
        val beerRepo = mockk<BeerRepository>(relaxed = true)
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow(listOf(beerDouble, beerNone, beerBlank))
        val achRepo = mockk<AchievementRepository>(relaxed = true)
        every { achRepo.getAllAchievements("userA") } returns MutableStateFlow(emptyList())

        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val fot001 = state.achievements.find { it.id == "FOT_001" }
        assertEquals(1, fot001?.currentProgress)
    }

    @Test
    fun `test 6 and 7 - progress 3 of 10, exactly at threshold`() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(mockk(relaxed = true) { every { uid } returns "userA" })
        every { authRepo.currentUser } returns userFlow
        val beerRepo = mockk<BeerRepository>(relaxed = true)
        val achRepo = mockk<AchievementRepository>(relaxed = true)
        every { achRepo.getAllAchievements("userA") } returns MutableStateFlow(emptyList())

        // 3 photos
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow((1..3).map { createBeer(it, photoUri = "p") })
        var viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertEquals(3, state.achievements.find { it.id == "FOT_002" }?.currentProgress) // 3/10
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.IN_PROGRESS, state.achievements.find { it.id == "FOT_002" }?.state)
        assertEquals(3, state.achievements.find { it.id == "FOT_005" }?.currentProgress) // 3/25
        assertEquals(3, state.achievements.find { it.id == "FOT_006" }?.currentProgress) // 3/50

        // 10 photos
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow((1..10).map { createBeer(it, photoUri = "p") })
        viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(10, state.achievements.find { it.id == "FOT_002" }?.currentProgress)
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED, state.achievements.find { it.id == "FOT_002" }?.state)
    }

    @Test
    fun `test 8 - camera vs gallery`() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(mockk(relaxed = true) { every { uid } returns "userA" })
        every { authRepo.currentUser } returns userFlow
        val beerRepo = mockk<BeerRepository>(relaxed = true)
        val achRepo = mockk<AchievementRepository>(relaxed = true)
        every { achRepo.getAllAchievements("userA") } returns MutableStateFlow(emptyList())

        // 3 gallery photos, 0 camera
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow((1..3).map { createBeer(it, photoUri = "p", photoSource = "GALLERY") })
        var viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertEquals(0, state.achievements.find { it.id == "FOT_003" }?.currentProgress)
        
        // 1 camera photo
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow(listOf(createBeer(4, photoUri = "p", photoSource = "CAMERA")))
        viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(1, state.achievements.find { it.id == "FOT_003" }?.currentProgress)
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED, state.achievements.find { it.id == "FOT_003" }?.state)
    }

    @Test
    fun `test 9 and 10 - months tracking`() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(mockk(relaxed = true) { every { uid } returns "userA" })
        every { authRepo.currentUser } returns userFlow
        val beerRepo = mockk<BeerRepository>(relaxed = true)
        val achRepo = mockk<AchievementRepository>(relaxed = true)
        every { achRepo.getAllAchievements("userA") } returns MutableStateFlow(emptyList())

        // 5 photos in same month
        val beersSameMonth = (1..5).map { createBeer(it, photoUri = "p", timestamp = timestampForMonth(2023, 1)) }
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow(beersSameMonth)
        var viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertEquals(1, state.achievements.find { it.id == "FOT_007" }?.currentProgress) // 1 month
        assertEquals(1, state.achievements.find { it.id == "FOT_008" }?.currentProgress) // 1/5 months

        // 5 photos in different months
        val beersDiffMonths = (1..5).map { createBeer(it, photoUri = "p", timestamp = timestampForMonth(2023, it)) }
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow(beersDiffMonths)
        viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(5, state.achievements.find { it.id == "FOT_008" }?.currentProgress) // 5/5 months
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED, state.achievements.find { it.id == "FOT_008" }?.state)
    }

    @Test
    fun `test 17 - conservative treatment of invalid historical unlocks`() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(mockk(relaxed = true) { every { uid } returns "userA" })
        every { authRepo.currentUser } returns userFlow
        val beerRepo = mockk<BeerRepository>(relaxed = true)
        // 1 photo in gallery, which incorrectly unlocked FOT_003 and FOT_008 before
        every { beerRepo.observeBeers("userA") } returns MutableStateFlow(listOf(createBeer(1, photoUri = "p", photoSource = "GALLERY", timestamp = timestampForMonth(2023, 1))))
        
        val achRepo = mockk<AchievementRepository>(relaxed = true)
        val badSavedAchievements = listOf(
            AchievementEntity(ownerUid = "userA", achievementId = "FOT_003", unlockedAt = 100L, progressAtUnlock = 1, points = 5),
            AchievementEntity(ownerUid = "userA", achievementId = "FOT_008", unlockedAt = 100L, progressAtUnlock = 5, points = 35)
        )
        every { achRepo.getAllAchievements("userA") } returns MutableStateFlow(badSavedAchievements)

        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        advanceUntilIdle()

        // It should delete them and show them as locked / in progress appropriately
        coVerify { achRepo.deleteAchievements("userA", listOf("FOT_003", "FOT_008")) }
        
        val state = viewModel.uiState.value
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.LOCKED, state.achievements.find { it.id == "FOT_003" }?.state)
        assertEquals(0, state.achievements.find { it.id == "FOT_003" }?.currentProgress)
        
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.IN_PROGRESS, state.achievements.find { it.id == "FOT_008" }?.state)
        assertEquals(1, state.achievements.find { it.id == "FOT_008" }?.currentProgress)
    }
}
