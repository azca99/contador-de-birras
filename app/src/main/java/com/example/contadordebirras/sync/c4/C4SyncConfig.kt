package com.example.contadordebirras.sync.c4

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
    C4_ACTIVE
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
     * Consulta segura de activación.
     */
    fun isC4Active(): Boolean = isEnabled && engineMode == SyncEngineMode.C4_ACTIVE

    /**
     * Restablece la configuración al estado seguro por defecto (desactivado y LEGACY).
     */
    fun reset() {
        isEnabled = false
        engineMode = SyncEngineMode.LEGACY
    }
}
