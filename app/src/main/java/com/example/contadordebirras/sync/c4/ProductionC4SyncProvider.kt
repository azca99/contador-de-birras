package com.example.contadordebirras.sync.c4

import android.content.Context
import com.example.contadordebirras.data.BeerDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

/**
 * Proveedor de sincronización C4 para entornos Release / Producción (Fase C4 Release Candidate).
 *
 * Utiliza los componentes reales de la aplicación (FirebaseAuth del usuario autenticado,
 * FirebaseFirestore, FirebaseStorage y la base de datos persistente Room "beer_database")
 * sin ninguna credencial ficticia ni dependencia de paneles de depuración.
 *
 * REGLAS DE SEGURIDAD (C4 Release Candidate):
 * 1. Desactivado por defecto: C4 no sincroniza en producción a menos que esté
 *    explícitamente autorizado en [C4SyncConfig.isProductionC4FeatureEnabled] y el usuario
 *    esté en modo [SyncEngineMode.C4_ACTIVE].
 * 2. Comprobación estricta de sesión: El usuario autenticado debe coincidir exactamente
 *    con el [ownerUid] solicitado.
 * 3. Base persistente real: No utiliza bases descartables. Opera directamente sobre
 *    [BeerDatabase.getDatabase(context)] ("beer_database").
 * 4. Aislamiento y bloqueo seguro: Si la cuenta está en HALTED o CUTOVER_IN_PROGRESS,
 *    se bloquea la sincronización y nunca se recurre a Legacy.
 */
class ProductionC4SyncProvider(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    private val storageProvider: () -> FirebaseStorage = { FirebaseStorage.getInstance() },
    private val authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    private val databaseProvider: (Context) -> BeerDatabase = { context -> BeerDatabase.getDatabase(context) }
) : C4SyncProvider {

    override suspend fun syncUser(context: Context, ownerUid: String): C4ProviderSyncResult {
        // 1. Verificación de Feature Flag de producción
        if (!C4SyncConfig.isProductionC4FeatureEnabled) {
            return C4ProviderSyncResult.InactiveOrBlocked(
                "Protocolo C4 desactivado por defecto en producción hasta autorización expresa."
            )
        }

        // 2. Verificación de modo persistente del usuario: solo sincronizar si está en C4_ACTIVE
        val persistedMode = try {
            C4SyncStateStore.getEngineMode(context, ownerUid)
        } catch (e: Exception) {
            return C4ProviderSyncResult.InactiveOrBlocked(
                "Fallo al leer el modo persistente de C4 para $ownerUid: ${e.message}"
            )
        }
        if (persistedMode != SyncEngineMode.C4_ACTIVE) {
            return C4ProviderSyncResult.InactiveOrBlocked(
                "Usuario $ownerUid no está en modo C4_ACTIVE (modo=$persistedMode). Sincronización C4 omitida."
            )
        }

        // 3. Verificación de identidad y sesión del usuario real
        val auth = try {
            authProvider()
        } catch (e: Exception) {
            return C4ProviderSyncResult.InactiveOrBlocked(
                "Error al acceder a FirebaseAuth: ${e.message}"
            )
        }
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid != ownerUid) {
            return C4ProviderSyncResult.InactiveOrBlocked(
                "Sesión no válida o UID discordante: currentUser=${currentUser?.uid}, ownerUid=$ownerUid"
            )
        }

        // 4. Inicializar componentes C4 contra la base persistente normal ("beer_database")
        return try {
            val database = databaseProvider(context)
            val persistence = RoomC4SyncPersistence(database)

            val firestore = firestoreProvider()
            val storage = storageProvider()

            val projectId = firestore.app.options.projectId.orEmpty()
            val firestoreAdapter = FirestoreC4SyncAdapter(
                firestore = firestore,
                expectedProjectId = projectId,
                allowProduction = true
            )
            val bucket = try { storage.reference.bucket } catch (_: Exception) { "" }
            val storageAdapter = StorageC4SyncAdapter(
                storage = storage,
                expectedBucket = bucket,
                allowProduction = true
            )
            val coordinator = AndroidC4SyncCoordinator(firestoreAdapter, storageAdapter, persistence)

            val manager = C4AppSyncManager(
                database = database,
                persistence = persistence,
                coordinator = coordinator,
                ownerUid = ownerUid,
                auth = auth,
                isDisposableDatabase = false, // Base de datos persistente real
                context = context
            )

            try {
                // 5. PUSH de mutaciones pendientes locales
                val pushResults = manager.pushAllPending()
                val anyPushFailed = pushResults.values.any {
                    it !is C4CoordinatorPushResult.Success && it !is C4CoordinatorPushResult.IdempotentRecovery
                }

                // 6. PULL de descubrimiento por colección
                val pullResults = manager.pullRemoteCollection()
                val anyPullBlocked = pullResults.values.any { it is C4CoordinatorPullResult.Blocked }

                if (!anyPushFailed && !anyPullBlocked) {
                    C4ProviderSyncResult.Success
                } else {
                    C4ProviderSyncResult.TransientError(
                        "Sincronización C4 incompleta: pushFailed=$anyPushFailed, pullBlocked=$anyPullBlocked"
                    )
                }
            } finally {
                manager.close()
            }
        } catch (e: IllegalStateException) {
            C4ProviderSyncResult.InactiveOrBlocked("Bloqueo de estado en ProductionC4SyncProvider: ${e.message}")
        } catch (e: SecurityException) {
            C4ProviderSyncResult.InactiveOrBlocked("Bloqueo de seguridad en ProductionC4SyncProvider: ${e.message}")
        } catch (e: Exception) {
            C4ProviderSyncResult.TransientError("Error transitorio durante la sincronización C4: ${e.message}")
        }
    }
}
