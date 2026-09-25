package com.example.contadordebirras.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.example.contadordebirras.data.UserRepository
import kotlinx.coroutines.flow.first

class ProfileHydratorTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var activeUid: MutableStateFlow<String?>
    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tmpFolder.root, "test_prefs_hydrator.preferences_pb") }
        )
        activeUid = MutableStateFlow(null)
        repository = UserRepository(dataStore, activeUid)
    }

    @Test
    fun `hydrateIfActive descarta hidratacion si sesion obsoleta`() = runTest {
        var simulateUidChange: () -> Unit = {}
        val hydrator = ProfileHydrator(repository) { uid ->
            // Network delay simulation: change UID before returning
            simulateUidChange()
            Pair("Alias Remoto", "user_remoto")
        }

        activeUid.value = "userA"
        simulateUidChange = { activeUid.value = "userB" }
        
        hydrator.hydrateIfActive("userA") { activeUid.value }
        
        // It should NOT have saved to userA because activeUid changed to userB
        activeUid.value = "userA"
        assertEquals("Cervecero", repository.userAlias.first())
        assertEquals("", repository.username.first())
    }

    @Test
    fun `hydrateIfActive aplica hidratacion si sesion coincide`() = runTest {
        val hydrator = ProfileHydrator(repository) { uid ->
            Pair("Alias Remoto", "user_remoto")
        }

        activeUid.value = "userA"
        hydrator.hydrateIfActive("userA") { activeUid.value }
        
        assertEquals("Alias Remoto", repository.userAlias.first())
        assertEquals("user_remoto", repository.username.first())
    }

    @Test
    fun `ausencia de red no borra preferencias existentes`() = runTest {
        val hydrator = ProfileHydrator(repository) { uid ->
            null // Network failure / doc not found
        }

        activeUid.value = "userA"
        repository.saveAlias("Local Alias")
        
        hydrator.hydrateIfActive("userA") { activeUid.value }
        
        assertEquals("Local Alias", repository.userAlias.first())
    }
}
