package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.FirebaseApp
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
 * REQUISITOS DE EJECUCIÓN Y DEMOSTRACIÓN FUNCIONAL:
 * - Conecta a los emuladores locales mediante la fábrica aislada [C4TestEnvironmentFactory] en 10.0.2.2:
 *     * Auth Emulator: 10.0.2.2:9099
 *     * Firestore Emulator: 10.0.2.2:8888
 *     * Storage Emulator: 10.0.2.2:9199
 * - Proyecto ficticio: demo-contador-c44
 * - PROHIBIDO: Conexión con proyectos reales o instalación en dispositivo físico Xiaomi.
 *
 * DEMOSTRACIÓN FUNCIONAL (SIN MOCKS):
 * 1. Cliente A crea una cerveza con fotografía versionada (CreateActive -> rev 1).
 * 2. Firestore recibe revisión 1 con las 5 unidades canónicas de fieldRevisions.
 * 3. Cliente B (segundo dispositivo con su propia base Room) la recupera vía PULL.
 * 4. Cliente A modifica el comentario (ApplyPatch -> rev 2).
 * 5. Cliente B recupera el cambio vía PULL y actualiza su copia local.
 * 6. Se verifica la coherencia final exacta de datos en ambos clientes y en Firestore.
 */
@RunWith(AndroidJUnit4::class)
class C4IntegrationInstrumentedTest {

    private lateinit var app: FirebaseApp
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    // Dispositivo / Cliente A
    private lateinit var databaseA: BeerDatabase
    private lateinit var persistenceA: RoomC4SyncPersistence
    private lateinit var firestoreAdapterA: FirestoreC4SyncAdapter
    private lateinit var storageAdapterA: StorageC4SyncAdapter
    private lateinit var coordinatorA: AndroidC4SyncCoordinator

    // Dispositivo / Cliente B
    private lateinit var databaseB: BeerDatabase
    private lateinit var persistenceB: RoomC4SyncPersistence
    private lateinit var firestoreAdapterB: FirestoreC4SyncAdapter
    private lateinit var storageAdapterB: StorageC4SyncAdapter
    private lateinit var coordinatorB: AndroidC4SyncCoordinator

    private var aliceUid: String = ""
    private val allFieldsEncoded = BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS)!!

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Configuración de emuladores con fábrica aislada y anti-fallback
        // En AVDs sin ruta de gateway predeterminada, adb reverse permite túnel directo sobre 127.0.0.1
        val targetHost = try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(C4SyncConstants.EMULATOR_HOST_ANDROID, C4SyncConstants.FIRESTORE_PORT), 1000)
                C4SyncConstants.EMULATOR_HOST_ANDROID
            }
        } catch (_: Exception) {
            C4SyncConstants.EMULATOR_HOST_LOCAL
        }

        val instances = C4TestEnvironmentFactory.createIsolatedInstances(
            context = context,
            host = targetHost,
            authPort = 9099,
            firestorePort = C4SyncConstants.FIRESTORE_PORT,
            storagePort = C4SyncConstants.STORAGE_PORT,
            projectId = C4SyncConstants.DEMO_PROJECT_ID
        )

        app = instances.app
        auth = instances.auth
        firestore = instances.firestore
        storage = instances.storage

        // 2. Cliente A: Base Room A aislada
        databaseA = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        persistenceA = RoomC4SyncPersistence(databaseA)
        firestoreAdapterA = FirestoreC4SyncAdapter(firestore, expectedProjectId = C4SyncConstants.DEMO_PROJECT_ID)
        storageAdapterA = StorageC4SyncAdapter(storage, expectedBucket = "${C4SyncConstants.DEMO_PROJECT_ID}.appspot.com")
        coordinatorA = AndroidC4SyncCoordinator(firestoreAdapterA, storageAdapterA, persistenceA)

        // 3. Cliente B: Base Room B aislada (simula segundo dispositivo independiente)
        databaseB = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        persistenceB = RoomC4SyncPersistence(databaseB)
        firestoreAdapterB = FirestoreC4SyncAdapter(firestore, expectedProjectId = C4SyncConstants.DEMO_PROJECT_ID)
        storageAdapterB = StorageC4SyncAdapter(storage, expectedBucket = "${C4SyncConstants.DEMO_PROJECT_ID}.appspot.com")
        coordinatorB = AndroidC4SyncCoordinator(firestoreAdapterB, storageAdapterB, persistenceB)

        // 4. Registro de usuario auténtico en Auth emulator
        val email = "alice_instrumented_${System.nanoTime()}@example.com"
        val userCredential = auth.createUserWithEmailAndPassword(email, "password123").await()
        aliceUid = userCredential.user!!.uid
    }

    @After
    fun tearDown() {
        databaseA.close()
        databaseB.close()
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
            dirtyFields = allFieldsEncoded,
            lastMutationId = mutationId
        )
        databaseA.beerDao().insertBeer(localBeer)
        persistenceA.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = aliceUid,
            mutationId = mutationId,
            dirtyFields = allFieldsEncoded,
            baseRevision = -1L,
            isLocalOnlyCreation = true
        )

        val pushResult = coordinatorA.executePush(syncId, aliceUid, photoBytes = photoBytes)
        assertTrue("Push on instrumented AVD must succeed: $pushResult", pushResult is C4CoordinatorPushResult.Success)

        // 1. Verificación Firestore
        val remoteDoc = firestoreAdapterA.readServerDocument(syncId)
        assertNotNull(remoteDoc)
        assertEquals(1L, remoteDoc!!.revision)
        assertEquals(aliceUid, remoteDoc.userId)
        assertEquals(mutationId, remoteDoc.lastMutationId)

        // Verificación de integridad causal: las 5 unidades canónicas deben estar presentes e inicializadas en 1
        val fr = remoteDoc.fieldRevisions
        assertEquals(5, fr.size)
        assertEquals(1L, fr["TYPE"])
        assertEquals(1L, fr["TIMESTAMP"])
        assertEquals(1L, fr["LOCATION"])
        assertEquals(1L, fr["COMMENT"])
        assertEquals(1L, fr["PHOTO"])

        // 2. Verificación Storage
        val photoPath = C4SyncConstants.buildVersionedPhotoPath(aliceUid, syncId, mutationId)
        val photoExists = storageAdapterA.photoExists(photoPath)
        assertTrue("Uploaded photo must exist in Storage emulator", photoExists)

        // 3. Verificación Room
        val updatedLocal = databaseA.beerDao().getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.SYNCED, updatedLocal.syncStatus)
        assertEquals(1L, updatedLocal.remoteRevision)
        assertEquals("content://local/photo.jpg", updatedLocal.photoUri)

        // 4. Verificación Historial
        val history = databaseA.beerDao().getHistoryRecordById(mutationId, aliceUid)!!
        assertEquals(MutationStatus.CONFIRMED, history.status)
        assertEquals(1L, history.confirmedRevision)
    }

    /**
     * Demostración funcional completa multidispositivo sin mocks:
     * - Cliente A crea una cerveza con foto (rev 1).
     * - Firestore recibe revisión 1 con las 5 unidades canónicas.
     * - Cliente B recupera la cerveza vía PULL en su base local Room B.
     * - Cliente A modifica el comentario (ApplyPatch -> rev 2).
     * - Cliente B recupera la actualización vía PULL.
     * - Ambos clientes verifican paridad total de datos finales.
     */
    @Test
    fun instrumented_multiClientFunctionalDemonstration_withoutMocks() = runBlocking {
        val syncId = "sync-flow-${UUID.randomUUID()}"
        val mutA1 = "mut-a1-${UUID.randomUUID()}"
        val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

        // 1. Cliente A crea la cerveza localmente
        val beerA1 = BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 2000L,
            latitude = 40.4168,
            longitude = -3.7038,
            photoUri = "content://device_a/photo.jpg",
            comment = "Initial Comment by Client A",
            locationName = "Plaza Mayor",
            syncId = syncId,
            syncStatus = SyncStatus.PENDING,
            ownerUid = aliceUid,
            remoteRevision = -1L,
            baseRevision = -1L,
            dirtyFields = allFieldsEncoded,
            lastMutationId = mutA1
        )
        databaseA.beerDao().insertBeer(beerA1)
        persistenceA.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = aliceUid,
            mutationId = mutA1,
            dirtyFields = allFieldsEncoded,
            baseRevision = -1L,
            isLocalOnlyCreation = true
        )

        // 2. Cliente A ejecuta PUSH
        val pushA1 = coordinatorA.executePush(syncId, aliceUid, photoBytes = photoBytes)
        assertTrue("PUSH Client A rev 1 should succeed: $pushA1", pushA1 is C4CoordinatorPushResult.Success)
        assertEquals(1L, (pushA1 as C4CoordinatorPushResult.Success).newRemoteRevision)

        // 3. Verificación en Firestore
        val remoteDoc1 = firestoreAdapterA.readServerDocument(syncId)
        assertNotNull(remoteDoc1)
        assertEquals(1L, remoteDoc1!!.revision)
        assertEquals("Initial Comment by Client A", remoteDoc1.comment)
        val expectedPhotoPath = C4SyncConstants.buildVersionedPhotoPath(aliceUid, syncId, mutA1)
        assertEquals(expectedPhotoPath, remoteDoc1.remotePhotoUrl)

        // 4. Cliente B recupera la cerveza vía PULL
        val pullB1 = coordinatorB.executePull(syncId, aliceUid)
        assertTrue("PULL Client B rev 1 should apply: $pullB1", pullB1 is C4CoordinatorPullResult.Applied)

        val localB1 = databaseB.beerDao().getBeerBySyncId(syncId, aliceUid)
        assertNotNull("Client B must have inserted the beer", localB1)
        assertEquals(SyncStatus.SYNCED, localB1!!.syncStatus)
        assertEquals(1L, localB1.remoteRevision)
        assertEquals(1L, localB1.baseRevision)
        assertEquals(BeerType.LATA, localB1.type)
        assertEquals("Initial Comment by Client A", localB1.comment)
        assertEquals(expectedPhotoPath, localB1.remotePhotoUrl)

        // 5. Cliente A modifica el comentario (edición local PENDING)
        val mutA2 = "mut-a2-${UUID.randomUUID()}"
        val updatedLocalA = localBeerA(syncId).copy(
            comment = "Updated Comment by Client A",
            syncStatus = SyncStatus.PENDING,
            dirtyFields = "COMMENT",
            lastMutationId = mutA2,
            updatedAt = System.currentTimeMillis()
        )
        persistenceA.updateBeerEntityDirect(updatedLocalA)
        persistenceA.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = aliceUid,
            mutationId = mutA2,
            dirtyFields = "COMMENT",
            baseRevision = 1L,
            isLocalOnlyCreation = false
        )

        // 6. Cliente A ejecuta PUSH del parche
        val pushA2 = coordinatorA.executePush(syncId, aliceUid)
        assertTrue("PUSH Client A rev 2 should succeed: $pushA2", pushA2 is C4CoordinatorPushResult.Success)
        assertEquals(2L, (pushA2 as C4CoordinatorPushResult.Success).newRemoteRevision)

        // 7. Verificación en Firestore rev 2
        val remoteDoc2 = firestoreAdapterA.readServerDocument(syncId)
        assertNotNull(remoteDoc2)
        assertEquals(2L, remoteDoc2!!.revision)
        assertEquals("Updated Comment by Client A", remoteDoc2.comment)
        assertEquals(2L, remoteDoc2.fieldRevisions["COMMENT"])
        assertEquals(1L, remoteDoc2.fieldRevisions["TYPE"]) // Campo no modificado conserva revisión 1

        // 8. Cliente B recupera la actualización vía PULL
        val pullB2 = coordinatorB.executePull(syncId, aliceUid)
        assertTrue("PULL Client B rev 2 should apply update: $pullB2", pullB2 is C4CoordinatorPullResult.Applied)

        val localB2 = databaseB.beerDao().getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.SYNCED, localB2.syncStatus)
        assertEquals(2L, localB2.remoteRevision)
        assertEquals(2L, localB2.baseRevision)
        assertEquals("Updated Comment by Client A", localB2.comment)
        assertEquals(expectedPhotoPath, localB2.remotePhotoUrl)
    }

    private fun localBeerA(syncId: String): BeerEntity =
        databaseA.beerDao().getBeerBySyncId(syncId, aliceUid)!!
}
