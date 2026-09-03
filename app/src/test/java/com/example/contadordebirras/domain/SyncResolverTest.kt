package com.example.contadordebirras.domain

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class SyncResolverTest {

    private fun mockEntity(status: String, updated: Long): BeerEntity {
        return BeerEntity(
            id = 1,
            type = BeerType.LATA,
            timestamp = 1000L,
            syncStatus = status,
            updatedAt = updated,
            syncId = UUID.randomUUID().toString()
        )
    }

    @Test
    fun `Insert remoto nuevo - Local vacio`() {
        val decision = SyncResolver.resolvePullConflict(null, 1000L)
        assertEquals(SyncResolver.SyncDecision.INSERT_LOCAL, decision)
    }

    @Test
    fun `Idempotencia - Mismo pull no hace nada (remoto mas antiguo o igual)`() {
        val local = mockEntity(SyncStatus.SYNCED, 1000L)
        val decision = SyncResolver.resolvePullConflict(local, 1000L)
        assertEquals(SyncResolver.SyncDecision.IGNORE, decision)
    }

    @Test
    fun `Remoto mas nuevo - Se actualiza local`() {
        val local = mockEntity(SyncStatus.SYNCED, 100L)
        val decision = SyncResolver.resolvePullConflict(local, 200L)
        assertEquals(SyncResolver.SyncDecision.UPDATE_LOCAL, decision)
    }

    @Test
    fun `Remoto mas antiguo - No se degrada local`() {
        val local = mockEntity(SyncStatus.SYNCED, 200L)
        val decision = SyncResolver.resolvePullConflict(local, 100L)
        assertEquals(SyncResolver.SyncDecision.IGNORE, decision)
    }

    @Test
    fun `Local PENDING - No se sobrescribe por remoto`() {
        val local = mockEntity(SyncStatus.PENDING, 100L)
        val decision = SyncResolver.resolvePullConflict(local, 200L)
        assertEquals(SyncResolver.SyncDecision.IGNORE, decision)
    }

    @Test
    fun `Local DELETED - No se resucita por remoto`() {
        val local = mockEntity(SyncStatus.DELETED, 100L)
        val decision = SyncResolver.resolvePullConflict(local, 200L)
        assertEquals(SyncResolver.SyncDecision.IGNORE, decision)
    }

    @Test
    fun `Borrado remoto - Local SYNCED ausente en remoto se elimina`() {
        val localSyncedIds = listOf("id1", "id2", "id3")
        val remoteIds = setOf("id1", "id3")
        val toDelete = SyncResolver.resolveDeletions(localSyncedIds, remoteIds)
        assertEquals(listOf("id2"), toDelete)
    }
}
