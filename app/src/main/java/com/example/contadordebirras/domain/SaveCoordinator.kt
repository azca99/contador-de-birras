package com.example.contadordebirras.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SaveCoordinator {
    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    suspend fun executeSave(
        saveAction: suspend () -> Unit
    ): Boolean {
        if (_isSaving.value) return false
        _isSaving.value = true
        return try {
            saveAction()
            true
        } catch (e: Exception) {
            false
        } finally {
            _isSaving.value = false
        }
    }
}
