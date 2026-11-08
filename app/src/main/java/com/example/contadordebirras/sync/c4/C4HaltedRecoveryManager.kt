package com.example.contadordebirras.sync.c4

import android.content.Context
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Representa un punto de verificación individual dentro del diagnóstico de recuperación.
 */
data class C4DiagnosisCheck(
    val id: String,
    val title: String,
    val passed: Boolean,
    val detail: String
)

/**
 * Resultado formal del diagnóstico previo obligatorio para la recuperación de cuentas HALTED.
 */
sealed class C4HaltedDiagnosisResult(open val checks: List<C4DiagnosisCheck>) {
    data class Eligible(
        val message: String,
        val localBeersCount: Int,
        val pendingMutationsCount: Int,
        val remoteDocumentsCount: Int,
        override val checks: List<C4DiagnosisCheck>
    ) : C4HaltedDiagnosisResult(checks)

    data class Blocked(
        val reason: String,
        override val checks: List<C4DiagnosisCheck>
    ) : C4HaltedDiagnosisResult(checks)
}

/**
 * Resultado del procedimiento de recuperación segura de HALTED a C4_ACTIVE.
 */
sealed class C4HaltedRecoveryResult {
    data class Success(val message: String, val checks: List<C4DiagnosisCheck>) : C4HaltedRecoveryResult()
    data class Blocked(val reason: String, val checks: List<C4DiagnosisCheck>) : C4HaltedRecoveryResult()
    data class Error(val message: String) : C4HaltedRecoveryResult()
}

/**
 * Gestor de diagnóstico e integridad para la recuperación segura de cuentas en estado HALTED.
 *
 * GARANTÍAS FORMALES DE SEGURIDAD:
 * 1. Diagnóstico previo obligatorio sin efectos secundarios (ZERO escrituras en Room y Firestore).
 * 2. Comprobación autoritativa en Firestore exclusivamente con [com.google.firebase.firestore.Source.SERVER].
 * 3. Validación estricta de identidad: solo se permite la cuenta autorizada de pruebas.
 * 4. Las mutaciones locales PENDING legítimas se reconocen y preservan; NO se consideran error.
 * 5. Detección de migraciones inconclusas (CUTOVER_IN_PROGRESS o checkpoints de cutover interrumpido).
 * 6. Ante cualquier error, anomalía o evidencia insuficiente, prevalece la seguridad manteniendo HALTED.
 */
class C4HaltedRecoveryManager(
    private val context: Context,
    private val database: BeerDatabase,
    private val firestoreAdapter: FirestoreC4SyncAdapter,
    private val auth: FirebaseAuth,
    private val expectedPilotEmail: String = C4SyncConstants.PILOT_USER_EMAIL
) {

    companion object {
        val INTERRUPTED_CUTOVER_CHECKPOINTS = setOf(
            "CUTOVER_STARTED",
            "HALTED_RECOVERABLE",
            "INTERRUPTED_RECOVERABLE",
            "HALTED_RECOVERABLE_LOCK_FAILED"
        )
    }

    /**
     * Ejecuta el diagnóstico de seguridad previo obligatorio sobre la cuenta.
     * Esta función es estrictamente de solo lectura y no altera ningún dato local ni remoto.
     */
    suspend fun diagnoseHaltedAccount(ownerUid: String): C4HaltedDiagnosisResult = withContext(Dispatchers.IO) {
        val checks = mutableListOf<C4DiagnosisCheck>()

        // -----------------------------------------------------------------------------------------
        // 1. Identidad y UID del usuario piloto
        // -----------------------------------------------------------------------------------------
        val currentUser = auth.currentUser
        if (currentUser == null || ownerUid.isBlank() || ownerUid == "guest_local") {
            val detail = "No hay una sesión de usuario autenticada en Firebase Emulator para el UID: $ownerUid"
            checks.add(C4DiagnosisCheck("identity", "Identidad del usuario piloto", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        if (currentUser.uid != ownerUid) {
            val detail = "Discrepancia de UID: la sesión actual (${currentUser.uid}) no coincide con el UID objetivo ($ownerUid)"
            checks.add(C4DiagnosisCheck("identity", "Identidad del usuario piloto", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        val userEmail = currentUser.email
        if (userEmail != expectedPilotEmail) {
            val detail = "Cuenta no autorizada: $userEmail (se requiere estrictamente $expectedPilotEmail)"
            checks.add(C4DiagnosisCheck("identity", "Identidad del usuario piloto", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        checks.add(C4DiagnosisCheck("identity", "Identidad del usuario piloto", true, "UID: $ownerUid ($userEmail)"))

        // -----------------------------------------------------------------------------------------
        // 2. Conexión exclusiva con Firebase Emulator
        // -----------------------------------------------------------------------------------------
        val host = try {
            firestoreAdapter.firestoreHost
        } catch (_: Exception) {
            "unknown"
        }
        val isEmulatorHost = host.contains("127.0.0.1") || host.contains("10.0.2.2") || host.contains("localhost")
        val isDemoProject = firestoreAdapter.expectedProjectId == C4SyncConstants.DEMO_PROJECT_ID

        if (!isEmulatorHost || !isDemoProject) {
            val detail = "Entorno no aislado: host=$host, projectId=${firestoreAdapter.expectedProjectId} (se requiere Emulator local y demo-contador-c44)"
            checks.add(C4DiagnosisCheck("emulator", "Aislamiento en Firebase Emulator", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        checks.add(C4DiagnosisCheck("emulator", "Aislamiento en Firebase Emulator", true, "Host: $host, Proyecto: ${firestoreAdapter.expectedProjectId}"))

        // -----------------------------------------------------------------------------------------
        // 3. Estado persistente y checkpoints de migración
        // -----------------------------------------------------------------------------------------
        val persistedMode = try {
            C4SyncStateStore.getEngineMode(context, ownerUid)
        } catch (e: Exception) {
            val detail = "Error al leer el estado del motor en SharedPreferences: ${e.message}"
            checks.add(C4DiagnosisCheck("checkpoints", "Estado persistente y checkpoints", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        if (persistedMode == SyncEngineMode.CUTOVER_IN_PROGRESS) {
            val detail = "Migración de cutover en curso (CUTOVER_IN_PROGRESS). Este procedimiento no autoriza desbloquear cutovers incompletos."
            checks.add(C4DiagnosisCheck("checkpoints", "Estado persistente y checkpoints", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        val checkpoint = C4SyncStateStore.getMigrationCheckpoint(context, ownerUid)
        if (checkpoint != null && checkpoint in INTERRUPTED_CUTOVER_CHECKPOINTS) {
            val detail = "Existe un checkpoint de cutover inconcluso ($checkpoint). Requiere procedimiento específico de recuperación de migración."
            checks.add(C4DiagnosisCheck("checkpoints", "Estado persistente y checkpoints", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        checks.add(C4DiagnosisCheck("checkpoints", "Estado persistente y checkpoints", true, "Modo: $persistedMode, Checkpoint: ${checkpoint ?: "ninguno"}"))

        // -----------------------------------------------------------------------------------------
        // 4. Integridad de las cervezas locales y de local_mutation_history
        // -----------------------------------------------------------------------------------------
        val allLocalBeers: List<BeerEntity>
        val pendingMutations: List<LocalMutationHistoryEntity>
        try {
            val active = database.beerDao().getActiveBeersSync(ownerUid)
            val pending = database.beerDao().getPendingSyncBeers(ownerUid)
            allLocalBeers = (active + pending).distinctBy { it.syncId }
            pendingMutations = database.localMutationHistoryDao().getPendingMutations(ownerUid)

            for (beer in allLocalBeers) {
                if (beer.ownerUid != ownerUid) {
                    val detail = "Aislamiento local violado: la cerveza ${beer.syncId} pertenece a ${beer.ownerUid}"
                    checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
                if (beer.syncId.isBlank()) {
                    val detail = "Cerveza local corrupta: syncId vacío en entidad local ${beer.id}"
                    checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
                if (beer.dirtyFields != null && beer.dirtyFields.isNotBlank()) {
                    val tokens = beer.dirtyFields.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val hasInvalidToken = tokens.any { token ->
                        try {
                            com.example.contadordebirras.domain.BeerDirtyField.valueOf(token)
                            false
                        } catch (_: IllegalArgumentException) {
                            true
                        }
                    }
                    if (hasInvalidToken) {
                        val detail = "Campos dirtyFields corruptos en cerveza local ${beer.syncId}: '${beer.dirtyFields}'"
                        checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                        return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                    }
                }
                if (beer.syncStatus == SyncStatus.SYNCED && beer.remoteRevision <= 0L) {
                    val detail = "Cerveza SYNCED con revisión remota inválida (${beer.remoteRevision}) en ${beer.syncId}"
                    checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
                if (beer.syncStatus == SyncStatus.DELETED && beer.deletedAt == null) {
                    val detail = "Cerveza DELETED sin timestamp de borrado local en ${beer.syncId}"
                    checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
            }

            for (mut in pendingMutations) {
                if (mut.ownerUid != ownerUid || mut.syncId.isBlank() || mut.mutationId.isBlank()) {
                    val detail = "Mutación durable corrupta en historial: mutationId=${mut.mutationId}, syncId=${mut.syncId}"
                    checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
                if (mut.status != MutationStatus.PENDING && mut.status != MutationStatus.PARTIALLY_CONFIRMED) {
                    val detail = "Mutación en estado anómalo en cola de pendientes: ${mut.status}"
                    checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
            }
        } catch (e: Exception) {
            val detail = "Error al inspeccionar la base Room local: ${e.message}"
            checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        checks.add(
            C4DiagnosisCheck(
                "local_integrity",
                "Integridad local Room",
                true,
                "${allLocalBeers.size} cervezas (${pendingMutations.size} mutaciones pendientes legítimas verificadas)"
            )
        )

        // -----------------------------------------------------------------------------------------
        // 5. Consultas autoritativas a Firestore mediante Source.SERVER
        // -----------------------------------------------------------------------------------------
        val accountLock = try {
            firestoreAdapter.readAccountProtocolLock(ownerUid)
        } catch (e: Exception) {
            val detail = "Fallo de comunicación con Firestore Emulator al consultar bloqueo de cuenta (Source.SERVER): ${e.message}"
            checks.add(C4DiagnosisCheck("remote_lock", "Bloqueo de protocolo remoto", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        if (accountLock != null) {
            if (accountLock.syncProtocolVersion != "C4" && accountLock.syncProtocolVersion != "LEGACY") {
                val detail = "Incompatibilidad de versión de protocolo en Firestore: ${accountLock.syncProtocolVersion}"
                checks.add(C4DiagnosisCheck("remote_lock", "Bloqueo de protocolo remoto", false, detail))
                return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
            }
        }
        checks.add(
            C4DiagnosisCheck(
                "remote_lock",
                "Bloqueo de protocolo remoto",
                true,
                "Protocolo de cuenta: ${accountLock?.syncProtocolVersion ?: "no establecido (permitido en piloto)"}"
            )
        )

        val remoteDocs = try {
            firestoreAdapter.queryServerDocuments(ownerUid)
        } catch (e: Exception) {
            val detail = "Fallo de comunicación con Firestore Emulator al consultar documentos (Source.SERVER): ${e.message}"
            checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        val localBeersMap = allLocalBeers.associateBy { it.syncId }

        for (remoteDoc in remoteDocs) {
            if (remoteDoc.userId != ownerUid) {
                val detail = "Documento remoto ${remoteDoc.syncId} pertenece a un usuario ajeno: ${remoteDoc.userId}"
                checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
            }

            val localBeer = localBeersMap[remoteDoc.syncId]
            if (localBeer != null) {
                if (localBeer.syncStatus == SyncStatus.SYNCED) {
                    // Si localmente ya estaba confirmada, la revisión local no puede ser mayor que la del servidor
                    if (localBeer.remoteRevision > remoteDoc.revision) {
                        val detail = "Inconsistencia de monotonía en ${remoteDoc.syncId}: local (${localBeer.remoteRevision}) > servidor (${remoteDoc.revision})"
                        checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                        return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                    }
                } else if (localBeer.syncStatus == SyncStatus.PENDING) {
                    if (localBeer.remoteRevision == 0L) {
                        // Creación local pendiente: no debería existir ya en servidor salvo que sea reintento idempotente
                        if (remoteDoc.revision > 0L && remoteDoc.lastMutationId != localBeer.lastMutationId) {
                            val detail = "Conflicto en nueva creación: documento remoto ya existe con revisión ${remoteDoc.revision} y mutación distinta en ${remoteDoc.syncId}"
                            checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                        }
                    } else {
                        // Edición local pendiente: la revisión remota no puede ser menor que la baseRevision sobre la que se editó
                        if (remoteDoc.revision < localBeer.baseRevision) {
                            val detail = "Inconsistencia en revisión base remota: servidor (${remoteDoc.revision}) < base local (${localBeer.baseRevision}) en ${remoteDoc.syncId}"
                            checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                        }
                    }
                }
            }
        }

        checks.add(
            C4DiagnosisCheck(
                "remote_docs",
                "Consistencia documental remota (SERVER)",
                true,
                "${remoteDocs.size} documentos remotos verificados con Source.SERVER sin discrepancias"
            )
        )

        // -----------------------------------------------------------------------------------------
        // Conclusión: todas las comprobaciones son concluyentes y favorables
        // -----------------------------------------------------------------------------------------
        C4HaltedDiagnosisResult.Eligible(
            message = "Diagnóstico concluyente y favorable: la cuenta cumple todos los requisitos de integridad local y remota.",
            localBeersCount = allLocalBeers.size,
            pendingMutationsCount = pendingMutations.size,
            remoteDocumentsCount = remoteDocs.size,
            checks = checks
        )
    }

    /**
     * Ejecuta el procedimiento controlado de recuperación de HALTED a C4_ACTIVE.
     * Solo transiciona si [diagnoseHaltedAccount] es concluyente y favorable ([C4HaltedDiagnosisResult.Eligible]).
     * Ante cualquier fallo, mantiene HALTED y no modifica datos locales ni remotos.
     */
    suspend fun recoverHaltedAccount(ownerUid: String): C4HaltedRecoveryResult = withContext(Dispatchers.IO) {
        val diagnosis = diagnoseHaltedAccount(ownerUid)
        if (diagnosis !is C4HaltedDiagnosisResult.Eligible) {
            val blocked = diagnosis as C4HaltedDiagnosisResult.Blocked
            return@withContext C4HaltedRecoveryResult.Blocked(blocked.reason, blocked.checks)
        }

        try {
            C4SyncStateStore.setEngineMode(context, ownerUid, SyncEngineMode.C4_ACTIVE)
            C4SyncConfig.isEnabled = true
            C4SyncConfig.engineMode = SyncEngineMode.C4_ACTIVE

            C4HaltedRecoveryResult.Success(
                message = "Cuenta recuperada exitosamente: modo C4_ACTIVE establecido con mutaciones y datos intactos.",
                checks = diagnosis.checks
            )
        } catch (e: Exception) {
            C4HaltedRecoveryResult.Error("Fallo inesperado al persistir el estado recuperado: ${e.message}")
        }
    }
}
