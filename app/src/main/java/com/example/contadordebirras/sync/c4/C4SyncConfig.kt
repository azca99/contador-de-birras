package com.example.contadordebirras.sync.c4

/**
 * Configuración y Feature Flag para el Protocolo C4 en la aplicación (Fase C4.4B).
 *
 * REGLAS DE SEGURIDAD Y AISLAMIENTO:
 * - Desactivado por defecto ([isEnabled] = false).
 * - En compilaciones o ejecuciones normales, la sincronización se realiza exclusivamente
 *   a través del motor legacy (BeerSyncEngine).
 * - Nunca se ejecutan ambos motores de sincronización simultáneamente sobre los mismos datos.
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
     * Consulta segura de activación.
     */
    fun isC4Active(): Boolean = isEnabled

    /**
     * Restablece la configuración al estado seguro por defecto (desactivado).
     */
    fun reset() {
        isEnabled = false
    }
}
