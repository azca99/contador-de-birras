package com.example.contadordebirras.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserRepository(
    private val dataStore: DataStore<Preferences>,
    private val activeUidFlow: Flow<String?>
) {
    // Legacy global keys
    private val LEGACY_ALIAS_KEY = stringPreferencesKey("alias")
    private val LEGACY_USERNAME_KEY = stringPreferencesKey("username")
    private val LEGACY_CREATION_DATE_KEY = longPreferencesKey("creation_date")
    private val LEGACY_LOCATION_ENABLED_KEY = booleanPreferencesKey("location_enabled")
    
    private val MIGRATED_LEGACY_V1 = booleanPreferencesKey("migrated_legacy_to_scoped_v1")

    // Scoped key generators
    private fun getScopeId(uid: String?): String = uid ?: "guest_local"
    
    private fun aliasKey(scopeId: String) = stringPreferencesKey("user_${scopeId}_alias")
    private fun usernameKey(scopeId: String) = stringPreferencesKey("user_${scopeId}_username")
    private fun creationDateKey(scopeId: String) = longPreferencesKey("user_${scopeId}_creation_date")
    private fun locationEnabledKey(scopeId: String) = booleanPreferencesKey("user_${scopeId}_location_enabled")

    val userAlias: Flow<String> = activeUidFlow.flatMapLatest { uid ->
        val scopeId = getScopeId(uid)
        dataStore.data.map { prefs -> prefs[aliasKey(scopeId)] ?: "Cervecero" }
    }

    val username: Flow<String> = activeUidFlow.flatMapLatest { uid ->
        val scopeId = getScopeId(uid)
        dataStore.data.map { prefs -> prefs[usernameKey(scopeId)] ?: "" }
    }

    val creationDate: Flow<Long> = activeUidFlow.flatMapLatest { uid ->
        val scopeId = getScopeId(uid)
        flow {
            val initialPrefs = dataStore.data.first()
            var storedDate = initialPrefs[creationDateKey(scopeId)]
            if (storedDate == null) {
                val newDate = System.currentTimeMillis()
                dataStore.edit { prefs ->
                    if (prefs[creationDateKey(scopeId)] == null) {
                        prefs[creationDateKey(scopeId)] = newDate
                    }
                }
                storedDate = newDate
            }
            emitAll(dataStore.data.map { prefs -> 
                prefs[creationDateKey(scopeId)] ?: storedDate
            })
        }
    }

    val isLocationEnabled: Flow<Boolean> = activeUidFlow.flatMapLatest { uid ->
        val scopeId = getScopeId(uid)
        dataStore.data.map { prefs -> prefs[locationEnabledKey(scopeId)] ?: false }
    }

    suspend fun saveAlias(alias: String) {
        val uid = activeUidFlow.first()
        val scopeId = getScopeId(uid)
        dataStore.edit { prefs ->
            prefs[aliasKey(scopeId)] = alias
        }
    }

    suspend fun saveUsername(username: String) {
        val uid = activeUidFlow.first()
        val scopeId = getScopeId(uid)
        dataStore.edit { prefs ->
            prefs[usernameKey(scopeId)] = username
        }
    }

    suspend fun setCreationDateIfEmpty(date: Long) {
        val uid = activeUidFlow.first()
        val scopeId = getScopeId(uid)
        dataStore.edit { prefs ->
            if (prefs[creationDateKey(scopeId)] == null) {
                prefs[creationDateKey(scopeId)] = date
            }
        }
    }

    suspend fun setLocationEnabled(enabled: Boolean) {
        val uid = activeUidFlow.first()
        val scopeId = getScopeId(uid)
        dataStore.edit { prefs ->
            prefs[locationEnabledKey(scopeId)] = enabled
        }
    }

    // Hydration functions to safely set remote data only to the specific UID
    suspend fun hydrateAlias(uid: String, alias: String) {
        val scopeId = getScopeId(uid)
        dataStore.edit { prefs ->
            prefs[aliasKey(scopeId)] = alias
        }
    }

    suspend fun hydrateUsername(uid: String, username: String) {
        val scopeId = getScopeId(uid)
        dataStore.edit { prefs ->
            prefs[usernameKey(scopeId)] = username
        }
    }

    suspend fun migrateLegacy() {
        dataStore.edit { prefs ->
            if (prefs[MIGRATED_LEGACY_V1] != true) {
                val legacyAlias = prefs[LEGACY_ALIAS_KEY]
                val legacyUsername = prefs[LEGACY_USERNAME_KEY]
                val legacyCreationDate = prefs[LEGACY_CREATION_DATE_KEY]
                val legacyLocation = prefs[LEGACY_LOCATION_ENABLED_KEY]

                if (legacyAlias != null) prefs[aliasKey("legacy_unassigned")] = legacyAlias
                if (legacyUsername != null) prefs[usernameKey("legacy_unassigned")] = legacyUsername
                if (legacyCreationDate != null) prefs[creationDateKey("legacy_unassigned")] = legacyCreationDate
                if (legacyLocation != null) prefs[locationEnabledKey("legacy_unassigned")] = legacyLocation

                prefs.remove(LEGACY_ALIAS_KEY)
                prefs.remove(LEGACY_USERNAME_KEY)
                prefs.remove(LEGACY_CREATION_DATE_KEY)
                prefs.remove(LEGACY_LOCATION_ENABLED_KEY)

                prefs[MIGRATED_LEGACY_V1] = true
            }
        }
    }
}
