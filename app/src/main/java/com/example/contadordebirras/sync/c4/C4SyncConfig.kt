package com.example.contadordebirras.sync.c4

import android.content.Context
import kotlinx.coroutines.sync.Mutex

/**
 * Modos de ejecución del motor de sincronización (Fase C4.5).
 */
enum class SyncEngineMode {
    /** Modo legacy exclusivo: BeerSyncEngine activo, C4 inactivo */
    LEGACY,
    /** Proceso de migración/cutover en ejecución: ambos motores pausados temporalmente */
    CUTOVER_IN_PROGRESS,
    /** Modo C4 activo: protocolo C4 activo, BeerSyncEngine inactivo */
    C4_ACTIVE,
    /** Modo detenido/seguro: ambos motores inactivos ante anomalía, irreversibilidad o estado desconocido */
    HALTED
}

/**
 * Configuración y Feature Flag para el Protocolo C4 en la aplicación (Fases C4.4B / C4.5).
 *
 * REGLAS DE SEGURIDAD Y AISLAMIENTO:
 * - Desactivado por defecto ([isEnabled] = false, [engineMode] = [SyncEngineMode.LEGACY]).
 * - En compilaciones o ejecuciones normales, la sincronización se realiza exclusivamente
 *   a través del motor legacy (BeerSyncEngine).
 * - Nunca se ejecutan ambos motores de sincronización simultáneamente sobre los mismos datos.
 * - Concurrencia protegida mediante [syncExecutionMutex].
 * - Cuando C4 está activo (en pruebas DEBUG), opera exclusivamente sobre una base de datos
 *   Room descartable y contra el Firebase Emulator ficticio (demo-contador-c44).
 */
object C4SyncConfig {

    /**
     * Indicador global de activación de la integración C4 en la aplicación.
     * Desactivado por defecto.
     */
    @Volatile
    var isEnabled: Boolean = false

    /**
     * Feature Flag para activación de C4 en producción/release.
     * Desactivado por defecto hasta autorización expresa.
     */
    @Volatile
    var isProductionC4FeatureEnabled: Boolean = false

    /**
     * Conjunto de UIDs autorizados expresamente para la activación controlada de C4 en producción/release.
     * Vacío por defecto. Ningún usuario no incluido en este conjunto puede activar C4.
     */
    @Volatile
    var authorizedPilotUids: Set<String> = emptySet()

    /**
     * UID del usuario actualmente activo en la sesión o proceso de sincronización.
     * Permite aislar el estado en memoria para que una cuenta C4 no altere el estado de otra en Legacy.
     */
    @Volatile
    var currentActiveOwnerUid: String? = null

    /**
     * Restaura la configuración persistente desde SharedPreferences (feature flag y UIDs autorizados).
     * Mantiene valores seguros por defecto (false y lista vacía).
     */
    fun restoreConfiguration(context: Context) {
        this.isProductionC4FeatureEnabled = C4SyncStateStore.isProductionFeatureEnabled(context)
        this.authorizedPilotUids = C4SyncStateStore.getAuthorizedPilotUids(context)
    }

    /**
     * Configuración reproducible de activación controlada en el arranque.
     * Permite habilitar o deshabilitar C4 para un UID específico autorizado de forma persistente.
     */
    fun configureControlledActivation(
        context: Context,
        featureEnabled: Boolean,
        authorizedUid: String? = null
    ) {
        this.isProductionC4FeatureEnabled = featureEnabled
        C4SyncStateStore.setProductionFeatureEnabled(context, featureEnabled)

        if (!authorizedUid.isNullOrBlank() && authorizedUid != "guest_local") {
            authorizePilotUid(context, authorizedUid)
        }
    }

    /**
     * Verifica si un usuario está expresamente autorizado para operar con C4 en entornos normales/producción.
     */
    fun isUserAuthorizedForC4(ownerUid: String): Boolean {
        if (ownerUid.isBlank() || ownerUid == "guest_local") return false
        return isProductionC4FeatureEnabled && authorizedPilotUids.contains(ownerUid)
    }

    /**
     * Autoriza un UID para la activación controlada de C4 (en memoria y opcionalmente persistente).
     */
    fun authorizePilotUid(ownerUid: String) {
        if (ownerUid.isNotBlank() && ownerUid != "guest_local") {
            authorizedPilotUids = authorizedPilotUids + ownerUid
        }
    }

    /**
     * Autoriza un UID para la activación controlada de C4 persistiendo en SharedPreferences.
     */
    fun authorizePilotUid(context: Context, ownerUid: String) {
        authorizePilotUid(ownerUid)
        C4SyncStateStore.addAuthorizedPilotUid(context, ownerUid)
    }

    /**
     * Revoca la autorización de un UID para C4 (en memoria y opcionalmente persistente).
     */
    fun revokePilotUid(ownerUid: String) {
        authorizedPilotUids = authorizedPilotUids - ownerUid
    }

    /**
     * Revoca la autorización de un UID para C4 persistiendo en SharedPreferences.
     */
    fun revokePilotUid(context: Context, ownerUid: String) {
        revokePilotUid(ownerUid)
        C4SyncStateStore.removeAuthorizedPilotUid(context, ownerUid)
    }

    /**
     * Limpia todos los UIDs autorizados.
     */
    fun clearAuthorizedPilotUids(context: Context? = null) {
        authorizedPilotUids = emptySet()
        context?.let { ctx ->
            C4SyncStateStore.setAuthorizedPilotUids(ctx, emptySet())
        }
    }

    /**
     * Modo de motor de sincronización. Por defecto LEGACY.
     */
    @Volatile
    var engineMode: SyncEngineMode = SyncEngineMode.LEGACY

    /**
     * Mutex global de exclusión mutua para impedir escrituras simultáneas entre
     * el motor legacy y el protocolo C4.
     */
    val syncExecutionMutex = Mutex()

    /**
     * Mutex para impedir que comprobaciones concurrentes del protocolo produzcan
     * cambios de estado incompatibles o condiciones de carrera.
     */
    val protocolDetectionMutex = Mutex()

    /**
     * Conjunto de UIDs cuyo protocolo ha sido verificado con el servidor en la sesión actual en memoria.
     * Evita confiar indefinidamente en verificaciones legacy antiguas tras reiniciar la aplicación.
     */
    private val sessionVerifiedUids = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun isSessionProtocolVerified(ownerUid: String): Boolean {
        if (ownerUid.isBlank() || ownerUid == "guest_local") return false
        return sessionVerifiedUids.contains(ownerUid)
    }

    fun markSessionProtocolVerified(ownerUid: String) {
        if (ownerUid.isNotBlank() && ownerUid != "guest_local") {
            sessionVerifiedUids.add(ownerUid)
        }
    }

    fun clearSessionProtocolVerification(ownerUid: String? = null) {
        if (ownerUid != null) {
            sessionVerifiedUids.remove(ownerUid)
        } else {
            sessionVerifiedUids.clear()
        }
    }

    /**
     * Consulta segura de activación general.
     */
    fun isC4Active(): Boolean = isEnabled && engineMode == SyncEngineMode.C4_ACTIVE

    /**
     * Consulta segura de activación aislada para un usuario específico.
     * Garantiza que una cuenta en Legacy jamás se vea afectada por el estado de una cuenta C4.
     */
    fun isC4ActiveForUser(context: Context, ownerUid: String): Boolean {
        if (ownerUid.isBlank() || ownerUid == "guest_local") return false
        val persistedMode = C4SyncStateStore.getEngineMode(context, ownerUid)
        val isPilot = context.packageName?.endsWith(".c4pilot") == true
        val isAuthorized = isPilot || isUserAuthorizedForC4(ownerUid)
        return (persistedMode == SyncEngineMode.C4_ACTIVE) && isAuthorized
    }

    /**
     * Consulta el modo persistente y sincroniza el estado en memoria para un usuario.
     */
    fun restoreUserEngineMode(context: Context, ownerUid: String): SyncEngineMode {
        // Asegurar que la configuración persistente esté cargada si en memoria aún no se ha inicializado
        if (!isProductionC4FeatureEnabled && C4SyncStateStore.isProductionFeatureEnabled(context)) {
            restoreConfiguration(context)
        }
        val persistedMode = C4SyncStateStore.getEngineMode(context, ownerUid)
        this.engineMode = persistedMode
        this.currentActiveOwnerUid = ownerUid
        val isPilot = context.packageName?.endsWith(".c4pilot") == true
        val isAuthorized = isPilot || isUserAuthorizedForC4(ownerUid)
        this.isEnabled = (persistedMode == SyncEngineMode.C4_ACTIVE) && isAuthorized
        return persistedMode
    }

    /**
     * Actualiza el modo del motor persistente y en memoria para un usuario.
     */
    fun setUserEngineMode(context: Context, ownerUid: String, mode: SyncEngineMode) {
        C4SyncStateStore.setEngineMode(context, ownerUid, mode)
        this.engineMode = mode
        this.currentActiveOwnerUid = ownerUid
        val isPilot = context.packageName?.endsWith(".c4pilot") == true
        val isAuthorized = isPilot || isUserAuthorizedForC4(ownerUid)
        this.isEnabled = (mode == SyncEngineMode.C4_ACTIVE) && isAuthorized
    }

    /**
     * Restablece la configuración al estado seguro por defecto (desactivado y LEGACY).
     */
    fun reset() {
        isEnabled = false
        isProductionC4FeatureEnabled = false
        authorizedPilotUids = emptySet()
        currentActiveOwnerUid = null
        clearSessionProtocolVerification()
        engineMode = SyncEngineMode.LEGACY
    }
}
