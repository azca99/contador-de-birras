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
import org.mockito.Mockito

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
        val authRepo = Mockito.mock(AuthRepository::class.java)
        val userFlow = MutableStateFlow<FirebaseUser?>(null)
        Mockito.when(authRepo.currentUser).thenReturn(userFlow)

        val userA = Mockito.mock(FirebaseUser::class.java)
        Mockito.when(userA.uid).thenReturn("userA")
        val userB = Mockito.mock(FirebaseUser::class.java)
        Mockito.when(userB.uid).thenReturn("userB")

        val beerRepo = Mockito.mock(BeerRepository::class.java)
        val beerFlowA = MutableStateFlow(listOf(BeerEntity(id = 1, ownerUid = "userA", type = BeerType.LATA, timestamp = 100L)))
        val beerFlowB = MutableStateFlow(listOf(BeerEntity(id = 2, ownerUid = "userB", type = BeerType.PINTA, timestamp = 100L)))
        Mockito.when(beerRepo.observeBeers("userA")).thenReturn(beerFlowA)
        Mockito.when(beerRepo.observeBeers("userB")).thenReturn(beerFlowB)
        
        val emptyFlow = MutableStateFlow(emptyList<BeerEntity>())
        Mockito.when(beerRepo.allBeers).thenReturn(emptyFlow)

        val achRepo = Mockito.mock(AchievementRepository::class.java)
        val achFlowA = MutableStateFlow(listOf(AchievementEntity("userA", "ach_first_beer", points = 10, unlockedAt = 100)))
        val achFlowB = MutableStateFlow(emptyList<AchievementEntity>())
        Mockito.when(achRepo.getAllAchievements("userA")).thenReturn(achFlowA)
        Mockito.when(achRepo.getAllAchievements("userB")).thenReturn(achFlowB)

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
        // B has 1 PINTA -> doesn't unlock achievements that A might have unlocked.
        
        // B has 1 beer so unlocks "first_beer" -> count is 1.
        // B does NOT get A's "two styles" achievement.
        assertEquals(1, state.unlockedCount)
    }
}
'''
with open(r'app\src\test\java\com\example\contadordebirras\ui\achievements\AchievementsViewModelTest.kt', 'w', encoding='utf-8') as f:
    f.write(content)
