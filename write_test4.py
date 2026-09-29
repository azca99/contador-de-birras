content = '''package com.example.contadordebirras.ui.achievements

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.achievements.AchievementEntity
import com.example.contadordebirras.data.achievements.AchievementRepository
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.auth.FirebaseUser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testViewModel_RaceCondition_A_to_B() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(null)
        every { authRepo.currentUser } returns userFlow

        val userA = mockk<FirebaseUser>(relaxed = true)
        every { userA.uid } returns "userA"
        val userB = mockk<FirebaseUser>(relaxed = true)
        every { userB.uid } returns "userB"

        val beerRepo = mockk<BeerRepository>(relaxed = true)
        val beerFlowA = MutableStateFlow(listOf(BeerEntity(id = 1, ownerUid = "userA", type = BeerType.LATA, timestamp = 100L)))
        val beerFlowB = MutableStateFlow(listOf(BeerEntity(id = 2, ownerUid = "userB", type = BeerType.PINTA, timestamp = 100L)))
        every { beerRepo.observeBeers("userA") } returns beerFlowA
        every { beerRepo.observeBeers("userB") } returns beerFlowB
        
        val emptyFlow = MutableStateFlow(emptyList<BeerEntity>())
        every { beerRepo.allBeers } returns emptyFlow

        val achRepo = mockk<AchievementRepository>(relaxed = true)
        val achFlowA = MutableStateFlow(listOf(AchievementEntity("userA", "GEN_001", points = 10, unlockedAt = 100)))
        val achFlowB = MutableStateFlow(emptyList<AchievementEntity>())
        every { achRepo.getAllAchievements("userA") } returns achFlowA
        every { achRepo.getAllAchievements("userB") } returns achFlowB

        // Initialize with A
        userFlow.value = userA
        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        
        advanceUntilIdle()

        // Switch to B
        userFlow.value = userB
        // Change A's beers at the exact same time as B logs in
        beerFlowA.value = listOf(
            BeerEntity(id = 1, ownerUid = "userA", type = BeerType.LATA, timestamp = 100L),
            BeerEntity(id = 3, ownerUid = "userA", type = BeerType.JARRA, timestamp = 100L)
        )
        
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        
        // B has 1 PINTA -> GEN_001 (total=1) + PIN_001 (pinta=1)
        // -> unlockedCount = 2
        assertEquals(2, state.unlockedCount)
    }
}
'''
with open(r'app\src\test\java\com\example\contadordebirras\ui\achievements\AchievementsViewModelTest.kt', 'w', encoding='utf-8') as f:
    f.write(content)
