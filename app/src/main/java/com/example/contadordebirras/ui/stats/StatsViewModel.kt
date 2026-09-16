package com.example.contadordebirras.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.BeerType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StatsViewModel(private val repository: BeerRepository) : ViewModel() {
    
    val allBeers = repository.allBeers.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val stats = repository.allBeers.map { beers ->
        val total = beers.size
        val byType = beers.groupBy { it.type }.mapValues { it.value.size }
        val topLocations = beers.filter { it.locationName != null }
            .groupBy { it.locationName!! }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            
        // Calculate Streaks
        val uniqueDatesDesc = beers.map { it.timestamp }
            .map { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            .distinct()
            .sortedDescending()
            
        var currentStreak = 0
        var bestStreak = 0
        
        if (uniqueDatesDesc.isNotEmpty()) {
            var tempStreak = 1
            val today = java.time.LocalDate.now()
            val firstDate = uniqueDatesDesc.first()
            var isCurrentAlive = firstDate == today || firstDate == today.minusDays(1)
            
            for (i in 0 until uniqueDatesDesc.size - 1) {
                val curr = uniqueDatesDesc[i]
                val older = uniqueDatesDesc[i + 1]
                if (curr.minusDays(1) == older) {
                    tempStreak++
                } else {
                    if (isCurrentAlive) {
                        currentStreak = tempStreak
                        isCurrentAlive = false
                    }
                    if (tempStreak > bestStreak) {
                        bestStreak = tempStreak
                    }
                    tempStreak = 1
                }
            }
            if (isCurrentAlive) {
                currentStreak = tempStreak
            }
            if (tempStreak > bestStreak) {
                bestStreak = tempStreak
            }
        }
        
        android.util.Log.d("StreakDebug", "Unique Dates: $uniqueDatesDesc")
        android.util.Log.d("StreakDebug", "Current Streak: $currentStreak, Best Streak: $bestStreak")

        StatsUiState(
            total = total, 
            byType = byType, 
            topLocations = topLocations,
            currentStreak = currentStreak,
            bestStreak = bestStreak
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState(0, emptyMap(), emptyList(), 0, 0)
    )

    fun deleteBeer(beer: com.example.contadordebirras.data.BeerEntity) {
        viewModelScope.launch {
            repository.deleteBeer(beer)
        }
    }

    fun updateBeer(beer: com.example.contadordebirras.data.BeerEntity) {
        viewModelScope.launch {
            repository.updateBeer(beer)
        }
    }
}

data class StatsUiState(
    val total: Int,
    val byType: Map<BeerType, Int>,
    val topLocations: List<Pair<String, Int>> = emptyList(),
    val currentStreak: Int = 0,
    val bestStreak: Int = 0
)
