package com.example.contadordebirras.ui.achievements

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.achievements.AchievementEntity
import com.example.contadordebirras.data.achievements.AchievementRepository
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.domain.achievements.AchievementUiModel
import com.google.firebase.auth.FirebaseUser
import io.mockk.coVerify
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    fun testViewModel_RaceCondition_Persistence_A_even_if_Auth_changes_to_B() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(null)
        every { authRepo.currentUser } returns userFlow

        val userA = mockk<FirebaseUser>(relaxed = true)
        every { userA.uid } returns "userA"
        val userB = mockk<FirebaseUser>(relaxed = true)
        every { userB.uid } returns "userB"

        val beerRepo = mockk<BeerRepository>(relaxed = true)
        val beerFlowA = MutableStateFlow(listOf(BeerEntity(id = 1, ownerUid = "userA", type = BeerType.LATA, timestamp = 100L)))
        val beerFlowB = MutableStateFlow(emptyList<BeerEntity>())
        every { beerRepo.observeBeers("userA") } returns beerFlowA
        every { beerRepo.observeBeers("userB") } returns beerFlowB

        val achRepo = mockk<AchievementRepository>(relaxed = true)
        val achFlowA = MutableStateFlow(emptyList<AchievementEntity>())
        val achFlowB = MutableStateFlow(emptyList<AchievementEntity>())
        every { achRepo.getAllAchievements("userA") } returns achFlowA
        every { achRepo.getAllAchievements("userB") } returns achFlowB
        
        coEvery { achRepo.insertAll(any(), any()) } coAnswers {
            userFlow.value = userB
        }

        userFlow.value = userA
        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)

        advanceUntilIdle()

        coVerify { achRepo.insertAll(eq("userA"), any()) }
        coVerify(exactly = 0) { achRepo.insertAll(eq("userB"), any()) }
    }

    @Test
    fun testViewModel_StaleEvent_B_does_not_receive_A_unlocks() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(null)
        every { authRepo.currentUser } returns userFlow

        val userA = mockk<FirebaseUser>(relaxed = true)
        every { userA.uid } returns "userA"
        val userB = mockk<FirebaseUser>(relaxed = true)
        every { userB.uid } returns "userB"

        val beerRepo = mockk<BeerRepository>(relaxed = true)
        val beerFlowA = MutableStateFlow(listOf(BeerEntity(id = 1, ownerUid = "userA", type = BeerType.LATA, timestamp = 100L)))
        every { beerRepo.observeBeers("userA") } returns beerFlowA
        every { beerRepo.observeBeers("userB") } returns MutableStateFlow(emptyList())

        val achRepo = mockk<AchievementRepository>(relaxed = true)
        every { achRepo.getAllAchievements(any()) } returns MutableStateFlow(emptyList())

        coEvery { achRepo.insertAll(any(), any()) } coAnswers {
            userFlow.value = userB
        }

        userFlow.value = userA
        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        
        val events = mutableListOf<List<AchievementUiModel>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.newUnlocksEvent.toList(events)
        }

        advanceUntilIdle()

        assertTrue("No se debe haber emitido evento visual para A ya que Auth ha cambiado a B", events.isEmpty())
    }

    @Test
    fun testViewModel_UiState_is_cleared_on_owner_change() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(null)
        every { authRepo.currentUser } returns userFlow

        val userA = mockk<FirebaseUser>(relaxed = true)
        every { userA.uid } returns "userA"
        val userB = mockk<FirebaseUser>(relaxed = true)
        every { userB.uid } returns "userB"

        val beerRepo = mockk<BeerRepository>(relaxed = true)
        val beerFlowA = MutableStateFlow(listOf(BeerEntity(id = 1, ownerUid = "userA", type = BeerType.LATA, timestamp = 100L)))
        every { beerRepo.observeBeers("userA") } returns beerFlowA
        
        // Simular que el Flow de B tarda en emitir, para poder ver el estado intermedio
        every { beerRepo.observeBeers("userB") } returns flow {
            delay(1000)
            emit(emptyList<BeerEntity>())
        }

        val achRepo = mockk<AchievementRepository>(relaxed = true)
        every { achRepo.getAllAchievements(any()) } returns MutableStateFlow(emptyList())

        userFlow.value = userA
        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        
        userFlow.value = userB
        // Avanzamos el tiempo lo suficiente para que flatMapLatest se dispare pero no los 1000ms de B
        testScheduler.advanceTimeBy(100) 
        runCurrent()
        
        // Ahora sí, isLoading DEBE ser true porque flatMapLatest lo ha reseteado
        assertEquals(true, viewModel.uiState.value.isLoading)
        
        advanceUntilIdle() // let B finish
        
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(0, viewModel.uiState.value.unlockedCount)
    }

    @Test
    fun testViewModel_A_to_B_without_flow_mixing() = runTest {
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val userFlow = MutableStateFlow<FirebaseUser?>(null)
        every { authRepo.currentUser } returns userFlow

        val userA = mockk<FirebaseUser>(relaxed = true)
        every { userA.uid } returns "userA"
        val userB = mockk<FirebaseUser>(relaxed = true)
        every { userB.uid } returns "userB"

        val beerRepo = mockk<BeerRepository>(relaxed = true)
        val beersA = (1..10).map { BeerEntity(id = it, ownerUid = "userA", type = BeerType.LATA, timestamp = 100L) }
        val beerFlowA = MutableStateFlow(beersA)
        val beerFlowB = MutableStateFlow(emptyList<BeerEntity>())
        
        every { beerRepo.observeBeers("userA") } returns beerFlowA
        every { beerRepo.observeBeers("userB") } returns beerFlowB

        val achRepo = mockk<AchievementRepository>(relaxed = true)
        every { achRepo.getAllAchievements(any()) } returns MutableStateFlow(emptyList())

        userFlow.value = userA
        val viewModel = AchievementsViewModel(beerRepo, achRepo, authRepo)
        
        advanceUntilIdle()
        
        val stateA = viewModel.uiState.value
        val achGEN003_A = stateA.achievements.find { it.id == "GEN_003" }
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED, achGEN003_A?.state)

        userFlow.value = userB
        advanceUntilIdle()

        val stateB = viewModel.uiState.value
        val achGEN003_B = stateB.achievements.find { it.id == "GEN_003" }
        assertEquals(com.example.contadordebirras.domain.achievements.AchievementState.LOCKED, achGEN003_B?.state)
    }
}


