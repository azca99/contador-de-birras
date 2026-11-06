package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.worker.BeerSyncWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba Real de Seguridad tras Cierre Forzado del Proceso (am force-stop) y Reinicio de la App.
 *
 * GARANTÍAS VERIFICADAS:
 * 1. Autenticación real en Firebase Emulator: Emplea un UID auténtico registrado en FirebaseAuth,
 *    evitando falsos positivos por UID mismatch (AUTH_CHANGED) en [BeerSyncWorker].
 * 2. Comprobación fehaciente de la ruta de bloqueo HALTED: Se verifica que currentUid == expectedUid
 *    y que la finalización del worker se debe estrictamente a [SyncEngineMode.HALTED].
 * 3. Supervivencia durable del estado: Comprueba que SharedPreferences en disco mantiene HALTED
 *    y el checkpoint de migración tras matar el proceso.
 * 4. Preservación estricta de datos locales: La mutación y entidad en Room permanecen en PENDING.
 * 5. Verificación efectiva de ausencia de escrituras remotas: Consulta directamente Firestore
 *    para comprobar que el documento no fue escrito en el servidor.
 */
@RunWith(AndroidJUnit4::class)
class C4ProcessRestartSafetyTest {

    companion object {
        const val TEST_EMAIL = "restart_safety_test@contador-c4.test"
        const val TEST_PASSWORD = "password123456"
        const val TEST_SYNC_ID = "sync_c4_restart_beer_1"
        const val TEST_MUTATION_ID = "mut_c4_restart_init_1"
        const val TEST_CHECKPOINT = "MIGRATION_INTERRUPTED_CHECKPOINT"

        private const val PREFS_TEST_NAME = "c4_restart_test_prefs"
        private const val KEY_RECORDED_UID = "test_authenticated_uid"
    }

    private fun ensureEmulatorConfigured() {
        try {
            FirebaseAuth.getInstance().useEmulator(
                C4SyncConstants.EMULATOR_HOST_ANDROID,
                C4TestEnvironmentFactory.REQUIRED_AUTH_PORT
            )
        } catch (_: IllegalStateException) {
            // Ya configurado o en uso
        }
        try {
            FirebaseFirestore.getInstance().useEmulator(
                C4SyncConstants.EMULATOR_HOST_ANDROID,
                C4SyncConstants.FIRESTORE_PORT
            )
        } catch (_: IllegalStateException) {
            // Ya configurado o en uso
        }
    }

    /**
     * Paso 1: Autentica un usuario real en Firebase Auth Emulator, persiste el estado HALTED,
     * registra el checkpoint de migración e inserta la mutación local PENDING en Room.
     */
    @Test
    fun step1_prepareHaltedAccount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureEmulatorConfigured()

        // 1. Autenticar usuario REAL en Firebase Auth Emulator
        val auth = FirebaseAuth.getInstance()
        val authUser = try {
            auth.signInWithEmailAndPassword(TEST_EMAIL, TEST_PASSWORD).await().user
        } catch (_: Exception) {
            auth.createUserWithEmailAndPassword(TEST_EMAIL, TEST_PASSWORD).await().user
        }
        assertNotNull("FirebaseAuth debe tener un usuario autenticado real en el emulador", authUser)
        val authenticatedUid = authUser!!.uid

        // Persistir el UID autenticado para verificación de ruta en step2
        context.getSharedPreferences(PREFS_TEST_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDED_UID, authenticatedUid)
            .commit()

        val db = BeerDatabase.getDatabase(context)
        val beerDao = db.beerDao()

        // 2. Limpiar rastro previo para este UID autenticado
        C4SyncStateStore.clearForTesting(context, authenticatedUid)

        // 3. Insertar cerveza local con mutación PENDING asociada al UID autenticado real
        val beer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = authenticatedUid,
            syncId = TEST_SYNC_ID,
            syncStatus = SyncStatus.PENDING,
            comment = "Cerveza Local Pendiente Antes del Force-Stop"
        ).copy(
            lastMutationId = TEST_MUTATION_ID,
            dirtyFields = "COMMENT"
        )
        beerDao.insertBeer(beer)

        // 4. Insertar historial de mutación PENDING asociada al UID autenticado real
        val historyRecord = LocalMutationHistoryEntity(
            mutationId = TEST_MUTATION_ID,
            syncId = TEST_SYNC_ID,
            ownerUid = authenticatedUid,
            timestamp = 1700000000000L,
            dirtyFields = "COMMENT",
            baseRevision = 0L,
            status = MutationStatus.PENDING,
            isLocalOnlyCreation = true
        )
        beerDao.insertHistoryRecord(historyRecord)

        // 5. Persistir estado HALTED y checkpoint en SharedPreferences (commit síncrono a disco)
        C4SyncStateStore.setEngineMode(context, authenticatedUid, SyncEngineMode.HALTED, commitSync = true)
        C4SyncStateStore.recordMigrationCheckpoint(context, authenticatedUid, TEST_CHECKPOINT, commitSync = true)

        // Verificación inmediata antes del force-stop
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, authenticatedUid))
        assertEquals(TEST_CHECKPOINT, C4SyncStateStore.getMigrationCheckpoint(context, authenticatedUid))
        val insertedBeer = beerDao.getBeerBySyncId(TEST_SYNC_ID, authenticatedUid)
        assertNotNull(insertedBeer)
        assertEquals(SyncStatus.PENDING, insertedBeer!!.syncStatus)

        println("[RESTART_TEST_STEP1] PREPARATION_HALTED_OK with real UID: $authenticatedUid")
    }

    /**
     * Paso 2: Ejecutado tras force-stop y reapertura del proceso.
     * Verifica la supervivencia del estado HALTED y la no reactivación del motor legacy en BeerSyncWorker.
     */
    @Test
    fun step2_verifyAfterRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureEmulatorConfigured()

        val auth = FirebaseAuth.getInstance()
        val currentUid = auth.currentUser?.uid

        // 1. VERIFICACIÓN DE CONDICIÓN DE AUTENTICACIÓN:
        // Asegurar que el usuario sigue autenticado tras el reinicio y coincide con el UID esperado
        val recordedUid = context.getSharedPreferences(PREFS_TEST_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDED_UID, null)

        assertNotNull(
            "UID autenticado previamente debe existir en las preferencias de test",
            recordedUid
        )
        assertNotNull(
            "FirebaseAuth.currentUser.uid NO debe ser nulo tras el reinicio. " +
            "Si el usuario no está autenticado, BeerSyncWorker finalizaría prematuramente por UID mismatch (AUTH_CHANGED) " +
            "generando un falso positivo antes de comprobar HALTED.",
            currentUid
        )
        assertEquals(
            "El usuario autenticado en FirebaseAuth debe coincidir exactamente con el UID registrado",
            recordedUid,
            currentUid
        )

        val authenticatedUid = currentUid!!

        // 2. COMPROBACIÓN FIABLE DE QUE SE TOMA LA RUTA DE BLOQUEO HALTED:
        // a) La memoria estática del proceso se reinició por defecto (C4SyncConfig.isEnabled = false)
        assertFalse("Memoria estática debe iniciar con isEnabled = false", C4SyncConfig.isEnabled)
        assertEquals("Memoria estática debe iniciar con engineMode = LEGACY", SyncEngineMode.LEGACY, C4SyncConfig.engineMode)

        // b) El estado persistente en disco SOBREVIVIÓ como HALTED para este usuario
        val persistedMode = C4SyncStateStore.getEngineMode(context, authenticatedUid)
        assertEquals(
            "El estado persistente en disco debe ser HALTED para el UID autenticado",
            SyncEngineMode.HALTED,
            persistedMode
        )
        val checkpoint = C4SyncStateStore.getMigrationCheckpoint(context, authenticatedUid)
        assertEquals(
            "El checkpoint recuperable debe persistir intacto",
            TEST_CHECKPOINT,
            checkpoint
        )
        assertTrue(
            "Legacy debe estar bloqueado formalmente según C4SyncStateStore",
            C4SyncStateStore.isLegacyBlocked(context, authenticatedUid)
        )

        // c) Ejecutar BeerSyncWorker en el entorno real de Android WorkManager.
        // Dado que currentUid == expectedUid, NO toma la ruta de aborto temprano por UID mismatch.
        // Toma la ruta de comprobación de modo persistente HALTED y retorna Result.success() sin invocar Legacy.
        val workRequest = OneTimeWorkRequestBuilder<BeerSyncWorker>()
            .setInputData(workDataOf(BeerSyncWorker.KEY_EXPECTED_UID to authenticatedUid))
            .build()
        WorkManager.getInstance(context).enqueue(workRequest).result.get()

        var attempts = 0
        var workInfo: WorkInfo? = WorkManager.getInstance(context).getWorkInfoById(workRequest.id).get()
        while (workInfo != null && !workInfo.state.isFinished && attempts < 30) {
            kotlinx.coroutines.delay(200)
            workInfo = WorkManager.getInstance(context).getWorkInfoById(workRequest.id).get()
            attempts++
        }
        assertNotNull("WorkInfo no debe ser nulo", workInfo)
        assertEquals("BeerSyncWorker debió finalizar en SUCCEEDED", WorkInfo.State.SUCCEEDED, workInfo!!.state)

        // 3. VERIFICACIÓN DE QUE NO SE EJECUTÓ EL MOTOR LEGACY:
        // Si el motor Legacy se hubiera ejecutado para este usuario autenticado:
        // - Habría subido la cerveza a Firestore
        // - Habría marcado la cerveza en Room como SYNCED
        val db = BeerDatabase.getDatabase(context)
        val beerDao = db.beerDao()
        val beerAfterWorker = beerDao.getBeerBySyncId(TEST_SYNC_ID, authenticatedUid)
        assertNotNull("La cerveza debe seguir existiendo en Room", beerAfterWorker)
        assertEquals(
            "La cerveza debe permanecer estrictamente en SyncStatus.PENDING (el motor Legacy no la procesó)",
            SyncStatus.PENDING,
            beerAfterWorker!!.syncStatus
        )
        assertEquals("El comentario debe preservarse", "Cerveza Local Pendiente Antes del Force-Stop", beerAfterWorker.comment)
        assertEquals("Revision remota debe seguir siendo 0", 0L, beerAfterWorker.remoteRevision)

        val targetHistory = db.beerDao().getHistoryRecordById(TEST_MUTATION_ID, authenticatedUid)
        assertNotNull("La mutación pendiente debe seguir existiendo en el historial", targetHistory)
        assertEquals(
            "La mutación debe seguir estrictamente en MutationStatus.PENDING",
            MutationStatus.PENDING,
            targetHistory!!.status
        )

        // 4. VERIFICACIÓN EFECTIVA DE AUSENCIA DE ESCRITURAS REMOTAS EN FIRESTORE:
        val firestore = FirebaseFirestore.getInstance()
        val remoteDoc = firestore.collection(C4SyncConstants.BEERS_COLLECTION)
            .document(TEST_SYNC_ID)
            .get()
            .await()
        assertFalse(
            "VERIFICACIÓN EFECTIVA: El documento remoto beers/$TEST_SYNC_ID NO debe existir en Firestore. " +
            "Si el motor Legacy se hubiese ejecutado, el documento existiría.",
            remoteDoc.exists()
        )

        println("[RESTART_TEST_STEP2] VERIFICATION_SUCCESS_HALTED_BLOCK_EFFECTIVE_NO_REMOTE_WRITES")
    }
}
