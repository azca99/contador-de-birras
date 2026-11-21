package com.example.contadordebirras.sync.c4

import android.content.Context
import android.net.Uri
import com.example.contadordebirras.data.BeerDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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
 * Resultado de la detección y aplicación multidispositivo del protocolo de la cuenta.
 */
sealed interface ProtocolResult {
    data class C4Active(
        val ownerUid: String,
        val alreadyActive: Boolean = false,
        val onboarded: Boolean = false,
        val cached: Boolean = false
    ) : ProtocolResult

    data class Legacy(
        val ownerUid: String,
        val cached: Boolean = false
    ) : ProtocolResult

    data class Blocked(
        val ownerUid: String,
        val reason: String
    ) : ProtocolResult

    data class Unverified(
        val ownerUid: String,
        val reason: String
    ) : ProtocolResult
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
     * Detección y aplicación multidispositivo del protocolo de la cuenta (Fase C4 Multidispositivo).
     *
     * 1. Al iniciar sesión, antes de permitir sincronización o escrituras Legacy, consulta el bloqueo remoto /users/{uid}.
     * 2. Si la cuenta ya está migrada a C4 desde otro dispositivo, impide que este dispositivo ejecute Legacy.
     *    Reconoce su estado C4 y utiliza el procedimiento seguro de incorporación (cutover/pull hacia Room)
     *    o bloquea en HALTED hasta poder recuperarlo si el dispositivo no está autorizado.
     * 3. Si no puede verificarse el estado remoto y tampoco existe un estado local fiable, NO asume que es Legacy.
     * 4. Mantiene intactas las cuentas realmente Legacy y los datos locales.
     * 5. Respeta el UID autenticado y los bloqueos HALTED.
     */
    suspend fun detectAndEnforceAccountProtocol(ownerUid: String): ProtocolResult = withContext(Dispatchers.IO) {
        if (ownerUid.isBlank() || ownerUid == "guest_local" || ownerUid == "legacy_unassigned") {
            return@withContext ProtocolResult.Legacy(ownerUid)
        }

        // Exclusión mutua: impedir que comprobaciones concurrentes produzcan cambios de estado incompatibles
        C4SyncConfig.protocolDetectionMutex.withLock {
            // 1. Respetar el UID autenticado
            val auth = try {
                authProvider()
            } catch (e: Exception) {
                return@withLock ProtocolResult.Blocked(ownerUid, "Error accediendo a FirebaseAuth: ${e.message}")
            }
            val currentUser = auth.currentUser
            if (currentUser == null || currentUser.uid != ownerUid) {
                return@withLock ProtocolResult.Blocked(
                    ownerUid,
                    "Discordancia de sesión: currentUser=${currentUser?.uid}, esperado=$ownerUid"
                )
            }

            // 2. Respetar bloqueos HALTED locales preexistentes
            val localMode = try {
                C4SyncStateStore.getEngineMode(context, ownerUid)
            } catch (_: Exception) {
                SyncEngineMode.LEGACY
            }

            if (localMode == SyncEngineMode.HALTED) {
                C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                return@withLock ProtocolResult.Blocked(
                    ownerUid,
                    "La cuenta está en estado HALTED localmente para proteger los datos C4."
                )
            }

            // 3. Consultar el bloqueo remoto /users/{ownerUid} desde Firestore
            val firestore = try {
                firestoreProvider()
            } catch (e: Exception) {
                return@withLock handleRemoteCheckFailure(ownerUid, localMode, "Fallo al inicializar Firestore: ${e.message}")
            }

            val projectId = firestore.app.options.projectId.orEmpty()
            val isRealApp = context.packageName?.endsWith(".c4pilot") == false &&
                    context.packageName?.endsWith(".internal") == false &&
                    !context.packageName.isNullOrBlank()
            val effectiveAllowProduction = allowProduction ?: (
                isRealApp || (C4SyncConfig.isProductionC4FeatureEnabled && C4SyncConfig.isUserAuthorizedForC4(ownerUid))
            )
            val firestoreAdapter = try {
                FirestoreC4SyncAdapter(
                    firestore = firestore,
                    expectedProjectId = projectId,
                    allowProduction = effectiveAllowProduction
                )
            } catch (e: Exception) {
                return@withLock handleRemoteCheckFailure(ownerUid, localMode, "Fallo al inicializar FirestoreAdapter: ${e.message}")
            }

            val remoteLock = try {
                firestoreAdapter.readAccountProtocolLock(ownerUid)
            } catch (e: Exception) {
                return@withLock handleRemoteCheckFailure(ownerUid, localMode, "Error consultando bloqueo remoto en /users/$ownerUid: ${e.message}")
            }

            // 4. Analizar respuesta remota:
            // Caso A: La cuenta ya está migrada a C4 remotamente desde este u otro dispositivo
            if (remoteLock != null && remoteLock.syncProtocolVersion == "C4") {
                if (remoteLock.accountSyncState != "MIGRATED_TO_C4") {
                    C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_REMOTE_ANOMALOUS_STATE_${remoteLock.accountSyncState}")
                    C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                    return@withLock ProtocolResult.Blocked(
                        ownerUid,
                        "Bloqueo remoto C4 anómalo detectado: state=${remoteLock.accountSyncState}. Cuenta detenida en HALTED por seguridad."
                    )
                }

                // IMPEDIR QUE ESTE DISPOSITIVO EJECUTE LEGACY
                if (localMode == SyncEngineMode.C4_ACTIVE) {
                    C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.C4_ACTIVE)
                    C4SyncStateStore.setProtocolVerified(context, ownerUid, true)
                    C4SyncConfig.markSessionProtocolVerified(ownerUid)
                    return@withLock ProtocolResult.C4Active(ownerUid, alreadyActive = true)
                }

                // Procedimiento seguro de incorporación de nuevo dispositivo:
                val isAuthorized = (context.packageName?.endsWith(".c4pilot") == true) ||
                        (C4SyncConfig.isProductionC4FeatureEnabled && C4SyncConfig.isUserAuthorizedForC4(ownerUid))

                if (!isAuthorized) {
                    // Bloquear hasta poder recuperarlo o habilitarlo. NUNCA ejecutar Legacy.
                    C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_C4_REMOTE_UNAUTHORIZED_DEVICE")
                    C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                    return@withLock ProtocolResult.Blocked(
                        ownerUid,
                        "La cuenta está migrada a C4 en otro dispositivo, pero este dispositivo no está autorizado para C4. Se bloquean escrituras y sincronización Legacy para proteger los datos."
                    )
                }

                // Ejecutar incorporación segura mediante activateAccount (cutover idempotente con pull de colección remota)
                return@withLock when (val activationResult = activateAccount(ownerUid)) {
                    is ActivationResult.Success -> {
                        C4SyncStateStore.setProtocolVerified(context, ownerUid, true)
                        C4SyncConfig.markSessionProtocolVerified(ownerUid)
                        ProtocolResult.C4Active(ownerUid, alreadyActive = false, onboarded = true)
                    }
                    is ActivationResult.AlreadyActive -> {
                        C4SyncStateStore.setProtocolVerified(context, ownerUid, true)
                        C4SyncConfig.markSessionProtocolVerified(ownerUid)
                        ProtocolResult.C4Active(ownerUid, alreadyActive = true)
                    }
                    is ActivationResult.Blocked -> {
                        C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_C4_ONBOARDING_FAILED")
                        C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                        ProtocolResult.Blocked(ownerUid, "Fallo durante la incorporación a C4: ${activationResult.reason}")
                    }
                    is ActivationResult.Failed -> {
                        C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_C4_ONBOARDING_FAILED")
                        C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                        ProtocolResult.Blocked(ownerUid, "Error durante la incorporación a C4: ${activationResult.reason}")
                    }
                    is ActivationResult.Unauthorized -> {
                        C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_C4_ONBOARDING_UNAUTHORIZED")
                        C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                        ProtocolResult.Blocked(ownerUid, "Incorporación a C4 no autorizada: ${activationResult.reason}")
                    }
                }
            }

            // Caso B: Protocolo remoto desconocido incompatible
            if (remoteLock != null && remoteLock.syncProtocolVersion.isNotBlank() && remoteLock.syncProtocolVersion != "LEGACY") {
                C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_UNKNOWN_REMOTE_PROTOCOL_${remoteLock.syncProtocolVersion}")
                C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                return@withLock ProtocolResult.Blocked(
                    ownerUid,
                    "Protocolo remoto incompatible o desconocido (${remoteLock.syncProtocolVersion}). Detenida en HALTED por seguridad."
                )
            }

            // Caso C: Cuentas donde el servidor indica LEGACY o carece de bloqueo C4
            // Si el estado local es C4_ACTIVE, NUNCA volver a Legacy. Detener en estado seguro y conservar los datos.
            if (localMode == SyncEngineMode.C4_ACTIVE) {
                C4SyncStateStore.recordMigrationCheckpoint(context, ownerUid, "HALTED_LOCAL_C4_REMOTE_LEGACY_CONFLICT")
                C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
                return@withLock ProtocolResult.Blocked(
                    ownerUid,
                    "Inconsistencia crítica: la cuenta está en C4_ACTIVE localmente pero el servidor indica LEGACY o carece de bloqueo C4. Detenida en HALTED para proteger los datos C4 locales."
                )
            }

            // Cuenta realmente Legacy:
            // Mantener intactas las cuentas realmente Legacy y los datos locales.
            C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.LEGACY)
            C4SyncStateStore.setProtocolVerified(context, ownerUid, true)
            C4SyncConfig.markSessionProtocolVerified(ownerUid)
            ProtocolResult.Legacy(ownerUid)
        }
    }

    private fun handleRemoteCheckFailure(
        ownerUid: String,
        localMode: SyncEngineMode,
        reason: String
    ): ProtocolResult {
        // "Si no puede verificarse el estado remoto y tampoco existe un estado local fiable, no asumir que es Legacy."
        if (localMode == SyncEngineMode.C4_ACTIVE) {
            C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.C4_ACTIVE)
            return ProtocolResult.C4Active(ownerUid, cached = true)
        }
        if (localMode == SyncEngineMode.HALTED) {
            C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.HALTED)
            return ProtocolResult.Blocked(ownerUid, "Cuenta detenida en HALTED localmente.")
        }
        // Para modo Legacy: comprobar si ya fue verificada en esta misma sesión en memoria
        if (C4SyncConfig.isSessionProtocolVerified(ownerUid) && localMode == SyncEngineMode.LEGACY) {
            C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.LEGACY)
            return ProtocolResult.Legacy(ownerUid, cached = true)
        }

        // Comprobar si existen datos locales en Room para mantener la protección offline-first de cuentas preexistentes
        val database = try { databaseProvider(context) } catch (_: Exception) { null }
        val hasLocalBeers = try {
            database?.beerDao()?.getActiveBeersSync(ownerUid)?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }

        if (hasLocalBeers && localMode == SyncEngineMode.LEGACY) {
            // Protección offline-first para usuario con datos locales previos sin prometer detección remota
            C4SyncConfig.setUserEngineMode(context, ownerUid, SyncEngineMode.LEGACY)
            return ProtocolResult.Legacy(ownerUid, cached = true)
        }

        // NO tratar una verificación legacy antigua como garantía si no hay datos locales ni verificación en esta sesión
        return ProtocolResult.Unverified(
            ownerUid,
            "No se pudo verificar el protocolo remoto ($reason) y no existe un estado local fiable en esta sesión. Operación retenida."
        )
    }

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
        val isRealApp = context.packageName?.endsWith(".c4pilot") == false &&
                context.packageName?.endsWith(".internal") == false &&
                !context.packageName.isNullOrBlank()
        val effectiveAllowProduction = allowProduction ?: (
            isRealApp || (C4SyncConfig.isProductionC4FeatureEnabled && C4SyncConfig.isUserAuthorizedForC4(ownerUid))
        )
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
