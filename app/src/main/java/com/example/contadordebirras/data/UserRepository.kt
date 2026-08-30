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
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserRepository(private val context: Context) {

    private val ALIAS_KEY = stringPreferencesKey("alias")
    private val USERNAME_KEY = stringPreferencesKey("username")
    private val CREATION_DATE_KEY = longPreferencesKey("creation_date")
    private val LOCATION_ENABLED_KEY = booleanPreferencesKey("location_enabled")

    val userAlias: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ALIAS_KEY] ?: "Cervecero"
    }

    val username: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[USERNAME_KEY] ?: ""
    }

    val creationDate: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[CREATION_DATE_KEY] ?: System.currentTimeMillis()
    }

    val isLocationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[LOCATION_ENABLED_KEY] ?: false
    }

    suspend fun saveAlias(alias: String) {
        context.dataStore.edit { prefs ->
            prefs[ALIAS_KEY] = alias
        }
    }

    suspend fun saveUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[USERNAME_KEY] = username
        }
    }

    suspend fun setCreationDateIfEmpty(date: Long) {
        context.dataStore.edit { prefs ->
            if (prefs[CREATION_DATE_KEY] == null) {
                prefs[CREATION_DATE_KEY] = date
            }
        }
    }

    suspend fun setLocationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[LOCATION_ENABLED_KEY] = enabled
        }
    }
}
