package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
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
 * Prueba Funcional de Preparación Final para Activación Controlada (C4 Release Readiness).
 *
 * Valida de extremo a extremo en Firebase Emulator y Android Runtime:
 * 1. Persistencia durable del estado del motor por usuario (C4SyncStateStore).
 * 2. Simulación de migración interrumpida (HALTED).
 * 3. Simulación de cierre forzado del proceso y reinicio de Android (reboot).
 * 4. Verificación de que el motor Legacy NO se reactiva automáticamente tras el reinicio.
 * 5. Reanudación segura de la migración sin pérdida de datos ni registros duplicados.
 * 6. Registro de Account Protocol Lock en Firestore (users/{userId}).
 * 7. Sincronización bidireccional entre dos clientes C4 independientes:
 *    creaciones, fotografías versionadas, mutaciones pendientes, ediciones y tombstones.
 */
@RunWith(AndroidJUnit4::class)
class C4ReleaseReadinessInstrumentedTest {

    private lateinit var context: Context
    private lateinit var targetHost: String
    private lateinit var instances: C4TestEnvironmentFactory.IsolatedFirebaseInstances
    private lateinit var auth: FirebaseAuth
    private var testUserUid: String = ""

    private var managerA: C4AppSyncManager? = null
    private var managerB: C4AppSyncManager? = null
    private var dbNameA: String = ""
    private var dbNameB: String = ""

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Limpieza de estado persistente previo
        C4SyncStateStore.clearForTesting(context)
        C4SyncConfig.reset()
        C4SyncProviderRegistry.reset()

        // 2. Detección de host de emuladores Firebase (10.0.2.2 o fallback 127.0.0.1)
        targetHost = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(C4SyncConstants.EMULATOR_HOST_ANDROID, C4SyncConstants.FIRESTORE_PORT), 1000)
                C4SyncConstants.EMULATOR_HOST_ANDROID
            }
        } catch (_: Exception) {
            C4SyncConstants.EMULATOR_HOST_LOCAL
        }

        // 3. Instancias aisladas de Firebase
        instances = C4TestEnvironmentFactory.createIsolatedInstances(
            context = context,
            host = targetHost,
            instanceSuffix = "readiness-${System.nanoTime()}"
        )
        auth = instances.auth
        val email = "user_readiness_${UUID.randomUUID().toString().take(8)}@contador-c4.test"
        val authResult = auth.createUserWithEmailAndPassword(email, "password123456").await()
        testUserUid = authResult.user!!.uid

        // 4. Inicializar Cliente A con SQLite en disco
        dbNameA = "c4_readiness_a_${System.nanoTime()}.db"
        managerA = C4AppSyncManager.createForTesting(
            context = context,
            ownerUid = testUserUid,
            host = targetHost,
            inMemory = false,
            dbName = dbNameA,
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
        C4SyncStateStore.clearForTesting(context)
        C4SyncConfig.reset()
        C4SyncProviderRegistry.reset()
    }

    @Test
    fun testCompleteReleaseReadinessLifecycle() {
        runBlocking {
            val clientA = managerA!!
            val dbA = clientA.database
            val beerDaoA = dbA.beerDao()
            val firestore = instances.firestore

            // =========================================================================
            // FASE 1: Sembrar datos legacy reales en Firestore y Room A
            // =========================================================================
            val beer1SyncId = UUID.randomUUID().toString()
            val beer2SyncId = UUID.randomUUID().toString()
            val beer3SyncId = UUID.randomUUID().toString()

            // Cerveza 1: SYNCED con foto remota legacy
            val beer1 = LegacyBeerFixtures.createLegacyRoomBeer(
                ownerUid = testUserUid,
                syncId = beer1SyncId,
                syncStatus = SyncStatus.SYNCED,
                comment = "Cerveza 1 Legacy Synced",
                hasRemotePhoto = true
            )
            beerDaoA.insertBeer(beer1)
            firestore.collection("beers").document(beer1SyncId).set(
                LegacyBeerFixtures.createLegacyFirestorePayload(
                    userId = testUserUid,
                    syncId = beer1SyncId,
                    comment = "Cerveza 1 Legacy Synced",
                    hasPhoto = true
                )
            ).await()

            // Cerveza 2: PENDING offline con foto local en archivo
            val localPhotoFile = File(context.cacheDir, "readiness_photo_${beer2SyncId}.jpg")
            val sampleJpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46)
            localPhotoFile.writeBytes(sampleJpgBytes)
            val beer2 = LegacyBeerFixtures.createLegacyRoomBeer(
                ownerUid = testUserUid,
                syncId = beer2SyncId,
                syncStatus = SyncStatus.PENDING,
                comment = "Cerveza 2 Legacy Pending con Foto",
                photoUri = localPhotoFile.absolutePath
            )
            beerDaoA.insertBeer(beer2)

            // Cerveza 3: DELETED en Room y presente en Firestore
            val beer3 = LegacyBeerFixtures.createLegacyRoomBeer(
                ownerUid = testUserUid,
                syncId = beer3SyncId,
                syncStatus = SyncStatus.DELETED,
                comment = "Cerveza 3 Legacy Borrada"
            )
            beerDaoA.insertBeer(beer3)
            firestore.collection("beers").document(beer3SyncId).set(
                LegacyBeerFixtures.createLegacyFirestorePayload(
                    userId = testUserUid,
                    syncId = beer3SyncId,
                    comment = "Cerveza 3 Legacy Borrada"
                )
            ).await()

            // =========================================================================
            // FASE 2: Interrupción de la migración y persistencia durable de HALTED
            // =========================================================================
            // Simular que el proceso de cutover fue interrumpido inesperadamente
            C4SyncStateStore.setEngineMode(context, testUserUid, SyncEngineMode.HALTED)
            C4SyncStateStore.recordMigrationCheckpoint(
                context,
                testUserUid,
                "INTERRUPTED_RECOVERABLE_CHECKPOINT"
            )

            assertEquals(
                "El modo persistente DEBE ser HALTED",
                SyncEngineMode.HALTED,
                C4SyncStateStore.getEngineMode(context, testUserUid)
            )
            assertTrue(
                "Legacy debe estar bloqueado",
                C4SyncStateStore.isLegacyBlocked(context, testUserUid)
            )
            assertEquals(
                "INTERRUPTED_RECOVERABLE_CHECKPOINT",
                C4SyncStateStore.getMigrationCheckpoint(context, testUserUid)
            )

            // =========================================================================
            // FASE 3: Simular reinicio completo del sistema / proceso Android
            // =========================================================================
            // En un reinicio de Android, todas las variables estáticas en memoria vuelven a su valor por defecto:
            C4SyncConfig.reset() // isEnabled = false, engineMode = LEGACY en memoria estática
            C4SyncProviderRegistry.reset()

            // Comprobación crítica: El estado persistente en disco SOBREVIVE al reseteo de memoria
            val modeAfterReboot = C4SyncStateStore.getEngineMode(context, testUserUid)
            assertEquals(
                "El estado persistente SOBREVIVE al reinicio y sigue siendo HALTED",
                SyncEngineMode.HALTED,
                modeAfterReboot
            )
            assertTrue(
                "Legacy continúa estrictamente bloqueado tras el reinicio",
                C4SyncStateStore.isLegacyBlocked(context, testUserUid)
            )

            // Al restaurar la configuración para el usuario tras el reinicio, se adopta HALTED de forma segura
            C4SyncConfig.restoreUserEngineMode(context, testUserUid)
            assertEquals(SyncEngineMode.HALTED, C4SyncConfig.engineMode)
            assertFalse(C4SyncConfig.isEnabled)
            assertFalse(C4SyncConfig.isC4Active())

            // Comprobar que Firestore permanece intacto (las cervezas legacy NO fueron alteradas)
            val doc1BeforeResume = clientA.coordinator.firestoreAdapter.readServerDocument(beer1SyncId)
            assertNotNull(doc1BeforeResume)
            assertEquals(0L, doc1BeforeResume!!.revision)

            // =========================================================================
            // FASE 4: Reanudación de la migración sin duplicados
            // =========================================================================
            val cutoverManager = C4StagingCutoverManager(
                coordinator = clientA.coordinator,
                persistence = clientA.persistence,
                firestoreAdapter = clientA.coordinator.firestoreAdapter,
                context = context
            )

            val cutoverResult = cutoverManager.executeCutover(testUserUid)
            assertTrue(
                "La reanudación del cutover DEBE completarse con éxito: $cutoverResult",
                cutoverResult is CutoverResult.Completed
            )

            // Verificar modo persistente post-migración
            assertEquals(
                "El modo persistente ahora es C4_ACTIVE",
                SyncEngineMode.C4_ACTIVE,
                C4SyncStateStore.getEngineMode(context, testUserUid)
            )
            assertTrue(C4SyncConfig.isEnabled)
            assertEquals(SyncEngineMode.C4_ACTIVE, C4SyncConfig.engineMode)

            // Verificar Account Protocol Lock en Firestore (/users/{userId})
            val accountLock = clientA.coordinator.firestoreAdapter.readAccountProtocolLock(testUserUid)
            assertNotNull("El bloqueo de protocolo a nivel de cuenta debe existir", accountLock)
            assertEquals("C4", accountLock!!.syncProtocolVersion)
            assertEquals("MIGRATED_TO_C4", accountLock.accountSyncState)

            // Verificar cero duplicados en Room A: exactamente 2 activas y 1 borrada
            val activeBeersA = beerDaoA.getActiveBeersSync(testUserUid)
            assertEquals("Debe haber exactamente 2 cervezas activas", 2, activeBeersA.size)

            val finalBeer1 = beerDaoA.getBeerBySyncId(beer1SyncId, testUserUid)!!
            assertEquals(SyncStatus.SYNCED, finalBeer1.syncStatus)
            assertEquals(1L, finalBeer1.remoteRevision)

            val finalBeer2 = beerDaoA.getBeerBySyncId(beer2SyncId, testUserUid)!!
            assertEquals(SyncStatus.SYNCED, finalBeer2.syncStatus)
            assertEquals(1L, finalBeer2.remoteRevision)
            assertNotNull(finalBeer2.remotePhotoUrl)
            assertTrue(
                "La foto debe existir en Storage versionado",
                clientA.coordinator.storageAdapter.photoExists(finalBeer2.remotePhotoUrl!!)
            )

            val finalBeer3 = beerDaoA.getBeerBySyncId(beer3SyncId, testUserUid)!!
            assertEquals(SyncStatus.DELETED, finalBeer3.syncStatus)
            assertEquals(2L, finalBeer3.remoteRevision)

            // =========================================================================
            // FASE 5: Sincronización bidireccional entre Cliente A y Cliente B
            // =========================================================================
            // Registrar StagingC4SyncProvider para la integración completa
            C4SyncProviderRegistry.setProvider(StagingC4SyncProvider { _, uid ->
                if (uid == testUserUid) clientA else throw IllegalArgumentException("Usuario desconocido")
            })

            // Crear Cliente B independiente para el mismo usuario
            dbNameB = "c4_readiness_b_${System.nanoTime()}.db"
            managerB = C4AppSyncManager.createForTesting(
                context = context,
                ownerUid = testUserUid,
                host = targetHost,
                inMemory = false,
                dbName = dbNameB,
                instances = instances
            )
            val clientB = managerB!!
            val beerDaoB = clientB.database.beerDao()

            // Cliente B inicia con Room vacía y realiza PULL
            val initialPullB = clientB.pullRemoteCollection()
            assertFalse("PULL de B no debe tener bloqueos", initialPullB.values.any { it is C4CoordinatorPullResult.Blocked })

            // Comprobar que Cliente B tiene las cervezas activas migradas
            val activeBeersB = beerDaoB.getActiveBeersSync(testUserUid)
            assertEquals("Cliente B debe recibir exactamente 2 cervezas activas", 2, activeBeersB.size)
            val bBeer1 = beerDaoB.getBeerBySyncId(beer1SyncId, testUserUid)!!
            val bBeer2 = beerDaoB.getBeerBySyncId(beer2SyncId, testUserUid)!!

            assertEquals(SyncStatus.SYNCED, bBeer1.syncStatus)
            assertEquals(1L, bBeer1.remoteRevision)
            assertEquals(SyncStatus.SYNCED, bBeer2.syncStatus)
            assertEquals(1L, bBeer2.remoteRevision)

            // Beer 3 era un tombstone remoto previo a la incorporación de B:
            // Según el protocolo C4 (SyncProtocolPullPlanner.IgnoreRemoteTombstone), B no crea entidades locales innecesarias
            val bBeer3 = beerDaoB.getBeerBySyncId(beer3SyncId, testUserUid)
            assertNull("Cliente B no debe crear entidad para un tombstone histórico que nunca poseyó", bBeer3)

            // Cliente A realiza nuevas operaciones C4:
            // 1. Modificar Cerveza 1
            val edited = clientA.editBeerComment(beer1SyncId, "Cerveza 1 Modificada por Cliente A")
            assertTrue("Edición de Cerveza 1 debió ser exitosa", edited)

            // 2. Crear Cerveza 4 nueva con foto
            val photoFile4 = File(context.cacheDir, "readiness_photo_4.jpg")
            photoFile4.writeBytes(sampleJpgBytes)
            val beer4SyncId = clientA.createBeer(
                type = BeerType.JARRA,
                comment = "Cerveza 4 Nueva creada en A",
                photoUri = photoFile4.absolutePath
            )

            // 3. Borrar Cerveza 2 (Tombstone)
            val deleted = clientA.deleteBeer(beer2SyncId)
            assertTrue("Borrado de Cerveza 2 debió ser exitoso", deleted)

            // Cliente A ejecuta PUSH
            val pushA = clientA.pushAllPending()
            assertTrue("PUSH de A debe tener éxito en todas sus mutaciones", pushA.values.all {
                it is C4CoordinatorPushResult.Success || it is C4CoordinatorPushResult.IdempotentRecovery
            })

            // Cliente B ejecuta PULL para descubrir las modificaciones de A
            val pullB2 = clientB.pullRemoteCollection()
            assertFalse("PULL B2 no debe tener bloqueos", pullB2.values.any { it is C4CoordinatorPullResult.Blocked })

            // Verificaciones en Cliente B
            val bBeer1Updated = beerDaoB.getBeerBySyncId(beer1SyncId, testUserUid)!!
            assertEquals("Cerveza 1 Modificada por Cliente A", bBeer1Updated.comment)
            assertEquals(2L, bBeer1Updated.remoteRevision)

            val bBeer2Deleted = beerDaoB.getBeerBySyncId(beer2SyncId, testUserUid)!!
            assertEquals(SyncStatus.DELETED, bBeer2Deleted.syncStatus)
            assertEquals(2L, bBeer2Deleted.remoteRevision)

            val bBeer4 = beerDaoB.getBeerBySyncId(beer4SyncId, testUserUid)!!
            assertEquals(SyncStatus.SYNCED, bBeer4.syncStatus)
            assertEquals(1L, bBeer4.remoteRevision)
            assertEquals("Cerveza 4 Nueva creada en A", bBeer4.comment)
            assertNotNull(bBeer4.remotePhotoUrl)

            // Verificar que en Cliente B hay exactamente 2 cervezas activas (Beer 1 y Beer 4)
            val finalActiveB = beerDaoB.getActiveBeersSync(testUserUid)
            assertEquals("Cliente B debe tener exactamente 2 cervezas activas (1 y 4)", 2, finalActiveB.size)

            // Limpieza de fotos temporales
            localPhotoFile.delete()
            photoFile4.delete()
        }
    }
}
