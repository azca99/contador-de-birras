package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Suite de integración instrumentada en dispositivo Android/AVD (Fase C4.4A).
 *
 * REQUISITOS DE EJECUCIÓN:
 * - Requiere un AVD descartable corriendo en el entorno.
 * - Conecta a los emuladores locales en el host mediante la IP virtual 10.0.2.2:
 *     * Auth Emulator: 10.0.2.2:9099
 *     * Firestore Emulator: 10.0.2.2:8888
 *     * Storage Emulator: 10.0.2.2:9199
 * - Proyecto ficticio: demo-contador-c44
 * - PROHIBIDO: Conexión con proyectos reales o instalación en dispositivo físico Xiaomi.
 *
 * En ausencia de AVD activo, esta clase permanece compilable pero se reporta como NO EJECUTADO.
 */
@RunWith(AndroidJUnit4::class)
class C4IntegrationInstrumentedTest {

    companion object {
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val AUTH_PORT = 9099
        private const val FIRESTORE_PORT = 8888
        private const val STORAGE_PORT = 9199
        private const val PROJECT_ID = "demo-contador-c44"
    }

    private lateinit var app: FirebaseApp
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var database: BeerDatabase
    private lateinit var beerDao: BeerDao

    private lateinit var firestoreAdapter: FirestoreC4SyncAdapter
    private lateinit var storageAdapter: StorageC4SyncAdapter
    private lateinit var persistence: RoomC4SyncPersistence
    private lateinit var coordinator: AndroidC4SyncCoordinator

    private var aliceUid: String = ""

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val appName = "instrumented-c4-app-${System.nanoTime()}"
        app = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId("1:123456789:android:instrumented")
                .setProjectId(PROJECT_ID)
                .setApiKey("fakeApiKeyInstrumented")
                .setStorageBucket("$PROJECT_ID.appspot.com")
                .build(),
            appName
        )

        auth = FirebaseAuth.getInstance(app).apply {
            useEmulator(EMULATOR_HOST, AUTH_PORT)
        }
        firestore = FirebaseFirestore.getInstance(app).apply {
            useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        }
        storage = FirebaseStorage.getInstance(app).apply {
            useEmulator(EMULATOR_HOST, STORAGE_PORT)
        }

        database = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        beerDao = database.beerDao()

        firestoreAdapter = FirestoreC4SyncAdapter(firestore, expectedProjectId = PROJECT_ID)
        storageAdapter = StorageC4SyncAdapter(storage, expectedBucket = "$PROJECT_ID.appspot.com")
        persistence = RoomC4SyncPersistence(database)
        coordinator = AndroidC4SyncCoordinator(firestoreAdapter, storageAdapter, persistence)

        // Registrar usuario de prueba en Auth emulator
        val email = "alice_instrumented_${System.nanoTime()}@example.com"
        val userCredential = auth.createUserWithEmailAndPassword(email, "password123").await()
        aliceUid = userCredential.user!!.uid
    }

    @After
    fun tearDown() {
        database.close()
        app.delete()
    }

    @Test
    fun instrumented_createActivePushAndStorageUpload() = runBlocking {
        val syncId = "sync-inst-${UUID.randomUUID()}"
        val mutationId = "mut-inst-${UUID.randomUUID()}"
        val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

        val localBeer = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = 40.4168,
            longitude = -3.7038,
            photoUri = "content://local/photo.jpg",
            comment = "Instrumented Test Beer",
            locationName = "Madrid AVD",
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = aliceUid,
            remoteRevision = -1L,
            baseRevision = -1L,
            dirtyFields = "ALL",
            lastMutationId = mutationId
        )
        beerDao.insertBeer(localBeer)
        persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = aliceUid,
            mutationId = mutationId,
            dirtyFields = "ALL",
            baseRevision = -1L,
            isLocalOnlyCreation = true
        )

        val pushResult = coordinator.executePush(syncId, aliceUid, photoBytes = photoBytes)
        assertTrue("Push on instrumented AVD must succeed: $pushResult", pushResult is C4CoordinatorPushResult.Success)

        // 1. Verificación Firestore
        val remoteDoc = firestoreAdapter.readServerDocument(syncId)
        assertNotNull(remoteDoc)
        assertEquals(1L, remoteDoc!!.revision)
        assertEquals(aliceUid, remoteDoc.userId)
        assertEquals(mutationId, remoteDoc.lastMutationId)

        // 2. Verificación Storage
        val photoPath = C4SyncConstants.buildVersionedPhotoPath(aliceUid, syncId, mutationId)
        val photoExists = storageAdapter.photoExists(photoPath)
        assertTrue("Uploaded photo must exist in Storage emulator", photoExists)

        // 3. Verificación Room
        val updatedLocal = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.SYNCED, updatedLocal.syncStatus)
        assertEquals(1L, updatedLocal.remoteRevision)
        assertEquals("content://local/photo.jpg", updatedLocal.photoUri)

        // 4. Verificación Historial
        val history = beerDao.getHistoryRecordById(mutationId, aliceUid)!!
        assertEquals(MutationStatus.CONFIRMED, history.status)
        assertEquals(1L, history.confirmedRevision)
    }
}
