package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.domain.LOCAL_ONLY_REVISION
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Suite de Integración Funcional en la Aplicación bajo el Protocolo C4 (Fase C4.4B).
 *
 * EJECUCIÓN EN AVD REAL CON FIREBASE EMULATOR:
 * 1. Crear cerveza con registro previo de mutación durable en Room.
 * 2. Editar comentario con invalidación causal de la mutación anterior (SUPERSEDED).
 * 3. Sincronizar cambios (PUSH) hacia Firestore Emulator y verificar confirmaciones atómicas.
 * 4. Recuperar cambios desde otra instancia (Cliente B con su propia base Room descartable - PULL).
 * 5. Recuperar mutaciones pendientes tras simulación de reinicio/caída de la app.
 * 6. Gestionar borrados (Tombstones) y verificar propagación causal hacia el Cliente B.
 */
@RunWith(AndroidJUnit4::class)
class C4AppIntegrationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var targetHost: String
    private lateinit var instances: C4TestEnvironmentFactory.IsolatedFirebaseInstances
    private lateinit var auth: FirebaseAuth
    private var testUserUid: String = ""

    private var managerA: C4AppSyncManager? = null
    private var managerB: C4AppSyncManager? = null

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Detección dinámica de host del emulador (10.0.2.2 o fallback 127.0.0.1)
        targetHost = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_ANDROID, C4SyncConstants.FIRESTORE_PORT), 1000)
                C4SyncConstants.EMULATOR_HOST_ANDROID
            }
        } catch (_: Exception) {
            C4SyncConstants.EMULATOR_HOST_LOCAL
        }

        // 2. Autenticación auténtica en Auth Emulator
        instances = C4TestEnvironmentFactory.createIsolatedInstances(
            context = context,
            host = targetHost,
            instanceSuffix = "setup-${System.nanoTime()}"
        )
        auth = instances.auth
        val email = "user_${UUID.randomUUID().toString().take(8)}@contador-c4.test"
        val authResult = auth.createUserWithEmailAndPassword(email, "password123456").await()
        testUserUid = authResult.user!!.uid

        // 3. Cliente A con base Room descartable en memoria
        managerA = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = true,
            instances = instances
        )
    }

    @After
    fun tearDown() {
        managerA?.close()
        managerB?.close()
        try {
            instances.app.delete()
        } catch (_: Exception) {}
    }

    @Test
    fun test1_createBeer_persistsDurableMutationAndBeerInRoom() = runBlocking {
        val manager = managerA!!

        // 1. Crear cerveza
        val syncId = manager.createBeer(
            type = BeerType.CANA,
            comment = "Cerveza inicial de prueba",
            now = 1000L
        )
        assertNotNull(syncId)

        // 2. Verificar entidad en Room
        val beer = manager.getBeer(syncId)
        assertNotNull(beer)
        assertEquals(SyncStatus.PENDING, beer!!.syncStatus)
        assertEquals(LOCAL_ONLY_REVISION, beer.remoteRevision)
        assertEquals(LOCAL_ONLY_REVISION, beer.baseRevision)
        assertEquals("Cerveza inicial de prueba", beer.comment)
        assertNotNull(beer.lastMutationId)

        // 3. Verificar mutación durable previa en local_mutation_history
        val history = manager.getHistoryForBeer(syncId)
        assertEquals(1, history.size)
        val mutation = history[0]
        assertEquals(beer.lastMutationId, mutation.mutationId)
        assertEquals(MutationStatus.PENDING, mutation.status)
        assertEquals(LOCAL_ONLY_REVISION, mutation.baseRevision)
        assertTrue(mutation.isLocalOnlyCreation)
        val dirtyFields = BeerDirtyFieldsCodec.decode(mutation.dirtyFields)
        assertEquals(5, dirtyFields.size)
    }

    @Test
    fun test2_editComment_registersDurableMutationAndSupersedesPrior() = runBlocking {
        val manager = managerA!!

        // 1. Crear cerveza inicial
        val syncId = manager.createBeer(
            type = BeerType.CANA,
            comment = "Comentario v1",
            now = 1000L
        )
        val initialBeer = manager.getBeer(syncId)!!
        val initialMutationId = initialBeer.lastMutationId!!

        // 2. Editar comentario
        val updated = manager.editBeerComment(syncId, "Comentario v2 modificado", now = 2000L)
        assertTrue("La edición del comentario debió ser exitosa", updated)

        // 3. Verificar entidad en Room
        val editedBeer = manager.getBeer(syncId)!!
        assertEquals("Comentario v2 modificado", editedBeer.comment)
        assertNotEquals(initialMutationId, editedBeer.lastMutationId)
        val dirtyFields = BeerDirtyFieldsCodec.decode(editedBeer.dirtyFields)
        assertTrue(dirtyFields.contains(BeerDirtyField.COMMENT))

        // 4. Verificar historial durable: la mutación previa debe estar SUPERSEDED
        val history = manager.getHistoryForBeer(syncId)
        assertEquals(2, history.size)

        val priorMutation = history.find { it.mutationId == initialMutationId }!!
        assertEquals(MutationStatus.SUPERSEDED, priorMutation.status)

        val activeMutation = history.find { it.mutationId == editedBeer.lastMutationId }!!
        assertEquals(MutationStatus.PENDING, activeMutation.status)
        assertEquals(LOCAL_ONLY_REVISION, activeMutation.baseRevision)
        assertTrue(activeMutation.isLocalOnlyCreation)
    }

    @Test
    fun test3_pushPending_synchronizesToFirestoreEmulator() = runBlocking {
        val manager = managerA!!

        // 1. Crear y editar cerveza
        val syncId = manager.createBeer(type = BeerType.JARRA, comment = "Para sincronizar", now = 1000L)
        manager.editBeerComment(syncId, "Comentario listo para push", now = 2000L)

        // 2. Sincronizar cambios (PUSH)
        val pushResults = manager.pushAllPending()
        assertEquals(1, pushResults.size)
        val result = pushResults[syncId]
        assertTrue("El PUSH debió tener éxito: $result", result is C4CoordinatorPushResult.Success)
        val successResult = result as C4CoordinatorPushResult.Success
        assertEquals(1L, successResult.newRemoteRevision)

        // 3. Verificar estado en base descartable Room
        val syncedBeer = manager.getBeer(syncId)!!
        assertEquals(SyncStatus.SYNCED, syncedBeer.syncStatus)
        assertEquals(1L, syncedBeer.remoteRevision)
        assertEquals(1L, syncedBeer.baseRevision)
        assertNull(syncedBeer.dirtyFields)

        // 4. Verificar que la mutación durable en Room pasó a CONFIRMED
        val history = manager.getHistoryForBeer(syncId)
        val activeMutation = history.find { it.mutationId == syncedBeer.lastMutationId }
        // Si fue confirmada, el registro activo pasa a CONFIRMED
        val confirmedRecords = history.filter { it.status == MutationStatus.CONFIRMED }
        assertTrue("Debe existir al menos un registro confirmado en historial", confirmedRecords.isNotEmpty())
        assertEquals(1L, confirmedRecords.first().confirmedRevision)

        // 5. Verificar documento en Firestore Emulator
        val remoteDoc = manager.coordinator.firestoreAdapter.readServerDocument(syncId)
        assertNotNull(remoteDoc)
        assertEquals(1L, remoteDoc!!.revision)
        assertEquals(testUserUid, remoteDoc.userId)
        assertEquals("Comentario listo para push", remoteDoc.comment)
        assertFalse(remoteDoc.isDeleted)
        assertEquals(5, remoteDoc.fieldRevisions.size)
    }

    @Test
    fun test4_recoverFromAnotherInstance_ClientB() = runBlocking {
        val manager1 = managerA!!

        // 1. Cliente A crea y sube cerveza
        val syncId = manager1.createBeer(type = BeerType.PINTA, comment = "Cerveza compartida A -> B")
        val pushResult = manager1.pushBeer(syncId)
        assertTrue(pushResult is C4CoordinatorPushResult.Success)

        // 2. Cliente B (segundo dispositivo con su propia base Room descartable)
        managerB = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = true,
            dbName = "client_b_db.db",
            instances = instances
        )
        val manager2 = managerB!!

        // Antes del pull, Cliente B no tiene la cerveza
        assertNull(manager2.getBeer(syncId))

        // 3. Cliente B ejecuta PULL desde Firestore Emulator
        val pullResult = manager2.pullBeer(syncId)
        assertTrue("El PULL de Cliente B debió ser exitoso: $pullResult", pullResult is C4CoordinatorPullResult.Applied)

        // 4. Verificar que Cliente B tiene la cerveza exactamente replicada en su base Room
        val pulledBeer = manager2.getBeer(syncId)
        assertNotNull(pulledBeer)
        assertEquals(BeerType.PINTA, pulledBeer!!.type)
        assertEquals("Cerveza compartida A -> B", pulledBeer.comment)
        assertEquals(1L, pulledBeer.remoteRevision)
        assertEquals(1L, pulledBeer.baseRevision)
        assertEquals(SyncStatus.SYNCED, pulledBeer.syncStatus)
    }

    @Test
    fun test5_crashAndRestart_preservesPendingMutations() = runBlocking {
        val persistentDbName = "c4_restart_test_${UUID.randomUUID()}.db"

        // 1. Sesión 1: Crear cerveza y editarla en disco antes de sincronizar
        var managerSession1: C4AppSyncManager? = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = false,
            dbName = persistentDbName,
            instances = instances
        )
        val syncId = managerSession1!!.createBeer(type = BeerType.BOTELLIN, comment = "Antes del crash")
        managerSession1.editBeerComment(syncId, "Editado offline antes del reinicio")

        val preCrashBeer = managerSession1.getBeer(syncId)!!
        val pendingMutationId = preCrashBeer.lastMutationId!!

        // Simular reinicio/cierre abrupto de la aplicación
        managerSession1.close()
        managerSession1 = null

        // 2. Sesión 2: Re-instanciar C4AppSyncManager sobre la base existente en disco
        val managerSession2 = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = false,
            dbName = persistentDbName,
            instances = instances
        )

        try {
            // Verificar que los datos y mutaciones pendientes sobrevivieron al reinicio
            val postCrashBeer = managerSession2.getBeer(syncId)
            assertNotNull("La cerveza debió conservarse tras el reinicio", postCrashBeer)
            assertEquals("Editado offline antes del reinicio", postCrashBeer!!.comment)
            assertEquals(SyncStatus.PENDING, postCrashBeer.syncStatus)
            assertEquals(pendingMutationId, postCrashBeer.lastMutationId)

            val pendingMutations = managerSession2.getPendingMutations()
            assertTrue("Las mutaciones pendientes deben sobrevivir", pendingMutations.any { it.mutationId == pendingMutationId })

            // 3. Sincronizar tras el reinicio
            val pushResult = managerSession2.pushBeer(syncId)
            assertTrue("PUSH tras reinicio debió completarse con éxito: $pushResult", pushResult is C4CoordinatorPushResult.Success)

            val finalBeer = managerSession2.getBeer(syncId)!!
            assertEquals(SyncStatus.SYNCED, finalBeer.syncStatus)
            assertEquals(1L, finalBeer.remoteRevision)
        } finally {
            managerSession2.close()
            context.deleteDatabase(persistentDbName)
        }
    }

    @Test
    fun test6_deleteBeer_createsTombstoneAndPropagatesToClientB() = runBlocking {
        val manager1 = managerA!!

        // 1. Cliente A crea y sube cerveza (rev 1)
        val syncId = manager1.createBeer(type = BeerType.COPA, comment = "Cerveza a eliminar")
        manager1.pushBeer(syncId)

        // 2. Cliente B recupera la cerveza (rev 1)
        managerB = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = true,
            instances = instances
        )
        val manager2 = managerB!!
        manager2.pullBeer(syncId)
        assertNotNull(manager2.getBeer(syncId))

        // 3. Cliente A borra la cerveza localmente (Soft delete + mutación durable tombstone)
        val deleted = manager1.deleteBeer(syncId)
        assertTrue(deleted)
        val localDeletedBeer = manager1.getBeer(syncId)!!
        assertEquals(SyncStatus.DELETED, localDeletedBeer.syncStatus)

        // 4. Cliente A sincroniza el borrado hacia Firestore Emulator (PUSH Tombstone -> rev 2)
        val pushDeleteResult = manager1.pushBeer(syncId)
        assertTrue("PUSH de Tombstone debió ser exitoso: $pushDeleteResult", pushDeleteResult is C4CoordinatorPushResult.Success)
        assertEquals(2L, (pushDeleteResult as C4CoordinatorPushResult.Success).newRemoteRevision)

        // Verificar que en Firestore el documento existe como tombstone con isDeleted = true
        val remoteDoc = manager1.coordinator.firestoreAdapter.readServerDocument(syncId)
        assertNotNull(remoteDoc)
        assertTrue(remoteDoc!!.isDeleted)
        assertEquals(2L, remoteDoc.revision)

        // 5. Cliente B ejecuta PULL y recibe el Tombstone
        val pullDeleteResult = manager2.pullBeer(syncId)
        assertTrue("PULL de Tombstone en Cliente B debió ser exitoso: $pullDeleteResult", pullDeleteResult is C4CoordinatorPullResult.Applied)

        // Verificar que en Cliente B la cerveza pasó a DELETED con revisión 2
        val clientBBeer = manager2.getBeer(syncId)!!
        assertEquals(SyncStatus.DELETED, clientBBeer.syncStatus)
        assertEquals(2L, clientBBeer.remoteRevision)
    }
}
