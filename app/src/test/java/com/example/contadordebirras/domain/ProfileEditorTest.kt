package com.example.contadordebirras.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.contadordebirras.data.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileEditorTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var activeUid: MutableStateFlow<String?>
    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tmpFolder.root, "test_prefs_editor.preferences_pb") }
        )
        activeUid = MutableStateFlow(null)
        repository = UserRepository(dataStore, activeUid)
    }

    @Test
    fun `setAlias a no se sincroniza a B remoto`() = runTest {
        var remoteSyncedAlias: String? = null
        var remoteSyncedUid: String? = null
        var simulateAuthChange: () -> Unit = {}
        
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { null },
            syncRemoteProfile = { alias, uid ->
                remoteSyncedAlias = alias
                remoteSyncedUid = uid
                simulateAuthChange()
            }
        )
        
        activeUid.value = "userA"
        simulateAuthChange = { activeUid.value = "userB" } // Will happen INSIDE syncRemoteProfile mock, but wait, the check is BEFORE syncRemoteProfile
        
        // Instead of simulating inside syncRemoteProfile, let's simulate the delay
        val delayedEditor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { null },
            syncRemoteProfile = { alias, uid ->
                remoteSyncedAlias = alias
                remoteSyncedUid = uid
            }
        )
        
        // We need activeUid to change BEFORE syncRemoteProfile is called!
        // To simulate that in this synchronous test, we pass a provider that changes state on the second call.
        var callCount = 0
        delayedEditor.setAlias("Alias A") {
            callCount++
            if (callCount == 1) "userA" else {
                activeUid.value = "userB"
                "userB"
            }
        }
        
        // Should NOT have synced remotely because UID changed
        assertNull(remoteSyncedAlias)
        
        // But SHOULD have saved locally to userA (scope captured at step 1)
        activeUid.value = "userA"
        assertEquals("Alias A", repository.userAlias.first())
        
        activeUid.value = "userB"
        assertEquals("Cervecero", repository.userAlias.first())
    }

    @Test
    fun `setAlias guest no se sincroniza`() = runTest {
        var remoteSynced = false
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { null },
            syncRemoteProfile = { _, _ -> remoteSynced = true }
        )
        
        activeUid.value = null
        editor.setAlias("Alias Guest") { activeUid.value }
        
        assertEquals(false, remoteSynced)
        assertEquals("Alias Guest", repository.userAlias.first())
    }

    @Test
    fun `setUsername a no se guarda localmente en B si auth cambia`() = runTest {
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { 
                // Simulate network returning success, but auth changed during network
                activeUid.value = "userB"
                null 
            },
            syncRemoteProfile = { _, _ -> }
        )
        
        activeUid.value = "userA"
        val result = editor.setUsername("user_a") { activeUid.value }
        
        assertEquals("La sesión cambió durante la operación.", result)
        
        activeUid.value = "userB"
        assertEquals("", repository.username.first())
        
        activeUid.value = "userA"
        assertEquals("", repository.username.first()) // Did not save anywhere
    }
}
