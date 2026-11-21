package com.example.contadordebirras.sync.c4

import android.content.Context
import android.net.Uri
import com.example.contadordebirras.data.BeerDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resultado formal de la activación controlada de una cuenta hacia el Protocolo C4 (Fase C4 Controlled Activation).
 */
sealed interface ActivationResult {
    data class Success(
        val ownerUid: String,
        val cutoverResult: CutoverResult.Completed,
        val remoteLock: AccountProtocolLock,
        val message: String
    ) : ActivationResult

    data class AlreadyActive(
        val ownerUid: String,
        val message: String
    ) : ActivationResult

    data class Unauthorized(
        val ownerUid: String,
        val reason: String
    ) : ActivationResult

    data class Blocked(
        val ownerUid: String,
        val reason: String,
        val details: List<String> = emptyList()
    ) : ActivationResult

    data class Failed(
        val ownerUid: String,
        val reason: String,
        val cause: Throwable? = null
    ) : ActivationResult
}

/**
 * Resultado del diagnóstico previo a la activación controlada.
 */
sealed interface ActivationDiagnosisResult {
    data class ReadyForCutover(
        val ownerUid: String,
        val localBeersCount: Int,
        val remoteLegacyCount: Int,
        val remoteC4Count: Int,
        val message: String
    ) : ActivationDiagnosisResult

    data class AlreadyActive(
        val ownerUid: String,
        val message: String
    ) : ActivationDiagnosisResult

    data class Blocked(
        val ownerUid: String,
        val reason: String
    ) : ActivationDiagnosisResult
}

/**
 * Gestor de Activación Controlada de C4 para cuentas reales/piloto (Fase C4 Controlled Activation).
 *
 * GARANTÍAS FORMALES DE SEGURIDAD:
 * 1. Activación limitada exclusivamente por UID autorizado: Requiere [C4SyncConfig.isProductionC4FeatureEnabled] == true
 *    y que [ownerUid] esté en [C4SyncConfig.authorizedPilotUids]. Desactivado por defecto.
 * 2. Integración en el flujo normal: Reutiliza la base Room persistente real ("beer_database") y la sesión
 *    de usuario autenticado en FirebaseAuth.
 * 3. Verificación remota previa: Inspecciona el estado de /users/{ownerUid} en Firestore antes de iniciar la migración.
 * 4. Cutover idempotente y seguro: Reconcilia entidades locales y remotas mediante [C4StagingCutoverManager].
 * 5. Confirmación estricta de postcondiciones: Cero mutaciones pendientes en Room y bloqueo remoto confirmado
 *    con syncProtocolVersion="C4" y accountSyncState="MIGRATED_TO_C4" antes de conceder [SyncEngineMode.C4_ACTIVE].
 * 6. Irreversibilidad: Una vez migrado, el usuario nunca vuelve a Legacy (se bloquean escrituras legacy).
 * 7. No alteración de cuentas ajenas: Cuentas no autorizadas continúan utilizando Legacy sin ningún cambio.
 * 8. Diagnóstico y recuperación seguro: Accesible exclusivamente en compilaciones DEBUG para pruebas internas.
 */
class C4ControlledActivationManager(
    private val context: Context,
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    private val storageProvider: () -> FirebaseStorage = { FirebaseStorage.getInstance() },
    private val authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    private val databaseProvider: (Context) -> BeerDatabase = { ctx -> BeerDatabase.getDatabase(ctx) },
    private val allowProduction: Boolean? = null
) {

    /**
     * Verifica si la cuenta está autorizada para C4 y, en caso afirmativo, ejecuta la transición si aún está en LEGACY.
     * Si no está autorizada, no realiza ninguna acción y retorna [ActivationResult.Unauthorized].
     */
    suspend fun checkAndActivateIfAuthorized(ownerUid: String): ActivationResult {
        if (!C4SyncConfig.isProductionC4FeatureEnabled || !C4SyncConfig.isUserAuthorizedForC4(ownerUid)) {
            return ActivationResult.Unauthorized(
                ownerUid,
                "Activación C4 omitida: Feature flag inactivo o usuario no autorizado."
            )
        }
        return activateAccount(ownerUid)
    }

    /**
     * Ejecuta el diagnóstico de la cuenta antes de la activación.
     * Estrictamente de solo lectura (cero escrituras en Room y Firestore).
     */
    suspend fun diagnoseAccount(ownerUid: String): ActivationDiagnosisResult = withContext(Dispatchers.IO) {
        if (ownerUid.isBlank() || ownerUid == "guest_local") {
            return@withContext ActivationDiagnosisResult.Blocked(ownerUid, "UID de usuario inválido o ausente ($ownerUid)")
        }

        val persistedMode = try {
            C4SyncStateStore.getEngineMode(context, ownerUid)
        } catch (_: Exception) {
            SyncEngineMode.LEGACY
        }

        if (persistedMode == SyncEngineMode.C4_ACTIVE) {
            return@withContext ActivationDiagnosisResult.AlreadyActive(
                ownerUid,
                "La cuenta ya está en modo C4_ACTIVE en el almacenamiento persistente local."
            )
        }
        if (persistedMode == SyncEngineMode.HALTED) {
            return@withContext ActivationDiagnosisResult.Blocked(
                ownerUid,
                "La cuenta está en estado HALTED. Requiere diagnóstico y recuperación de integridad."
            )
        }

        val database = databaseProvider(context)
        val activeBeers = database.beerDao().getActiveBeersSync(ownerUid)

        val firestore = try {
            firestoreProvider()
        } catch (e: Exception) {
            return@withContext ActivationDiagnosisResult.Blocked(ownerUid, "Error accediendo a Firestore: ${e.message}")
        }
        val projectId = firestore.app.options.projectId.orEmpty()
        val effectiveAllowProduction = allowProduction ?: (
            C4SyncConfig.isProductionC4FeatureEnabled && C4SyncConfig.isUserAuthorizedForC4(ownerUid)
        )
        val firestoreAdapter = FirestoreC4SyncAdapter(
            firestore = firestore,
            expectedProjectId = projectId,
            allowProduction = effectiveAllowProduction
        )

        val remoteDocs = try {
            firestoreAdapter.queryServerDocuments(ownerUid)
        } catch (e: Exception) {
            return@withContext ActivationDiagnosisResult.Blocked(
                ownerUid,
                "Error consultando documentos remotos en Firestore: ${e.message}"
            )
        }

        val remoteLegacyCount = remoteDocs.count { it.revision == 0L }
        val remoteC4Count = remoteDocs.count { it.revision >= 1L }

        ActivationDiagnosisResult.ReadyForCutover(
            ownerUid = ownerUid,
            localBeersCount = activeBeers.size,
            remoteLegacyCount = remoteLegacyCount,
            remoteC4Count = remoteC4Count,
            message = "Cuenta lista para migración C4: ${activeBeers.size} cervezas locales, $remoteLegacyCount remotas legacy, $remoteC4Count remotas C4."
        )
    }

    /**
     * Ejecuta el procedimiento completo de activación controlada hacia C4.
     */
    suspend fun activateAccount(ownerUid: String): ActivationResult = withContext(Dispatchers.IO) {
        // 1. Verificación de UID
        if (ownerUid.isBlank() || ownerUid == "guest_local" || ownerUid == "legacy_unassigned") {
            return@withContext ActivationResult.Unauthorized(
                ownerUid,
                "PROHIBIDO: la activación controlada requiere un UID de usuario real y válido ($ownerUid)."
            )
        }

        // 2. Verificación de Feature Flag y Autorización de UID
        if (!C4SyncConfig.isProductionC4FeatureEnabled) {
            return@withContext ActivationResult.Unauthorized(
                ownerUid,
                "Activación rechazada: el feature flag de producción de C4 (isProductionC4FeatureEnabled) está desactivado."
            )
        }
        if (!C4SyncConfig.isUserAuthorizedForC4(ownerUid)) {
            return@withContext ActivationResult.Unauthorized(
                ownerUid,
                "Activación rechazada: el UID $ownerUid no está incluido en la lista de UIDs autorizados (authorizedPilotUids)."
            )
        }

        // 3. Verificación de sesión auténtica en FirebaseAuth
        val auth = try {
            authProvider()
        } catch (e: Exception) {
            return@withContext ActivationResult.Failed(
                ownerUid,
                "Error al acceder a FirebaseAuth: ${e.message}",
                e
            )
        }
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid != ownerUid) {
            return@withContext ActivationResult.Unauthorized(
                ownerUid,
                "Activación rechazada: la sesión actual (${currentUser?.uid}) no coincide con el UID solicitado ($ownerUid)."
            )
        }

        // 4. Verificación de estado persistente local
        val persistedMode = try {
            C4SyncStateStore.getEngineMode(context, ownerUid)
        } catch (e: Exception) {
            return@withContext ActivationResult.Failed(
                ownerUid,
                "Error al leer el estado persistente del motor C4: ${e.message}",
                e
            )
        }

        if (persistedMode == SyncEngineMode.HALTED) {
            return@withContext ActivationResult.Blocked(
                ownerUid,
                "Operación bloqueada: la cuenta está en estado HALTED. Requiere diagnóstico y recuperación de integridad."
            )
        }

        // 5. Inicializar adaptadores de Firestore y Storage
        val firestore = try {
            firestoreProvider()
        } catch (e: Exception) {
            return@withContext ActivationResult.Failed(
                ownerUid,
                "Error al inicializar Firestore: ${e.message}",
                e
            )
        }
        val projectId = firestore.app.options.projectId.orEmpty()
        val effectiveAllowProduction = allowProduction ?: true
        val firestoreAdapter = FirestoreC4SyncAdapter(
            firestore = firestore,
            expectedProjectId = projectId,
            allowProduction = effectiveAllowProduction
        )

        // 6. Verificar estado remoto del protocolo en Firestore ANTES del Cutover
        val existingRemoteLock = try {
            firestoreAdapter.readAccountProtocolLock(ownerUid)
        } catch (e: Exception) {
            return@withContext ActivationResult.Failed(
                ownerUid,
                "Error al consultar el bloqueo de protocolo remoto en Firestore: ${e.message}",
                e
            )
        }

        if (existingRemoteLock != null &&
            existingRemoteLock.syncProtocolVersion.isNotBlank() &&
            existingRemoteLock.syncProtocolVersion != "C4"
        ) {
            return@withContext ActivationResult.Blocked(
                ownerUid,
                "Bloqueo remoto incompatible detectado en Firestore: version=${existingRemoteLock.syncProtocolVersion}, state=${existingRemoteLock.accountSyncState}"
            )
        }

        // Si ya está activo localmente y tiene bloqueo remoto C4 confirmado
        if (persistedMode == SyncEngineMode.C4_ACTIVE &&
            existingRemoteLock?.syncProtocolVersion == "C4" &&
            existingRemoteLock.accountSyncState == "MIGRATED_TO_C4"
        ) {
            C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.C4_ACTIVE)
            return@withContext ActivationResult.AlreadyActive(
                ownerUid,
                "La cuenta $ownerUid ya se encuentra migrada y activa en Protocolo C4 con bloqueo remoto confirmado."
            )
        }

        // 7. Preparar componentes reales contra la base Room persistente
        val database = databaseProvider(context)
        val persistence = RoomC4SyncPersistence(database)

        val storage = try {
            storageProvider()
        } catch (e: Exception) {
            return@withContext ActivationResult.Failed(
                ownerUid,
                "Error al inicializar FirebaseStorage: ${e.message}",
                e
            )
        }
        val bucket = try { storage.reference.bucket } catch (_: Exception) { "" }
        val storageAdapter = StorageC4SyncAdapter(
            storage = storage,
            expectedBucket = bucket,
            allowProduction = effectiveAllowProduction
        )

        val coordinator = AndroidC4SyncCoordinator(
            firestoreAdapter = firestoreAdapter,
            storageAdapter = storageAdapter,
            persistence = persistence
        )

        val cutoverManager = C4StagingCutoverManager(
            coordinator = coordinator,
            persistence = persistence,
            firestoreAdapter = firestoreAdapter,
            context = context,
            photoResolver = { photoUri ->
                resolvePhotoBytes(context, photoUri)
            }
        )

        // 8. Ejecutar el Cutover completo bajo exclusión mutua
        val cutoverResult = try {
            cutoverManager.executeCutover(ownerUid)
        } catch (e: Exception) {
            return@withContext ActivationResult.Failed(
                ownerUid,
                "Excepción durante el proceso de Cutover: ${e.message}",
                e
            )
        }

        when (cutoverResult) {
            is CutoverResult.Completed -> {
                // 9. Confirmación estricta de postcondiciones y del bloqueo remoto
                val verifiedLock = try {
                    firestoreAdapter.readAccountProtocolLock(ownerUid)
                } catch (_: Exception) {
                    null
                }

                if (verifiedLock == null || verifiedLock.syncProtocolVersion != "C4" || verifiedLock.accountSyncState != "MIGRATED_TO_C4") {
                    // Ante fallo de verificación de bloqueo, forzar HALTED para proteger los datos
                    C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_LOCK_VERIFICATION_FAILED")
                    C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                    return@withContext ActivationResult.Blocked(
                        ownerUid,
                        "Fallo crítico: El Cutover finalizó pero no se pudo verificar el bloqueo remoto 'C4'/'MIGRATED_TO_C4' en /users/$ownerUid. La cuenta se detiene en HALTED por seguridad."
                    )
                }

                val pendingMutations = persistence.historyDao.getPendingMutations(ownerUid)
                if (pendingMutations.isNotEmpty()) {
                    C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_PENDING_MUTATIONS_REMAIN")
                    C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                    return@withContext ActivationResult.Blocked(
                        ownerUid,
                        "Fallo de postcondición: Restan ${pendingMutations.size} mutaciones pendientes en Room tras el Cutover. Se establece HALTED."
                    )
                }

                // 10. Activar C4 definitivamente: persistir modo y sincronizar en memoria
                C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.C4_ACTIVE)

                ActivationResult.Success(
                    ownerUid = ownerUid,
                    cutoverResult = cutoverResult,
                    remoteLock = verifiedLock,
                    message = "Activación C4 completada exitosamente para la cuenta $ownerUid. Protocolo C4 activo y bloqueado remotamente."
                )
            }

            is CutoverResult.Incomplete -> {
                ActivationResult.Blocked(
                    ownerUid = ownerUid,
                    reason = "Cutover incompleto: existen ${cutoverResult.pendingMutationsCount} mutaciones pendientes o fallos de subida.",
                    details = cutoverResult.details
                )
            }

            is CutoverResult.Blocked -> {
                ActivationResult.Blocked(
                    ownerUid = ownerUid,
                    reason = "Cutover bloqueado: ${cutoverResult.reason}",
                    details = cutoverResult.details
                )
            }
        }
    }

    /**
     * Procedimiento de recuperación segura para cuentas en estado HALTED.
     * Solo accesible en compilaciones internas de pruebas (BuildConfig.DEBUG).
     */
    suspend fun recoverHaltedAccount(ownerUid: String): C4HaltedRecoveryResult = withContext(Dispatchers.IO) {
        if (!com.example.contadordebirras.BuildConfig.DEBUG) {
            throw SecurityException("PROHIBIDO: La recuperación manual solo está permitida en compilaciones internas DEBUG.")
        }
        val database = databaseProvider(context)
        val firestore = firestoreProvider()
        val projectId = firestore.app.options.projectId.orEmpty()
        val effectiveAllowProduction = allowProduction ?: false
        val firestoreAdapter = FirestoreC4SyncAdapter(
            firestore = firestore,
            expectedProjectId = projectId,
            allowProduction = effectiveAllowProduction
        )
        val auth = authProvider()
        val recoveryManager = C4HaltedRecoveryManager(
            context = context,
            database = database,
            firestoreAdapter = firestoreAdapter,
            auth = auth
        )
        val diagnosis = recoveryManager.diagnoseHaltedAccount(ownerUid)
        when (diagnosis) {
            is C4HaltedDiagnosisResult.Eligible -> {
                recoveryManager.recoverHaltedAccount(ownerUid)
            }
            is C4HaltedDiagnosisResult.Blocked -> {
                C4HaltedRecoveryResult.Blocked(diagnosis.reason, diagnosis.checks)
            }
        }
    }

    companion object {
        fun resolvePhotoBytes(context: Context, photoUri: String?): ByteArray? {
            if (photoUri.isNullOrBlank()) return null
            return try {
                val file = File(photoUri)
                if (file.exists() && file.isFile) {
                    return file.readBytes()
                }
                val internalFile = File(context.filesDir, photoUri)
                if (internalFile.exists() && internalFile.isFile) {
                    return internalFile.readBytes()
                }
                val uri = Uri.parse(photoUri)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
