package com.example.contadordebirras.sync.c4

import android.content.Context
import android.content.SharedPreferences

/**
 * Almacén persistente del estado del motor de sincronización por usuario (Fase C4 Release Readiness).
 *
 * GARANTÍAS FORMALES:
 * 1. Persistencia durable: Sobrevive al cierre forzado del proceso de la app y al reinicio del sistema Android.
 * 2. Aislamiento por usuario: Cada ownerUid mantiene su propio estado de motor independiente.
 * 3. Protección contra reactivación automática de Legacy: Si un usuario ha iniciado o completado cutover
 *    (CUTOVER_IN_PROGRESS, C4_ACTIVE, HALTED), nunca vuelve a LEGACY por defecto tras un reinicio.
 * 4. Lectura síncrona segura: Permite a WorkManager ([BeerSyncWorker]) consultar el estado de forma
 *    inmediata sin requerir suspensiones ni bloqueos complejos.
 */
object C4SyncStateStore {

    private const val PREFS_NAME = "c4_sync_state_prefs"
    private const val KEY_PREFIX_MODE = "sync_mode_"
    private const val KEY_PREFIX_CHECKPOINT = "migration_checkpoint_"
    private const val KEY_PREFIX_CHECKPOINT_TS = "migration_checkpoint_ts_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Obtiene el modo persistente del motor de sincronización para el usuario especificado.
     * Si no existe configuración previa, retorna [SyncEngineMode.LEGACY] por defecto.
     */
    fun getEngineMode(context: Context, ownerUid: String): SyncEngineMode {
        if (ownerUid.isBlank()) return SyncEngineMode.LEGACY
        val prefs = getPrefs(context)
        val modeStr = prefs.getString("$KEY_PREFIX_MODE$ownerUid", null) ?: return SyncEngineMode.LEGACY
        return try {
            SyncEngineMode.valueOf(modeStr)
        } catch (_: Exception) {
            SyncEngineMode.HALTED // En caso de valor corrupto, mantener detenido por seguridad
        }
    }

    /**
     * Persiste de forma síncrona o durable el modo del motor de sincronización para el usuario.
     */
    fun setEngineMode(context: Context, ownerUid: String, mode: SyncEngineMode, commitSync: Boolean = true) {
        if (ownerUid.isBlank()) return
        val prefs = getPrefs(context)
        val editor = prefs.edit().putString("$KEY_PREFIX_MODE$ownerUid", mode.name)
        if (commitSync) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    /**
     * Registra un checkpoint recuperable durante el proceso de migración.
     */
    fun recordMigrationCheckpoint(
        context: Context,
        ownerUid: String,
        checkpoint: String,
        commitSync: Boolean = true
    ) {
        if (ownerUid.isBlank()) return
        val prefs = getPrefs(context)
        val editor = prefs.edit()
            .putString("$KEY_PREFIX_CHECKPOINT$ownerUid", checkpoint)
            .putLong("$KEY_PREFIX_CHECKPOINT_TS$ownerUid", System.currentTimeMillis())
        if (commitSync) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    /**
     * Obtiene el último checkpoint de migración registrado para el usuario.
     */
    fun getMigrationCheckpoint(context: Context, ownerUid: String): String? {
        if (ownerUid.isBlank()) return null
        return getPrefs(context).getString("$KEY_PREFIX_CHECKPOINT$ownerUid", null)
    }

    /**
     * Verifica si las escrituras legacy están estrictamente bloqueadas para este usuario.
     * Retorna true si el estado es CUTOVER_IN_PROGRESS, C4_ACTIVE o HALTED.
     */
    fun isLegacyBlocked(context: Context, ownerUid: String): Boolean {
        val mode = getEngineMode(context, ownerUid)
        return mode != SyncEngineMode.LEGACY
    }

    /**
     * Limpia la configuración persistente (utilizado en pruebas o reseteo administrativo).
     */
    fun clearForTesting(context: Context, ownerUid: String? = null) {
        val editor = getPrefs(context).edit()
        if (ownerUid != null) {
            editor.remove("$KEY_PREFIX_MODE$ownerUid")
                .remove("$KEY_PREFIX_CHECKPOINT$ownerUid")
                .remove("$KEY_PREFIX_CHECKPOINT_TS$ownerUid")
        } else {
            editor.clear()
        }
        editor.commit()
    }
}
