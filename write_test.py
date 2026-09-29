content = '''package com.example.contadordebirras.ui.achievements

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.achievements.AchievementEntity
import com.example.contadordebirras.data.achievements.AchievementRepository
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.auth.FirebaseUser
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.when

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
        val authRepo = mock(AuthRepository::class.java)
        val userFlow = MutableStateFlow<FirebaseUser?>(null)
        when(authRepo.currentUser).thenReturn(userFlow)

        val userA = mock(FirebaseUser::class.java)
        when(userA.uid).thenReturn("userA")
        val userB = mock(FirebaseUser::class.java)
        when(userB.uid).thenReturn("userB")

        val beerRepo = mock(BeerRepository::class.java)
        val beerFlowA = MutableStateFlow(listOf(BeerEntity(id = 1, ownerUid = "userA", type = BeerType.LAGER, timestamp = 100L)))
        val beerFlowB = MutableStateFlow(listOf(BeerEntity(id = 2, ownerUid = "userB", type = BeerType.ALE, timestamp = 100L)))
        when(beerRepo.observeBeers("userA")).thenReturn(beerFlowA)
        when(beerRepo.observeBeers("userB")).thenReturn(beerFlowB)
        
        val emptyFlow = MutableStateFlow(emptyList<BeerEntity>())
        when(beerRepo.allBeers).thenReturn(emptyFlow)

        val achRepo = mock(AchievementRepository::class.java)
        val achFlowA = MutableStateFlow(listOf(AchievementEntity("userA", "ach_first_beer", points = 10, unlockedAt = 100)))
        val achFlowB = MutableStateFlow(emptyList<AchievementEntity>())
        when(achRepo.getAllAchievements("userA")).thenReturn(achFlowA)
        when(achRepo.getAllAchievements("userB")).thenReturn(achFlowB)

        // Initialize with A
        userFlow.value = userA
        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        
        advanceUntilIdle()

        // Switch to B
        userFlow.value = userB
        // Change A's beers at the exact same time as B logs in
        beerFlowA.value = listOf(
            BeerEntity(id = 1, ownerUid = "userA", type = BeerType.LAGER, timestamp = 100L),
            BeerEntity(id = 3, ownerUid = "userA", type = BeerType.STOUT, timestamp = 100L)
        )
        
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        // B has 1 Ale -> doesn't unlock "two_styles" which A might have unlocked.
        
        // 1 beer unlocks "first_beer" + 1 style unlocks "style_explorer_1" (assuming that's how it works)
        // But A's data should NOT be present.
        assertEquals(1, state.unlockedCount)
    }
}
'''
with open(r'app\src\test\java\com\example\contadordebirras\ui\achievements\AchievementsViewModelTest.kt', 'w', encoding='utf-8') as f:
    f.write(content)
