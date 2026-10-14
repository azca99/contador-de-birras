package com.example.contadordebirras.domain

import com.example.contadordebirras.data.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolPushPlannerTest {

    // ==================================================
    // 1. CREACIÓN NUEVA (CREATE ACTIVE)
    // ==================================================

    @Test
    fun `planPush creates active document when remote is null and entity has ALL_FIELDS dirty`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertEquals(
            PushPlan.CreateActive(
                nextRevision = FIRST_PROTOCOL_REVISION,
                fieldsToWrite = BeerDirtyFieldsCodec.ALL_FIELDS
            ),
            plan
        )
    }

    @Test
    fun `planPush rejects creation with InvalidMetadata when dirtyFields are incomplete`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.TYPE),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertTrue(plan is PushPlan.InvalidMetadata)
        val reason = (plan as PushPlan.InvalidMetadata).reason
        assertTrue(reason.contains("incomplete dirty fields"))
    }

    // ==================================================
    // 2. REMOTE AUSENTE CON ENTIDAD CONOCIDA (ANTI-RESURRECCIÓN)
    // ==================================================

    @Test
    fun `planPush returns RemoteAbsentForKnownEntity when remote is null for entity with positive revisions`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 4L,
            baseRevision = 4L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertEquals(PushPlan.RemoteAbsentForKnownEntity, plan)
    }

    @Test
    fun `planPush returns RemoteAbsentForKnownEntity when remote is null for legacy 0 0 entity`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LEGACY_ABSENT"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertEquals(PushPlan.RemoteAbsentForKnownEntity, plan)
    }

    // ==================================================
    // 3. INVARIANTES DE REVISIONES LOCALES Y REMOTAS
    // ==================================================

    @Test
    fun `planPush returns InvalidMetadata when baseRevision is greater than remoteRevision`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 3L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertTrue(plan is PushPlan.InvalidMetadata)
        val reason = (plan as PushPlan.InvalidMetadata).reason
        assertTrue(reason.contains("cannot exceed local remoteRevision"))
    }

    @Test
    fun `planPush returns InvalidMetadata when local mixes -1 and known revision (remote -1, base 0)`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns InvalidMetadata when local mixes -1 and known revision (remote 2, base -1)`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertTrue(plan is PushPlan.InvalidMetadata)
        val reason = (plan as PushPlan.InvalidMetadata).reason
        assertTrue(reason.contains("Incoherent local revisions"))
    }

    @Test
    fun `planPush returns InvalidMetadata when remote revision is less than local remoteRevision`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 3L,
            isDeleted = false,
            lastMutationId = "OTHER",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 3L)
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.InvalidMetadata)
        val reason = (plan as PushPlan.InvalidMetadata).reason
        assertTrue(reason.contains("cannot be less than local remoteRevision"))
    }

    @Test
    fun `planPush returns InvalidMetadata when local is local-only but remote exists with different mutationId`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_LOCAL_NEW"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_OTHER",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.InvalidMetadata)
        val reason = (plan as PushPlan.InvalidMetadata).reason
        assertTrue(reason.contains("already exists with revision"))
    }

    // ==================================================
    // 4. LEGACY REMOTO
    // ==================================================

    @Test
    fun `planPush applies patch against legacy remote with revision 0 and empty fieldRevisions`() {
        val remote = RemoteBeerSyncMetadata(
            revision = LEGACY_REMOTE_REVISION,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = emptyMap()
        )
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LEGACY"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(
            PushPlan.ApplyPatch(
                nextRevision = 1L,
                fieldsToWrite = setOf(BeerDirtyField.COMMENT),
                remoteWinsFields = emptySet()
            ),
            plan
        )
    }

    // ==================================================
    // 5. CAMBIOS ORTOGONALES
    // ==================================================

    @Test
    fun `planPush merges orthogonal changes when remote modified different field`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_ORTHO"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_OTHER",
            fieldRevisions = mapOf(
                BeerDirtyField.LOCATION to 6L,
                BeerDirtyField.COMMENT to 5L
            )
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(
            PushPlan.ApplyPatch(
                nextRevision = 7L,
                fieldsToWrite = setOf(BeerDirtyField.COMMENT),
                remoteWinsFields = emptySet()
            ),
            plan
        )
    }

    // ==================================================
    // 6. MISMO CAMPO (REMOTE WINS)
    // ==================================================

    @Test
    fun `planPush lets remote win when the same field was modified remotely since baseRevision`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_SAME"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_SAME",
            fieldRevisions = mapOf(
                BeerDirtyField.COMMENT to 6L
            )
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(
            PushPlan.RemoteWinsOnly(
                remoteWinsFields = setOf(BeerDirtyField.COMMENT)
            ),
            plan
        )
    }

    // ==================================================
    // 7. CONFLICTO MIXTO
    // ==================================================

    @Test
    fun `planPush writes safe fields and yields conflicting fields in mixed mutation`() {
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
                BeerDirtyField.LOCATION to 5L
            )
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(
            PushPlan.ApplyPatch(
                nextRevision = 8L,
                fieldsToWrite = setOf(BeerDirtyField.LOCATION),
                remoteWinsFields = setOf(BeerDirtyField.COMMENT)
            ),
            plan
        )
    }

    // ==================================================
    // 8. VARIOS CAMBIOS REMOTOS
    // ==================================================

    @Test
    fun `planPush handles complex multi-field remote updates accurately`() {
        val remote = RemoteBeerSyncMetadata(
            revision = 10L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_MULTI",
            fieldRevisions = mapOf(
                BeerDirtyField.TYPE to 7L,
                BeerDirtyField.COMMENT to 10L,
                BeerDirtyField.LOCATION to 8L,
                BeerDirtyField.PHOTO to 4L
            )
        )
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 6L,
            baseRevision = 6L,
            dirtyFields = setOf(BeerDirtyField.TYPE, BeerDirtyField.COMMENT, BeerDirtyField.PHOTO),
            lastMutationId = "M_LOCAL_MULTI"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(
            PushPlan.ApplyPatch(
                nextRevision = 11L,
                fieldsToWrite = setOf(BeerDirtyField.PHOTO),
                remoteWinsFields = setOf(BeerDirtyField.TYPE, BeerDirtyField.COMMENT)
            ),
            plan
        )
    }

    // ==================================================
    // 9. REMOTE TOMBSTONE
    // ==================================================

    @Test
    fun `planPush yields to remote tombstone when local has pending edits`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 3L,
            baseRevision = 3L,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.LOCATION),
            lastMutationId = "M_RESURRECT"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = true,
            lastMutationId = "M_DELETE_REMOTE",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(PushPlan.RemoteTombstoneWins, plan)
    }

    // ==================================================
    // 10. DELETE SOBRE ACTIVO (DELETE WINS)
    // ==================================================

    @Test
    fun `planPush writes tombstone when local is deleted even if remote has advanced revision`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 3L,
            baseRevision = 3L,
            dirtyFields = emptySet(),
            lastMutationId = "M_DELETE_LOCAL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 8L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_EDIT",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 8L)
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(
            PushPlan.WriteTombstone(nextRevision = 9L),
            plan
        )
    }

    @Test
    fun `planPush writes tombstone when local is deleted regardless of dirtyFields contents`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_DELETE_WITH_DIRTY"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 4L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_ACTIVE",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(
            PushPlan.WriteTombstone(nextRevision = 5L),
            plan
        )
    }

    // ==================================================
    // 11. DELETE SOBRE TOMBSTONE O REMOTE AUSENTE
    // ==================================================

    @Test
    fun `planPush returns DeleteAlreadySatisfied when remote is already a tombstone with different mutationId`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 3L,
            baseRevision = 3L,
            dirtyFields = emptySet(),
            lastMutationId = "M_LOCAL_DEL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = true,
            lastMutationId = "M_REMOTE_DEL",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(PushPlan.DeleteAlreadySatisfied, plan)
    }

    @Test
    fun `planPush returns DeleteAlreadySatisfied when remote is null for local-only entity`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = emptySet(),
            lastMutationId = "M_LOCAL_DEL"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertEquals(PushPlan.DeleteAlreadySatisfied, plan)
    }

    @Test
    fun `planPush returns DeleteAlreadySatisfied when remote is null for previously known entity`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 3L,
            baseRevision = 3L,
            dirtyFields = emptySet(),
            lastMutationId = "M_LOCAL_DEL"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertEquals(PushPlan.DeleteAlreadySatisfied, plan)
    }

    // ==================================================
    // 12. IDEMPOTENCIA
    // ==================================================

    @Test
    fun `planPush returns AlreadyApplied when active remote has matching lastMutationId`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "ABC"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "ABC",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(PushPlan.AlreadyApplied, plan)
    }

    @Test
    fun `planPush returns AlreadyApplied when tombstone remote has matching lastMutationId with local DELETED`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = emptySet(),
            lastMutationId = "ABC"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = true,
            lastMutationId = "ABC",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(PushPlan.AlreadyApplied, plan)
    }

    @Test
    fun `planPush returns AlreadyApplied when tombstone remote has matching lastMutationId with local PENDING`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "ABC"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 6L,
            isDeleted = true,
            lastMutationId = "ABC",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(PushPlan.AlreadyApplied, plan)
    }

    @Test
    fun `planPush returns AlreadyApplied on idempotent retry of local-only creation`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_NEW"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_NEW",
            fieldRevisions = mapOf(BeerDirtyField.TYPE to 1L)
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(PushPlan.AlreadyApplied, plan)
    }

    @Test
    fun `planPush does not consider null mutationIds as matching`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = null
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 2L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.ApplyPatch)
    }

    // ==================================================
    // 13. TESTS DE METADATA INVÁLIDA
    // ==================================================

    @Test
    fun `planPush returns InvalidMetadata when remote revision is negative`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = -1L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns InvalidMetadata when field revision is negative`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to -2L)
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns InvalidMetadata when field revision exceeds remote revision`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 2L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns InvalidMetadata when baseRevision is less than LOCAL_ONLY_REVISION`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = -2L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns InvalidMetadata when remoteRevision is less than LOCAL_ONLY_REVISION`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = -3L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns InvalidMetadata on Long MAX_VALUE when increment is required for patch`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = Long.MAX_VALUE,
            isDeleted = false,
            lastMutationId = "OTHER",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 5L)
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns RemoteWinsOnly on Long MAX_VALUE when no safe fields exist to write`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = Long.MAX_VALUE,
            isDeleted = false,
            lastMutationId = "OTHER",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to Long.MAX_VALUE)
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertEquals(
            PushPlan.RemoteWinsOnly(remoteWinsFields = setOf(BeerDirtyField.COMMENT)),
            plan
        )
    }

    @Test
    fun `planPush returns InvalidMetadata on Long MAX_VALUE when increment is required for tombstone`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = emptySet(),
            lastMutationId = "M_DEL"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = Long.MAX_VALUE,
            isDeleted = false,
            lastMutationId = "OTHER",
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns InvalidMetadata when PENDING entity has empty dirtyFields`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = emptySet(),
            lastMutationId = "M1"
        )
        val remote = RemoteBeerSyncMetadata(
            revision = 1L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = emptyMap()
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    @Test
    fun `planPush returns InvalidMetadata when syncStatus is neither PENDING nor DELETED`() {
        val local = LocalMutationContext(
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M1"
        )

        val plan = SyncProtocolPushPlanner.planPush(local, remote = null)

        assertTrue(plan is PushPlan.InvalidMetadata)
    }

    // ==================================================
    // 14. ADVANCE FIELD REVISIONS HELPER
    // ==================================================

    @Test
    fun `advanceFieldRevisions updates only written fields and preserves unchanged fields`() {
        val current = mapOf(
            BeerDirtyField.COMMENT to 5L,
            BeerDirtyField.LOCATION to 6L
        )
        val written = setOf(BeerDirtyField.COMMENT)

        val updated = SyncProtocolPushPlanner.advanceFieldRevisions(
            current = current,
            writtenFields = written,
            nextRevision = 7L
        )

        assertEquals(7L, updated[BeerDirtyField.COMMENT])
        assertEquals(6L, updated[BeerDirtyField.LOCATION])
    }

    @Test
    fun `advanceFieldRevisions does not mutate original map`() {
        val current = mapOf(
            BeerDirtyField.COMMENT to 5L,
            BeerDirtyField.LOCATION to 6L
        )

        val updated = SyncProtocolPushPlanner.advanceFieldRevisions(
            current = current,
            writtenFields = setOf(BeerDirtyField.COMMENT),
            nextRevision = 7L
        )

        assertNotSame(current, updated)
        assertEquals(5L, current[BeerDirtyField.COMMENT])
    }

    @Test
    fun `advanceFieldRevisions advances multiple fields to the same nextRevision`() {
        val current = mapOf(
            BeerDirtyField.TYPE to 2L,
            BeerDirtyField.LOCATION to 4L,
            BeerDirtyField.PHOTO to 1L
        )
        val written = setOf(BeerDirtyField.TYPE, BeerDirtyField.LOCATION)

        val updated = SyncProtocolPushPlanner.advanceFieldRevisions(
            current = current,
            writtenFields = written,
            nextRevision = 9L
        )

        assertEquals(9L, updated[BeerDirtyField.TYPE])
        assertEquals(9L, updated[BeerDirtyField.LOCATION])
        assertEquals(1L, updated[BeerDirtyField.PHOTO])
    }

    @Test
    fun `advanceFieldRevisions introduces new field not previously in map`() {
        val current = emptyMap<BeerDirtyField, Long>()
        val written = setOf(BeerDirtyField.LOCATION)

        val updated = SyncProtocolPushPlanner.advanceFieldRevisions(
            current = current,
            writtenFields = written,
            nextRevision = 1L
        )

        assertEquals(1L, updated[BeerDirtyField.LOCATION])
    }

    @Test
    fun `advanceFieldRevisions with empty writtenFields returns equivalent map`() {
        val current = mapOf(BeerDirtyField.COMMENT to 3L)

        val updated = SyncProtocolPushPlanner.advanceFieldRevisions(
            current = current,
            writtenFields = emptySet(),
            nextRevision = 5L
        )

        assertEquals(current, updated)
    }
}
