package com.example.contadordebirras.ui.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.data.achievements.AchievementEntity
import com.example.contadordebirras.data.achievements.AchievementRepository
import com.example.contadordebirras.domain.achievements.AchievementCalculator
import com.example.contadordebirras.domain.achievements.AchievementStatsInput
import com.example.contadordebirras.domain.achievements.AchievementUiModel
import com.example.contadordebirras.domain.achievements.UserLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

data class AchievementsUiState(
    val isLoading: Boolean = true,
    val achievements: List<AchievementUiModel> = emptyList(),
    val totalPoints: Int = 0,
    val userLevel: UserLevel? = null,
    val nextLevelPoints: Int = 3000,
    val unlockedCount: Int = 0,
    val totalCount: Int = 0
)

class AchievementsViewModel(
    private val beerRepository: BeerRepository,
    private val achievementRepository: AchievementRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val calculator = AchievementCalculator()

    private val _uiState = MutableStateFlow(AchievementsUiState())
    val uiState: StateFlow<AchievementsUiState> = _uiState.asStateFlow()

    private val _newUnlocksEvent = MutableSharedFlow<List<AchievementUiModel>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val newUnlocksEvent = _newUnlocksEvent.asSharedFlow()

    init {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        authRepository.currentUser
            .map { it?.uid ?: "guest_local" }
            .distinctUntilChanged()
            .flatMapLatest { ownerUid ->
                _uiState.value = AchievementsUiState(isLoading = true)
                combine(
                    beerRepository.observeBeers(ownerUid),
                    achievementRepository.getAllAchievements(ownerUid)
                ) { beers, savedAchievements ->
                    Triple(ownerUid, beers, savedAchievements)
                }
            }
            .onEach { (ownerUid, beers, savedAchievements) ->
                val countByType = beers.groupingBy { it.type }.eachCount()
                val distinctLocations = beers.mapNotNull { it.locationName }.distinct().size
                val beersWithPhotos = beers.filter { it.hasPhoto() }
                val photosAdded = beersWithPhotos.size
                val photosFromCamera = beersWithPhotos.count { it.photoSource == "CAMERA" }
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM").withZone(java.time.ZoneId.systemDefault())
                val photosByMonth = beersWithPhotos.groupingBy { formatter.format(java.time.Instant.ofEpochMilli(it.timestamp)) }.eachCount()

                val input = AchievementStatsInput(
                    beers = beers,
                    totalBeers = beers.size,
                    countByType = countByType,
                    distinctLocations = distinctLocations,
                    photosAdded = photosAdded,
                    photosFromCamera = photosFromCamera,
                    photosByMonth = photosByMonth
                )

                val invalidIds = savedAchievements.filter { saved ->
                    when (saved.achievementId) {
                        "FOT_003" -> input.photosFromCamera == 0
                        "FOT_004" -> true // Cannot be verified from beers alone without event tracking, conservatively lock
                        "FOT_007" -> input.photosByMonth.isEmpty()
                        "FOT_008" -> input.photosByMonth.size < 5
                        else -> false
                    }
                }.map { it.achievementId }

                if (invalidIds.isNotEmpty()) {
                    viewModelScope.launch {
                        achievementRepository.deleteAchievements(ownerUid, invalidIds)
                    }
                }

                val validSavedAchievements = savedAchievements.filter { it.achievementId !in invalidIds }

                val progresses = calculator.calculateProgress(input, validSavedAchievements)
                val uiModels = calculator.buildUiModels(progresses)

                val totalPoints = uiModels.filter { it.state == com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED || it.state == com.example.contadordebirras.domain.achievements.AchievementState.CLAIMED }.sumOf { it.points }
                val level = calculator.calculateLevels(totalPoints)
                val nextLevelPts = calculator.getNextLevelRequiredPoints(totalPoints)

                // Save new unlocks to DB
                val newUnlocks = progresses.filter { it.isUnlocked && validSavedAchievements.none { saved -> saved.achievementId == it.id } }
                if (newUnlocks.isNotEmpty()) {
                    val entitiesToSave = newUnlocks.map {
                        AchievementEntity(
                            ownerUid = ownerUid,
                            achievementId = it.id,
                            unlockedAt = it.unlockedAt,
                            claimed = false,
                            progressAtUnlock = it.currentProgress,
                            points = uiModels.first { ui -> ui.id == it.id }.points
                        )
                    }
                    viewModelScope.launch {
                        // Siempre persiste el logro para el owner original que lo obtuvo, incluso si Auth cambia.
                        achievementRepository.insertAll(ownerUid, entitiesToSave)
                        
                        val currentActiveUid = authRepository.currentUser.value?.uid ?: "guest_local"
                        if (currentActiveUid == ownerUid) {
                            val newlyUnlockedModels = uiModels.filter { ui -> newUnlocks.any { it.id == ui.id } }
                            _newUnlocksEvent.tryEmit(newlyUnlockedModels)
                        }
                    }
                }

                val currentActiveUid = authRepository.currentUser.value?.uid ?: "guest_local"
                if (currentActiveUid == ownerUid) {
                    _uiState.value = AchievementsUiState(
                        isLoading = false,
                        achievements = uiModels,
                        totalPoints = totalPoints,
                        userLevel = level,
                        nextLevelPoints = nextLevelPts,
                        unlockedCount = uiModels.count { it.state == com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED || it.state == com.example.contadordebirras.domain.achievements.AchievementState.CLAIMED },
                        totalCount = uiModels.filter { !it.isHidden || it.state == com.example.contadordebirras.domain.achievements.AchievementState.UNLOCKED }.size
                    )
                }
            }.launchIn(viewModelScope)
    }
}
