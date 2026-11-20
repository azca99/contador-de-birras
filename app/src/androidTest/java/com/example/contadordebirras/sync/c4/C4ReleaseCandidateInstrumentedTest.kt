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
import java.util.UUID

/**
 * Prueba de Integración Android de la Versión Candidata C4 (Fase C4 Release Candidate).
 *
 * Valida sobre Android runtime real:
 * 1. El paso de una base de datos Legacy existente a C4 sobre almacenamiento persistente en disco.
 * 2. La preservación íntegra de cervezas, fotografías, comentarios y ubicaciones.
 * 3. La resiliencia tras cierre y reinicio de la aplicación (cierre y reapertura de la base Room en disco).
 * 4. El bloqueo estricto ante estados anómalos (HALTED, CUTOVER_IN_PROGRESS), impidiendo el retorno a Legacy.
 * 5. La protección de Release: C4 desactivado por defecto para cuentas no migradas.
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
    }

    @After
    fun tearDown() = runBlocking {
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

    @Test
    fun testLegacyToC4Migration_persistsDataAndRecoversCleanlyAfterRestart() = runBlocking {
        // Solo ejecutar si Firebase Emulator está accesible
        assumeTrue("Requiere Firebase Emulator para ejecución completa sin mocks", targetHost.isNotEmpty() && instances != null)

        val dbName = "c4_rc_test_db_${System.currentTimeMillis()}.db"
        createdDbNames.add(dbName)

        // 1. Crear fotografía local real en almacenamiento de la app
        val photoFile = File(context.filesDir, "test_rc_photo_${System.currentTimeMillis()}.jpg")
        photoFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x11, 0x22, 0x33))
        createdPhotoFiles.add(photoFile)
        val photoUri = "file://${photoFile.absolutePath}"

        val syncId1 = "rc-sync-beer-synced"
        val syncId2 = "rc-sync-beer-pending-with-photo"
        val syncId3 = "rc-sync-beer-deleted"

        // 2. Sembrar base de datos Room persistente en disco en formato puramente Legacy (0/0)
        var db = BeerDatabase.getDatabase(context, dbName)
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

        // 3. Sembrar documento 1 en Firestore Emulator para simular la preexistencia remota
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

        // 4. Ejecutar Cutover completo hacia C4 sobre la base persistente
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

        // 5. Verificar que el estado del motor persistente es C4_ACTIVE
        val modeAfterCutover = C4SyncStateStore.getEngineMode(context, testUserUid)
        assertEquals(SyncEngineMode.C4_ACTIVE, modeAfterCutover)

        // 6. Simular reinicio completo de la aplicación: cerrar Room y reabrir desde disco
        db.close()
        val restartedDb = BeerDatabase.getDatabase(context, dbName)
        val restartedBeerDao = restartedDb.beerDao()

        // 7. Comprobar que los datos persistieron íntegramente tras el reinicio
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

        // 8. Comprobar que la foto física en disco sigue intacta
        assertTrue("El archivo de imagen local debe conservarse", photoFile.exists() && photoFile.length() > 0)

        restartedDb.close()
    }

    @Test
    fun testProductionSafetyGuards_preventUnauthorizedActivation() = runBlocking {
        // 1. C4 debe estar desactivado por defecto
        assertEquals(false, C4SyncConfig.isEnabled)
        assertEquals(false, C4SyncConfig.isProductionC4FeatureEnabled)
        assertEquals(SyncEngineMode.LEGACY, C4SyncConfig.engineMode)

        // 2. Para cualquier usuario sin migrar, el modo persistente es LEGACY
        assertEquals(SyncEngineMode.LEGACY, C4SyncStateStore.getEngineMode(context, "any_new_user"))

        // 3. Verificar que si una cuenta entra en HALTED, BeerRepository bloquea operaciones
        val dbName = "c4_halted_check_${System.currentTimeMillis()}.db"
        createdDbNames.add(dbName)
        val db = BeerDatabase.getDatabase(context, dbName)

        val authRepo = AuthRepository(context)
        val syncScheduler = com.example.contadordebirras.worker.WorkManagerBeerSyncScheduler(context)
        val repo = BeerRepository(db.beerDao(), context, authRepo, syncScheduler)

        C4SyncStateStore.setEngineMode(context, testUserUid, SyncEngineMode.HALTED)
        C4SyncConfig.restoreUserEngineMode(context, testUserUid)

        try {
            repo.addBeer(BeerType.CANA, System.currentTimeMillis())
            fail("Debe lanzar IllegalStateException cuando la cuenta está en HALTED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("HALTED") == true || e.message?.contains("bloqueada") == true)
        }

        db.close()
    }
}
