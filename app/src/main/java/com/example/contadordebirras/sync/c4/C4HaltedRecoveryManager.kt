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
 * 3. Validación estricta e inmutable de identidad: solo se permite [C4SyncConstants.PILOT_USER_EMAIL].
 * 4. El estado persistente debe ser exactamente [SyncEngineMode.HALTED] (se rechaza LEGACY, C4_ACTIVE y CUTOVER_IN_PROGRESS).
 * 5. Criterio conservador de migración: no se autoriza recuperación ante checkpoints desconocidos o inconclusos.
 * 6. Bloqueo remoto estricto: requiere syncProtocolVersion == C4 y state == MIGRATED_TO_C4 (salvo excepción justificada de piloto en emulador).
 * 7. Integridad de mutaciones y tombstones: cada mutación PENDING debe pertenecer a una cerveza local coherente.
 * 8. Una cerveza SYNCED no puede haber desaparecido del servidor sin explicación.
 * 9. Ediciones pendientes con revisión incompatible se bloquean salvo recuperación idempotente demostrable.
 * 10. Ante cualquier error, anomalía o evidencia insuficiente, prevalece la seguridad manteniendo HALTED.
 */
class C4HaltedRecoveryManager(
    private val context: Context,
    private val database: BeerDatabase,
    private val firestoreAdapter: FirestoreC4SyncAdapter,
    private val auth: FirebaseAuth
) {

    companion object {
        const val CHECKPOINT_COMPLETED = "COMPLETED"
    }

    private val authorizedPilotEmail: String = C4SyncConstants.PILOT_USER_EMAIL

    /**
     * Ejecuta el diagnóstico de seguridad previo obligatorio sobre la cuenta.
     * Esta función es estrictamente de solo lectura y no altera ningún dato local ni remoto.
     */
    suspend fun diagnoseHaltedAccount(ownerUid: String): C4HaltedDiagnosisResult = withContext(Dispatchers.IO) {
        val checks = mutableListOf<C4DiagnosisCheck>()

        // -----------------------------------------------------------------------------------------
        // 1. Identidad y UID del usuario piloto (estrictamente C4SyncConstants.PILOT_USER_EMAIL)
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
        if (userEmail != authorizedPilotEmail) {
            val detail = "Cuenta no autorizada: $userEmail (se requiere estrictamente $authorizedPilotEmail)"
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
        // 3. Estado persistente (exactamente HALTED) y checkpoints de migración (conservador)
        // -----------------------------------------------------------------------------------------
        val persistedMode = try {
            C4SyncStateStore.getEngineMode(context, ownerUid)
        } catch (e: Exception) {
            val detail = "Error al leer el estado del motor en SharedPreferences: ${e.message}"
            checks.add(C4DiagnosisCheck("checkpoints", "Estado persistente y checkpoints", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        if (persistedMode != SyncEngineMode.HALTED) {
            val detail = "Estado persistente incompatible: $persistedMode. La recuperación segura de cuenta detenida solo procede cuando el estado persistente es exactamente HALTED (rechazados LEGACY, C4_ACTIVE y CUTOVER_IN_PROGRESS)."
            checks.add(C4DiagnosisCheck("checkpoints", "Estado persistente y checkpoints", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        val checkpoint = C4SyncStateStore.getMigrationCheckpoint(context, ownerUid)
        if (checkpoint != null && checkpoint != CHECKPOINT_COMPLETED) {
            val detail = "Existe un checkpoint de migración inconcluso o desconocido ('$checkpoint'). Requiere procedimiento específico y no autoriza recuperación a C4_ACTIVE."
            checks.add(C4DiagnosisCheck("checkpoints", "Estado persistente y checkpoints", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        checks.add(C4DiagnosisCheck("checkpoints", "Estado persistente y checkpoints", true, "Modo: $persistedMode (exacto HALTED), Checkpoint: ${checkpoint ?: "ninguno"}"))

        // -----------------------------------------------------------------------------------------
        // 4. Integridad de las cervezas locales y de local_mutation_history
        // -----------------------------------------------------------------------------------------
        val allLocalBeers: List<BeerEntity>
        val pendingMutations: List<LocalMutationHistoryEntity>
        val localBeersMap: Map<String, BeerEntity>
        try {
            val active = database.beerDao().getActiveBeersSync(ownerUid)
            val pending = database.beerDao().getPendingSyncBeers(ownerUid)
            allLocalBeers = (active + pending).distinctBy { it.syncId }
            localBeersMap = allLocalBeers.associateBy { it.syncId }
            pendingMutations = database.localMutationHistoryDao().getPendingMutations(ownerUid)

            // Validar cada cerveza local
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
                // Verificación de tombstones locales (syncStatus == DELETED)
                if (beer.syncStatus == SyncStatus.DELETED) {
                    if (beer.deletedAt == null || beer.deletedAt <= 0L) {
                        val detail = "Tombstone local corrupto: cerveza DELETED sin deletedAt válido en ${beer.syncId}"
                        checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                        return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                    }
                    val hasPendingTombstoneMutation = pendingMutations.any { it.syncId == beer.syncId }
                    if (!hasPendingTombstoneMutation) {
                        val detail = "Tombstone local huérfano en ${beer.syncId}: estado DELETED sin mutación en local_mutation_history"
                        checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                        return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                    }
                }
            }

            // Validar que cada mutación PENDING corresponda a una cerveza local válida y coherente
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
                val localBeer = localBeersMap[mut.syncId]
                if (localBeer == null) {
                    val detail = "Mutación PENDING huérfana: la mutación ${mut.mutationId} (${mut.syncId}) no tiene cerveza local correspondiente en Room"
                    checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
                if (localBeer.syncStatus == SyncStatus.SYNCED) {
                    val detail = "Incoherencia local en ${mut.syncId}: existe mutación PENDING (${mut.mutationId}) pero la cerveza está marcada como SYNCED"
                    checks.add(C4DiagnosisCheck("local_integrity", "Integridad local Room", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
                if (localBeer.syncStatus == SyncStatus.DELETED && localBeer.deletedAt == null) {
                    val detail = "Incoherencia local en tombstone ${mut.syncId}: mutación PENDING asociada a cerveza DELETED sin deletedAt"
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
                "${allLocalBeers.size} cervezas (${pendingMutations.size} mutaciones pendientes legítimas coherentes con entidades locales)"
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
            if (accountLock.syncProtocolVersion != "C4") {
                val detail = "Bloqueo remoto incompatible: syncProtocolVersion=${accountLock.syncProtocolVersion} (se requiere C4)"
                checks.add(C4DiagnosisCheck("remote_lock", "Bloqueo de protocolo remoto", false, detail))
                return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
            }
            if (accountLock.accountSyncState != "MIGRATED_TO_C4") {
                val detail = "Estado de migración remota incompatible: ${accountLock.accountSyncState} (se requiere MIGRATED_TO_C4)"
                checks.add(C4DiagnosisCheck("remote_lock", "Bloqueo de protocolo remoto", false, detail))
                return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
            }
            checks.add(
                C4DiagnosisCheck(
                    "remote_lock",
                    "Bloqueo de protocolo remoto",
                    true,
                    "Protocolo C4 verificado: syncProtocolVersion=C4, state=${accountLock.accountSyncState}"
                )
            )
        } else {
            // Excepción de cuentas piloto sin lock solamente cuando sea inequívocamente segura y justificada:
            // 1. Usuario autenticado es estrictamente PILOT_USER_EMAIL.
            // 2. Entorno es estrictamente Firebase Emulator en demo-contador-c44.
            // 3. Estado local es exactamente HALTED sin checkpoints de migración pendientes.
            checks.add(
                C4DiagnosisCheck(
                    "remote_lock",
                    "Bloqueo de protocolo remoto",
                    true,
                    "Sin bloqueo remoto previo: excepción de cuenta piloto autorizada (cuenta aislada en Firebase Emulator demo-contador-c44 sin migración legacy previa)"
                )
            )
        }

        val remoteDocs = try {
            firestoreAdapter.queryServerDocuments(ownerUid)
        } catch (e: Exception) {
            val detail = "Fallo de comunicación con Firestore Emulator al consultar documentos (Source.SERVER): ${e.message}"
            checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
        }

        val remoteDocsMap = remoteDocs.associateBy { it.syncId }

        // A) Validar que ninguna cerveza SYNCED haya desaparecido del servidor sin explicación
        for (localBeer in allLocalBeers) {
            if (localBeer.syncStatus == SyncStatus.SYNCED) {
                val remoteDoc = remoteDocsMap[localBeer.syncId]
                if (remoteDoc == null) {
                    val detail = "Cerveza SYNCED desaparecida del servidor (Source.SERVER): ${localBeer.syncId} (rev local ${localBeer.remoteRevision}) no existe en Firestore"
                    checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
            }
        }

        // B) Validar todos los documentos remotos y la relación con el estado local
        for (remoteDoc in remoteDocs) {
            if (remoteDoc.userId != ownerUid) {
                val detail = "Documento remoto ${remoteDoc.syncId} pertenece a un usuario ajeno: ${remoteDoc.userId}"
                checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
            }

            val localBeer = localBeersMap[remoteDoc.syncId]
            if (localBeer != null) {
                when (localBeer.syncStatus) {
                    SyncStatus.SYNCED -> {
                        // Monotonía: la revisión local no puede superar la del servidor
                        if (localBeer.remoteRevision > remoteDoc.revision) {
                            val detail = "Inconsistencia de monotonía en ${remoteDoc.syncId}: local (${localBeer.remoteRevision}) > servidor (${remoteDoc.revision})"
                            checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                        }
                    }
                    SyncStatus.PENDING -> {
                        if (localBeer.remoteRevision == 0L && localBeer.baseRevision == 0L) {
                            // Creación local pendiente: no debería existir en servidor salvo recuperación idempotente demostrable
                            if (remoteDoc.revision > 0L) {
                                if (remoteDoc.lastMutationId == localBeer.lastMutationId) {
                                    // Recuperación idempotente demostrable: la creación llegó al servidor antes del HALTED
                                } else {
                                    val detail = "Conflicto en nueva creación: documento remoto ${remoteDoc.syncId} ya existe (rev ${remoteDoc.revision}) con mutación distinta (${remoteDoc.lastMutationId} != ${localBeer.lastMutationId})"
                                    checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                                }
                            }
                        } else {
                            // Edición pendiente sobre documento existente (remoteRevision > 0 o baseRevision > 0)
                            if (remoteDoc.revision < localBeer.baseRevision) {
                                val detail = "Inconsistencia en revisión base remota: servidor (${remoteDoc.revision}) < base local (${localBeer.baseRevision}) en ${remoteDoc.syncId}"
                                checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                                return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                            }
                            if (remoteDoc.revision > localBeer.baseRevision) {
                                // Revisión remota incompatible: solo se permite si es una recuperación idempotente demostrable
                                if (remoteDoc.lastMutationId == localBeer.lastMutationId) {
                                    // Recuperación idempotente demostrable de edición
                                } else {
                                    val detail = "Edición pendiente con revisión incompatible en ${remoteDoc.syncId}: servidor (${remoteDoc.revision}) > base local (${localBeer.baseRevision}) con mutación ajena (${remoteDoc.lastMutationId} != ${localBeer.lastMutationId})"
                                    checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                                }
                            }
                        }
                    }
                    SyncStatus.DELETED -> {
                        // Verificación de tombstone pendiente contra servidor
                        if (remoteDoc.revision < localBeer.baseRevision) {
                            val detail = "Inconsistencia en revisión base de tombstone: servidor (${remoteDoc.revision}) < base local (${localBeer.baseRevision}) en ${remoteDoc.syncId}"
                            checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                            return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                        }
                        if (remoteDoc.revision > localBeer.baseRevision) {
                            if (remoteDoc.isDeleted && remoteDoc.lastMutationId == localBeer.lastMutationId) {
                                // Idempotente demostrable: tombstone ya confirmado remotamente
                            } else {
                                val detail = "Conflicto en tombstone pendiente: documento remoto en ${remoteDoc.syncId} modificado concurrentemente (rev remota ${remoteDoc.revision} > base local ${localBeer.baseRevision})"
                                checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                                return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                            }
                        }
                    }
                }
            }
        }

        // C) Validar que documentos con ediciones o tombstones pendientes no hayan desaparecido si tenían baseRevision > 0
        for (localBeer in allLocalBeers) {
            if (localBeer.syncStatus == SyncStatus.DELETED && localBeer.baseRevision > 0L) {
                if (remoteDocsMap[localBeer.syncId] == null) {
                    val detail = "Tombstone pendiente sobre documento base desaparecido del servidor: ${localBeer.syncId} (baseRevision ${localBeer.baseRevision})"
                    checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
            }
            if (localBeer.syncStatus == SyncStatus.PENDING && (localBeer.baseRevision > 0L || localBeer.remoteRevision > 0L)) {
                if (remoteDocsMap[localBeer.syncId] == null) {
                    val detail = "Edición pendiente sobre documento base desaparecido del servidor: ${localBeer.syncId} (baseRevision ${localBeer.baseRevision})"
                    checks.add(C4DiagnosisCheck("remote_docs", "Consistencia documental remota (SERVER)", false, detail))
                    return@withContext C4HaltedDiagnosisResult.Blocked(detail, checks)
                }
            }
        }

        checks.add(
            C4DiagnosisCheck(
                "remote_docs",
                "Consistencia documental remota (SERVER)",
                true,
                "${remoteDocs.size} documentos remotos verificados con Source.SERVER sin discrepancias ni desapariciones"
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
