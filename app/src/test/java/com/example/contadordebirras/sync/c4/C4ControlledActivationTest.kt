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

        // 2. Cuenta B no autorizada y en LEGACY confirmada
        C4SyncStateStore.setEngineMode(context, userB, SyncEngineMode.LEGACY)
        C4SyncStateStore.setProtocolVerified(context, userB, true)
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

    @Test
    fun testMultiDeviceProtocolDetection_remoteC4OnUnauthorizedDevice_blocksAndPreventsLegacy() = runBlocking {
        // Simular que la cuenta ya está migrada a C4 en otro dispositivo (remoto /users/{userA} es C4)
        val mockFirebaseApp = mockk<FirebaseApp>(relaxed = true) {
            every { options.projectId } returns "contador-de-birras-prod"
        }
        val mockDocSnapshot = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true) {
            every { exists() } returns true
            every { getString("syncProtocolVersion") } returns "C4"
            every { getString("accountSyncState") } returns "MIGRATED_TO_C4"
            every { getLong("minSupportedAppVersionCode") } returns 1L
            every { getLong("c4MigratedAt") } returns 123456789L
        }
        val mockDocRef = mockk<com.google.firebase.firestore.DocumentReference>(relaxed = true) {
            every { get(com.google.firebase.firestore.Source.SERVER) } returns com.google.android.gms.tasks.Tasks.forResult(mockDocSnapshot)
        }
        val mockCollection = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true) {
            every { document(userA) } returns mockDocRef
        }
        val mockFirestore = mockk<FirebaseFirestore>(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { collection("users") } returns mockCollection
        }
        val mockUserA = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns userA
        }
        val mockAuthA = mockk<FirebaseAuth>(relaxed = true) {
            every { currentUser } returns mockUserA
        }

        // En este dispositivo (Dispositivo 2), C4 no está autorizado
        C4SyncConfig.isProductionC4FeatureEnabled = false
        C4SyncConfig.clearAuthorizedPilotUids()
        assertEquals(SyncEngineMode.LEGACY, C4SyncStateStore.getEngineMode(context, userA))

        val manager = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { mockFirestore },
            storageProvider = { mockk(relaxed = true) },
            authProvider = { mockAuthA },
            databaseProvider = { testDatabase },
            allowProduction = true
        )

        val result = manager.detectAndEnforceAccountProtocol(userA)

        // Debe ser bloqueado en HALTED y jamás asumir Legacy
        assertTrue("Dispositivo no autorizado para cuenta C4 debe ser bloqueado: $result", result is ProtocolResult.Blocked)
        assertEquals("El modo persistente debe pasar a HALTED", SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, userA))
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertTrue(C4SyncStateStore.isLegacyBlocked(context, userA))
    }

    @Test
    fun testMultiDeviceProtocolDetection_genuineLegacy_confirmsLegacy() = runBlocking {
        // Simular cuenta que sigue en Legacy en el servidor (remoto no existe o syncProtocolVersion != "C4")
        val mockFirebaseApp = mockk<FirebaseApp>(relaxed = true) {
            every { options.projectId } returns "contador-de-birras-prod"
        }
        val mockDocSnapshot = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true) {
            every { exists() } returns false
        }
        val mockDocRef = mockk<com.google.firebase.firestore.DocumentReference>(relaxed = true) {
            every { get(com.google.firebase.firestore.Source.SERVER) } returns com.google.android.gms.tasks.Tasks.forResult(mockDocSnapshot)
        }
        val mockCollection = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true) {
            every { document(userB) } returns mockDocRef
        }
        val mockFirestore = mockk<FirebaseFirestore>(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { collection("users") } returns mockCollection
        }
        val mockUserB = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns userB
        }
        val mockAuthB = mockk<FirebaseAuth>(relaxed = true) {
            every { currentUser } returns mockUserB
        }

        val manager = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { mockFirestore },
            storageProvider = { mockk(relaxed = true) },
            authProvider = { mockAuthB },
            databaseProvider = { testDatabase },
            allowProduction = true
        )

        val result = manager.detectAndEnforceAccountProtocol(userB)

        assertTrue("Cuenta legacy real debe ser confirmada como Legacy: $result", result is ProtocolResult.Legacy)
        assertEquals(SyncEngineMode.LEGACY, C4SyncStateStore.getEngineMode(context, userB))
        assertTrue(C4SyncStateStore.isProtocolVerified(context, userB))
        assertTrue(C4SyncStateStore.hasReliableLocalState(context, userB))
    }

    @Test
    fun testMultiDeviceProtocolDetection_unreachableRemoteWithoutReliableLocalState_doesNotAssumeLegacy() = runBlocking {
        // Simular fallo de red hacia Firestore
        val mockFirebaseApp = mockk<FirebaseApp>(relaxed = true) {
            every { options.projectId } returns "contador-de-birras-prod"
        }
        val mockDocRef = mockk<com.google.firebase.firestore.DocumentReference>(relaxed = true) {
            every { get(com.google.firebase.firestore.Source.SERVER) } throws RuntimeException("Network timeout / offline")
        }
        val mockCollection = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true) {
            every { document(userA) } returns mockDocRef
        }
        val mockFirestore = mockk<FirebaseFirestore>(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { collection("users") } returns mockCollection
        }
        val mockUserA = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns userA
        }
        val mockAuthA = mockk<FirebaseAuth>(relaxed = true) {
            every { currentUser } returns mockUserA
        }

        // Sin estado local fiable para userA
        C4SyncStateStore.clearForTesting(context, userA)
        assertFalse(C4SyncStateStore.hasReliableLocalState(context, userA))

        val manager = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { mockFirestore },
            storageProvider = { mockk(relaxed = true) },
            authProvider = { mockAuthA },
            databaseProvider = { testDatabase },
            allowProduction = true
        )

        val result = manager.detectAndEnforceAccountProtocol(userA)

        // NO asumir que es Legacy: debe retornar Unverified
        assertTrue("No debe asumir Legacy ante fallo remoto sin estado local fiable: $result", result is ProtocolResult.Unverified)
        assertFalse(C4SyncStateStore.isProtocolVerified(context, userA))

        // Al intentar escribir mediante BeerRepository sin estado local fiable, debe bloquear con IllegalStateException
        val prodContext = object : android.content.ContextWrapper(context) {
            override fun getPackageName(): String = "com.daniel.contadordebirras"
        }
        val currentUserFlow = MutableStateFlow<FirebaseUser?>(mockUserA)
        val mockAuthRepo = mockk<AuthRepository>(relaxed = true) {
            every { currentUser } returns currentUserFlow
        }
        val repository = BeerRepository(
            beerDao = testDatabase.beerDao(),
            context = prodContext,
            authRepository = mockAuthRepo,
            syncScheduler = mockk(relaxed = true)
        )

        try {
            repository.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe bloquear la escritura si el protocolo no es verificable y no existe estado local fiable")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Debe indicar bloqueo por protocolo no verificable",
                e.message?.contains("No se puede verificar el protocolo de la cuenta") == true ||
                e.message?.contains("No se pudo verificar el protocolo remoto") == true
            )
        }
    }

    @Test
    fun testLocalC4Active_remoteLegacy_haltsAndNeverRevertsToLegacy() = runBlocking {
        // Estado local es C4_ACTIVE
        C4SyncStateStore.setEngineMode(context, userA, SyncEngineMode.C4_ACTIVE)
        C4SyncConfig.setUserEngineMode(context, userA, SyncEngineMode.C4_ACTIVE)

        // El servidor indica LEGACY (sin bloqueo C4)
        val mockFirebaseApp = mockk<FirebaseApp>(relaxed = true) {
            every { options.projectId } returns "contador-de-birras-prod"
        }
        val mockDocSnapshot = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true) {
            every { exists() } returns false
        }
        val mockDocRef = mockk<com.google.firebase.firestore.DocumentReference>(relaxed = true) {
            every { get(com.google.firebase.firestore.Source.SERVER) } returns com.google.android.gms.tasks.Tasks.forResult(mockDocSnapshot)
        }
        val mockCollection = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true) {
            every { document(userA) } returns mockDocRef
        }
        val mockFirestore = mockk<FirebaseFirestore>(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { collection("users") } returns mockCollection
        }
        val mockUserA = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns userA
        }
        val mockAuthA = mockk<FirebaseAuth>(relaxed = true) {
            every { currentUser } returns mockUserA
        }

        val manager = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { mockFirestore },
            storageProvider = { mockk(relaxed = true) },
            authProvider = { mockAuthA },
            databaseProvider = { testDatabase },
            allowProduction = true
        )

        val result = manager.detectAndEnforceAccountProtocol(userA)

        // Debe bloquear y detenerse en HALTED, nunca volver a Legacy
        assertTrue("Conflicto local C4 con remoto Legacy debe ser bloqueado: $result", result is ProtocolResult.Blocked)
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, userA))
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
    }

    @Test
    fun testStalePersistentLegacyCheck_newSession_rechecksServer() = runBlocking {
        // Sesión anterior persistió isProtocolVerified = true
        C4SyncStateStore.setEngineMode(context, userA, SyncEngineMode.LEGACY)
        C4SyncStateStore.setProtocolVerified(context, userA, true)
        // Pero en la nueva sesión, la memoria no está verificada
        C4SyncConfig.clearSessionProtocolVerification()
        assertFalse(C4SyncConfig.isSessionProtocolVerified(userA))

        // Ahora el servidor remoto tiene un bloqueo C4
        val mockFirebaseApp = mockk<FirebaseApp>(relaxed = true) {
            every { options.projectId } returns "contador-de-birras-prod"
        }
        val mockDocSnapshot = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true) {
            every { exists() } returns true
            every { getString("syncProtocolVersion") } returns "C4"
            every { getString("accountSyncState") } returns "MIGRATED_TO_C4"
            every { getLong("schemaVersion") } returns 10L
            every { getLong("minSupportedClientVersion") } returns 1L
            every { getLong("updatedAt") } returns System.currentTimeMillis()
            every { getString("lockId") } returns "lock_test_1"
        }
        val mockDocRef = mockk<com.google.firebase.firestore.DocumentReference>(relaxed = true) {
            every { get(com.google.firebase.firestore.Source.SERVER) } returns com.google.android.gms.tasks.Tasks.forResult(mockDocSnapshot)
        }
        val mockCollection = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true) {
            every { document(userA) } returns mockDocRef
        }
        val mockFirestore = mockk<FirebaseFirestore>(relaxed = true) {
            every { app } returns mockFirebaseApp
            every { collection("users") } returns mockCollection
        }
        val mockUserA = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns userA
        }
        val mockAuthA = mockk<FirebaseAuth>(relaxed = true) {
            every { currentUser } returns mockUserA
        }

        val manager = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { mockFirestore },
            storageProvider = { mockk(relaxed = true) },
            authProvider = { mockAuthA },
            databaseProvider = { testDatabase },
            allowProduction = true
        )

        // El usuario no está autorizado en este dispositivo
        val result = manager.detectAndEnforceAccountProtocol(userA)

        // Debe detectar que el servidor es C4 y bloquear en HALTED, no permitir escrituras Legacy
        assertTrue("No debe confiar en verificación antigua: debe detectar C4 remoto y bloquear dispositivo no autorizado: $result", result is ProtocolResult.Blocked)
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, userA))
    }
}
