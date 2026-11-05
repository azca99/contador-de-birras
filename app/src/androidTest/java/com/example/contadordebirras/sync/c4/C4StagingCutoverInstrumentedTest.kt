package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.MutationStatus
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
 * Demostración Instrumentada de Seguridad de Cutover (Fase C4.5).
 *
 * Ejecuta la verificación en entorno AVD / Firebase Emulator con los 8 escenarios de seguridad:
 * 1. Legacy SYNCED con foto.
 * 2. Legacy PENDING con foto real en disco.
 * 3. Legacy DELETED.
 * 4. Local sin equivalente remoto (preservación estricta y bloqueo anti-pérdida).
 * 5. Reintento e idempotencia post-cierre.
 * 6. Subida y verificación de foto en Storage versionado.
 * 7. Intento de sobreescritura de cliente legacy bloqueado por reglas de Firestore.
 * 8. Frontera de irreversibilidad en rollback.
 */
@RunWith(AndroidJUnit4::class)
class C4StagingCutoverInstrumentedTest {

    private lateinit var context: Context
    private lateinit var targetHost: String
    private lateinit var instances: C4TestEnvironmentFactory.IsolatedFirebaseInstances
    private lateinit var auth: FirebaseAuth
    private var testUserUid: String = ""

    private var syncManager: C4AppSyncManager? = null

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
            instanceSuffix = "cutover-${System.nanoTime()}"
        )
        auth = instances.auth
        val email = "user_cutover_${UUID.randomUUID().toString().take(8)}@contador-c4.test"
        val authResult = auth.createUserWithEmailAndPassword(email, "password123456").await()
        testUserUid = authResult.user!!.uid

        syncManager = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = false,
            dbName = "c4_cutover_test_${System.nanoTime()}.db",
            instances = instances
        )
    }

    @After
    fun tearDown() {
        syncManager?.close()
        try {
            instances.app.delete()
        } catch (_: Exception) {}
        C4SyncConfig.reset()
    }

    @Test
    fun testCompleteCutoverSecurityDemonstration() {
        runBlocking {
        val manager = syncManager!!
        val db = manager.database
        val beerDao = db.beerDao()
        val firestore = instances.firestore

        // =========================================================================
        // PASO 1: Sembrar fixtures ficticios legacy
        // =========================================================================
        val beer1SyncId = UUID.randomUUID().toString()
        val beer2SyncId = UUID.randomUUID().toString()
        val beer3SyncId = UUID.randomUUID().toString()
        val beer4SyncId = UUID.randomUUID().toString()

        // 1. Cerveza 1: Legacy SYNCED (0/0) con foto remota existente
        val beer1 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = testUserUid,
            syncId = beer1SyncId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Beer 1 Legacy Sincronizada",
            hasRemotePhoto = true
        )
        beerDao.insertBeer(beer1)
        val payload1 = LegacyBeerFixtures.createLegacyFirestorePayload(
            userId = testUserUid,
            syncId = beer1SyncId,
            comment = "Beer 1 Legacy Sincronizada",
            hasPhoto = true
        )
        firestore.collection("beers").document(beer1SyncId).set(payload1).await()

        // 2. Cerveza 2: Legacy PENDING (0/0) con foto local real sin subir
        val localPhotoFile = File(context.cacheDir, "test_photo_$beer2SyncId.jpg")
        val sampleJpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46)
        localPhotoFile.writeBytes(sampleJpgBytes)
        val beer2 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = testUserUid,
            syncId = beer2SyncId,
            syncStatus = SyncStatus.PENDING,
            comment = "Beer 2 Creada Offline con Foto",
            photoUri = localPhotoFile.absolutePath
        )
        beerDao.insertBeer(beer2)

        // 3. Cerveza 3: Legacy DELETED (0/0) en Room y doc legacy en Firestore
        val beer3 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = testUserUid,
            syncId = beer3SyncId,
            syncStatus = SyncStatus.DELETED,
            comment = "Beer 3 Borrada Offline"
        )
        beerDao.insertBeer(beer3)
        val payload3 = LegacyBeerFixtures.createLegacyFirestorePayload(
            userId = testUserUid,
            syncId = beer3SyncId,
            comment = "Beer 3 Borrada Offline"
        )
        firestore.collection("beers").document(beer3SyncId).set(payload3).await()

        // 4. Cerveza 4: Local SYNCED sin equivalente remoto en Firestore
        val beer4 = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = testUserUid,
            syncId = beer4SyncId,
            syncStatus = SyncStatus.SYNCED,
            comment = "Beer 4 Local Sin Remoto"
        )
        beerDao.insertBeer(beer4)

        // =========================================================================
        // PASO 2: Ejecutar cutover con Beer 4 presente -> DEBE BLOQUEARSE (Anti-Data-Loss)
        // =========================================================================
        val cutoverManager = C4StagingCutoverManager(
            coordinator = manager.coordinator,
            persistence = manager.persistence,
            firestoreAdapter = manager.coordinator.firestoreAdapter,
            context = context
        )

        val blockedResult = cutoverManager.executeCutover(testUserUid)
        assertTrue("Cutover DEBE bloquearse por documento local ausente en servidor", blockedResult is CutoverResult.Blocked)
        assertFalse("C4 NO debe activarse si cutover es bloqueado", C4SyncConfig.isEnabled)

        // Comprobar preservación estricta: Beer 4 NO fue borrada
        val preservedBeer4 = beerDao.getBeerBySyncId(beer4SyncId, testUserUid)
        assertNotNull("Beer 4 local DEBE existir intacta", preservedBeer4)
        assertEquals(SyncStatus.SYNCED, preservedBeer4!!.syncStatus)
        assertEquals("Beer 4 Local Sin Remoto", preservedBeer4.comment)
        assertNull(preservedBeer4.deletedAt)

        // =========================================================================
        // PASO 3: Reconciliar Beer 4 y ejecutar cutover definitivo
        // =========================================================================
        // Para proceder al cutover limpio de la cuenta, sembramos el documento remoto de Beer 4
        val payload4 = LegacyBeerFixtures.createLegacyFirestorePayload(
            userId = testUserUid,
            syncId = beer4SyncId,
            comment = "Beer 4 Local Sin Remoto"
        )
        firestore.collection("beers").document(beer4SyncId).set(payload4).await()

        val completedResult = cutoverManager.executeCutover(testUserUid)
        assertTrue("Cutover debe completarse exitosamente pero fue: $completedResult", completedResult is CutoverResult.Completed)
        val completed = completedResult as CutoverResult.Completed
        assertTrue(C4SyncConfig.isEnabled)
        assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)

        // =========================================================================
        // PASO 4: Comprobar estados en Room, Storage y Firestore
        // =========================================================================
        // Beer 1: ascendida a rev 1 en Firestore y alineada en Room
        val finalBeer1 = beerDao.getBeerBySyncId(beer1SyncId, testUserUid)!!
        assertEquals(SyncStatus.SYNCED, finalBeer1.syncStatus)
        assertEquals(1L, finalBeer1.remoteRevision)
        val remoteDoc1 = manager.coordinator.firestoreAdapter.readServerDocument(beer1SyncId)!!
        assertEquals(1L, remoteDoc1.revision)
        assertFalse(remoteDoc1.isDeleted)

        // Beer 2: foto subida a versioned storage y confirmada en rev 1
        val finalBeer2 = beerDao.getBeerBySyncId(beer2SyncId, testUserUid)!!
        assertEquals(SyncStatus.SYNCED, finalBeer2.syncStatus)
        assertEquals(1L, finalBeer2.remoteRevision)
        assertEquals(localPhotoFile.absolutePath, finalBeer2.photoUri)
        assertNotNull(finalBeer2.remotePhotoUrl)
        assertTrue(finalBeer2.remotePhotoUrl!!.contains(beer2SyncId))
        // Verificar que el archivo realmente existe en Storage Emulator
        val photoExistsInStorage = manager.coordinator.storageAdapter.photoExists(finalBeer2.remotePhotoUrl!!)
        assertTrue("La fotografía debe existir en Storage Emulator", photoExistsInStorage)

        // Beer 3: tombstone escrito y confirmado en rev 2
        val finalBeer3 = beerDao.getBeerBySyncId(beer3SyncId, testUserUid)!!
        assertEquals(SyncStatus.DELETED, finalBeer3.syncStatus)
        assertEquals(2L, finalBeer3.remoteRevision)
        val remoteDoc3 = manager.coordinator.firestoreAdapter.readServerDocument(beer3SyncId)!!
        assertEquals(2L, remoteDoc3.revision)
        assertTrue(remoteDoc3.isDeleted)

        // Cero mutaciones pendientes en Room
        val remainingPending = manager.persistence.historyDao.getPendingMutations(testUserUid)
        assertEquals(0, remainingPending.size)

        // =========================================================================
        // PASO 5: Idempotencia estricta en reintento
        // =========================================================================
        val idempotentResult = cutoverManager.executeCutover(testUserUid)
        assertTrue(idempotentResult is CutoverResult.Completed)
        assertTrue((idempotentResult as CutoverResult.Completed).alreadyMigrated)

        // =========================================================================
        // PASO 6: Demostrar que cliente legacy NO puede sobreescribir documento modernizado
        // =========================================================================
        // Un cliente legacy intentaría escribir un documento sin el campo 'revision'
        val legacyWriteAttempt = hashMapOf<String, Any?>(
            "userId" to testUserUid,
            "type" to "JARRA",
            "timestamp" to System.currentTimeMillis(),
            "comment" to "Intento de sobreescritura legacy no autorizada",
            "updatedAt" to System.currentTimeMillis()
        )

        try {
            firestore.collection("beers").document(beer1SyncId).update(legacyWriteAttempt).await()
            fail("Las reglas de Firestore DEBEN rechazar una actualización legacy sobre un documento con revision >= 1")
        } catch (e: FirebaseFirestoreException) {
            assertEquals("Escritura legacy debe ser rechazada con PERMISSION_DENIED", FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }

        // =========================================================================
        // PASO 7: Frontera de irreversibilidad en Rollback
        // =========================================================================
        val rollbackResult = cutoverManager.rollbackToLegacy(testUserUid)
        assertTrue("El cliente local revierte y desactiva C4", rollbackResult.isLocalReverted)
        assertEquals("El motor debe permanecer DETENIDO (HALTED) por documentos modernizados", SyncEngineMode.HALTED, C4SyncConfig.engineMode)
        assertFalse(C4SyncConfig.isEnabled)
        assertFalse(rollbackResult.isLegacyReactivated)
        assertTrue("Se debe marcar alcanzado el punto de no retorno remoto", rollbackResult.pointOfNoReturnReached)
        assertTrue("Debe incluir advertencia de no reactivar escrituras legacy", rollbackResult.warningMessage.contains("NO se reactivan escrituras legacy"))

        // Limpieza de foto local de prueba
        localPhotoFile.delete()
        }
    }
}
