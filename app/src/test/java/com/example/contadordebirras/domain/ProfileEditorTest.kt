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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After

class ProfileEditorTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var activeUid: MutableStateFlow<String?>
    private lateinit var repository: UserRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tmpFolder.root, "test_prefs_editor.preferences_pb") }
        )
        activeUid = MutableStateFlow(null)
        repository = UserRepository(dataStore, activeUid)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `A setUsername desde guest remote no se invoca`() = runTest {
        var remoteCalled = false
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { _, _ -> 
                remoteCalled = true
                null 
            },
            syncRemoteProfile = { _, _ -> }
        )
        
        activeUid.value = null
        val result = editor.setUsername("guest_user", null) { activeUid.value }
        assert(result?.contains("iniciar sesi") == true)
        assertEquals(false, remoteCalled)
        assertEquals("", repository.username.first())
    }

    @Test
    fun `B expectedUid operacion iniciada por A pasa exactamente userA al callback remoto`() = runTest {
        var passedUid: String? = null
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { _, expectedUid -> 
                passedUid = expectedUid
                null 
            },
            syncRemoteProfile = { _, _ -> }
        )
        
        activeUid.value = "userA"
        editor.setUsername("my_user", "userA") { activeUid.value }
        assertEquals("userA", passedUid)
    }

    @Test
    fun `C exito normal A remote recibe A sigue A username se guarda en A B permanece vacio`() = runTest {
        var passedUid: String? = null
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { _, expectedUid -> 
                passedUid = expectedUid
                null 
            },
            syncRemoteProfile = { _, _ -> }
        )
        
        activeUid.value = "userA"
        val result = editor.setUsername("user_a", "userA") { activeUid.value }
        
        assertNull(result)
        assertEquals("userA", passedUid)
        
        activeUid.value = "userA"
        assertEquals("user_a", repository.username.first())
        
        activeUid.value = "userB"
        assertEquals("", repository.username.first())
    }

    @Test
    fun `D UID capturado A pero current ya B ANTES de ejecutar la logica suspendida no se llama remoto no se escribe B`() = runTest {
        var remoteCalled = false
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { _, _ -> 
                remoteCalled = true
                null 
            },
            syncRemoteProfile = { _, _ -> }
        )
        
        activeUid.value = "userB" // Changed before launch executed
        val result = editor.setUsername("sneaky", "userA") { activeUid.value }
        
        assert(result?.contains("cambi") == true && result?.contains("antes") == true)
        assertEquals(false, remoteCalled)
        
        activeUid.value = "userB"
        assertEquals("", repository.username.first())
        
        activeUid.value = "userA"
        assertEquals("", repository.username.first())
    }

    @Test
    fun `E alias con initialUid A pero current B al comenzar la coroutine Alias A no se guarda en B no se sincroniza remotamente como B`() = runTest {
        var remoteSyncedAlias: String? = null
        var remoteSyncedUid: String? = null
        
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { _, _ -> null },
            syncRemoteProfile = { alias, uid ->
                remoteSyncedAlias = alias
                remoteSyncedUid = uid
            }
        )
        
        activeUid.value = "userB" // Changed before coroutine executes
        
        // Editor is called with initialUid="userA", but current provider returns "userB"
        editor.setAlias("Alias A", "userA") { activeUid.value }
        
        // Should NOT have synced remotely because activeUid is B but initialUid was A
        assertNull(remoteSyncedAlias)
        
        // Should have saved locally to userA ONLY
        activeUid.value = "userA"
        assertEquals("Alias A", repository.userAlias.first())
        
        activeUid.value = "userB"
        assertEquals("Cervecero", repository.userAlias.first())
    }

    @Test
    fun `setUsername a no se guarda localmente en B si auth cambia durante network`() = runTest {
        val editor = ProfileEditor(
            userRepository = repository,
            setRemoteUsername = { _, _ -> 
                activeUid.value = "userB"
                null 
            },
            syncRemoteProfile = { _, _ -> }
        )
        
        activeUid.value = "userA"
        val result = editor.setUsername("user_a", "userA") { activeUid.value }
        
        assert(result?.contains("cambi") == true && result?.contains("durante") == true)
        
        activeUid.value = "userB"
        assertEquals("", repository.username.first())
        
        activeUid.value = "userA"
        assertEquals("", repository.username.first())
    }
}
