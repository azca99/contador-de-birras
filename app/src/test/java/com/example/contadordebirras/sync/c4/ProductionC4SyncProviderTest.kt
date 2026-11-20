package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProductionC4SyncProviderTest {

    private lateinit var context: Context
    private val ownerUid = "prod_user_alice"

    private lateinit var mockAuth: FirebaseAuth
    private lateinit var mockUser: FirebaseUser
    private lateinit var mockFirestore: FirebaseFirestore
    private lateinit var mockStorage: FirebaseStorage
    private lateinit var mockStorageRef: StorageReference
    private lateinit var mockFirebaseApp: FirebaseApp
    private lateinit var testDatabase: BeerDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        C4SyncConfig.reset()
        C4SyncStateStore.clearForTesting(context, ownerUid)

        mockUser = mockk(relaxed = true) {
            every { uid } returns ownerUid
            every { email } returns "alice@example.com"
        }
        mockAuth = mockk(relaxed = true) {
            every { currentUser } returns mockUser
        }
        mockFirebaseApp = mockk(relaxed = true) {
            every { options.projectId } returns "contador-de-birras-prod"
        }
        mockFirestore = mockk(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { firestoreSettings.host } returns "firestore.googleapis.com"
        }
        mockStorageRef = mockk(relaxed = true) {
            every { bucket } returns "contador-de-birras-prod.appspot.com"
        }
        mockStorage = mockk(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { reference } returns mockStorageRef
        }

        testDatabase = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        testDatabase.close()
        C4SyncConfig.reset()
        C4SyncStateStore.clearForTesting(context, ownerUid)
    }

    private fun createProvider(): ProductionC4SyncProvider {
        return ProductionC4SyncProvider(
            firestoreProvider = { mockFirestore },
            storageProvider = { mockStorage },
            authProvider = { mockAuth },
            databaseProvider = { testDatabase }
        )
    }

    @Test
    fun testProductionProvider_whenFeatureDisabled_returnsInactiveOrBlocked() = runBlocking {
        // C4SyncConfig.isProductionC4FeatureEnabled es false por defecto
        assertEquals(false, C4SyncConfig.isProductionC4FeatureEnabled)
        C4SyncStateStore.setEngineMode(context, ownerUid, SyncEngineMode.C4_ACTIVE)

        val provider = createProvider()
        val result = provider.syncUser(context, ownerUid)

        assertTrue("Debe bloquearse cuando la feature flag de producción está desactivada",
            result is C4ProviderSyncResult.InactiveOrBlocked)
        val blocked = result as C4ProviderSyncResult.InactiveOrBlocked
        assertTrue(blocked.reason.contains("desactivado por defecto en producción"))
    }

    @Test
    fun testProductionProvider_whenUserInLegacyMode_returnsInactiveOrBlocked() = runBlocking {
        C4SyncConfig.isProductionC4FeatureEnabled = true
        // El modo del usuario es LEGACY por defecto
        assertEquals(SyncEngineMode.LEGACY, C4SyncStateStore.getEngineMode(context, ownerUid))

        val provider = createProvider()
        val result = provider.syncUser(context, ownerUid)

        assertTrue("Debe omitir sincronización si el usuario está en modo LEGACY",
            result is C4ProviderSyncResult.InactiveOrBlocked)
        val blocked = result as C4ProviderSyncResult.InactiveOrBlocked
        assertTrue(blocked.reason.contains("no está en modo C4_ACTIVE"))
    }

    @Test
    fun testProductionProvider_whenUserHalted_returnsInactiveOrBlocked() = runBlocking {
        C4SyncConfig.isProductionC4FeatureEnabled = true
        C4SyncStateStore.setEngineMode(context, ownerUid, SyncEngineMode.HALTED)

        val provider = createProvider()
        val result = provider.syncUser(context, ownerUid)

        assertTrue("Debe bloquearse estrictamente si la cuenta está en HALTED",
            result is C4ProviderSyncResult.InactiveOrBlocked)
        val blocked = result as C4ProviderSyncResult.InactiveOrBlocked
        assertTrue(blocked.reason.contains("no está en modo C4_ACTIVE"))
    }

    @Test
    fun testProductionProvider_whenUserCutoverInProgress_returnsInactiveOrBlocked() = runBlocking {
        C4SyncConfig.isProductionC4FeatureEnabled = true
        C4SyncStateStore.setEngineMode(context, ownerUid, SyncEngineMode.CUTOVER_IN_PROGRESS)

        val provider = createProvider()
        val result = provider.syncUser(context, ownerUid)

        assertTrue("Debe bloquearse si la cuenta está en CUTOVER_IN_PROGRESS",
            result is C4ProviderSyncResult.InactiveOrBlocked)
    }

    @Test
    fun testProductionProvider_whenAuthMismatch_returnsInactiveOrBlocked() = runBlocking {
        C4SyncConfig.isProductionC4FeatureEnabled = true
        C4SyncStateStore.setEngineMode(context, ownerUid, SyncEngineMode.C4_ACTIVE)

        // Simular que el usuario autenticado tiene un UID diferente
        every { mockUser.uid } returns "another_user"

        val provider = createProvider()
        val result = provider.syncUser(context, ownerUid)

        assertTrue("Debe bloquearse ante discordancia de identidad",
            result is C4ProviderSyncResult.InactiveOrBlocked)
        val blocked = result as C4ProviderSyncResult.InactiveOrBlocked
        assertTrue(blocked.reason.contains("Sesión no válida o UID discordante"))
    }

    @Test
    fun testProductionProvider_whenAuthNull_returnsInactiveOrBlocked() = runBlocking {
        C4SyncConfig.isProductionC4FeatureEnabled = true
        C4SyncStateStore.setEngineMode(context, ownerUid, SyncEngineMode.C4_ACTIVE)

        every { mockAuth.currentUser } returns null

        val provider = createProvider()
        val result = provider.syncUser(context, ownerUid)

        assertTrue("Debe bloquearse si no hay sesión autenticada",
            result is C4ProviderSyncResult.InactiveOrBlocked)
    }
}
