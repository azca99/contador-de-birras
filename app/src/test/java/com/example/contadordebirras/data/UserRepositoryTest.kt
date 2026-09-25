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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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
    fun `secuencia completa A - guest - B - guest - A`() = runTest {
        // A
        activeUid.value = "userA"
        repository.saveAlias("Alias A")
        assertEquals("Alias A", repository.userAlias.first())
        
        // Guest
        activeUid.value = null
        assertEquals("Cervecero", repository.userAlias.first())
        repository.saveAlias("Alias Guest")
        assertEquals("Alias Guest", repository.userAlias.first())
        
        // B
        activeUid.value = "userB"
        assertEquals("Cervecero", repository.userAlias.first())
        repository.saveAlias("Alias B")
        assertEquals("Alias B", repository.userAlias.first())
        
        // Guest again
        activeUid.value = null
        assertEquals("Alias Guest", repository.userAlias.first())
        
        // A again
        activeUid.value = "userA"
        assertEquals("Alias A", repository.userAlias.first())
    }

    @Test
    fun `guest y legacy no comparten alias ni username`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("alias")] = "Legacy Alias"
            prefs[stringPreferencesKey("username")] = "legacy_user"
        }
        repository.migrateLegacy()
        
        activeUid.value = null // Guest
        assertEquals("Cervecero", repository.userAlias.first())
        assertEquals("", repository.username.first())
    }

    @Test
    fun `B y legacy no comparten preferencias`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("alias")] = "Legacy Alias"
        }
        repository.migrateLegacy()
        
        activeUid.value = "userB"
        assertEquals("Cervecero", repository.userAlias.first())
    }

    @Test
    fun `migracion elimina TODAS las claves globales y las mueve a legacy`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("alias")] = "Legacy Alias"
            prefs[stringPreferencesKey("username")] = "LegacyUser"
            prefs[longPreferencesKey("creation_date")] = 500L
            prefs[booleanPreferencesKey("location_enabled")] = true
        }
        
        repository.migrateLegacy()
        
        val prefs = dataStore.data.first()
        // Deleted globals
        assertEquals(null, prefs[stringPreferencesKey("alias")])
        assertEquals(null, prefs[stringPreferencesKey("username")])
        assertEquals(null, prefs[longPreferencesKey("creation_date")])
        assertEquals(null, prefs[booleanPreferencesKey("location_enabled")])
        
        // Moved to legacy
        assertEquals("Legacy Alias", prefs[stringPreferencesKey("user_legacy_unassigned_alias")])
        assertEquals("LegacyUser", prefs[stringPreferencesKey("user_legacy_unassigned_username")])
        assertEquals(500L, prefs[longPreferencesKey("user_legacy_unassigned_creation_date")])
        assertEquals(true, prefs[booleanPreferencesKey("user_legacy_unassigned_location_enabled")])
    }

    @Test
    fun `saveUsername dirigido a A nunca escribe B con scope explicito`() = runTest {
        repository.saveUsernameForScope("userA", "user_a")
        
        activeUid.value = "userA"
        assertEquals("user_a", repository.username.first())
        
        activeUid.value = "userB"
        assertEquals("", repository.username.first())
    }

    @Test
    fun `saveAlias dirigido a A nunca escribe B con scope explicito`() = runTest {
        repository.saveAliasForScope("userA", "alias_a")
        
        activeUid.value = "userA"
        assertEquals("alias_a", repository.userAlias.first())
        
        activeUid.value = "userB"
        assertEquals("Cervecero", repository.userAlias.first())
    }

    @Test
    fun `setLocationEnabled A no modifica B`() = runTest {
        activeUid.value = "userA"
        repository.setLocationEnabled(true)
        
        activeUid.value = "userB"
        assertEquals(false, repository.isLocationEnabled.first())
    }

    @Test
    fun `setCreationDateIfEmpty A no modifica B`() = runTest {
        activeUid.value = "userA"
        repository.setCreationDateIfEmpty(1234L)
        
        activeUid.value = "userB"
        val dateB = repository.creationDate.first()
        assertNotEquals(1234L, dateB)
    }

    @Test
    fun `mismo scope conserva creationDate entre nuevas colecciones`() = runTest {
        activeUid.value = "userA"
        val date1 = repository.creationDate.first()
        
        // Change auth and back to force new collection
        activeUid.value = "userB"
        repository.creationDate.first()
        
        activeUid.value = "userA"
        val date2 = repository.creationDate.first()
        
        assertEquals(date1, date2)
    }
    
    @Test
    fun `hydrateAlias A no modifica B`() = runTest {
        // En la implementación real hydrateAlias es saveAliasForScope
        repository.saveAliasForScope("userA", "hydrated_alias")
        
        activeUid.value = "userB"
        assertEquals("Cervecero", repository.userAlias.first())
    }

    @Test
    fun `hydrateUsername A no modifica B`() = runTest {
        repository.saveUsernameForScope("userA", "hydrated_user")
        
        activeUid.value = "userB"
        assertEquals("", repository.username.first())
    }

    @Test
    fun `saveAlias iniciado como A no termina escribiendo B si cambia el scope (convenience)`() = runTest(UnconfinedTestDispatcher()) {
        activeUid.value = "userA"
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
    fun `saveUsername iniciado como A no termina escribiendo B si cambia el scope (convenience)`() = runTest(UnconfinedTestDispatcher()) {
        activeUid.value = "userA"
        val job = launch {
            repository.saveUsername("username_a")
        }
        activeUid.value = "userB"
        job.join()
        
        val currentPrefs = dataStore.data.first()
        assertEquals("username_a", currentPrefs[stringPreferencesKey("user_userA_username")])
        assertEquals(null, currentPrefs[stringPreferencesKey("user_userB_username")])
    }
}
