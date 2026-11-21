package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.BeerSyncScheduler
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class C4ControlledActivationTest {

    private lateinit var context: Context
    private val userA = "authorized_user_alice"
    private val userB = "unauthorized_user_bob"

    private lateinit var testDatabase: BeerDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        C4SyncConfig.reset()
        C4SyncStateStore.clearForTesting(context, userA)
        C4SyncStateStore.clearForTesting(context, userB)

        testDatabase = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        testDatabase.close()
        C4SyncConfig.reset()
        C4SyncStateStore.clearForTesting(context, userA)
        C4SyncStateStore.clearForTesting(context, userB)
    }

    @Test
    fun testStableAuthorizationAfterRestarts_persistsAndRestoresSeamlessly() {
        // 1. Configuración inicial con valores seguros por defecto
        assertEquals(false, C4SyncConfig.isProductionC4FeatureEnabled)
        assertTrue(C4SyncConfig.authorizedPilotUids.isEmpty())
        assertFalse(C4SyncConfig.isUserAuthorizedForC4(userA))

        // 2. Configurar activación controlada persistente para userA
        C4SyncConfig.configureControlledActivation(context, featureEnabled = true, authorizedUid = userA)
        C4SyncStateStore.setEngineMode(context, userA, SyncEngineMode.C4_ACTIVE)

        assertTrue(C4SyncConfig.isProductionC4FeatureEnabled)
        assertTrue(C4SyncConfig.isUserAuthorizedForC4(userA))
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncStateStore.getEngineMode(context, userA))

        // 3. Simular reinicio de la aplicación (proceso muere y C4SyncConfig se resetea a valores iniciales)
        C4SyncConfig.reset()
        assertEquals(false, C4SyncConfig.isProductionC4FeatureEnabled)
        assertTrue(C4SyncConfig.authorizedPilotUids.isEmpty())
        assertNull(C4SyncConfig.currentActiveOwnerUid)
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode)

        // 4. Simular inicialización de la app (Application.onCreate -> restoreConfiguration)
        C4SyncConfig.restoreConfiguration(context)
        assertTrue("El feature flag debe restaurarse en true", C4SyncConfig.isProductionC4FeatureEnabled)
        assertTrue("userA debe estar en la lista de UIDs autorizados tras reinicio", C4SyncConfig.isUserAuthorizedForC4(userA))

        // 5. Simular login / selección de userA
        val restoredMode = C4SyncConfig.restoreUserEngineMode(context, userA)
        assertEquals(SyncEngineMode.C4_ACTIVE, restoredMode)
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)
        assertTrue(C4SyncConfig.isEnabled)
        assertTrue(C4SyncConfig.isC4Active())
        assertEquals(userA, C4SyncConfig.currentActiveOwnerUid)
        assertTrue(C4SyncConfig.isC4ActiveForUser(context, userA))
    }

    @Test
    fun testAccountIsolation_userBContinuesInLegacyEvenWhenUserAIsC4ActiveOrHalted() = runBlocking {
        // 1. Configurar Cuenta A como C4 activa
        C4SyncConfig.configureControlledActivation(context, featureEnabled = true, authorizedUid = userA)
        C4SyncConfig.setUserEngineMode(context, userA, SyncEngineMode.C4_ACTIVE)
        assertEquals(userA, C4SyncConfig.currentActiveOwnerUid)
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)
        assertTrue(C4SyncConfig.isEnabled)

        // 2. Cuenta B no autorizada y en LEGACY
        assertEquals(SyncEngineMode.LEGACY, C4SyncStateStore.getEngineMode(context, userB))
        assertFalse(C4SyncConfig.isUserAuthorizedForC4(userB))
        assertFalse(C4SyncConfig.isC4ActiveForUser(context, userB))

        // 3. Preparar repositorio para Cuenta B
        val authUserB = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns userB
        }
        val currentUserFlow = MutableStateFlow<FirebaseUser?>(authUserB)
        val mockAuthRepo = mockk<AuthRepository>(relaxed = true) {
            every { currentUser } returns currentUserFlow
        }
        val mockScheduler = mockk<BeerSyncScheduler>(relaxed = true)

        val prodContext = object : android.content.ContextWrapper(context) {
            override fun getPackageName(): String = "com.daniel.contadordebirras"
        }

        val repository = BeerRepository(
            beerDao = testDatabase.beerDao(),
            context = prodContext,
            authRepository = mockAuthRepo,
            syncScheduler = mockScheduler
        )

        // Registrar cerveza con Cuenta B: NO debe ser bloqueada por el estado en memoria de Cuenta A
        val beerId1 = repository.addBeer(BeerType.CANA, System.currentTimeMillis())
        assertTrue("Cuenta B debe registrar cerveza en Legacy sin ser bloqueada", beerId1 > 0)

        val savedBeer1 = testDatabase.beerDao().getBeerById(beerId1.toInt(), userB)
        assertNotNull(savedBeer1)
        assertEquals(userB, savedBeer1?.ownerUid)
        assertEquals("Debe ser registrada con revision 0 (Legacy)", 0L, savedBeer1?.remoteRevision)

        // 4. Ahora simular que Cuenta A entra en HALTED
        C4SyncConfig.setUserEngineMode(context, userA, SyncEngineMode.HALTED)
        assertEquals(userA, C4SyncConfig.currentActiveOwnerUid)
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)

        // Registrar otra cerveza con Cuenta B: TAMPOCO debe ser bloqueada por el HALTED de Cuenta A
        val beerId2 = repository.addBeer(BeerType.BOTELLIN, System.currentTimeMillis())
        assertTrue("Cuenta B debe seguir operando en Legacy incluso si Cuenta A está en HALTED", beerId2 > 0)

        val savedBeer2 = testDatabase.beerDao().getBeerById(beerId2.toInt(), userB)
        assertNotNull(savedBeer2)
        assertEquals(userB, savedBeer2?.ownerUid)
        assertEquals(0L, savedBeer2?.remoteRevision)
    }

    @Test
    fun testControlledProductionConnection_allowsRealFirebaseOnlyWhenExplicitlyAuthorized() = runBlocking {
        // 1. Simular Firebase de producción
        val mockFirebaseApp = mockk<FirebaseApp>(relaxed = true) {
            every { options.projectId } returns "contador-de-birras-prod"
        }
        val mockFirestore = mockk<FirebaseFirestore>(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { firestoreSettings.host } returns "firestore.googleapis.com"
        }
        val mockStorageRef = mockk<StorageReference>(relaxed = true) {
            every { bucket } returns "contador-de-birras-prod.appspot.com"
        }
        val mockStorage = mockk<FirebaseStorage>(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { reference } returns mockStorageRef
        }

        val mockUserA = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns userA
        }
        val mockAuthA = mockk<FirebaseAuth>(relaxed = true) {
            every { currentUser } returns mockUserA
        }

        // 2. Para Cuenta B (no autorizada), el manager rechaza antes de conectar a Firestore
        val managerUnauth = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { mockFirestore },
            storageProvider = { mockStorage },
            authProvider = { mockAuthA },
            databaseProvider = { testDatabase }
        )

        val resultUnauth = managerUnauth.checkAndActivateIfAuthorized(userB)
        assertTrue("Usuario no autorizado debe ser rechazado sin tocar Firestore", resultUnauth is ActivationResult.Unauthorized)

        // 3. Para Cuenta A autorizada, con allowProduction por defecto (null),
        // diagnoseAccount e inicializaciones permiten producción sin violar restricciones
        C4SyncConfig.configureControlledActivation(context, featureEnabled = true, authorizedUid = userA)

        val managerAuth = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { mockFirestore },
            storageProvider = { mockStorage },
            authProvider = { mockAuthA },
            databaseProvider = { testDatabase }
        )

        val diagnosis = managerAuth.diagnoseAccount(userA)
        assertTrue("Diagnóstico para usuario autorizado debe completarse con éxito: $diagnosis", diagnosis is ActivationDiagnosisResult.ReadyForCutover)
    }
}
