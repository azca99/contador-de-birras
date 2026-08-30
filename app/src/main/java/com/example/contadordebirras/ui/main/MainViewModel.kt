package com.example.contadordebirras.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.BeerType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.contadordebirras.data.UserRepository

class MainViewModel(
    private val repository: BeerRepository,
    private val userRepository: UserRepository
) : ViewModel() {
    val locationEnabled = userRepository.isLocationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val userAlias = userRepository.userAlias
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Cervecero")

    val totalCount = repository.totalCount.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    val lastBeer = repository.lastBeer.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    fun addBeer(type: BeerType, lat: Double? = null, lng: Double? = null, photoUri: String? = null, comment: String? = null) {
        viewModelScope.launch {
            repository.addBeer(type = type, timestamp = System.currentTimeMillis(), latitude = lat, longitude = lng, photoUri = photoUri, comment = comment)
        }
    }

    private var isUndoing = false

    fun undoLastBeer(onResult: (String) -> Unit) {
        if (isUndoing) return
        val currentLast = lastBeer.value
        if (currentLast != null) {
            isUndoing = true
            viewModelScope.launch {
                repository.deleteBeer(currentLast)
                val timeString = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(currentLast.timestamp))
                onResult("Has borrado la ${currentLast.type.displayName} de las $timeString")
                kotlinx.coroutines.delay(300) // Small delay to allow Flow to emit new lastBeer
                isUndoing = false
            }
        }
    }
}
