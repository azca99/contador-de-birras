package com.example.contadordebirras.domain

import com.example.contadordebirras.data.UserRepository

class ProfileEditor(
    private val userRepository: UserRepository,
    private val setRemoteUsername: suspend (String) -> String?,
    private val syncRemoteProfile: suspend (String, String?) -> Unit
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
        val error = setRemoteUsername(newUsername)
        if (error == null) {
            if (initialUid != null && activeUidProvider() == initialUid) {
                val scopeId = UserRepository.getScopeId(initialUid)
                userRepository.saveUsernameForScope(scopeId, newUsername.trim())
            } else {
                return "La sesión cambió durante la operación."
            }
        }
        return error
    }
}
