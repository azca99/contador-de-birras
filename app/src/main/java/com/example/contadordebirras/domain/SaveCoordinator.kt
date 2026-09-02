package com.example.contadordebirras.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SaveCoordinator {
    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    suspend fun executeSave(
        saveAction: suspend () -> Unit,
        onComplete: () -> Unit
    ) {
        if (_isSaving.value) return
        _isSaving.value = true
        try {
            saveAction()
            onComplete()
        } finally {
            _isSaving.value = false
        }
    }
}
