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
import org.junit.Assume.assumeTrue
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
 * 1. Migración de Esquema Room (SQLite): Corresponde al salto de versiones estructurales (ej. v8/v9 a v10
 *    mediante MIGRATION_8_9 y MIGRATION_9_10), donde Room añade columnas (remoteRevision=0, baseRevision=0)
 *    y la tabla `local_mutation_history` vacía, sin alterar los datos existentes.
 * 2. Migración Funcional C4 (Cutover): Proceso aplicativo donde los datos Legacy preexistentes (0/0)
 *    ascienden al protocolo C4 (revisión 1 con fieldRevisions), se suben fotos versionadas y se escribe
 *    el bloqueo remoto.
 *
 * GARANTÍAS FORMALES VALIDAS EN ESTA SUITE:
 * 1. Cierre y reapertura REAL de Room desde disco (sin reutilizar singletons cacheados o cerrados).
 * 2. Preservación íntegra de cervezas, fotografías físicas en almacenamiento interno, comentarios y GPS.
 * 3. Feature Flag de Producción coherente: si la cuenta es C4_ACTIVE pero C4 está deshabilitado,
 *    se impiden nuevas escrituras locales para evitar mutaciones que jamás sincronicen, sin recurrir a Legacy.
 * 4. Bloqueo estricto ante estados anómalos (HALTED, CUTOVER_IN_PROGRESS).
 */
@RunWith(AndroidJUnit4::class)
class C4ReleaseCandidateInstrumentedTest {

    private lateinit var context: Context
    private var testUserUid: String = ""
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
                socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_ANDROID, C4SyncConstants.FIRESTORE_PORT), 1000)
                C4SyncConstants.EMULATOR_HOST_ANDROID
            }
        } catch (_: Exception) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_LOCAL, C4SyncConstants.FIRESTORE_PORT), 1000)
                    C4SyncConstants.EMULATOR_HOST_LOCAL
                }
            } catch (_: Exception) {
                ""
            }
        }

        if (targetHost.isNotEmpty()) {
            try {
                instances = C4TestEnvironmentFactory.createIsolatedInstances(
                    context = context,
                    host = targetHost,
                    authPort = 9099,
                    firestorePort = C4SyncConstants.FIRESTORE_PORT,
                    storagePort = C4SyncConstants.STORAGE_PORT,
                    projectId = C4SyncConstants.DEMO_PROJECT_ID
                )
                auth = instances?.auth
                val email = "rc_user_${System.currentTimeMillis()}@demo-contador-c44.test"
                val password = "password123456"
                val authResult = auth?.createUserWithEmailAndPassword(email, password)?.await()
                testUserUid = authResult?.user?.uid ?: "rc_fallback_uid"
            } catch (_: Exception) {
                testUserUid = "rc_offline_uid_${System.currentTimeMillis()}"
            }
        } else {
            testUserUid = "rc_offline_uid_${System.currentTimeMillis()}"
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
     * Prueba Crítica: Migración funcional C4 sobre base persistente en disco y reapertura real tras reinicio.
     */
    @Test
    fun testLegacyToC4Migration_persistsDataAndRecoversCleanlyAfterRestart() = runBlocking {
        // Solo ejecutar si Firebase Emulator está accesible
        assumeTrue("Requiere Firebase Emulator para ejecución completa sin mocks", targetHost.isNotEmpty() && instances != null)

        val dbName = "c4_rc_test_db_${System.currentTimeMillis()}.db"
        createdDbNames.add(dbName)

        // 1. Crear fotografía local real en almacenamiento físico de la app
        val photoFile = File(context.filesDir, "test_rc_photo_${System.currentTimeMillis()}.jpg")
        photoFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x11, 0x22, 0x33))
        createdPhotoFiles.add(photoFile)
        val photoUri = "file://${photoFile.absolutePath}"

        val syncId1 = "rc-sync-beer-synced"
        val syncId2 = "rc-sync-beer-pending-with-photo"
        val syncId3 = "rc-sync-beer-deleted"

        // 2. Sembrar base de datos Room persistente en disco en formato Legacy (0/0)
        var db = BeerDatabase.getDatabase(context, dbName)
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

        // 3. Sembrar documento 1 en Firestore Emulator para simular preexistencia remota
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
     * Valida:
     * - Si C4 está deshabilitado y la cuenta es C4_ACTIVE, se impiden nuevas escrituras.
     * - Jamás se activa Legacy automáticamente para cuentas C4_ACTIVE.
     * - Cuentas en HALTED y CUTOVER_IN_PROGRESS permanecen estrictamente bloqueadas.
     */
    @Test
    fun testProductionSafetyGuards_featureFlagCoherenceAndSafeHalt() = runBlocking {
        val dbName = "c4_safety_check_${System.currentTimeMillis()}.db"
        createdDbNames.add(dbName)
        val db = BeerDatabase.getDatabase(context, dbName)

        val authRepo = AuthRepository(context)
        val syncScheduler = com.example.contadordebirras.worker.WorkManagerBeerSyncScheduler(context)
        val repo = BeerRepository(db.beerDao(), context, authRepo, syncScheduler)

        // 1. C4 deshabilitado por defecto en producción
        assertEquals(false, C4SyncConfig.isEnabled)
        assertEquals(false, C4SyncConfig.isProductionC4FeatureEnabled)
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode)

        // 2. Si la cuenta está en C4_ACTIVE pero el feature flag está deshabilitado:
        //    Debe impedir nuevas escrituras locales para no acumular mutaciones sin sincronizar.
        //    Y JAMÁS debe ejecutar escrituras en Legacy.
        C4SyncStateStore.setEngineMode(context, testUserUid, SyncEngineMode.C4_ACTIVE)
        C4SyncConfig.isProductionC4FeatureEnabled = false
        C4SyncConfig.restoreUserEngineMode(context, testUserUid)

        try {
            repo.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe impedir escrituras si la cuenta es C4_ACTIVE pero C4 está deshabilitado")
        } catch (e: IllegalStateException) {
            assertTrue("Debe informar que C4 está deshabilitado: ${e.message}",
                e.message?.contains("protocolo C4 está deshabilitado") == true || e.message?.contains("bloqueada") == true)
        }

        // 3. Verificar que si una cuenta entra en HALTED, se bloquean operaciones
        C4SyncStateStore.setEngineMode(context, testUserUid, SyncEngineMode.HALTED)
        C4SyncConfig.restoreUserEngineMode(context, testUserUid)

        try {
            repo.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe lanzar IllegalStateException cuando la cuenta está en HALTED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("HALTED") == true || e.message?.contains("bloqueada") == true)
        }

        // 4. Verificar que si una cuenta está en CUTOVER_IN_PROGRESS, se bloquean operaciones
        C4SyncStateStore.setEngineMode(context, testUserUid, SyncEngineMode.CUTOVER_IN_PROGRESS)
        C4SyncConfig.restoreUserEngineMode(context, testUserUid)

        try {
            repo.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe lanzar IllegalStateException cuando la cuenta está en CUTOVER_IN_PROGRESS")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("CUTOVER_IN_PROGRESS") == true || e.message?.contains("bloqueada") == true)
        }

        db.close()
    }
}
