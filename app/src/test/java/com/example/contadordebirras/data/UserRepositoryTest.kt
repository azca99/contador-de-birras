package com.example.contadordebirras.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UserRepositoryTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var activeUid: MutableStateFlow<String?>
    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tmpFolder.root, "test_prefs.preferences_pb") }
        )
        activeUid = MutableStateFlow(null)
        repository = UserRepository(dataStore, activeUid)
    }

    @Test
    fun `alias A no aparece en B, ni en guest`() = runTest {
        activeUid.value = "userA"
        repository.saveAlias("Alias A")
        
        activeUid.value = "userB"
        repository.saveAlias("Alias B")

        activeUid.value = "userA"
        assertEquals("Alias A", repository.userAlias.first())

        activeUid.value = "userB"
        assertEquals("Alias B", repository.userAlias.first())
        
        activeUid.value = null // logout -> guest
        assertEquals("Cervecero", repository.userAlias.first()) // default
    }

    @Test
    fun `username A no aparece en B`() = runTest {
        activeUid.value = "userA"
        repository.saveUsername("username_a")
        
        activeUid.value = "userB"
        repository.saveUsername("username_b")

        activeUid.value = "userA"
        assertEquals("username_a", repository.username.first())
    }

    @Test
    fun `locationEnabled A no aparece en B`() = runTest {
        activeUid.value = "userA"
        repository.setLocationEnabled(true)
        
        activeUid.value = "userB"
        repository.setLocationEnabled(false)

        activeUid.value = "userA"
        assertEquals(true, repository.isLocationEnabled.first())
    }

    @Test
    fun `creationDate A no aparece en B y se autogenera`() = runTest {
        activeUid.value = "userA"
        val dateA = repository.creationDate.first()
        
        activeUid.value = "userB"
        val dateB = repository.creationDate.first()

        activeUid.value = "userA"
        assertEquals(dateA, repository.creationDate.first())
        assertNotEquals(dateA, dateB) // Podrian ser iguales si el test corre en 0ms, pero no comparten clave
        
        // Explicitly check isolation with setCreationDateIfEmpty
        activeUid.value = "userC"
        repository.setCreationDateIfEmpty(100L)
        assertEquals(100L, repository.creationDate.first())
        
        activeUid.value = "userD"
        repository.setCreationDateIfEmpty(200L)
        assertEquals(200L, repository.creationDate.first())
    }

    @Test
    fun `migracion mueve alias global a legacy_unassigned y lo elimina de global`() = runTest {
        // Pre-populate legacy
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("alias")] = "Legacy Alias"
            prefs[stringPreferencesKey("username")] = "LegacyUsername"
            prefs[longPreferencesKey("creation_date")] = 999L
            prefs[booleanPreferencesKey("location_enabled")] = true
        }

        repository.migrateLegacy()

        // After migration, global keys should not exist in datastore
        val currentPrefs = dataStore.data.first()
        assertEquals(null, currentPrefs[stringPreferencesKey("alias")])
        assertEquals(null, currentPrefs[stringPreferencesKey("username")])
        
        // Legacy keys should be in legacy_unassigned scope
        assertEquals("Legacy Alias", currentPrefs[stringPreferencesKey("user_legacy_unassigned_alias")])
        assertEquals("LegacyUsername", currentPrefs[stringPreferencesKey("user_legacy_unassigned_username")])
        assertEquals(999L, currentPrefs[longPreferencesKey("user_legacy_unassigned_creation_date")])
        assertEquals(true, currentPrefs[booleanPreferencesKey("user_legacy_unassigned_location_enabled")])
        
        // Active user shouldn't see legacy
        activeUid.value = "userA"
        assertEquals("Cervecero", repository.userAlias.first())
    }

    @Test
    fun `migracion es idempotente`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("alias")] = "Legacy Alias"
        }
        repository.migrateLegacy()
        
        // Somebody writes a new 'alias' (shouldn't happen, but test idempotency)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("alias")] = "Rogue Alias"
        }
        repository.migrateLegacy()
        
        val currentPrefs = dataStore.data.first()
        // Legacy should still be "Legacy Alias", not "Rogue Alias"
        assertEquals("Legacy Alias", currentPrefs[stringPreferencesKey("user_legacy_unassigned_alias")])
    }

    @Test
    fun `saveAlias iniciado como A no termina escribiendo B si cambia el scope`() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        activeUid.value = "userA"
        // This validates our structure where saveAlias reads first() and uses it
        val job = launch {
            repository.saveAlias("Alias A")
        }
        activeUid.value = "userB"
        job.join()
        
        val currentPrefs = dataStore.data.first()
        assertEquals("Alias A", currentPrefs[stringPreferencesKey("user_userA_alias")])
        assertEquals(null, currentPrefs[stringPreferencesKey("user_userB_alias")])
    }

    @Test
    fun `hydrateAlias y hydrateUsername escriben solo en el UID especificado`() = runTest {
        activeUid.value = "guest_local" // doesn't matter what's active
        
        repository.hydrateAlias("userX", "Remote Alias")
        repository.hydrateUsername("userX", "RemoteUser")
        
        activeUid.value = "userX"
        assertEquals("Remote Alias", repository.userAlias.first())
        assertEquals("RemoteUser", repository.username.first())
        
        activeUid.value = "guest_local"
        assertEquals("Cervecero", repository.userAlias.first()) // guest is untouched
    }
}
