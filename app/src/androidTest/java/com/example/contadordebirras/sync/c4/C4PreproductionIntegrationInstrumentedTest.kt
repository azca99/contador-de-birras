package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
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
 * Demostración de Integración de Preproducción en Entorno Aislado (C4PreproductionIntegrationInstrumentedTest).
 *
 * Ejecuta el recorrido completo sobre componentes reales sin mocks:
 * - Base de datos Room persistente de prueba en disco (no volátil en memoria).
 * - Firebase Emulator suite real (Auth, Firestore y Storage) con reglas candidatas endurecidas.
 * - Proyecto ficticio demo-contador-c44 aislado de producción.
 *
 * Valida los 6 pilares de la integración previa a producción:
 * 1. Migración de una base de datos Legacy existente con datos reales (revisión 0/0, fotos en disco, sin historial C4).
 * 2. Bloqueo de protocolo remoto en /users/{uid} (syncProtocolVersion: 'C4', minSupportedAppVersionCode: 3, c4MigratedAt).
 * 3. Reanudación segura tras interrupción y detección preventiva anti-pérdida de datos.
 * 4. Idempotencia total en reejecución de corte.
 * 5. Rechazo estricto de escrituras y borrados Legacy en Firestore tras el corte.
 * 6. Recuperación completa desde un cliente con base local nueva (Client B).
 */
@RunWith(AndroidJUnit4::class)
class C4PreproductionIntegrationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var targetHost: String
    private lateinit var instances: C4TestEnvironmentFactory.IsolatedFirebaseInstances
    private lateinit var auth: FirebaseAuth
    private var testUserUid: String = ""

    private var syncManager: C4AppSyncManager? = null
    private var clientBDatabase: BeerDatabase? = null
    private val createdDbNames = mutableListOf<String>()
    private val createdPhotoFiles = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()

        targetHost = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_ANDROID, C4SyncConstants.FIRESTORE_PORT), 1000)
                C4SyncConstants.EMULATOR_HOST_ANDROID
            }
        } catch (_: Exception) {
            C4SyncConstants.EMULATOR_HOST_LOCAL
        }

        instances = C4TestEnvironmentFactory.createIsolatedInstances(
            context = context,
            host = targetHost,
            instanceSuffix = "preprod-${System.nanoTime()}"
        )
        auth = instances.auth
        val email = "user_preprod_${UUID.randomUUID().toString().take(8)}@demo-contador-c44.test"
        val authResult = auth.createUserWithEmailAndPassword(email, "password123456").await()
        testUserUid = authResult.user!!.uid

        val legacyDbName = "c4_preprod_legacy_${System.nanoTime()}.db"
        createdDbNames.add(legacyDbName)

        syncManager = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = false,
            dbName = legacyDbName,
            instances = instances
        )
    }

    @After
    fun tearDown() {
        syncManager?.close()
        clientBDatabase?.close()
        try {
            instances.app.delete()
        } catch (_: Exception) {}

        createdDbNames.forEach { dbName ->
            try {
                context.deleteDatabase(dbName)
            } catch (_: Exception) {}
        }

        createdPhotoFiles.forEach { file ->
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }

        C4SyncConfig.reset()
    }

    @Test
    fun testPreproductionRealIntegration_legacyMigration_lock_andClientBRecovery() = runBlocking {
        val manager = syncManager!!
        val db = manager.database
        val beerDao = db.beerDao()
        val firestore = instances.firestore
        val storage = instances.storage

        val beerSyncedId = "sync-preprod-01-legacy-synced"
        val beerPendingPhotoId = "sync-preprod-02-offline-photo"
        val beerDeletedId = "sync-preprod-03-legacy-deleted"
        val beerLocalOrphanId = "sync-preprod-04-orphan-local"

        // =========================================================================
        // 1. SEMBRAR ESTADO DE INSTALACIÓN LEGACY EXISTENTE (Room en disco + Firestore)
        // =========================================================================

        // A. Cerveza 1: Legacy SYNCED (0/0) con foto legacy en Storage
        val legacyPhotoPath = "users/$testUserUid/beers/$beerSyncedId.jpg"
        val fakeLegacyBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        storage.reference.child(legacyPhotoPath).putBytes(fakeLegacyBytes).await()

        val beer1 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = testUserUid,
            syncId = beerSyncedId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Cerveza legacy sincronizada previa",
            hasRemotePhoto = true
        )
        beerDao.insertBeer(beer1)

        val payload1 = LegacyBeerFixtures.createLegacyFirestorePayload(
            userId = testUserUid,
            syncId = beerSyncedId,
            comment = "Cerveza legacy sincronizada previa",
            hasPhoto = true
        )
        firestore.collection("beers").document(beerSyncedId).set(payload1).await()

        // B. Cerveza 2: Legacy PENDING (0/0) con foto real en disco
        val localPhotoFile = File(context.cacheDir, "preprod_photo_$beerPendingPhotoId.jpg")
        val sampleJpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02)
        localPhotoFile.writeBytes(sampleJpg)
        createdPhotoFiles.add(localPhotoFile)

        val beer2 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = testUserUid,
            syncId = beerPendingPhotoId,
            syncStatus = SyncStatus.PENDING,
            comment = "Cerveza creada offline con foto",
            photoUri = localPhotoFile.absolutePath
        )
        beerDao.insertBeer(beer2)

        // C. Cerveza 3: Legacy DELETED (0/0) en Room y doc legacy en Firestore
        val beer3 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = testUserUid,
            syncId = beerDeletedId,
            syncStatus = SyncStatus.DELETED,
            comment = "Cerveza borrada offline en legacy"
        )
        beerDao.insertBeer(beer3)

        val payload3 = LegacyBeerFixtures.createLegacyFirestorePayload(
            userId = testUserUid,
            syncId = beerDeletedId,
            comment = "Cerveza borrada offline en legacy"
        )
        firestore.collection("beers").document(beerDeletedId).set(payload3).await()

        // D. Cerveza 4: Local SYNCED sin equivalente en Firestore (para probar anti-data-loss)
        val beer4 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = testUserUid,
            syncId = beerLocalOrphanId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Cerveza local valiosa sin réplica remota"
        )
        beerDao.insertBeer(beer4)

        // =========================================================================
        // 2. COMPROBAR PREVALIDACIÓN DEFENSIVA Y ANTI-PÉRDIDA DE DATOS
        // =========================================================================
        val cutoverManager = C4StagingCutoverManager(
            coordinator = manager.coordinator,
            persistence = manager.persistence,
            firestoreAdapter = manager.coordinator.firestoreAdapter,
            context = context
        )

        val blockedResult = cutoverManager.executeCutover(testUserUid)
        assertTrue("Cutover DEBE bloquearse por cerveza local ausente en Firestore", blockedResult is CutoverResult.Blocked)
        assertFalse("C4 NO debe activarse en estado bloqueado", C4SyncConfig.isEnabled)
        assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)

        // Comprobación anti-pérdida: beer4 no fue eliminada ni alterada
        val preservedBeer4 = beerDao.getBeerBySyncId(beerLocalOrphanId, testUserUid)
        assertNotNull(preservedBeer4)
        assertEquals(SyncStatus.SYNCED, preservedBeer4!!.syncStatus)
        assertEquals("Cerveza local valiosa sin réplica remota", preservedBeer4.comment)
        assertNull(preservedBeer4.deletedAt)

        // =========================================================================
        // 3. RECONCILIACIÓN Y REANUDACIÓN EXITOSA DEL CORTE
        // =========================================================================
        // Sembramos el documento remoto de beer4 para completar la reconciliación
        val payload4 = LegacyBeerFixtures.createLegacyFirestorePayload(
            userId = testUserUid,
            syncId = beerLocalOrphanId,
            comment = "Cerveza local valiosa sin réplica remota"
        )
        firestore.collection("beers").document(beerLocalOrphanId).set(payload4).await()

        val completedResult = cutoverManager.executeCutover(testUserUid)
        assertTrue("Cutover debe completarse exitosamente tras reconciliación", completedResult is CutoverResult.Completed)
        val completed = completedResult as CutoverResult.Completed
        assertTrue(C4SyncConfig.isEnabled)
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)

        // =========================================================================
        // 4. VERIFICAR BLOQUEO REMOTO DE PROTOCOLO EN /users/{uid}
        // =========================================================================
        val userDoc = firestore.collection("users").document(testUserUid).get().await()
        assertTrue("Documento /users/{uid} debe existir", userDoc.exists())
        assertEquals("C4", userDoc.getString("syncProtocolVersion"))
        assertEquals(3L, userDoc.getLong("minSupportedAppVersionCode"))
        assertEquals("MIGRATED_TO_C4", userDoc.getString("accountSyncState"))
        val c4MigratedAt = userDoc.getLong("c4MigratedAt")
        assertNotNull(c4MigratedAt)
        assertTrue(c4MigratedAt!! > 0L)

        // =========================================================================
        // 5. VERIFICAR POSTCONDICIONES EN ROOM, FIRESTORE Y STORAGE
        // =========================================================================
        // Beer 1: ascendida a rev 1 en Firestore y alineada en Room
        val finalBeer1 = beerDao.getBeerBySyncId(beerSyncedId, testUserUid)!!
        assertEquals(SyncStatus.SYNCED, finalBeer1.syncStatus)
        assertEquals(1L, finalBeer1.remoteRevision)
        val remoteDoc1 = manager.coordinator.firestoreAdapter.readServerDocument(beerSyncedId)!!
        assertEquals(1L, remoteDoc1.revision)
        assertFalse(remoteDoc1.isDeleted)
        assertEquals(legacyPhotoPath, finalBeer1.remotePhotoUrl)

        // Beer 2: foto subida a Storage versionado y confirmada en rev 1
        val finalBeer2 = beerDao.getBeerBySyncId(beerPendingPhotoId, testUserUid)!!
        assertEquals(SyncStatus.SYNCED, finalBeer2.syncStatus)
        assertEquals(1L, finalBeer2.remoteRevision)
        assertEquals(localPhotoFile.absolutePath, finalBeer2.photoUri)
        assertNotNull(finalBeer2.remotePhotoUrl)
        val photoExistsInStorage = manager.coordinator.storageAdapter.photoExists(finalBeer2.remotePhotoUrl!!)
        assertTrue("Foto versionada debe existir en Storage Emulator", photoExistsInStorage)

        // Beer 3: tombstone escrito y confirmado en rev 2
        val finalBeer3 = beerDao.getBeerBySyncId(beerDeletedId, testUserUid)!!
        assertEquals(SyncStatus.DELETED, finalBeer3.syncStatus)
        assertEquals(2L, finalBeer3.remoteRevision)
        val remoteDoc3 = manager.coordinator.firestoreAdapter.readServerDocument(beerDeletedId)!!
        assertEquals(2L, remoteDoc3.revision)
        assertTrue(remoteDoc3.isDeleted)

        // Cero mutaciones pendientes en Room
        val pendingMutations = manager.persistence.historyDao.getPendingMutations(testUserUid)
        assertEquals(0, pendingMutations.size)

        // =========================================================================
        // 6. IDEMPOTENCIA TOTAL EN REEJECUCIÓN DE CORTE
        // =========================================================================
        val idempotentResult = cutoverManager.executeCutover(testUserUid)
        assertTrue("Reintento debe concluir en Completed", idempotentResult is CutoverResult.Completed)
        assertTrue("Debe marcar alreadyMigrated = true", (idempotentResult as CutoverResult.Completed).alreadyMigrated)

        // =========================================================================
        // 7. RECHAZO ESTRICTO DE ESCRITURAS Y BORRADOS LEGACY TRAS EL CORTE
        // =========================================================================
        // Intento A: Actualización legacy (sin revision)
        val rogueLegacyUpdate = hashMapOf<String, Any?>(
            "userId" to testUserUid,
            "type" to "JARRA",
            "timestamp" to System.currentTimeMillis(),
            "comment" to "Sobreescritura legacy no permitida",
            "updatedAt" to System.currentTimeMillis()
        )
        try {
            firestore.collection("beers").document(beerSyncedId).update(rogueLegacyUpdate).await()
            fail("Reglas deben rechazar actualización legacy tras bloqueo C4")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }

        // Intento B: Creación legacy (sin revision)
        val rogueLegacyCreate = hashMapOf<String, Any?>(
            "userId" to testUserUid,
            "type" to "CANA",
            "timestamp" to System.currentTimeMillis(),
            "comment" to "Creación legacy no permitida tras bloqueo C4",
            "updatedAt" to System.currentTimeMillis()
        )
        try {
            firestore.collection("beers").document("sync-rogue-legacy-new").set(rogueLegacyCreate).await()
            fail("Reglas deben rechazar creación legacy bajo cuenta bloqueada en C4")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }

        // Intento C: Hard delete legacy
        try {
            firestore.collection("beers").document(beerSyncedId).delete().await()
            fail("Reglas deben rechazar hard delete en cuenta bloqueada en C4")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }

        // =========================================================================
        // 8. RECUPERACIÓN COMPLETA DESDE CLIENTE B CON BASE LOCAL NUEVA
        // =========================================================================
        val clientBDbName = "c4_preprod_client_b_${System.nanoTime()}.db"
        createdDbNames.add(clientBDbName)

        val clientBManager = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = false,
            dbName = clientBDbName,
            instances = instances
        )
        clientBDatabase = clientBManager.database
        val clientBDao = clientBManager.database.beerDao()

        // En el cliente B, la base de datos comienza totalmente vacía
        val initialClientBBeers = clientBDao.getActiveBeersSync(testUserUid)
        assertEquals(0, initialClientBBeers.size)

        // Ejecutar PULL de colección desde Firestore
        val pullResults = clientBManager.coordinator.executePullCollection(testUserUid)
        assertTrue("PULL debe descargar al menos los documentos remotos", pullResults.isNotEmpty())

        // Comprobación de recuperación en Cliente B:
        val clientBActiveBeers = clientBDao.getActiveBeersSync(testUserUid)
        // 3 cervezas activas: beerSyncedId, beerPendingPhotoId, beerLocalOrphanId (beerDeletedId es tombstone)
        assertEquals("Cliente B debe haber recuperado exactamente 3 cervezas activas", 3, clientBActiveBeers.size)

        val clientBBeer1 = clientBDao.getBeerBySyncId(beerSyncedId, testUserUid)
        assertNotNull(clientBBeer1)
        assertEquals(SyncStatus.SYNCED, clientBBeer1!!.syncStatus)
        assertEquals(1L, clientBBeer1.remoteRevision)
        assertEquals(legacyPhotoPath, clientBBeer1.remotePhotoUrl)
        assertEquals("Cerveza legacy sincronizada previa", clientBBeer1.comment)

        val clientBBeer2 = clientBDao.getBeerBySyncId(beerPendingPhotoId, testUserUid)
        assertNotNull(clientBBeer2)
        assertEquals(SyncStatus.SYNCED, clientBBeer2!!.syncStatus)
        assertEquals(1L, clientBBeer2.remoteRevision)
        assertNotNull(clientBBeer2.remotePhotoUrl)
        assertTrue(clientBBeer2.remotePhotoUrl!!.contains(beerPendingPhotoId))

        // La cerveza borrada (tombstone) NO debe figurar como activa en Cliente B
        val clientBDeletedBeer = clientBDao.getBeerBySyncId(beerDeletedId, testUserUid)
        // O no existe o está marcada como DELETED
        assertTrue(clientBDeletedBeer == null || clientBDeletedBeer.syncStatus == SyncStatus.DELETED)

        clientBManager.close()
    }
}
