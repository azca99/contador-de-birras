package com.example.contadordebirras.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.data.UserRepository
import com.example.contadordebirras.domain.AuthRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ProfileViewModel(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository
) : ViewModel() {
    val alias = userRepository.userAlias
    val username = userRepository.username
    val isLocationEnabled = userRepository.isLocationEnabled
    val currentUser = authRepository.currentUser
    
    private val _usernameError = MutableStateFlow<String?>(null)
    val usernameError: StateFlow<String?> = _usernameError

    fun setAlias(newAlias: String) {
        viewModelScope.launch {
            userRepository.saveAlias(newAlias)
            authRepository.syncProfile(newAlias)
        }
    }

    fun setUsername(newUsername: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _usernameError.value = null
            val error = authRepository.setUsername(newUsername)
            if (error == null) {
                userRepository.saveUsername(newUsername.trim())
                onSuccess()
            } else {
                _usernameError.value = error
            }
        }
    }

    fun setLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userRepository.setLocationEnabled(enabled)
        }
    }

    fun signOut() {
        authRepository.signOut()
    }

    fun updateCurrentUser() {
        authRepository.updateCurrentUser()
        viewModelScope.launch {
            val currentAlias = userRepository.userAlias.first()
            authRepository.syncProfile(currentAlias)
        }
    }
}
