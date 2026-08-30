package com.example.contadordebirras.domain.achievements

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.domain.GroupEntity
import com.example.contadordebirras.data.FriendProfile
import java.time.LocalDate

data class AchievementStatsInput(
    val beers: List<BeerEntity> = emptyList(),
    val totalBeers: Int = 0,
    val countByType: Map<BeerType, Int> = emptyMap(),
    val countByLocation: Map<String, Int> = emptyMap(),
    val distinctCities: Int = 0, // TODO: Implement city extraction
    val distinctLocations: Int = 0,
    val recordsByDay: Map<LocalDate, Int> = emptyMap(),
    val recordsByMonth: Map<String, Int> = emptyMap(), // yyyy-MM
    val recordsByYear: Map<Int, Int> = emptyMap(),
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val photosAdded: Int = 0,
    val photosByMonth: Map<String, Int> = emptyMap(),
    val friendsAdded: Int = 0,
    val groupsCreated: Int = 0,
    val commentsWritten: Int = 0, // TODO: comments
    val rankingMonthlyExists: Boolean = false, // TODO: ranking
    val rankingTop10: Int = 0,
    val rankingTop5: Int = 0,
    val rankingTop3: Int = 0,
    val rankingFirst: Int = 0,
    val statsUses: Int = 0, // TODO: stats usage counter
    val responsibleActions: Map<String, Int> = emptyMap(), // TODO: responsible tracking
    val hiddenActions: Map<String, Int> = emptyMap() // TODO: hidden actions tracking
)
