package com.example.contadordebirras.sync.c4

import android.content.Context

/**
 * Proveedor desacoplado de sincronización para el Protocolo C4 (Fase C4 Release Readiness).
 *
 * Separa limpiamente el entorno de desarrollo/staging (Firebase Emulator) del de producción.
 * Por defecto en producción se mantiene [InactiveProductionC4SyncProvider] para garantizar
 * que Firebase de producción NUNCA es conectado sin activación explícita.
 */
interface C4SyncProvider {
    /**
     * Ejecuta una sincronización completa (PUSH mutaciones pendientes y PULL colección)
     * para el usuario especificado bajo el protocolo C4.
     */
    suspend fun syncUser(context: Context, ownerUid: String): Boolean
}

/**
 * Proveedor seguro por defecto para producción: permanece completamente INACTIVO
 * y nunca se conecta a Firebase de producción.
 */
class InactiveProductionC4SyncProvider : C4SyncProvider {
    override suspend fun syncUser(context: Context, ownerUid: String): Boolean {
        // En producción el protocolo C4 no está activo; retorna true para completar el worker sin reintentos erróneos
        return true
    }
}

/**
 * Proveedor configurable para entornos de prueba / Staging con Firebase Emulator.
 */
class StagingC4SyncProvider(
    private val syncManagerProvider: suspend (Context, String) -> C4AppSyncManager
) : C4SyncProvider {
    override suspend fun syncUser(context: Context, ownerUid: String): Boolean {
        return try {
            val manager = syncManagerProvider(context, ownerUid)
            // 1. PUSH de mutaciones pendientes locales
            val pushResults = manager.pushAllPending()
            val anyPushFailed = pushResults.values.any {
                it !is C4CoordinatorPushResult.Success && it !is C4CoordinatorPushResult.IdempotentRecovery
            }
            // 2. PULL de descubrimiento por colección
            val pullResults = manager.pullRemoteCollection()
            val anyPullBlocked = pullResults.values.any { it is C4CoordinatorPullResult.Blocked }
            !anyPushFailed && !anyPullBlocked
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Registro de inyección de dependencias para el proveedor de sincronización C4.
 */
object C4SyncProviderRegistry {
    private var provider: C4SyncProvider = InactiveProductionC4SyncProvider()

    fun getProvider(): C4SyncProvider = provider

    fun setProvider(newProvider: C4SyncProvider) {
        provider = newProvider
    }

    fun reset() {
        provider = InactiveProductionC4SyncProvider()
    }
}
