package com.example.contadordebirras.domain

import com.example.contadordebirras.data.UserRepository

class ProfileEditor(
    private val userRepository: UserRepository,
    private val setRemoteUsername: suspend (String, String) -> String?,
    private val syncRemoteProfile: suspend (String, String) -> Unit
) {
    suspend fun setAlias(newAlias: String, activeUidProvider: () -> String?) {
        val initialUid = activeUidProvider()
        val scopeId = UserRepository.getScopeId(initialUid)
        userRepository.saveAliasForScope(scopeId, newAlias)
        
        if (initialUid != null && activeUidProvider() == initialUid) {
            syncRemoteProfile(newAlias, initialUid)
        }
    }

    suspend fun setUsername(newUsername: String, activeUidProvider: () -> String?): String? {
        val initialUid = activeUidProvider()
        if (initialUid == null) {
            return "Debes iniciar sesión para asignar un username."
        }
        if (activeUidProvider() != initialUid) {
            return "La sesión cambió antes de la operación."
        }
        val error = setRemoteUsername(newUsername, initialUid)
        if (error == null) {
            if (activeUidProvider() == initialUid) {
                userRepository.saveUsernameForScope(initialUid, newUsername.trim())
            } else {
                return "La sesión cambió durante la operación."
            }
        }
        return error
    }
}
