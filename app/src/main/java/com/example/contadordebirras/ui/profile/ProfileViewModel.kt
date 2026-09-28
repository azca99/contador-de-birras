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

    private val profileEditor = com.example.contadordebirras.domain.ProfileEditor(
        userRepository = userRepository,
        setRemoteUsername = { username, expectedUid -> authRepository.setUsername(username, expectedUid) },
        syncRemoteProfile = { alias, uid -> authRepository.syncProfile(alias, uid) }
    )

    fun setAlias(newAlias: String) {
        val initialUid = currentUser.value?.uid
        viewModelScope.launch {
            profileEditor.setAlias(newAlias, initialUid) { currentUser.value?.uid }
        }
    }

    fun setUsername(newUsername: String, onSuccess: () -> Unit) {
        val initialUid = currentUser.value?.uid
        viewModelScope.launch {
            _usernameError.value = null
            val error = profileEditor.setUsername(newUsername, initialUid) { currentUser.value?.uid }
            if (error == null) {
                onSuccess()
            } else {
                _usernameError.value = error
            }
        }
    }

    fun setLocationEnabled(enabled: Boolean) {
        val initialUid = currentUser.value?.uid
        val scope = UserRepository.getScopeId(initialUid)
        viewModelScope.launch {
            userRepository.setLocationEnabledForScope(scope, enabled)
        }
    }

    fun signOut() {
        authRepository.signOut()
    }

    fun updateCurrentUser() {
        authRepository.updateCurrentUser()
    }
}
