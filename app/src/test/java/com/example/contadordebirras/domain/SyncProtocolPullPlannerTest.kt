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

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

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

        val plan = SyncProtocolPullPlanner.planPull(local, remote)

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
}
