package com.example.contadordebirras.domain

import com.example.contadordebirras.data.UserRepository

class ProfileHydrator(
    private val userRepository: UserRepository,
    private val fetchProfile: suspend (String) -> Pair<String?, String?>?
) {
    suspend fun hydrateIfActive(uid: String, activeUidProvider: () -> String?) {
        val profile = fetchProfile(uid) ?: return
        if (activeUidProvider() == uid) {
            val (displayName, username) = profile
            if (!displayName.isNullOrBlank()) {
                userRepository.saveAliasForScope(uid, displayName)
            }
            if (!username.isNullOrBlank()) {
                userRepository.saveUsernameForScope(uid, username)
            }
        }
    }
}
