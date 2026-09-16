package com.example.contadordebirras.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.BeerType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.example.contadordebirras.data.UserRepository

import kotlinx.coroutines.flow.asStateFlow

import com.example.contadordebirras.domain.SaveCoordinator

class MainViewModel(
    private val repository: BeerRepository,
    private val userRepository: UserRepository
) : ViewModel() {
    private val saveCoordinator = SaveCoordinator()
    val isSavingBeer = saveCoordinator.isSaving
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
        repository.requestSync()
    }



    fun executeSave(
        type: BeerType, 
        photoUri: String?, 
        comment: String?, 
        photoSource: String?, 
        locationFetcher: (suspend () -> Pair<Double?, Double?>)?,
        onFinished: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            var beerId = -1L
            val timestamp = System.currentTimeMillis()
            val success = saveCoordinator.executeSave(
                saveAction = {
                    beerId = repository.addBeer(type = type, timestamp = timestamp, latitude = null, longitude = null, photoUri = photoUri, comment = comment, photoSource = photoSource)
                }
            )
            onFinished(success)
            
            if (success && beerId != -1L) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (locationFetcher != null) {
                        try {
                            val loc = locationFetcher()
                            val lat = loc.first
                            val lng = loc.second
                            if (lat != null && lng != null) {
                                repository.updateBeerLocation(beerId, lat, lng)
                            }
                        } catch (e: Exception) {}
                    }
                    repository.requestSync()
                }
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
