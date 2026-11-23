package com.example.contadordebirras.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.data.UserRepository
import com.example.contadordebirras.domain.AuthRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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

    fun signInTestUser(email: String = "test_internal@demo-contador-c44.test", password: String = "password123456", onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                try {
                    auth.signInWithEmailAndPassword(email, password).await()
                } catch (_: Exception) {
                    auth.createUserWithEmailAndPassword(email, password).await()
                }
                authRepository.updateCurrentUser()
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    fun updateCurrentUser() {
        authRepository.updateCurrentUser()
    }

    fun activateC4ForCurrentTestUser(context: android.content.Context, onResult: (Boolean, String) -> Unit) {
        val user = currentUser.value
        if (user == null) {
            onResult(false, "No hay usuario autenticado")
            return
        }
        if (user.email != "test_internal@demo-contador-c44.test") {
            onResult(false, "Solo se permite activar C4 para el usuario ficticio de prueba autorizado")
            return
        }
        viewModelScope.launch {
            try {
                // 1. Autorizar únicamente el UID de la cuenta ficticia
                com.example.contadordebirras.sync.c4.C4SyncConfig.configureControlledActivation(
                    context = context.applicationContext,
                    featureEnabled = true,
                    authorizedUid = user.uid
                )
                // 2. Activar C4 mediante C4ControlledActivationManager
                val activationManager = com.example.contadordebirras.sync.c4.C4ControlledActivationManager(
                    context = context.applicationContext,
                    allowProduction = false
                )
                when (val result = activationManager.activateAccount(user.uid)) {
                    is com.example.contadordebirras.sync.c4.ActivationResult.Success -> {
                        onResult(true, "C4 activado exitosamente")
                    }
                    is com.example.contadordebirras.sync.c4.ActivationResult.AlreadyActive -> {
                        onResult(true, "C4 ya activo")
                    }
                    is com.example.contadordebirras.sync.c4.ActivationResult.Blocked -> {
                        onResult(false, "Activación bloqueada: ${result.reason}")
                    }
                    is com.example.contadordebirras.sync.c4.ActivationResult.Unauthorized -> {
                        onResult(false, "No autorizado: ${result.reason}")
                    }
                    is com.example.contadordebirras.sync.c4.ActivationResult.Failed -> {
                        onResult(false, "Fallo: ${result.reason}")
                    }
                }
            } catch (e: Exception) {
                onResult(false, "Error: ${e.message}")
            }
        }
    }
}
