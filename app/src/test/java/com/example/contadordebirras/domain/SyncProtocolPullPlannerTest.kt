package com.example.contadordebirras.domain

import com.example.contadordebirras.data.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolPullPlannerTest {

    // ==================================================
    // 1. LOCAL AUSENTE + REMOTO ACTIVO (INSERT LOCAL)
    // ==================================================

    @Test
    fun `planPull inserts local entity when local is null and remote is active`() {
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = "M1",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 5L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local = null, remoteMetadata = remote)

        assertEquals(
            PullPlan.InsertLocal(
                remoteRevision = 5L,
                fieldsToApply = BeerDirtyFieldsCodec.ALL_FIELDS
            ),
            plan
        )
    }

    // ==================================================
    // 2. LOCAL AUSENTE + REMOTO TOMBSTONE
    // ==================================================

    @Test
    fun `planPull ignores remote tombstone when local is null`() {
        val remote = RemoteBeerSyncMetadata(
            revision = 3L,
            isDeleted = true,
            lastMutationId = "DEL_REMOTE",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local = null, remoteMetadata = remote)

        assertEquals(PullPlan.IgnoreRemoteTombstone, plan)
    }

    // ==================================================
    // 3. LOCAL SYNCED + REMOTO ACTIVO MÁS RECIENTE
    // ==================================================

    @Test
    fun `planPull updates local SYNCED entity when remote revision is strictly greater`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = emptySet(),
            lastMutationId = "M_OLD"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 8L,
            isDeleted = false,
            lastMutationId = "M_NEW",
            fieldRevisions = mapOf(BeerDirtyField.LOCATION to 8L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(
            PullPlan.UpdateLocalSynced(
                newRemoteRevision = 8L,
                fieldsToApply = BeerDirtyFieldsCodec.ALL_FIELDS
            ),
            plan
        )
    }

    // ==================================================
    // 4. LOCAL SYNCED + MISMA REVISIÓN (IDEMPOTENCIA)
    // ==================================================

    @Test
    fun `planPull returns NoOpAlreadyUpToDate when local SYNCED has same revision as remote`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = emptySet(),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = "M1",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.NoOpAlreadyUpToDate, plan)
    }

    // ==================================================
    // 5. LOCAL SYNCED + REVISIÓN REMOTA INFERIOR (REGRESIÓN)
    // ==================================================

    @Test
    fun `planPush returns InvalidMetadata when remote revision regresses below local remoteRevision for SYNCED`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = emptySet(),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 3L,
            isDeleted = false,
            lastMutationId = "OTHER",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertTrue(plan is PullPlan.InvalidMetadata)
        val reason = (plan as PullPlan.InvalidMetadata).reason
        assertTrue(reason.contains("cannot be less than local remoteRevision"))
    }

    // ==================================================
    // 6. LOCAL PENDING + REMOTO ACTIVO (FUSIÓN CAMPO POR CAMPO)
    // ==================================================

    @Test
    fun `planPull merges orthogonal remote change into PENDING entity preserving local dirty field`() {
        // Local editó COMMENT en base 5. Remoto editó LOCATION en revision 6.
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE",
            fieldRevisions = mapOf(
                BeerDirtyField.LOCATION to 6L,
                BeerDirtyField.COMMENT to 5L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.LOCATION),
                remoteWinsConflictFields = emptySet(),
                localPendingFieldsToPreserve = setOf(BeerDirtyField.COMMENT),
                newRemoteRevision = 6L,
                newBaseRevision = 5L // Frozen: local pending changes remain
            ),
            plan
        )
    }

    @Test
    fun `planPull surrenders dirty field to remote when same field has remote conflict (Remote Confirmed Wins)`() {
        // Local editó COMMENT en base 5. Remoto confirmó cambio en COMMENT en revision 6.
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE",
            fieldRevisions = mapOf(
                BeerDirtyField.COMMENT to 6L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(
            local,
            remote,
            provenance = PullProvenance(origin = RemoteOrigin.EXTERNAL_CONFIRMED)
        )

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.COMMENT),
                remoteWinsConflictFields = setOf(BeerDirtyField.COMMENT),
                localPendingFieldsToPreserve = emptySet(),
                newRemoteRevision = 6L,
                newBaseRevision = 6L // Advanced: no pending fields remain
            ),
            plan
        )
    }

    @Test
    fun `planPull handles mixed conflict accurately with orthogonal, conflict and safe local fields`() {
        // Local dirty: [COMMENT, LOCATION], base = 5
        // Remote: rev = 7, COMMENT = 7 (conflict), LOCATION = 5 (safe local), TYPE = 7 (orthogonal remote)
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.LOCATION),
            lastMutationId = "M_MIXED"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 7L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_MIXED",
            fieldRevisions = mapOf(
                BeerDirtyField.COMMENT to 7L,
                BeerDirtyField.LOCATION to 5L,
                BeerDirtyField.TYPE to 7L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(
            local,
            remote,
            provenance = PullProvenance(origin = RemoteOrigin.EXTERNAL_CONFIRMED)
        )

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.TYPE, BeerDirtyField.COMMENT),
                remoteWinsConflictFields = setOf(BeerDirtyField.COMMENT),
                localPendingFieldsToPreserve = setOf(BeerDirtyField.LOCATION),
                newRemoteRevision = 7L,
                newBaseRevision = 5L // Frozen: LOCATION is still pending
            ),
            plan
        )
    }

    @Test
    fun `planPull preserves accumulated dirtyFields when remote updates an unrelated field`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = setOf(BeerDirtyField.TYPE, BeerDirtyField.LOCATION, BeerDirtyField.COMMENT),
            lastMutationId = "M_ACCUM"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_PHOTO",
            fieldRevisions = mapOf(BeerDirtyField.PHOTO to 5L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.PHOTO),
                remoteWinsConflictFields = emptySet(),
                localPendingFieldsToPreserve = setOf(
                    BeerDirtyField.TYPE,
                    BeerDirtyField.LOCATION,
                    BeerDirtyField.COMMENT
                ),
                newRemoteRevision = 5L,
                newBaseRevision = 4L
            ),
            plan
        )
    }

    @Test
    fun `planPull returns NoOpAlreadyUpToDate when PENDING local has same revision and no remote fields changed since base`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_PENDING"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = "M_REMOTE",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 5L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.NoOpAlreadyUpToDate, plan)
    }

    @Test
    fun `planPull advances newRemoteRevision when remote revision increased without touching local fields`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_PENDING"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 5L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = emptySet(),
                remoteWinsConflictFields = emptySet(),
                localPendingFieldsToPreserve = setOf(BeerDirtyField.COMMENT),
                newRemoteRevision = 6L,
                newBaseRevision = 5L
            ),
            plan
        )
    }

    // ==================================================
    // 7. LOCAL DELETED (DELETE WINS)
    // ==================================================

    @Test
    fun `planPull preserves local DELETED state when remote is active (never resurrect)`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 3L,
            baseRevision = 3L,
            dirtyFields = emptySet(),
            lastMutationId = "DEL_LOCAL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 8L,
            isDeleted = false,
            lastMutationId = "EDIT_REMOTE",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 8L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.PreserveLocalDelete, plan)
    }

    @Test
    fun `planPull returns DeleteAlreadySatisfied when local is DELETED and remote is tombstone`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 3L,
            baseRevision = 3L,
            dirtyFields = emptySet(),
            lastMutationId = "DEL_LOCAL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 8L,
            isDeleted = true,
            lastMutationId = "DEL_REMOTE",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.DeleteAlreadySatisfied, plan)
    }

    // ==================================================
    // 8. REMOTO TOMBSTONE + LOCAL ACTIVO (DELETE WINS)
    // ==================================================

    @Test
    fun `planPull applies remote tombstone when local is PENDING (remote deletion wins)`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_EDIT"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = true,
            lastMutationId = "DEL_REMOTE",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.ApplyRemoteTombstone(newRemoteRevision = 6L), plan)
    }

    @Test
    fun `planPull applies remote tombstone when local is SYNCED`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = emptySet(),
            lastMutationId = "M_SYNCED"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = true,
            lastMutationId = "DEL_REMOTE",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.ApplyRemoteTombstone(newRemoteRevision = 6L), plan)
    }

    // ==================================================
    // 9. REMOTO AUSENTE (AUTORITATIVO VS INCOMPLETO)
    // ==================================================

    @Test
    fun `planPull ignores incomplete remote read for any local state without altering data`() {
        val localPending = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val localSynced = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = emptySet(),
            lastMutationId = "M2"
        )
        val localDeleted = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = emptySet(),
            lastMutationId = "M3"
        )

        assertEquals(
            PullPlan.IgnoreIncompleteRead,
            SyncProtocolPullPlanner.planPull(localPending, RemotePullState.IncompleteOrUnconfirmed)
        )
        assertEquals(
            PullPlan.IgnoreIncompleteRead,
            SyncProtocolPullPlanner.planPull(localSynced, RemotePullState.IncompleteOrUnconfirmed)
        )
        assertEquals(
            PullPlan.IgnoreIncompleteRead,
            SyncProtocolPullPlanner.planPull(localDeleted, RemotePullState.IncompleteOrUnconfirmed)
        )
        assertEquals(
            PullPlan.IgnoreIncompleteRead,
            SyncProtocolPullPlanner.planPull(local = null, RemotePullState.IncompleteOrUnconfirmed)
        )
    }

    @Test
    fun `planPull returns NoOpAlreadyUpToDate when both local and remote are absent`() {
        val plan = SyncProtocolPullPlanner.planPull(local = null, RemotePullState.ConfirmedAbsent)
        assertEquals(PullPlan.NoOpAlreadyUpToDate, plan)
    }

    @Test
    fun `planPull purges local SYNCED entity when authoritatively absent from remote`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = emptySet(),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPullPlanner.planPull(local, RemotePullState.ConfirmedAbsent)

        assertEquals(PullPlan.ApplyRemotePurge, plan)
    }

    @Test
    fun `planPull returns DeleteAlreadySatisfied when local is DELETED and remote is confirmed absent`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = emptySet(),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPullPlanner.planPull(local, RemotePullState.ConfirmedAbsent)

        assertEquals(PullPlan.DeleteAlreadySatisfied, plan)
    }

    @Test
    fun `planPull preserves local-only PENDING entity when remote is confirmed absent`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPullPlanner.planPull(local, RemotePullState.ConfirmedAbsent)

        assertEquals(PullPlan.PreserveLocalPending, plan)
    }

    @Test
    fun `planPull signals RemoteAbsentForKnownEntity when previously known PENDING entity is confirmed absent`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPullPlanner.planPull(local, RemotePullState.ConfirmedAbsent)

        assertEquals(PullPlan.RemoteAbsentForKnownEntity, plan)
    }

    // ==================================================
    // 10. LOCAL-ONLY / ACK PERDIDO
    // ==================================================

    @Test
    fun `planPull safely blocks with RemotePresentForLocalOnlyNeedsReconciliation when local-only PENDING meets active remote`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M2"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M1",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.RemotePresentForLocalOnlyNeedsReconciliation, plan)
    }

    // ==================================================
    // 11. METADATA LEGACY
    // ==================================================

    @Test
    fun `planPull returns LegacyRemoteNeedsResolution when PENDING entity on base 0 meets remote revision 0 without fieldRevisions`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 0L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.LegacyRemoteNeedsResolution, plan)
    }

    @Test
    fun `planPull returns NoOpAlreadyUpToDate when SYNCED entity on revision 0 meets remote revision 0`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = emptySet(),
            lastMutationId = null
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 0L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.NoOpAlreadyUpToDate, plan)
    }

    // ==================================================
    // 12. FOTO Y CONSERVACIÓN DE METADATA LOCAL
    // ==================================================

    @Test
    fun `planPull accurately tracks PHOTO field in remoteFieldsToApply without clearing local-only properties`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M2",
            fieldRevisions = mapOf(
                BeerDirtyField.PHOTO to 6L,
                BeerDirtyField.COMMENT to 5L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertTrue(plan is PullPlan.MergeWithPending)
        val mergePlan = plan as PullPlan.MergeWithPending
        assertTrue(mergePlan.remoteFieldsToApply.contains(BeerDirtyField.PHOTO))
        assertTrue(mergePlan.localPendingFieldsToPreserve.contains(BeerDirtyField.COMMENT))
        assertEquals(5L, mergePlan.newBaseRevision) // baseRevision remains frozen
        assertEquals(6L, mergePlan.newRemoteRevision)
    }

    // ==================================================
    // 13. VALIDACIÓN DE METADATA INVÁLIDA
    // ==================================================

    @Test
    fun `planPull returns InvalidMetadata when remote revision is negative`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = emptySet(),
            lastMutationId = null
        )
        val remote = RemoteBeerSyncMetadata(
            revision = -1L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertTrue(plan is PullPlan.InvalidMetadata)
    }

    @Test
    fun `planPull returns InvalidMetadata when field revision is negative`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = emptySet(),
            lastMutationId = null
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to -2L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertTrue(plan is PullPlan.InvalidMetadata)
    }

    @Test
    fun `planPull returns InvalidMetadata when field revision exceeds remote revision`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = emptySet(),
            lastMutationId = null
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertTrue(plan is PullPlan.InvalidMetadata)
    }

    @Test
    fun `planPull returns InvalidMetadata when local baseRevision is less than -1`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = -2L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = null
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remoteMetadata = null)

        assertTrue(plan is PullPlan.InvalidMetadata)
    }

    @Test
    fun `planPull returns InvalidMetadata when local remoteRevision is less than -1`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = -3L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = null
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remoteMetadata = null)

        assertTrue(plan is PullPlan.InvalidMetadata)
    }

    @Test
    fun `planPull returns InvalidMetadata when local baseRevision exceeds remoteRevision`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 4L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = null
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remoteMetadata = null)

        assertTrue(plan is PullPlan.InvalidMetadata)
        val reason = (plan as PullPlan.InvalidMetadata).reason
        assertTrue(reason.contains("cannot exceed local remoteRevision"))
    }

    @Test
    fun `planPull returns InvalidMetadata when local mixes -1 with known revisions`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = null
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remoteMetadata = null)

        assertTrue(plan is PullPlan.InvalidMetadata)
        val reason = (plan as PullPlan.InvalidMetadata).reason
        assertTrue(reason.contains("Incoherent local revisions"))
    }

    @Test
    fun `planPull returns InvalidMetadata when PENDING local entity has empty dirtyFields`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = emptySet(),
            lastMutationId = null
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remoteMetadata = null)

        assertTrue(plan is PullPlan.InvalidMetadata)
    }

    @Test
    fun `planPull returns InvalidMetadata when local has unsupported syncStatus`() {
        val local = LocalMutationContext(
            syncStatus = "UNKNOWN_STATUS",
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = emptySet(),
            lastMutationId = null
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remoteMetadata = null)

        assertTrue(plan is PullPlan.InvalidMetadata)
    }

    @Test
    fun `planPull returns InvalidMetadata when remote revision regresses below local remoteRevision for PENDING`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = null
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 3L,
            isDeleted = false,
            lastMutationId = "OTHER",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 3L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertTrue(plan is PullPlan.InvalidMetadata)
        val reason = (plan as PullPlan.InvalidMetadata).reason
        assertTrue(reason.contains("cannot be less than local remoteRevision"))
    }

    // ==================================================
    // 14. PREVENCIÓN DE REGRESIÓN: CONFLICTOS AMBIGUOS Y BASE REVISION CONGELADA
    // ==================================================

    @Test
    fun `planPull protects local PHOTO edit after first pull when subsequent pull has same remote revision (regression scenario)`() {
        // PASO 1: Local PENDING en base 5 con COMMENT dirty
        val localStep1 = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL_COMMENT"
        )

        // PASO 2: Remoto con revision 6 (PHOTO cambió a 6, COMMENT sigue en 5)
        val remoteRev6 = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_PHOTO",
            fieldRevisions = mapOf(
                BeerDirtyField.PHOTO to 6L,
                BeerDirtyField.COMMENT to 5L
            )
        )

        // PASO 3: Primer PULL: PHOTO se incorpora, remoteRevision pasa a 6, baseRevision se congela en 5
        val pull1Plan = SyncProtocolPullPlanner.planPull(localStep1, remoteRev6)
        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.PHOTO),
                remoteWinsConflictFields = emptySet(),
                localPendingFieldsToPreserve = setOf(BeerDirtyField.COMMENT),
                newRemoteRevision = 6L,
                newBaseRevision = 5L // Frozen: COMMENT sigue pendiente
            ),
            pull1Plan
        )

        // PASO 4: El usuario modifica PHOTO localmente tras el pull mientras COMMENT sigue pendiente
        val localStep4 = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.PHOTO),
            lastMutationId = "M_LOCAL_PHOTO"
        )

        // PASO 5: Segundo PULL contra el mismo remoto revision 6
        // RESULTADO REQUERIDO: No descartar PHOTO automáticamente como REMOTE WINS.
        // Identificar que el orden es ambiguo y emitir PendingFieldOrderNeedsReconciliation.
        val pull2Plan = SyncProtocolPullPlanner.planPull(localStep4, remoteRev6)
        assertEquals(
            PullPlan.PendingFieldOrderNeedsReconciliation(
                ambiguousFields = setOf(BeerDirtyField.PHOTO),
                localPendingFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.PHOTO),
                remoteRevision = 6L,
                localRemoteRevision = 6L,
                localBaseRevision = 5L
            ),
            pull2Plan
        )
    }

    @Test
    fun `planPull applies REMOTE WINS when remote modification is genuinely new with fieldRevision strictly greater than local remoteRevision`() {
        // Local PENDING con remoteRevision 6 y baseRevision 5 (congelada), con PHOTO y COMMENT dirty
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.PHOTO),
            lastMutationId = "M_LOCAL"
        )

        // Remoto publica revision 7 donde PHOTO avanzó a 7 (7 > local.remoteRevision 6)
        val remote = RemoteBeerSyncMetadata(
            revision = 7L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_NEW",
            fieldRevisions = mapOf(
                BeerDirtyField.PHOTO to 7L,
                BeerDirtyField.COMMENT to 5L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(
            local,
            remote,
            provenance = PullProvenance(origin = RemoteOrigin.EXTERNAL_CONFIRMED)
        )

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.PHOTO),
                remoteWinsConflictFields = setOf(BeerDirtyField.PHOTO),
                localPendingFieldsToPreserve = setOf(BeerDirtyField.COMMENT),
                newRemoteRevision = 7L,
                newBaseRevision = 5L // Frozen: COMMENT sigue pendiente sin cambios remotos
            ),
            plan
        )
    }

    @Test
    fun `planPull applies orthogonal remote change while preserving local dirty fields with frozen baseRevision`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL"
        )
        // Remoto en revision 7 modifica LOCATION (7L > 6L), COMMENT permanece en 5
        val remote = RemoteBeerSyncMetadata(
            revision = 7L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_LOC",
            fieldRevisions = mapOf(
                BeerDirtyField.LOCATION to 7L,
                BeerDirtyField.COMMENT to 5L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.LOCATION),
                remoteWinsConflictFields = emptySet(),
                localPendingFieldsToPreserve = setOf(BeerDirtyField.COMMENT),
                newRemoteRevision = 7L,
                newBaseRevision = 5L
            ),
            plan
        )
    }

    @Test
    fun `planPull safely halts with PendingFieldOrderNeedsReconciliation on mixed case with ambiguous and new remote fields`() {
        // Local: base 5, remoteRevision 6. Dirty: COMMENT, PHOTO, TYPE
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.PHOTO, BeerDirtyField.TYPE),
            lastMutationId = "M_LOCAL_MIXED"
        )
        // Remoto: revision 7
        // - COMMENT = 5 (safe local: <= baseRevision 5)
        // - PHOTO = 6 (ambiguous: baseRevision 5 < 6 <= remoteRevision 6)
        // - TYPE = 7 (genuinely new: 7 > remoteRevision 6)
        val remote = RemoteBeerSyncMetadata(
            revision = 7L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_MIXED",
            fieldRevisions = mapOf(
                BeerDirtyField.COMMENT to 5L,
                BeerDirtyField.PHOTO to 6L,
                BeerDirtyField.TYPE to 7L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(
            local,
            remote,
            provenance = PullProvenance(origin = RemoteOrigin.EXTERNAL_CONFIRMED)
        )

        // Se requiere detener la sincronización de forma segura sin sobrescribir ningún campo
        assertEquals(
            PullPlan.PendingFieldOrderNeedsReconciliation(
                ambiguousFields = setOf(BeerDirtyField.PHOTO),
                localPendingFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.PHOTO, BeerDirtyField.TYPE),
                remoteRevision = 7L,
                localRemoteRevision = 6L,
                localBaseRevision = 5L
            ),
            plan
        )
    }

    @Test
    fun `planPull returns NoOpAlreadyUpToDate on repeated pull when revisions match and fields are already integrated`() {
        // Local PENDING con remoteRevision 6 y baseRevision 5 (COMMENT dirty en base 5, PHOTO ya incorporado en rev 6)
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE",
            fieldRevisions = mapOf(
                BeerDirtyField.COMMENT to 5L,
                BeerDirtyField.PHOTO to 6L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(PullPlan.NoOpAlreadyUpToDate, plan)
    }

    @Test
    fun `planPull preserves tombstones and DELETE WINS across all states without regression`() {
        // Caso 1: Local DELETED frente a remoto activo con revisiones superiores -> PreserveLocalDelete
        val localDeleted = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 3L,
            baseRevision = 3L,
            dirtyFields = emptySet(),
            lastMutationId = "M_DEL"
        )
        val remoteActive = RemoteBeerSyncMetadata(
            revision = 8L,
            isDeleted = false,
            lastMutationId = "M_ACTIVE",
            fieldRevisions = mapOf(BeerDirtyField.PHOTO to 8L)
        )
        assertEquals(
            PullPlan.PreserveLocalDelete,
            SyncProtocolPullPlanner.planPull(localDeleted, remoteActive)
        )

        // Caso 2: Local PENDING con base congelada frente a remoto tombstone -> ApplyRemoteTombstone
        val localPending = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.PHOTO),
            lastMutationId = "M_EDIT"
        )
        val remoteTombstone = RemoteBeerSyncMetadata(
            revision = 9L,
            isDeleted = true,
            lastMutationId = "M_TOMBSTONE",
            fieldRevisions = emptyMap()
        )
        assertEquals(
            PullPlan.ApplyRemoteTombstone(newRemoteRevision = 9L),
            SyncProtocolPullPlanner.planPull(localPending, remoteTombstone)
        )

        // Caso 3: Local DELETED frente a remoto tombstone -> DeleteAlreadySatisfied
        val localDeleted2 = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 6L,
            baseRevision = 5L,
            dirtyFields = emptySet(),
            lastMutationId = "M_DEL_2"
        )
        assertEquals(
            PullPlan.DeleteAlreadySatisfied,
            SyncProtocolPullPlanner.planPull(localDeleted2, remoteTombstone)
        )
    }

    @Test
    fun `planPull advances newBaseRevision to remote revision only when all dirty fields are surrendered`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.TYPE),
            lastMutationId = "M_ALL_DIRTY"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_ALL",
            fieldRevisions = mapOf(
                BeerDirtyField.COMMENT to 6L,
                BeerDirtyField.TYPE to 6L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(
            local,
            remote,
            provenance = PullProvenance(origin = RemoteOrigin.EXTERNAL_CONFIRMED)
        )

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.COMMENT, BeerDirtyField.TYPE),
                remoteWinsConflictFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.TYPE),
                localPendingFieldsToPreserve = emptySet(),
                newRemoteRevision = 6L,
                newBaseRevision = 6L // Avanza a 6L porque localPendingFieldsToPreserve está vacío
            ),
            plan
        )
    }

    // ==================================================
    // 15. PROTECCIÓN ANTE CONFLICTOS DE ORIGEN/ORDEN INDETERMINADO Y ACKS PERDIDOS
    // ==================================================

    @Test
    fun `planPull protects local subsequent edit M2 when remote reflects confirmed M1 whose ACK was lost`() {
        // ESCENARIO OBLIGATORIO:
        // 1. Local en revisión 5 (base 5)
        // 2. Usuario editó COMMENT (mutación M1)
        // 3. Firestore confirmó M1 en revisión 6 con lastMutationId = M1
        // 4. Se perdió el ACK antes de actualizar Room
        // 5. Usuario vuelve a editar COMMENT generando mutación M2
        // 6. Room mantiene: PENDING, remoteRevision 5, baseRevision 5, dirty={COMMENT}, lastMutationId=M2
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M2"
        )
        // 7. Firestore devuelve: revision 6, fieldRevisions={COMMENT:6}, lastMutationId=M1
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M1",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )
        // Con evidencia de que M1 fue una mutación previa del propio dispositivo
        val provenance = PullProvenance(pastLocalMutationIds = setOf("M1"))

        val plan = SyncProtocolPullPlanner.planPull(local, remote, provenance)

        // RESULTADO REQUERIDO: Nunca descartar M2 automáticamente. Requerir reconciliación preservando M2.
        assertEquals(
            PullPlan.PendingConflictNeedsReconciliation(
                conflictingFields = setOf(BeerDirtyField.COMMENT),
                localPendingFields = setOf(BeerDirtyField.COMMENT),
                remoteRevision = 6L,
                localRemoteRevision = 5L,
                localBaseRevision = 5L,
                remoteLastMutationId = "M1",
                localLastMutationId = "M2",
                isKnownLostAck = true
            ),
            plan
        )
    }

    @Test
    fun `planPull halts safely with PendingConflictNeedsReconciliation when same field is edited remotely by another device without provenance evidence`() {
        // Mismo campo editado remotamente por otro dispositivo, sin evidencia de procedencia (origin = INDETERMINATE)
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_DEVICE_A"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_DEVICE_B",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        // Sin evidencia suficiente, se preserva el cambio local M_DEVICE_A y se bloquea el descarte
        assertEquals(
            PullPlan.PendingConflictNeedsReconciliation(
                conflictingFields = setOf(BeerDirtyField.COMMENT),
                localPendingFields = setOf(BeerDirtyField.COMMENT),
                remoteRevision = 6L,
                localRemoteRevision = 5L,
                localBaseRevision = 5L,
                remoteLastMutationId = "M_DEVICE_B",
                localLastMutationId = "M_DEVICE_A",
                isKnownLostAck = false
            ),
            plan
        )
    }

    @Test
    fun `planPull applies REMOTE CONFIRMED WINS when explicit verifiable provenance evidence is present`() {
        // Caso con evidencia explícita de origen externo posterior
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_EXTERNAL",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )
        val provenance = PullProvenance(origin = RemoteOrigin.EXTERNAL_CONFIRMED)

        val plan = SyncProtocolPullPlanner.planPull(local, remote, provenance)

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.COMMENT),
                remoteWinsConflictFields = setOf(BeerDirtyField.COMMENT),
                localPendingFieldsToPreserve = emptySet(),
                newRemoteRevision = 6L,
                newBaseRevision = 6L
            ),
            plan
        )
    }

    @Test
    fun `planPull returns UpdateLocalSynced when remote lastMutationId matches active local mutationId (exact idempotent pull)`() {
        // Local PENDING con M1 que aún no recibió ACK, pero usuario NO ha hecho cambios posteriores
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M1",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(
            PullPlan.UpdateLocalSynced(
                newRemoteRevision = 6L,
                fieldsToApply = emptySet()
            ),
            plan
        )
    }

    @Test
    fun `planPull applies orthogonal remote changes while preserving local pending edits without conflict`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE",
            fieldRevisions = mapOf(
                BeerDirtyField.LOCATION to 6L,
                BeerDirtyField.COMMENT to 5L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        assertEquals(
            PullPlan.MergeWithPending(
                remoteFieldsToApply = setOf(BeerDirtyField.LOCATION),
                remoteWinsConflictFields = emptySet(),
                localPendingFieldsToPreserve = setOf(BeerDirtyField.COMMENT),
                newRemoteRevision = 6L,
                newBaseRevision = 5L
            ),
            plan
        )
    }

    @Test
    fun `planPull halts safely on mixed case with indeterminate conflict and orthogonal change without modifying any fields`() {
        // Local dirty: COMMENT con M2
        // Remote: COMMENT = 6 (conflicto indeterminado con M1), LOCATION = 6 (ortogonal)
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M2"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M1",
            fieldRevisions = mapOf(
                BeerDirtyField.COMMENT to 6L,
                BeerDirtyField.LOCATION to 6L
            )
        )

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

        // Regla estricta: ninguna modificación parcial mientras haya reconciliación bloqueada
        assertEquals(
            PullPlan.PendingConflictNeedsReconciliation(
                conflictingFields = setOf(BeerDirtyField.COMMENT),
                localPendingFields = setOf(BeerDirtyField.COMMENT),
                remoteRevision = 6L,
                localRemoteRevision = 5L,
                localBaseRevision = 5L,
                remoteLastMutationId = "M1",
                localLastMutationId = "M2",
                isKnownLostAck = false
            ),
            plan
        )
    }
}
