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
    private const val KEY_PREFIX_PROTOCOL_VERIFIED = "c4_protocol_verified_"
    private const val KEY_PRODUCTION_FEATURE_ENABLED = "c4_production_feature_enabled"
    private const val KEY_AUTHORIZED_PILOT_UIDS = "c4_authorized_pilot_uids"

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
        val modeStr = prefs.getString("$KEY_PREFIX_MODE$ownerUid", null)
        if (modeStr.isNullOrBlank()) return SyncEngineMode.LEGACY
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
     * Consulta si el protocolo de la cuenta ha sido verificado con el servidor en este dispositivo.
     */
    fun isProtocolVerified(context: Context, ownerUid: String): Boolean {
        if (ownerUid.isBlank() || ownerUid == "guest_local") return false
        return getPrefs(context).getBoolean("$KEY_PREFIX_PROTOCOL_VERIFIED$ownerUid", false)
    }

    /**
     * Marca o desmarca si el protocolo de la cuenta ha sido verificado con el servidor.
     */
    fun setProtocolVerified(context: Context, ownerUid: String, verified: Boolean, commitSync: Boolean = true) {
        if (ownerUid.isBlank() || ownerUid == "guest_local") return
        val editor = getPrefs(context).edit().putBoolean("$KEY_PREFIX_PROTOCOL_VERIFIED$ownerUid", verified)
        if (commitSync) editor.commit() else editor.apply()
    }

    /**
     * Determina si existe un estado local fiable para el usuario en este dispositivo.
     * Es fiable si está en C4_ACTIVE, HALTED, CUTOVER_IN_PROGRESS, o si ya fue verificado como LEGACY.
     * Si nunca ha sido verificado y no tiene modo explícito, no es fiable y no debe asumirse Legacy.
     */
    fun hasReliableLocalState(context: Context, ownerUid: String): Boolean {
        if (ownerUid.isBlank() || ownerUid == "guest_local") return false
        val modeStr = getPrefs(context).getString("$KEY_PREFIX_MODE$ownerUid", null)
        if (modeStr == SyncEngineMode.C4_ACTIVE.name ||
            modeStr == SyncEngineMode.HALTED.name ||
            modeStr == SyncEngineMode.CUTOVER_IN_PROGRESS.name
        ) {
            return true
        }
        return isProtocolVerified(context, ownerUid)
    }

    /**
     * Consulta el estado del feature flag de producción persistido.
     * Desactivado (false) por defecto.
     */
    fun isProductionFeatureEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PRODUCTION_FEATURE_ENABLED, false)
    }

    /**
     * Persiste el estado del feature flag de producción.
     */
    fun setProductionFeatureEnabled(context: Context, enabled: Boolean, commitSync: Boolean = true) {
        val editor = getPrefs(context).edit().putBoolean(KEY_PRODUCTION_FEATURE_ENABLED, enabled)
        if (commitSync) editor.commit() else editor.apply()
    }

    /**
     * Obtiene el conjunto de UIDs autorizados persistido.
     * Conjunto vacío por defecto.
     */
    fun getAuthorizedPilotUids(context: Context): Set<String> {
        val set = getPrefs(context).getStringSet(KEY_AUTHORIZED_PILOT_UIDS, null)
        return set?.toSet() ?: emptySet()
    }

    /**
     * Persiste el conjunto de UIDs autorizados.
     */
    fun setAuthorizedPilotUids(context: Context, uids: Set<String>, commitSync: Boolean = true) {
        val editor = getPrefs(context).edit().putStringSet(KEY_AUTHORIZED_PILOT_UIDS, uids.toSet())
        if (commitSync) editor.commit() else editor.apply()
    }

    /**
     * Añade un UID a la lista autorizada persistente.
     */
    fun addAuthorizedPilotUid(context: Context, uid: String, commitSync: Boolean = true) {
        if (uid.isBlank() || uid == "guest_local") return
        val current = getAuthorizedPilotUids(context).toMutableSet()
        current.add(uid)
        setAuthorizedPilotUids(context, current, commitSync)
    }

    /**
     * Elimina un UID de la lista autorizada persistente.
     */
    fun removeAuthorizedPilotUid(context: Context, uid: String, commitSync: Boolean = true) {
        val current = getAuthorizedPilotUids(context).toMutableSet()
        current.remove(uid)
        setAuthorizedPilotUids(context, current, commitSync)
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
                .remove("$KEY_PREFIX_PROTOCOL_VERIFIED$ownerUid")
        } else {
            editor.clear()
        }
        editor.commit()
    }
}
