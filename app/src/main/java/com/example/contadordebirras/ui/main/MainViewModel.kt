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

import kotlinx.coroutines.flow.asStateFlow

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

    init {
        viewModelScope.launch {
            repository.syncWithCloud()
        }
    }

    private val _isSavingBeer = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isSavingBeer = _isSavingBeer.asStateFlow()

    fun addBeer(type: BeerType, lat: Double? = null, lng: Double? = null, photoUri: String? = null, comment: String? = null, photoSource: String? = null) {
        if (_isSavingBeer.value) return
        viewModelScope.launch {
            _isSavingBeer.value = true
            try {
                repository.addBeer(type = type, timestamp = System.currentTimeMillis(), latitude = lat, longitude = lng, photoUri = photoUri, comment = comment, photoSource = photoSource)
            } finally {
                _isSavingBeer.value = false
            }
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
