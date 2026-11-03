package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
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
 * Demostración de Descubrimiento Remoto Multidispositivo bajo el Protocolo C4 (Fase C4.4B - Paso 4).
 *
 * FLUJO VALIDADO EN AVD REAL CON FIREBASE EMULATOR:
 * 1. Cliente A crea dos cervezas en su base Room descartable.
 * 2. Cliente A ejecuta PUSH hacia Firestore Emulator.
 * 3. Cliente B arranca con una base Room descartable completamente vacía (sin conocer los syncIds).
 * 4. Cliente B ejecuta PULL por colección (descubrimiento desde SERVER).
 * 5. Aparecen ambas cervezas en la base Room de Cliente B como SYNCED.
 * 6. Cliente A modifica el comentario de la cerveza 1 y hace PUSH (rev 2).
 * 7. Cliente B ejecuta PULL y recibe la actualización de la cerveza 1.
 * 8. Cliente A borra la cerveza 2 (soft-delete / tombstone) y hace PUSH (rev 2).
 * 9. Cliente B ejecuta PULL y aplica el tombstone a la cerveza 2 (marcada DELETED con rev 2).
 * 10. Verificación exhaustiva de estados finales locales y remotos.
 */
@RunWith(AndroidJUnit4::class)
class C4RemoteDiscoveryInstrumentedTest {

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

        // 1. Detección dinámica de host (10.0.2.2 o fallback 127.0.0.1 con adb reverse)
        targetHost = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_ANDROID, C4SyncConstants.FIRESTORE_PORT), 1000)
                C4SyncConstants.EMULATOR_HOST_ANDROID
            }
        } catch (_: Exception) {
            C4SyncConstants.EMULATOR_HOST_LOCAL
        }

        // 2. Entorno aislado y autenticación en Auth Emulator
        instances = C4TestEnvironmentFactory.createIsolatedInstances(
            context = context,
            host = targetHost,
            instanceSuffix = "discovery-${System.nanoTime()}"
        )
        auth = instances.auth
        val email = "user_disco_${UUID.randomUUID().toString().take(8)}@contador-c4.test"
        val authResult = auth.createUserWithEmailAndPassword(email, "password123456").await()
        testUserUid = authResult.user!!.uid

        // 3. Cliente A con su propia base Room descartable
        managerA = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = true,
            dbName = "client_a_discovery.db",
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
    fun testRemoteDiscovery_ClientB_DiscoversBeers_ReceivesUpdates_And_AppliesTombstones() = runBlocking {
        val clientA = managerA!!

        // =========================================================================
        // 1. Cliente A crea dos cervezas en su base Room
        // =========================================================================
        val beer1SyncId = clientA.createBeer(
            type = BeerType.CANA,
            comment = "Cerveza 1 Cliente A",
            now = 1000L
        )
        val beer2SyncId = clientA.createBeer(
            type = BeerType.JARRA,
            comment = "Cerveza 2 Cliente A",
            now = 1001L
        )
        assertNotNull(beer1SyncId)
        assertNotNull(beer2SyncId)
        assertEquals(2, clientA.getActiveBeers().size)

        // =========================================================================
        // 2. Cliente A ejecuta PUSH hacia Firestore Emulator
        // =========================================================================
        val pushResults = clientA.pushAllPending()
        assertEquals(2, pushResults.size)
        assertTrue("PUSH Cerveza 1 debió ser exitoso", pushResults[beer1SyncId] is C4CoordinatorPushResult.Success)
        assertTrue("PUSH Cerveza 2 debió ser exitoso", pushResults[beer2SyncId] is C4CoordinatorPushResult.Success)

        val aBeer1 = clientA.getBeer(beer1SyncId)!!
        val aBeer2 = clientA.getBeer(beer2SyncId)!!
        assertEquals(SyncStatus.SYNCED, aBeer1.syncStatus)
        assertEquals(SyncStatus.SYNCED, aBeer2.syncStatus)
        assertEquals(1L, aBeer1.remoteRevision)
        assertEquals(1L, aBeer2.remoteRevision)

        // =========================================================================
        // 3. Cliente B arranca con Room completamente vacía (sin conocer syncIds)
        // =========================================================================
        managerB = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = true,
            dbName = "client_b_discovery.db",
            instances = instances
        )
        val clientB = managerB!!

        // Verificación previa: Cliente B no tiene ninguna cerveza
        val initialBBeers = clientB.getActiveBeers()
        assertTrue("Cliente B debe arrancar con Room vacía", initialBBeers.isEmpty())
        assertNull(clientB.getBeer(beer1SyncId))
        assertNull(clientB.getBeer(beer2SyncId))

        // =========================================================================
        // 4. Cliente B pulsa PULL (Descubrimiento por colección desde SERVER)
        // =========================================================================
        val discoveryResults = clientB.pullRemoteCollection()
        assertEquals("Cliente B debió descubrir exactamente 2 documentos remotos", 2, discoveryResults.size)
        assertTrue("PULL de Cerveza 1 debió aplicarse en Cliente B", discoveryResults[beer1SyncId] is C4CoordinatorPullResult.Applied)
        assertTrue("PULL de Cerveza 2 debió aplicarse en Cliente B", discoveryResults[beer2SyncId] is C4CoordinatorPullResult.Applied)

        // =========================================================================
        // 5. Aparecen ambas cervezas en la base Room de Cliente B
        // =========================================================================
        val discoveredBeers = clientB.getActiveBeers()
        assertEquals("Cliente B debe tener ahora exactamente 2 cervezas activas", 2, discoveredBeers.size)

        val bBeer1 = clientB.getBeer(beer1SyncId)
        assertNotNull("Cerveza 1 debe existir en Cliente B", bBeer1)
        assertEquals("Cerveza 1 Cliente A", bBeer1!!.comment)
        assertEquals(BeerType.CANA, bBeer1.type)
        assertEquals(1L, bBeer1.remoteRevision)
        assertEquals(1L, bBeer1.baseRevision)
        assertEquals(SyncStatus.SYNCED, bBeer1.syncStatus)

        val bBeer2 = clientB.getBeer(beer2SyncId)
        assertNotNull("Cerveza 2 debe existir en Cliente B", bBeer2)
        assertEquals("Cerveza 2 Cliente A", bBeer2!!.comment)
        assertEquals(BeerType.JARRA, bBeer2.type)
        assertEquals(1L, bBeer2.remoteRevision)
        assertEquals(1L, bBeer2.baseRevision)
        assertEquals(SyncStatus.SYNCED, bBeer2.syncStatus)

        // =========================================================================
        // 6. Cliente A modifica una cerveza (cerveza 1) y hace PUSH
        // =========================================================================
        val edited = clientA.editBeerComment(beer1SyncId, "Comentario modificado por Cliente A", now = 2000L)
        assertTrue("Edición en Cliente A debió ser exitosa", edited)
        val pushEditResult = clientA.pushBeer(beer1SyncId)
        assertTrue("PUSH de edición debió ser exitoso", pushEditResult is C4CoordinatorPushResult.Success)
        assertEquals(2L, (pushEditResult as C4CoordinatorPushResult.Success).newRemoteRevision)

        // =========================================================================
        // 7. Cliente B recibe la actualización mediante PULL por colección
        // =========================================================================
        val pullUpdateResults = clientB.pullRemoteCollection()
        assertTrue("PULL de cerveza 1 modificada debió aplicarse", pullUpdateResults[beer1SyncId] is C4CoordinatorPullResult.Applied)

        val bBeer1Updated = clientB.getBeer(beer1SyncId)!!
        assertEquals("Comentario modificado por Cliente A", bBeer1Updated.comment)
        assertEquals(2L, bBeer1Updated.remoteRevision)
        assertEquals(2L, bBeer1Updated.baseRevision)
        assertEquals(SyncStatus.SYNCED, bBeer1Updated.syncStatus)

        // =========================================================================
        // 8. Cliente A borra la otra cerveza (cerveza 2) y hace PUSH (Tombstone)
        // =========================================================================
        val deleted = clientA.deleteBeer(beer2SyncId, now = 3000L)
        assertTrue("Borrado local en Cliente A debió ser exitoso", deleted)
        val pushDeleteResult = clientA.pushBeer(beer2SyncId)
        assertTrue("PUSH de Tombstone debió ser exitoso", pushDeleteResult is C4CoordinatorPushResult.Success)
        assertEquals(2L, (pushDeleteResult as C4CoordinatorPushResult.Success).newRemoteRevision)

        // =========================================================================
        // 9. Cliente B aplica el tombstone mediante PULL por colección
        // =========================================================================
        val pullTombstoneResults = clientB.pullRemoteCollection()
        assertTrue("PULL de Tombstone debió aplicarse en Cliente B", pullTombstoneResults[beer2SyncId] is C4CoordinatorPullResult.Applied)

        val bBeer2Deleted = clientB.getBeer(beer2SyncId)!!
        assertEquals("Cerveza 2 en Cliente B debe haber pasado a DELETED", SyncStatus.DELETED, bBeer2Deleted.syncStatus)
        assertEquals(2L, bBeer2Deleted.remoteRevision)
        assertEquals(2L, bBeer2Deleted.baseRevision)

        // =========================================================================
        // 10. Verificación exhaustiva de estado final
        // =========================================================================
        // En Cliente B solo queda 1 cerveza activa (la cerveza 1 modificada)
        val finalActiveB = clientB.getActiveBeers()
        assertEquals(1, finalActiveB.size)
        assertEquals(beer1SyncId, finalActiveB[0].syncId)
        assertEquals("Comentario modificado por Cliente A", finalActiveB[0].comment)

        // En Cliente A solo queda 1 cerveza activa
        val finalActiveA = clientA.getActiveBeers()
        assertEquals(1, finalActiveA.size)
        assertEquals(beer1SyncId, finalActiveA[0].syncId)

        // En Firestore Emulator:
        // Cerveza 1: activa con rev 2
        val remoteDoc1 = clientA.coordinator.firestoreAdapter.readServerDocument(beer1SyncId)!!
        assertFalse(remoteDoc1.isDeleted)
        assertEquals(2L, remoteDoc1.revision)
        assertEquals("Comentario modificado por Cliente A", remoteDoc1.comment)

        // Cerveza 2: tombstone con rev 2
        val remoteDoc2 = clientA.coordinator.firestoreAdapter.readServerDocument(beer2SyncId)!!
        assertTrue(remoteDoc2.isDeleted)
        assertEquals(2L, remoteDoc2.revision)
    }
}
