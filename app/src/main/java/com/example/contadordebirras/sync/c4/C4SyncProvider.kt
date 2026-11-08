package com.example.contadordebirras.sync.c4

import android.content.Context

/**
 * Resultado tipado de sincronización ejecutada por un [C4SyncProvider] (Fase C4 Release Readiness).
 */
sealed class C4ProviderSyncResult {
    /** Sincronización C4 completada con éxito */
    object Success : C4ProviderSyncResult()

    /**
     * Proveedor inactivo o no configurado para el entorno.
     * Señal explícita de bloqueo que impide comunicar éxito ficticio y evita reintentos infinitos.
     */
    data class InactiveOrBlocked(val reason: String) : C4ProviderSyncResult()

    /**
     * Fallo transitorio de sincronización (por ejemplo conectividad temporal)
     * que amerita reintento controlado por WorkManager.
     */
    data class TransientError(val reason: String) : C4ProviderSyncResult()
}

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
    suspend fun syncUser(context: Context, ownerUid: String): C4ProviderSyncResult
}

/**
 * Proveedor seguro por defecto para producción: permanece completamente INACTIVO
 * y nunca se conecta a Firebase de producción.
 *
 * REGLA DE SEGURIDAD (C4 Release Readiness):
 * Nunca retorna éxito ficticio. Retorna [C4ProviderSyncResult.InactiveOrBlocked] para que
 * [BeerSyncWorker] registre un error explícito (Result.failure()) sin reintentos infinitos
 * y sin ejecutar el motor legacy.
 */
class InactiveProductionC4SyncProvider : C4SyncProvider {
    override suspend fun syncUser(context: Context, ownerUid: String): C4ProviderSyncResult {
        return C4ProviderSyncResult.InactiveOrBlocked(
            "Protocolo C4 no conectado en producción: proveedor inactivo por defecto."
        )
    }
}

/**
 * Proveedor configurable para entornos de prueba / Staging con Firebase Emulator.
 */
class StagingC4SyncProvider(
    private val syncManagerProvider: suspend (Context, String) -> C4AppSyncManager
) : C4SyncProvider {
    override suspend fun syncUser(context: Context, ownerUid: String): C4ProviderSyncResult {
        return try {
            val manager = syncManagerProvider(context, ownerUid)
            try {
                // Comprobación de que el UID de Firestore/Auth coincide con el UID del worker
                val currentAuthUid = manager.auth?.currentUser?.uid
                if (currentAuthUid.isNullOrBlank() || currentAuthUid != ownerUid) {
                    return C4ProviderSyncResult.InactiveOrBlocked(
                        "El UID de Firestore/Auth ($currentAuthUid) no coincide con el UID del worker ($ownerUid)"
                    )
                }

                // 1. PUSH de mutaciones pendientes locales
                val pushResults = manager.pushAllPending()
                val anyPushFailed = pushResults.values.any {
                    it !is C4CoordinatorPushResult.Success && it !is C4CoordinatorPushResult.IdempotentRecovery
                }
                // 2. PULL de descubrimiento por colección
                val pullResults = manager.pullRemoteCollection()
                val anyPullBlocked = pullResults.values.any { it is C4CoordinatorPullResult.Blocked }

                if (!anyPushFailed && !anyPullBlocked) {
                    C4ProviderSyncResult.Success
                } else {
                    C4ProviderSyncResult.TransientError(
                        "Sincronización incompleta en staging: pushFailed=$anyPushFailed, pullBlocked=$anyPullBlocked"
                    )
                }
            } finally {
                manager.close()
            }
        } catch (e: IllegalStateException) {
            C4ProviderSyncResult.InactiveOrBlocked(
                "Bloqueo en StagingC4SyncProvider: ${e.message}"
            )
        } catch (e: SecurityException) {
            C4ProviderSyncResult.InactiveOrBlocked(
                "Bloqueo de seguridad en StagingC4SyncProvider: ${e.message}"
            )
        } catch (e: Exception) {
            C4ProviderSyncResult.TransientError(
                "Excepción en StagingC4SyncProvider: ${e.message}"
            )
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
