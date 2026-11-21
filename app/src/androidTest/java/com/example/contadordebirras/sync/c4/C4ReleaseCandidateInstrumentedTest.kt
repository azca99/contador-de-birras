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

/**
 * Prueba de Integración Android de la Versión Candidata C4 (Fase C4 Release Candidate).
 *
 * DISTINCIÓN METODOLÓGICA FUNDAMENTAL:
 * 1. Migración de Esquema Room (SQLite): Salto estructural (v8/v9 a v10 mediante MIGRATION_8_9 y MIGRATION_9_10),
 *    donde Room añade columnas (remoteRevision=0, baseRevision=0) y la tabla `local_mutation_history` vacía.
 * 2. Migración Funcional C4 (Cutover): Ascenso aplicativo donde los datos Legacy preexistentes (0/0)
 *    ascienden al protocolo C4 (revisión 1 con fieldRevisions), se suben fotos versionadas y se escribe
 *    el bloqueo remoto.
 *
 * GARANTÍAS FORMALES VALIDADAS:
 * 1. Cierre y reapertura REAL de Room desde disco (sin reutilizar singletons cacheados o cerrados).
 * 2. Preservación íntegra de cervezas, fotografías físicas en almacenamiento interno, comentarios y GPS.
 * 3. Feature Flag de Producción coherente: si la cuenta es C4_ACTIVE pero C4 está deshabilitado,
 *    se impiden nuevas escrituras locales con mensajes específicos, sin recurrir a Legacy.
 * 4. Bloqueo estricto ante estados anómalos (HALTED, CUTOVER_IN_PROGRESS), validando que se evalúa
 *    el UID autenticado real y no "guest_local", distinguiendo el entorno productivo de ".c4pilot".
 */
@RunWith(AndroidJUnit4::class)
class C4ReleaseCandidateInstrumentedTest {

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
            testUserEmail = "rc_user_${System.currentTimeMillis()}@demo-contador-c44.test"
            val password = "password123456"
            val authResult = auth?.createUserWithEmailAndPassword(testUserEmail, password)?.await()
            testUserUid = authResult?.user?.uid ?: "rc_fallback_uid"

            // Sincronizar también con la instancia por defecto de FirebaseAuth para que AuthRepository refleje la sesión
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
            testUserUid = "rc_offline_uid_${System.currentTimeMillis()}"
            testUserEmail = "rc_offline_${System.currentTimeMillis()}@demo-contador-c44.test"
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
     * Prueba Crítica 1: Migración funcional C4 sobre base persistente en disco y reapertura real tras reinicio.
     * Verifica que NO se omite la prueba y se ejecuta contra Firebase Emulator real.
     */
    @Test
    fun testLegacyToC4Migration_persistsDataAndRecoversCleanlyAfterRestart() = runBlocking {
        assertTrue(
            "Firebase Emulator debe estar activo en $targetHost:${C4SyncConstants.FIRESTORE_PORT} para esta prueba crítica",
            targetHost.isNotEmpty() && instances != null
        )

        val dbName = "c4_rc_test_db_${System.currentTimeMillis()}.db"
        createdDbNames.add(dbName)

        // 1. Crear fotografía local real en almacenamiento físico de la app
        val photoFile = File(context.filesDir, "test_rc_photo_${System.currentTimeMillis()}.jpg")
        photoFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x11, 0x22, 0x33))
        createdPhotoFiles.add(photoFile)
        val photoUri = "file://${photoFile.absolutePath}"

        val testSuffix = System.currentTimeMillis()
        val syncId1 = "sync-rc-synced-$testSuffix"
        val syncId2 = "sync-rc-pending-$testSuffix"
        val syncId3 = "sync-rc-deleted-$testSuffix"

        // 2. Sembrar base de datos Room persistente en disco en formato Legacy (0/0)
        var db = BeerDatabase.getDatabase(context, dbName)
        db.openHelper.writableDatabase
        assertTrue("La base de datos en disco debe estar abierta", db.isOpen)
        val beerDao = db.beerDao()

        val beer1 = BeerEntity(
            ownerUid = testUserUid,
            type = BeerType.CANA,
            timestamp = System.currentTimeMillis() - 3600000,
            comment = "Cerveza legacy preexistente",
            latitude = 40.4168,
            longitude = -3.7038,
            locationName = "Madrid",
            syncId = syncId1,
            syncStatus = SyncStatus.SYNCED,
            remotePhotoUrl = "users/$testUserUid/beers/$syncId1.jpg",
            remoteRevision = 0L,
            baseRevision = 0L
        )
        beerDao.insertBeer(beer1)

        val beer2 = BeerEntity(
            ownerUid = testUserUid,
            type = BeerType.PINTA,
            timestamp = System.currentTimeMillis() - 1800000,
            comment = "Cerveza offline con foto local",
            latitude = 41.3851,
            longitude = 2.1734,
            locationName = "Barcelona",
            photoUri = photoUri,
            syncId = syncId2,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L
        )
        beerDao.insertBeer(beer2)

        val beer3 = BeerEntity(
            ownerUid = testUserUid,
            type = BeerType.JARRA,
            timestamp = System.currentTimeMillis() - 900000,
            comment = "Cerveza borrada preexistente",
            syncId = syncId3,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 0L,
            baseRevision = 0L
        )
        beerDao.insertBeer(beer3)

        // 3. Sembrar documentos legacy preexistentes en Firestore Emulator
        val firestore = instances!!.firestore
        val firestoreAdapter = FirestoreC4SyncAdapter(firestore, expectedProjectId = C4SyncConstants.DEMO_PROJECT_ID)
        val storageAdapter = StorageC4SyncAdapter(instances!!.storage, expectedBucket = "${C4SyncConstants.DEMO_PROJECT_ID}.appspot.com")
        val persistence = RoomC4SyncPersistence(db)
        val coordinator = AndroidC4SyncCoordinator(firestoreAdapter, storageAdapter, persistence)

        val initialRemoteDoc1 = mapOf(
            "syncId" to syncId1,
            "userId" to testUserUid,
            "type" to "CANA",
            "timestamp" to beer1.timestamp,
            "latitude" to beer1.latitude,
            "longitude" to beer1.longitude,
            "comment" to beer1.comment,
            "remotePhotoUrl" to beer1.remotePhotoUrl,
            "updatedAt" to beer1.updatedAt
        )
        firestore.collection("beers").document(syncId1).set(initialRemoteDoc1).await()

        val initialRemoteDoc3 = mapOf(
            "syncId" to syncId3,
            "userId" to testUserUid,
            "type" to "JARRA",
            "timestamp" to beer3.timestamp,
            "comment" to beer3.comment,
            "updatedAt" to beer3.updatedAt
        )
        firestore.collection("beers").document(syncId3).set(initialRemoteDoc3).await()

        // 4. Ejecutar Cutover completo hacia C4 sobre la base persistente en disco
        val cutoverManager = C4StagingCutoverManager(
            coordinator = coordinator,
            persistence = persistence,
            firestoreAdapter = firestoreAdapter,
            context = context,
            photoResolver = { uri ->
                if (uri == photoUri && photoFile.exists()) photoFile.readBytes() else null
            }
        )

        val cutoverResult = cutoverManager.executeCutover(testUserUid)
        assertTrue("El cutover debe completarse con éxito: $cutoverResult", cutoverResult is CutoverResult.Completed)

        // 5. Verificar que el modo persistente es C4_ACTIVE
        val modeAfterCutover = C4SyncStateStore.getEngineMode(context, testUserUid)
        assertEquals(SyncEngineMode.C4_ACTIVE, modeAfterCutover)

        // 6. Simular reinicio completo de la aplicación:
        //    Cerrar físicamente la conexión Room y verificar que isOpen es false
        db.close()
        assertFalse("La conexión original a Room debe estar cerrada", db.isOpen)
        BeerDatabase.resetInstanceForTesting()

        // 7. Reabrir una conexión completamente nueva desde el archivo persistente en disco
        val restartedDb = BeerDatabase.getDatabase(context, dbName)
        restartedDb.openHelper.writableDatabase
        assertTrue("La nueva conexión a Room debe estar abierta", restartedDb.isOpen)
        assertNotSame("Debe ser una instancia Room diferente, leída de disco", db, restartedDb)
        val restartedBeerDao = restartedDb.beerDao()

        // 8. Comprobar que los datos persistieron íntegramente tras el reinicio
        val activeBeers = restartedBeerDao.getActiveBeersSync(testUserUid)
        assertEquals("Deben existir exactamente 2 cervezas activas tras reinicio", 2, activeBeers.size)

        val recoveredBeer1 = restartedBeerDao.getBeerBySyncId(syncId1, testUserUid)
        assertNotNull(recoveredBeer1)
        assertEquals(SyncStatus.SYNCED, recoveredBeer1!!.syncStatus)
        assertEquals(1L, recoveredBeer1.remoteRevision)
        assertEquals("Madrid", recoveredBeer1.locationName)
        assertEquals(beer1.comment, recoveredBeer1.comment)

        val recoveredBeer2 = restartedBeerDao.getBeerBySyncId(syncId2, testUserUid)
        assertNotNull(recoveredBeer2)
        assertEquals(SyncStatus.SYNCED, recoveredBeer2!!.syncStatus)
        assertEquals(1L, recoveredBeer2.remoteRevision)
        assertEquals(photoUri, recoveredBeer2.photoUri)
        assertTrue(recoveredBeer2.remotePhotoUrl?.startsWith("users/$testUserUid/beers/${syncId2}_") == true)
        assertEquals("Barcelona", recoveredBeer2.locationName)

        val recoveredBeer3 = restartedBeerDao.getBeerBySyncId(syncId3, testUserUid)
        assertNotNull(recoveredBeer3)
        assertEquals(SyncStatus.DELETED, recoveredBeer3!!.syncStatus)
        assertEquals(2L, recoveredBeer3.remoteRevision)

        // 9. Comprobar que el archivo fotográfico físico en disco sigue intacto
        assertTrue("El archivo de imagen local debe conservarse", photoFile.exists() && photoFile.length() > 0)

        restartedDb.close()
    }

    /**
     * Prueba de Seguridad: Coherencia del Feature Flag de Producción y Parada Segura.
     *
     * Valida con una sesión autenticada real (evitando falsos positivos de 'guest_local' o mismatch de '.c4pilot'):
     * - Si C4 está deshabilitado y la cuenta es C4_ACTIVE, se impiden nuevas escrituras.
     * - Jamás se activa Legacy automáticamente para cuentas C4_ACTIVE.
     * - Cuentas en HALTED y CUTOVER_IN_PROGRESS permanecen estrictamente bloqueadas.
     */
    @Test
    fun testProductionSafetyGuards_featureFlagCoherenceAndSafeHalt() = runBlocking {
        // 1. Contexto emulado para ruta de producción (evita bypass del piloto .c4pilot)
        val prodContext = object : android.content.ContextWrapper(context) {
            override fun getPackageName(): String = "com.daniel.contadordebirras"
        }

        // 2. Asegurar que AuthRepository tiene una sesión auténtica y no 'guest_local'
        val defaultAuth = FirebaseAuth.getInstance()
        try {
            defaultAuth.useEmulator(targetHost, 9099)
        } catch (_: Exception) {}
        if (defaultAuth.currentUser == null && targetHost.isNotEmpty()) {
            val password = "password123456"
            try {
                defaultAuth.signInWithEmailAndPassword(testUserEmail, password).await()
            } catch (_: Exception) {
                defaultAuth.createUserWithEmailAndPassword(testUserEmail, password).await()
            }
        }
        val authRepo = AuthRepository(context)
        authRepo.updateCurrentUser()
        val effectiveUid = authRepo.currentUser.value?.uid ?: defaultAuth.currentUser?.uid ?: testUserUid

        assertNotNull("La sesión de usuario no debe ser nula para evitar falsos positivos", authRepo.currentUser.value)
        assertNotEquals("El usuario no debe ser guest_local", "guest_local", effectiveUid)

        val dbName = "c4_safety_check_${System.currentTimeMillis()}.db"
        createdDbNames.add(dbName)
        val db = BeerDatabase.getDatabase(prodContext, dbName)

        val syncScheduler = com.example.contadordebirras.worker.WorkManagerBeerSyncScheduler(prodContext)
        val repo = BeerRepository(db.beerDao(), prodContext, authRepo, syncScheduler)

        // 3. C4 deshabilitado por defecto en producción
        assertEquals(false, C4SyncConfig.isEnabled)
        assertEquals(false, C4SyncConfig.isProductionC4FeatureEnabled)
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode)

        // 4. Si la cuenta está en C4_ACTIVE pero el feature flag de producción está deshabilitado:
        //    Debe impedir nuevas escrituras locales para no acumular mutaciones sin sincronizar.
        //    Y JAMÁS debe ejecutar escrituras en Legacy.
        C4SyncStateStore.setEngineMode(prodContext, effectiveUid, SyncEngineMode.C4_ACTIVE)
        C4SyncConfig.isProductionC4FeatureEnabled = false
        C4SyncConfig.restoreUserEngineMode(prodContext, effectiveUid)

        try {
            repo.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe impedir escrituras si la cuenta es C4_ACTIVE pero C4 está deshabilitado")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Operación bloqueada: la cuenta está migrada a C4 pero el protocolo C4 está deshabilitado en este entorno. Se impiden nuevas escrituras para evitar mutaciones sin sincronización.",
                e.message
            )
        }

        // 5. Verificar que si una cuenta entra en HALTED, se bloquean operaciones con mensaje específico
        C4SyncStateStore.setEngineMode(prodContext, effectiveUid, SyncEngineMode.HALTED)
        C4SyncConfig.restoreUserEngineMode(prodContext, effectiveUid)

        try {
            repo.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe lanzar IllegalStateException cuando la cuenta está en HALTED")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Operación bloqueada: la cuenta está en estado HALTED para proteger los datos C4.",
                e.message
            )
        }

        // 6. Verificar que si una cuenta está en CUTOVER_IN_PROGRESS, se bloquean operaciones con mensaje específico
        C4SyncStateStore.setEngineMode(prodContext, effectiveUid, SyncEngineMode.CUTOVER_IN_PROGRESS)
        C4SyncConfig.restoreUserEngineMode(prodContext, effectiveUid)

        try {
            repo.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe lanzar IllegalStateException cuando la cuenta está en CUTOVER_IN_PROGRESS")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Operación bloqueada: la cuenta está en estado CUTOVER_IN_PROGRESS para proteger los datos C4.",
                e.message
            )
        }

        // 7. Verificar que cuando C4 se activa explícitamente en modo C4_ACTIVE, permite registrar
        C4SyncConfig.isProductionC4FeatureEnabled = true
        C4SyncConfig.authorizePilotUid(effectiveUid)
        C4SyncConfig.setUserEngineMode(prodContext, effectiveUid, SyncEngineMode.C4_ACTIVE)
        val createdBeerId = repo.addBeer(BeerType.CANA, System.currentTimeMillis())
        assertTrue("Debe registrar la cerveza con ID válido cuando C4 está autorizado y en modo C4_ACTIVE", createdBeerId > 0)

        db.close()
    }
}
