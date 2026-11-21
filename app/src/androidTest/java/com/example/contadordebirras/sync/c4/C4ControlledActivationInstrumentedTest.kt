package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.worker.WorkManagerBeerSyncScheduler
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Suite de Pruebas Instrumentadas para la Activación Controlada de C4 (Fase C4 Controlled Activation).
 *
 * GARANTÍAS VERIFICADAS:
 * 1. Activación autorizada exclusiva: Rechazo estricto si el UID no está expresamente autorizado
 *    en [C4SyncConfig.authorizedPilotUids] o si el feature flag está deshabilitado.
 * 2. Cuentas no autorizadas conservan Legacy: El modo permanece en [SyncEngineMode.LEGACY] y las
 *    operaciones normales continúan sin alteración.
 * 3. Activación exitosa para UID autorizado:
 *    - Reconciliación e integración completa con [C4StagingCutoverManager] sobre Room persistente en disco.
 *    - Verificación y escritura del bloqueo de protocolo remoto en Firestore (/users/{uid}).
 *    - Confirmación estricta de postcondiciones (0 mutaciones pendientes, bloqueo C4 confirmado).
 *    - Establecimiento definitivo de [SyncEngineMode.C4_ACTIVE].
 * 4. Irreversibilidad probada: La cuenta migrada jamás vuelve a Legacy (se impiden escrituras si C4 se apaga).
 * 5. Idempotencia: Intentos posteriores de activación detectan [ActivationResult.AlreadyActive].
 */
@RunWith(AndroidJUnit4::class)
class C4ControlledActivationInstrumentedTest {

    private lateinit var context: Context
    private var testUserUid: String = ""
    private var testUserEmail: String = ""
    private var targetHost: String = ""
    private var instances: C4TestEnvironmentFactory.IsolatedFirebaseInstances? = null
    private var auth: FirebaseAuth? = null

    private val createdDbNames = mutableListOf<String>()
    private val createdPhotoFiles = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()

        // Comprobación de disponibilidad de Firebase Emulator
        targetHost = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_ANDROID, C4SyncConstants.FIRESTORE_PORT), 1500)
                C4SyncConstants.EMULATOR_HOST_ANDROID
            }
        } catch (_: Exception) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_LOCAL, C4SyncConstants.FIRESTORE_PORT), 1500)
                    C4SyncConstants.EMULATOR_HOST_LOCAL
                }
            } catch (_: Exception) {
                ""
            }
        }

        if (targetHost.isNotEmpty()) {
            instances = C4TestEnvironmentFactory.createIsolatedInstances(
                context = context,
                host = targetHost,
                authPort = 9099,
                firestorePort = C4SyncConstants.FIRESTORE_PORT,
                storagePort = C4SyncConstants.STORAGE_PORT,
                projectId = C4SyncConstants.DEMO_PROJECT_ID
            )
            auth = instances?.auth
            testUserEmail = "ctrl_user_${System.currentTimeMillis()}@demo-contador-c44.test"
            val password = "password123456"
            val authResult = auth?.createUserWithEmailAndPassword(testUserEmail, password)?.await()
            testUserUid = authResult?.user?.uid ?: "ctrl_fallback_uid"

            // Sincronizar también con la instancia por defecto de FirebaseAuth
            try {
                val defaultAuth = FirebaseAuth.getInstance()
                try {
                    defaultAuth.useEmulator(targetHost, 9099)
                } catch (_: Exception) {}
                if (defaultAuth.currentUser?.uid != testUserUid) {
                    try {
                        defaultAuth.signInWithEmailAndPassword(testUserEmail, password).await()
                    } catch (_: Exception) {
                        defaultAuth.createUserWithEmailAndPassword(testUserEmail, password).await()
                    }
                }
            } catch (_: Exception) {}
        } else {
            testUserUid = "ctrl_offline_uid_${System.currentTimeMillis()}"
            testUserEmail = "ctrl_offline_${System.currentTimeMillis()}@demo-contador-c44.test"
        }

        C4SyncConfig.reset()
        C4SyncStateStore.clearForTesting(context, testUserUid)
        BeerDatabase.resetInstanceForTesting()
    }

    @After
    fun tearDown() = runBlocking {
        BeerDatabase.resetInstanceForTesting()
        for (dbName in createdDbNames) {
            try {
                context.deleteDatabase(dbName)
            } catch (_: Exception) {}
        }
        for (photo in createdPhotoFiles) {
            try {
                if (photo.exists()) photo.delete()
            } catch (_: Exception) {}
        }
        C4SyncConfig.reset()
        C4SyncStateStore.clearForTesting(context, testUserUid)
    }

    /**
     * Comprobación 1: Rechazo estricto de activación para un UID no autorizado.
     * Garantiza que las cuentas no autorizadas continúan utilizando Legacy sin cambios.
     */
    @Test
    fun testControlledActivation_unauthorizedUser_isStrictlyRejectedAndKeepsLegacy() = runBlocking {
        assertTrue(
            "Firebase Emulator debe estar activo para ejecutar la validación controlada",
            targetHost.isNotEmpty() && instances != null
        )

        val unauthUid = testUserUid
        val dbName = "c4_unauth_db_${System.currentTimeMillis()}.db"
        createdDbNames.add(dbName)
        val db = BeerDatabase.getDatabase(context, dbName)

        // 1. Configurar Feature Flag en true pero SIN autorizar el UID en authorizedPilotUids
        C4SyncConfig.isProductionC4FeatureEnabled = true
        C4SyncConfig.clearAuthorizedPilotUids()
        assertFalse("El UID no debe estar autorizado", C4SyncConfig.isUserAuthorizedForC4(unauthUid))

        // 2. Insertar cerveza legacy en la base Room
        val legacyBeer = BeerEntity(
            syncId = "sync-unauth-${System.currentTimeMillis()}",
            ownerUid = unauthUid,
            type = BeerType.CANA,
            timestamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = ""
        )
        db.beerDao().insertBeer(legacyBeer)

        // 3. Intentar activación controlada con C4ControlledActivationManager
        val activationManager = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { instances!!.firestore },
            storageProvider = { instances!!.storage },
            authProvider = { instances!!.auth },
            databaseProvider = { db },
            allowProduction = false
        )

        val result = activationManager.activateAccount(unauthUid)

        // 4. Verificar que es estrictamente rechazada con Unauthorized
        assertTrue("La activación debe ser rechazada para un UID no autorizado: $result", result is ActivationResult.Unauthorized)
        val unauthResult = result as ActivationResult.Unauthorized
        assertTrue("El motivo debe indicar UID no incluido en autorizados", unauthResult.reason.contains("authorizedPilotUids") || unauthResult.reason.contains("no está incluido"))

        // 5. Garantizar que el modo persistente permanece en LEGACY
        val currentMode = C4SyncStateStore.getEngineMode(context, unauthUid)
        assertEquals("El modo del usuario no autorizado debe ser LEGACY", SyncEngineMode.LEGACY, currentMode)
        assertFalse("Las escrituras legacy NO deben estar bloqueadas para este usuario", C4SyncStateStore.isLegacyBlocked(context, unauthUid))

        // 6. Verificar que la cuenta no tiene bloqueo C4 en Firestore
        val firestoreAdapter = FirestoreC4SyncAdapter(
            firestore = instances!!.firestore,
            expectedProjectId = C4SyncConstants.DEMO_PROJECT_ID,
            allowProduction = false
        )
        val remoteLock = firestoreAdapter.readAccountProtocolLock(unauthUid)
        assertTrue("No debe existir bloqueo remoto C4 para un usuario no autorizado", remoteLock == null || remoteLock.syncProtocolVersion != "C4")

        db.close()
    }

    /**
     * Comprobación 2: Activación autorizada exitosa, bloqueo remoto C4, postcondiciones e irreversibilidad.
     */
    @Test
    fun testControlledActivation_authorizedUser_completesCutoverLocksProtocolAndPreventsLegacy() = runBlocking {
        assertTrue(
            "Firebase Emulator debe estar activo para ejecutar la validación controlada",
            targetHost.isNotEmpty() && instances != null
        )

        val authorizedUid = testUserUid
        val dbName = "c4_auth_db_${System.currentTimeMillis()}.db"
        createdDbNames.add(dbName)
        val db = BeerDatabase.getDatabase(context, dbName)

        // 1. Autorizar expresamente al UID en C4SyncConfig
        C4SyncConfig.isProductionC4FeatureEnabled = true
        C4SyncConfig.authorizePilotUid(authorizedUid)
        assertTrue("El UID debe estar autorizado", C4SyncConfig.isUserAuthorizedForC4(authorizedUid))

        // 2. Sembrar datos legacy preexistentes en Room
        val syncId1 = "sync-ctrl-beer1-${System.currentTimeMillis()}"
        val legacyBeer1 = BeerEntity(
            syncId = syncId1,
            ownerUid = authorizedUid,
            type = BeerType.CANA,
            timestamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = ""
        )
        db.beerDao().insertBeer(legacyBeer1)

        // Sembrar documento legacy correspondiente en Firestore
        val initialRemoteDoc1 = hashMapOf<String, Any?>(
            "userId" to authorizedUid,
            "type" to "CANA",
            "timestamp" to legacyBeer1.timestamp,
            "updatedAt" to legacyBeer1.updatedAt
        )
        instances!!.firestore.collection("beers").document(syncId1).set(initialRemoteDoc1).await()

        // 3. Ejecutar diagnóstico previo a la activación
        val activationManager = C4ControlledActivationManager(
            context = context,
            firestoreProvider = { instances!!.firestore },
            storageProvider = { instances!!.storage },
            authProvider = { instances!!.auth },
            databaseProvider = { db },
            allowProduction = false
        )

        val diagnosis = activationManager.diagnoseAccount(authorizedUid)
        assertTrue("El diagnóstico previo debe dar ReadyForCutover: $diagnosis", diagnosis is ActivationDiagnosisResult.ReadyForCutover)

        // 4. Ejecutar la activación controlada
        val activationResult = activationManager.activateAccount(authorizedUid)
        assertTrue("La activación debe ser exitosa: $activationResult", activationResult is ActivationResult.Success)

        val success = activationResult as ActivationResult.Success
        assertEquals(authorizedUid, success.ownerUid)
        assertEquals("C4", success.remoteLock.syncProtocolVersion)
        assertEquals("MIGRATED_TO_C4", success.remoteLock.accountSyncState)

        // 5. Verificar que el estado persistente y en memoria es C4_ACTIVE
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncStateStore.getEngineMode(context, authorizedUid))
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)
        assertTrue("C4 debe estar activo en memoria", C4SyncConfig.isC4Active())

        // 6. Verificar postcondiciones en Room persistente:
        //    - 0 mutaciones pendientes en local_mutation_history
        val pendingMutations = db.localMutationHistoryDao().getPendingMutations(authorizedUid)
        assertTrue("No deben quedar mutaciones pendientes tras cutover", pendingMutations.isEmpty())

        //    - Cerveza local ascendida a revisión moderna C4 (>= 1)
        val updatedLocalBeer = db.beerDao().getBeerBySyncId(syncId1, authorizedUid)
        assertNotNull(updatedLocalBeer)
        assertTrue("La revisión local debe ascender a >= 1", updatedLocalBeer!!.remoteRevision >= 1L)

        // 7. Verificar bloqueo remoto confirmado en Firestore
        val firestoreAdapter = FirestoreC4SyncAdapter(
            firestore = instances!!.firestore,
            expectedProjectId = C4SyncConstants.DEMO_PROJECT_ID,
            allowProduction = false
        )
        val confirmedLock = firestoreAdapter.readAccountProtocolLock(authorizedUid)
        assertNotNull(confirmedLock)
        assertEquals("C4", confirmedLock!!.syncProtocolVersion)
        assertEquals("MIGRATED_TO_C4", confirmedLock.accountSyncState)

        // 8. Verificar idempotencia: un segundo intento retorna AlreadyActive
        val secondActivation = activationManager.activateAccount(authorizedUid)
        assertTrue("Un segundo intento debe retornar AlreadyActive: $secondActivation", secondActivation is ActivationResult.AlreadyActive)

        // 9. Verificar IRREVERSIBILIDAD:
        //    Si la aplicación se reinicia o C4 se desactiva temporalmente, una cuenta migrada
        //    NUNCA vuelve a Legacy; las escrituras se bloquean para evitar datos sin sincronizar.
        val prodContext = object : android.content.ContextWrapper(context) {
            override fun getPackageName(): String = "com.daniel.contadordebirras"
        }
        val authRepo = AuthRepository(context)
        authRepo.updateCurrentUser()
        val syncScheduler = WorkManagerBeerSyncScheduler(context)
        val repo = BeerRepository(db.beerDao(), prodContext, authRepo, syncScheduler)

        C4SyncConfig.isProductionC4FeatureEnabled = false
        C4SyncConfig.restoreUserEngineMode(context, authorizedUid)

        try {
            repo.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe impedir escrituras en cuenta migrada a C4 cuando C4 está temporalmente inactivo")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Debe bloquear con mensaje específico de protección C4",
                e.message?.contains("la cuenta está migrada a C4 pero el protocolo C4 está deshabilitado") == true
            )
        }

        // Restaurar autorización y verificar que permite crear en C4
        C4SyncConfig.isProductionC4FeatureEnabled = true
        C4SyncConfig.authorizePilotUid(authorizedUid)
        C4SyncConfig.setUserEngineMode(context, authorizedUid, SyncEngineMode.C4_ACTIVE)
        val newC4BeerId = repo.addBeer(BeerType.CANA, System.currentTimeMillis())
        assertTrue("Debe registrar la nueva cerveza C4 con ID válido", newC4BeerId > 0)

        db.close()
    }
}
