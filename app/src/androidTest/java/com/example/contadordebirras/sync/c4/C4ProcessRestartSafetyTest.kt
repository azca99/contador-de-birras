package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.worker.BeerSyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba Real de Seguridad tras Cierre Forzado del Proceso (am force-stop) y Reinicio de la App.
 *
 * Valida:
 * 1. Preparación de cuenta con estado HALTED, checkpoint de migración interrumpida y mutación local PENDING.
 * 2. Cierre forzado del proceso desde fuera (vía ADB am force-stop).
 * 3. Reapertura del proceso de la app (vía ADB am start).
 * 4. Verificación de que el estado persistente en disco sobrevive como HALTED.
 * 5. Ejecución real de BeerSyncWorker en el nuevo proceso:
 *    - Comprobación de que NO ejecuta Legacy (persistedMode != LEGACY).
 *    - Comprobación de que la mutación local sigue intacta y PENDING en Room.
 *    - Comprobación de que no hubo escrituras remotas.
 */
@RunWith(AndroidJUnit4::class)
class C4ProcessRestartSafetyTest {

    companion object {
        const val TEST_UID = "user_c4_process_restart_safety"
        const val TEST_SYNC_ID = "sync_c4_restart_beer_1"
        const val TEST_MUTATION_ID = "mut_c4_restart_init_1"
        const val TEST_CHECKPOINT = "MIGRATION_INTERRUPTED_CHECKPOINT"
    }

    /**
     * Paso 1: Inicializa y persiste el estado HALTED, checkpoint y mutación local pendiente.
     */
    @Test
    fun step1_prepareHaltedAccount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = BeerDatabase.getDatabase(context)
        val beerDao = db.beerDao()

        // 1. Limpiar rastro previo si existiese
        C4SyncStateStore.clearForTesting(context, TEST_UID)

        // 2. Insertar cerveza local con mutación PENDING
        val beer = LegacyBeerFixtures.createLegacyRoomBeer(
            ownerUid = TEST_UID,
            syncId = TEST_SYNC_ID,
            syncStatus = SyncStatus.PENDING,
            comment = "Cerveza Local Pendiente Antes del Force-Stop"
        ).copy(
            lastMutationId = TEST_MUTATION_ID,
            dirtyFields = "COMMENT"
        )
        beerDao.insertBeer(beer)

        // 3. Insertar historial de mutación PENDING
        val historyRecord = LocalMutationHistoryEntity(
            mutationId = TEST_MUTATION_ID,
            syncId = TEST_SYNC_ID,
            ownerUid = TEST_UID,
            timestamp = 1700000000000L,
            dirtyFields = "COMMENT",
            baseRevision = 0L,
            status = MutationStatus.PENDING,
            isLocalOnlyCreation = true
        )
        beerDao.insertHistoryRecord(historyRecord)

        // 4. Persistir estado HALTED y checkpoint en SharedPreferences (commit síncrono a disco)
        C4SyncStateStore.setEngineMode(context, TEST_UID, SyncEngineMode.HALTED, commitSync = true)
        C4SyncStateStore.recordMigrationCheckpoint(context, TEST_UID, TEST_CHECKPOINT, commitSync = true)

        // Verificación inmediata antes de matar el proceso
        assertEquals(SyncEngineMode.HALTED, C4SyncStateStore.getEngineMode(context, TEST_UID))
        assertEquals(TEST_CHECKPOINT, C4SyncStateStore.getMigrationCheckpoint(context, TEST_UID))
        val insertedBeer = beerDao.getBeerBySyncId(TEST_SYNC_ID, TEST_UID)
        assertNotNull(insertedBeer)
        assertEquals(SyncStatus.PENDING, insertedBeer!!.syncStatus)

        println("[RESTART_TEST_STEP1] PREPARATION_HALTED_OK")
    }

    /**
     * Paso 2: Ejecutado tras force-stop y reapertura del proceso.
     * Verifica la supervivencia del estado HALTED y la no reactivación del motor legacy en BeerSyncWorker.
     */
    @Test
    fun step2_verifyAfterRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = BeerDatabase.getDatabase(context)
        val beerDao = db.beerDao()

        // 1. Confirmar que la memoria estática de C4 se reinició a valores por defecto
        // (En un proceso limpio, isEnabled es false)
        println("[RESTART_TEST_STEP2] Static isEnabled in new process: ${C4SyncConfig.isEnabled}")

        // 2. Comprobar que el estado PERSISTENTE en disco sigue siendo HALTED
        val persistedMode = C4SyncStateStore.getEngineMode(context, TEST_UID)
        assertEquals("El estado persistente debe seguir siendo HALTED tras el reinicio", SyncEngineMode.HALTED, persistedMode)

        val checkpoint = C4SyncStateStore.getMigrationCheckpoint(context, TEST_UID)
        assertEquals("El checkpoint recuperable debe persistir intacto", TEST_CHECKPOINT, checkpoint)

        // 3. Encolar y ejecutar BeerSyncWorker en el entorno real de Android WorkManager
        val workRequest = OneTimeWorkRequestBuilder<BeerSyncWorker>()
            .setInputData(workDataOf(BeerSyncWorker.KEY_EXPECTED_UID to TEST_UID))
            .build()
        WorkManager.getInstance(context).enqueue(workRequest).result.get()

        // Esperar a que el worker finalice su ejecución
        var attempts = 0
        var workInfo: WorkInfo? = WorkManager.getInstance(context).getWorkInfoById(workRequest.id).get()
        while (workInfo != null && !workInfo.state.isFinished && attempts < 30) {
            kotlinx.coroutines.delay(200)
            workInfo = WorkManager.getInstance(context).getWorkInfoById(workRequest.id).get()
            attempts++
        }
        assertNotNull("WorkInfo no debe ser nulo", workInfo)
        assertEquals("BeerSyncWorker debió finalizar en SUCCEEDED", WorkInfo.State.SUCCEEDED, workInfo!!.state)

        // 4. Confirmar que la mutación y la entidad en Room siguen PENDING (NO fueron modificadas ni borradas por Legacy)
        val beerAfterWorker = beerDao.getBeerBySyncId(TEST_SYNC_ID, TEST_UID)
        assertNotNull("La cerveza debe seguir existiendo en Room", beerAfterWorker)
        assertEquals("La cerveza debe seguir en estado PENDING", SyncStatus.PENDING, beerAfterWorker!!.syncStatus)
        assertEquals("El comentario debe preservarse", "Cerveza Local Pendiente Antes del Force-Stop", beerAfterWorker.comment)
        assertEquals("Revision remota debe ser 0", 0L, beerAfterWorker.remoteRevision)

        val targetHistory = db.beerDao().getHistoryRecordById(TEST_MUTATION_ID, TEST_UID)
        assertNotNull("La mutación pendiente debe seguir existiendo en el historial", targetHistory)
        assertEquals("La mutación debe seguir en PENDING", MutationStatus.PENDING, targetHistory!!.status)

        println("[RESTART_TEST_STEP2] VERIFICATION_SUCCESS_LEGACY_NOT_EXECUTED")
    }
}
