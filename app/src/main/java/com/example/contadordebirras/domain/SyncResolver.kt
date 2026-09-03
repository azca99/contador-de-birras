package com.example.contadordebirras.domain

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus

object SyncResolver {

    enum class SyncDecision {
        INSERT_LOCAL, UPDATE_LOCAL, IGNORE
    }

    fun resolvePullConflict(local: BeerEntity?, remoteUpdatedAt: Long): SyncDecision {
        if (local == null) return SyncDecision.INSERT_LOCAL
        
        if (local.syncStatus == SyncStatus.PENDING || local.syncStatus == SyncStatus.DELETED) {
            return SyncDecision.IGNORE
        }
        
        if (local.syncStatus == SyncStatus.SYNCED && remoteUpdatedAt > local.updatedAt) {
            return SyncDecision.UPDATE_LOCAL
        }
        
        return SyncDecision.IGNORE
    }
    
    fun resolveDeletions(localSyncedIds: List<String>, remoteIds: Set<String>): List<String> {
        return localSyncedIds.filter { it !in remoteIds }
    }

    fun parseRemoteBeerType(typeStr: String?): BeerType? {
        if (typeStr == null) return null
        return try {
            BeerType.valueOf(typeStr)
        } catch (e: Exception) {
            null
        }
    }
}
