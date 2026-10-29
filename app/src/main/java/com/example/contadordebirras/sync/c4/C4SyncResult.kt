package com.example.contadordebirras.sync.c4

import com.example.contadordebirras.domain.PullPlan
import com.example.contadordebirras.domain.PushPlan

/**
 * Resultados semánticos de transacciones en Firestore.
 */
sealed interface C4TransactionResult {
    data class Success(val newRevision: Long, val mutationId: String) : C4TransactionResult
    data class IdempotentAck(val currentRevision: Long, val mutationId: String) : C4TransactionResult
    data class Conflict(val currentRevision: Long, val reason: String) : C4TransactionResult
    data class TombstonePrevailed(val currentRevision: Long) : C4TransactionResult
    data class PreconditionFailed(val reason: String) : C4TransactionResult
    data class OwnershipMismatch(val documentOwner: String, val callerOwner: String) : C4TransactionResult
    data object NotFound : C4TransactionResult
    data class Error(val exception: Throwable) : C4TransactionResult
}

/**
 * Resultados de operaciones en Firebase Storage.
 */
sealed interface C4StorageResult {
    data class Success(val remotePath: String, val alreadyExisted: Boolean) : C4StorageResult
    data class OverwriteRejected(val reason: String) : C4StorageResult
    data class Error(val exception: Throwable) : C4StorageResult
}

/**
 * Resultados de alto nivel de operaciones PUSH ejecutadas por el coordinador C4.
 */
sealed interface C4CoordinatorPushResult {
    data class Success(val plan: PushPlan, val newRemoteRevision: Long) : C4CoordinatorPushResult
    data class Conflict(val plan: PushPlan, val currentRemoteRevision: Long, val reason: String) : C4CoordinatorPushResult
    data class Blocked(val reason: String) : C4CoordinatorPushResult
    data class IdempotentRecovery(val currentRemoteRevision: Long, val mutationId: String) : C4CoordinatorPushResult
    data class StorageFailed(val reason: String) : C4CoordinatorPushResult
    data object Offline : C4CoordinatorPushResult
    data object NotFound : C4CoordinatorPushResult
}

/**
 * Resultados de alto nivel de operaciones PULL ejecutadas por el coordinador C4.
 */
sealed interface C4CoordinatorPullResult {
    data class Applied(val plan: PullPlan) : C4CoordinatorPullResult
    data class Blocked(val reason: String) : C4CoordinatorPullResult
    data object NoOp : C4CoordinatorPullResult
    data object IncompleteReadIgnored : C4CoordinatorPullResult
}
