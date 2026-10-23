package com.example.contadordebirras.domain

import com.example.contadordebirras.data.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de pruebas unitarias puras en la JVM para LegacyBeerClassifier (Fase C4.1A).
 *
 * Valida de forma hermética todas las reglas de clasificación segura sin dependencias
 * de Android, Room, Firebase ni WorkManager.
 */
class LegacyBeerClassifierTest {

    private val defaultSyncId = "sync-test-uuid-1234"
    private val activeUserUid = "user_test_active_uid"

    // -------------------------------------------------------------------------
    // 1. REGLA 1: LEGACY 0/0 Y CREACIÓN SEGURA (PENDING 0/0 + ALL_FIELDS)
    // -------------------------------------------------------------------------

    @Test
    fun `legacy pending 0-0 with ALL_FIELDS without positive proof of unsynced creation blocks with NeedsReconciliation`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_LEGACY_1",
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = false,
            hasPriorSyncHistory = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertTrue(classification is LegacyClassification.NeedsReconciliation)
        val needsRecon = classification as LegacyClassification.NeedsReconciliation
        assertEquals("PENDING", needsRecon.localSyncStatus)
        assertEquals(false, needsRecon.remoteObserved)
    }

    @Test
    fun `legacy pending 0-0 with ALL_FIELDS with proven unsynced creation promotes to LocalOnlyCreation`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_PROVEN_NEW",
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = true,
            hasPriorSyncHistory = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertEquals(
            LegacyClassification.PromoteToLocalOnlyCreation(initialMutationId = "M_PROVEN_NEW"),
            classification
        )
    }

    // -------------------------------------------------------------------------
    // 2. REGLA 2: REMOTO AUSENTE Y ENTIDADES PREVIAMENTE SINCRONIZADAS
    // -------------------------------------------------------------------------

    @Test
    fun `legacy pending 0-0 with partial dirtyFields and absent remote halts as RemoteAbsentForPreviouslySyncedEntity`() {
        // Entidad con solo COMMENT dirty: forzosamente existió antes y se editó localmente.
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_EDIT_COMMENT",
            hasRemotePhotoUrl = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.RemoteAbsentForPreviouslySyncedEntity)
    }

    @Test
    fun `entity with prior sync history in evidence is classified as RemoteAbsentForPreviouslySyncedEntity when absent remotely`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_WITH_HISTORY",
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = false,
            hasPriorSyncHistory = true
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertTrue(classification is LegacyClassification.RemoteAbsentForPreviouslySyncedEntity)
    }

    @Test
    fun `entity with remotePhotoUrl is classified as RemoteAbsentForPreviouslySyncedEntity when absent remotely`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_WITH_PHOTO_URL",
            hasRemotePhotoUrl = true // Certifica que ya tuvo foto remota en Storage
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.RemoteAbsentForPreviouslySyncedEntity)
    }

    @Test
    fun `absent remote with incomplete read scope yields IgnoreIncompleteRead`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_PENDING"
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.INCOMPLETE_OR_UNCONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(LegacyClassification.IgnoreIncompleteRead, classification)
    }

    // -------------------------------------------------------------------------
    // 3. REGLA 4: ACK PERDIDO DE MUTACIÓN PROPIA
    // -------------------------------------------------------------------------

    @Test
    fun `matching lastMutationId in active remote document yields PotentialOwnMutationAck`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.TYPE, BeerDirtyField.COMMENT),
            lastMutationId = "M_MUTATION_10"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_MUTATION_10",
            fieldRevisions = mapOf(
                BeerDirtyField.TYPE to 1L,
                BeerDirtyField.COMMENT to 1L
            )
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.PotentialOwnMutationAck(
                matchedMutationId = "M_MUTATION_10",
                isModern = true,
                remoteRevision = 1L
            ),
            classification
        )
    }

    @Test
    fun `matching pastLocalMutationId in evidence yields PotentialOwnMutationAck for lost ACK with subsequent edit`() {
        // Local tiene M2 activa, pero remoto confirma M1 que está en el historial pasado
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL_2"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_LOCAL_1",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 1L)
        )
        val evidence = LegacyHistoricalEvidence(
            pastLocalMutationIds = setOf("M_LOCAL_0", "M_LOCAL_1")
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertEquals(
            LegacyClassification.PotentialOwnMutationAck(
                matchedMutationId = "M_LOCAL_1",
                isModern = true,
                remoteRevision = 1L
            ),
            classification
        )
    }

    @Test
    fun `lost ACK with incomplete metadata delegates to PotentialOwnMutationAck without assuming complete write`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_ALL"
        )
        // Remoto tiene el mismo mutationId pero solo tiene fieldRevisions para 2 campos
        val remote = LegacyRemoteSnapshot.Present(
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_ALL",
            fieldRevisions = mapOf(
                BeerDirtyField.TYPE to 1L,
                BeerDirtyField.COMMENT to 1L
            )
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.PotentialOwnMutationAck(
                matchedMutationId = "M_ALL",
                isModern = true,
                remoteRevision = 1L
            ),
            classification
        )
    }

    // -------------------------------------------------------------------------
    // 4. REGLA 5: DOCUMENTOS LEGACY Y MODERNOS EXISTENTES
    // -------------------------------------------------------------------------

    @Test
    fun `active legacy remote document with revision 0 yields LegacyRemoteActive`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL_EDIT"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 0L,
            isDeleted = false,
            lastMutationId = null,
            fieldRevisions = emptyMap()
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.LegacyRemoteActive(remoteLastMutationId = null),
            classification
        )
    }

    @Test
    fun `active modern remote document with different mutationId against legacy PENDING 0-0 halts with NeedsReconciliation`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 2L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_OTHER",
            fieldRevisions = mapOf(BeerDirtyField.LOCATION to 2L)
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.NeedsReconciliation)
        val needsRecon = classification as LegacyClassification.NeedsReconciliation
        assertEquals(SyncStatus.PENDING, needsRecon.localSyncStatus)
        assertEquals(true, needsRecon.remoteObserved)
    }

    @Test
    fun `active modern remote document with different mutationId against legacy SYNCED 0-0 yields ModernRemoteActive`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = emptySet(),
            lastMutationId = "M_LOCAL_OLD"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 2L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_OTHER",
            fieldRevisions = mapOf(BeerDirtyField.LOCATION to 2L)
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.ModernRemoteActive(
                remoteRevision = 2L,
                remoteLastMutationId = "M_REMOTE_OTHER"
            ),
            classification
        )
    }

    // -------------------------------------------------------------------------
    // 5. REGLA 6: AISLAMIENTO ESTRICTO DE OWNER UID
    // -------------------------------------------------------------------------

    @Test
    fun `local entity belonging to guest_local is preserved as PreserveIsolatedGuestOrUnassigned`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = LegacyBeerClassifier.GUEST_OWNER_UID,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_GUEST"
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.PreserveIsolatedGuestOrUnassigned)
        val guestResult = classification as LegacyClassification.PreserveIsolatedGuestOrUnassigned
        assertEquals(LegacyBeerClassifier.GUEST_OWNER_UID, guestResult.ownerUid)
    }

    @Test
    fun `local entity belonging to legacy_unassigned is preserved as PreserveIsolatedGuestOrUnassigned`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = LegacyBeerClassifier.LEGACY_UNASSIGNED_UID,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_UNASSIGNED"
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.PreserveIsolatedGuestOrUnassigned)
        val unassignedResult = classification as LegacyClassification.PreserveIsolatedGuestOrUnassigned
        assertEquals(LegacyBeerClassifier.LEGACY_UNASSIGNED_UID, unassignedResult.ownerUid)
    }

    @Test
    fun `local entity belonging to different authenticated user yields OwnershipMismatch`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = "other_user_uid_999",
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_OTHER_USER"
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.OwnershipMismatch)
        val mismatch = classification as LegacyClassification.OwnershipMismatch
        assertEquals("other_user_uid_999", mismatch.localOwnerUid)
        assertEquals(activeUserUid, mismatch.expectedOwnerUid)
    }

    // -------------------------------------------------------------------------
    // 6. REGLA 7: BORRADOS Y TOMBSTONES (DELETE WINS)
    // -------------------------------------------------------------------------

    @Test
    fun `remote tombstone prevails over active local entity`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_PENDING"
        )
        val remoteTombstone = LegacyRemoteSnapshot.Present(
            revision = 3L,
            isDeleted = true,
            lastMutationId = "M_TOMBSTONE"
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remoteTombstone,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.RemoteTombstonePrevailed(
                remoteRevision = 3L,
                isModern = true
            ),
            classification
        )
    }

    @Test
    fun `local DELETED against active remote document yields LocalDeleteWins`() {
        val localDeleted = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = emptySet(),
            lastMutationId = "M_LOCAL_DEL"
        )
        val remoteActive = LegacyRemoteSnapshot.Present(
            revision = 2L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_ACTIVE"
        )

        val classification = LegacyBeerClassifier.classify(
            local = localDeleted,
            remote = remoteActive,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.LocalDeleteWins(
                remoteExists = true,
                remoteRevision = 2L
            ),
            classification
        )
    }

    @Test
    fun `local DELETED against remote tombstone yields DeleteAlreadySatisfied`() {
        val localDeleted = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = emptySet(),
            lastMutationId = "M_LOCAL_DEL"
        )
        val remoteTombstone = LegacyRemoteSnapshot.Present(
            revision = 4L,
            isDeleted = true,
            lastMutationId = "M_TOMB"
        )

        val classification = LegacyBeerClassifier.classify(
            local = localDeleted,
            remote = remoteTombstone,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(LegacyClassification.DeleteAlreadySatisfied, classification)
    }

    @Test
    fun `local DELETED against absent remote under authoritative read yields DeleteAlreadySatisfied`() {
        val localDeleted = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = emptySet(),
            lastMutationId = "M_LOCAL_DEL"
        )

        val classification = LegacyBeerClassifier.classify(
            local = localDeleted,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(LegacyClassification.DeleteAlreadySatisfied, classification)
    }

    // -------------------------------------------------------------------------
    // 7. VALIDACIÓN Y DETECCIÓN DE DATOS AMBIGUOS / INVALID METADATA
    // -------------------------------------------------------------------------

    @Test
    fun `incoherent local revisions (base greater than remote) yields InvalidMetadata`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 1L, // Incoherente: base > remote
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_INV"
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.InvalidMetadata)
    }

    @Test
    fun `local PENDING with empty dirtyFields yields InvalidMetadata`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = emptySet(), // Incoherente: PENDING sin dirty fields
            lastMutationId = "M_EMPTY"
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.InvalidMetadata)
    }

    @Test
    fun `remote negative revision yields InvalidMetadata`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_1"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = -2L, // Incoherente
            isDeleted = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.InvalidMetadata)
    }

    @Test
    fun `remote fieldRevision exceeding document revision yields InvalidMetadata`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_1"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 2L,
            isDeleted = false,
            fieldRevisions = mapOf(BeerDirtyField.PHOTO to 3L) // Incoherente: 3 > 2
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.InvalidMetadata)
    }

    // -------------------------------------------------------------------------
    // 8. MATRIZ DE ESCENARIOS DE CLASIFICACIÓN REUTILIZABLE
    // -------------------------------------------------------------------------

    data class MatrixScenario(
        val name: String,
        val localStatus: String,
        val dirtyFields: Set<BeerDirtyField>,
        val remoteSnapshot: LegacyRemoteSnapshot,
        val readScope: RemoteReadScope,
        val evidence: LegacyHistoricalEvidence,
        val expectedClassificationClass: Class<out LegacyClassification>
    )

    @Test
    fun `matrix of legacy scenarios satisfies deterministic invariants`() {
        val scenarios = listOf(
            MatrixScenario(
                name = "Pending 0/0 All Fields absent without proof -> NeedsReconciliation",
                localStatus = SyncStatus.PENDING,
                dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
                remoteSnapshot = LegacyRemoteSnapshot.Absent,
                readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
                evidence = LegacyHistoricalEvidence(isProvenNeverSyncedCreation = false),
                expectedClassificationClass = LegacyClassification.NeedsReconciliation::class.java
            ),
            MatrixScenario(
                name = "Pending 0/0 All Fields absent with proven creation -> PromoteToLocalOnlyCreation",
                localStatus = SyncStatus.PENDING,
                dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
                remoteSnapshot = LegacyRemoteSnapshot.Absent,
                readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
                evidence = LegacyHistoricalEvidence(isProvenNeverSyncedCreation = true),
                expectedClassificationClass = LegacyClassification.PromoteToLocalOnlyCreation::class.java
            ),
            MatrixScenario(
                name = "Pending 0/0 Partial Fields absent -> RemoteAbsentForPreviouslySyncedEntity",
                localStatus = SyncStatus.PENDING,
                dirtyFields = setOf(BeerDirtyField.COMMENT),
                remoteSnapshot = LegacyRemoteSnapshot.Absent,
                readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
                evidence = LegacyHistoricalEvidence(),
                expectedClassificationClass = LegacyClassification.RemoteAbsentForPreviouslySyncedEntity::class.java
            ),
            MatrixScenario(
                name = "Absent with incomplete read -> IgnoreIncompleteRead",
                localStatus = SyncStatus.PENDING,
                dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
                remoteSnapshot = LegacyRemoteSnapshot.Absent,
                readScope = RemoteReadScope.INCOMPLETE_OR_UNCONFIRMED,
                evidence = LegacyHistoricalEvidence(),
                expectedClassificationClass = LegacyClassification.IgnoreIncompleteRead::class.java
            ),
            MatrixScenario(
                name = "Remote is Tombstone -> RemoteTombstonePrevailed",
                localStatus = SyncStatus.PENDING,
                dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
                remoteSnapshot = LegacyRemoteSnapshot.Present(revision = 1L, isDeleted = true),
                readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
                evidence = LegacyHistoricalEvidence(),
                expectedClassificationClass = LegacyClassification.RemoteTombstonePrevailed::class.java
            ),
            MatrixScenario(
                name = "Local is DELETED with active remote -> LocalDeleteWins",
                localStatus = SyncStatus.DELETED,
                dirtyFields = emptySet(),
                remoteSnapshot = LegacyRemoteSnapshot.Present(revision = 1L, isDeleted = false),
                readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
                evidence = LegacyHistoricalEvidence(),
                expectedClassificationClass = LegacyClassification.LocalDeleteWins::class.java
            ),
            MatrixScenario(
                name = "Local is DELETED with tombstone remote -> DeleteAlreadySatisfied",
                localStatus = SyncStatus.DELETED,
                dirtyFields = emptySet(),
                remoteSnapshot = LegacyRemoteSnapshot.Present(revision = 1L, isDeleted = true),
                readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
                evidence = LegacyHistoricalEvidence(),
                expectedClassificationClass = LegacyClassification.DeleteAlreadySatisfied::class.java
            )
        )

        for (scenario in scenarios) {
            val local = LegacyLocalEntityContext(
                syncId = defaultSyncId,
                ownerUid = activeUserUid,
                syncStatus = scenario.localStatus,
                remoteRevision = 0L,
                baseRevision = 0L,
                dirtyFields = scenario.dirtyFields,
                lastMutationId = "M_MATRIX"
            )

            val result = LegacyBeerClassifier.classify(
                local = local,
                remote = scenario.remoteSnapshot,
                readScope = scenario.readScope,
                expectedOwnerUid = activeUserUid,
                evidence = scenario.evidence
            )

            assertEquals(
                "Failed for scenario: ${scenario.name}",
                scenario.expectedClassificationClass,
                result.javaClass
            )
        }
    }

    // -------------------------------------------------------------------------
    // 9. SOBRECARGA CON RemoteBeerSyncMetadata
    // -------------------------------------------------------------------------

    @Test
    fun `convenience overload with RemoteBeerSyncMetadata converts accurately`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_OVERLOAD"
        )
        val remoteMetadata = RemoteBeerSyncMetadata(
            revision = 5L,
            isDeleted = false,
            lastMutationId = "M_OVERLOAD",
            fieldRevisions = mapOf(BeerDirtyField.PHOTO to 5L)
        )

        val result = LegacyBeerClassifier.classify(
            local = local,
            remoteMetadata = remoteMetadata,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.PotentialOwnMutationAck(
                matchedMutationId = "M_OVERLOAD",
                isModern = true,
                remoteRevision = 5L
            ),
            result
        )
    }

    // -------------------------------------------------------------------------
    // 10. TESTS OBLIGATORIOS DE AUDITORÍA C4.1A (CASOS 1 A 8)
    // -------------------------------------------------------------------------

    @Test
    fun `audit test 1 - pending 5-5 with isProvenNeverSyncedCreation true and absent remote does not promote`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 5L,
            baseRevision = 5L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_LOCAL_5",
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = true,
            hasPriorSyncHistory = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertTrue(classification is LegacyClassification.NeedsReconciliation)
    }

    @Test
    fun `audit test 2 - synced 0-0 with ALL_FIELDS and isProvenNeverSyncedCreation true does not promote`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_SYNCED_0",
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = true,
            hasPriorSyncHistory = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertTrue(
            classification is LegacyClassification.NeedsReconciliation ||
            classification is LegacyClassification.RemoteAbsentForPreviouslySyncedEntity
        )
        assertTrue(classification !is LegacyClassification.PromoteToLocalOnlyCreation)
    }

    @Test
    fun `audit test 3 - pending 0-0 ALL_FIELDS with proven creation, absent remote, and valid lastMutationId promotes`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_VALID_PROVEN",
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = true,
            hasPriorSyncHistory = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertEquals(
            LegacyClassification.PromoteToLocalOnlyCreation(initialMutationId = "M_VALID_PROVEN"),
            classification
        )
    }

    @Test
    fun `audit test 4 - pending 0-0 ALL_FIELDS with proven creation, absent remote, and null lastMutationId yields NeedsReconciliation`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = null, // Nulo: no debe usar "CREATION_INITIAL" ni UUID sintético
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = true,
            hasPriorSyncHistory = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertTrue(classification is LegacyClassification.NeedsReconciliation)
        val needsRecon = classification as LegacyClassification.NeedsReconciliation
        assertEquals(SyncStatus.PENDING, needsRecon.localSyncStatus)
        assertEquals(false, needsRecon.remoteObserved)
        assertTrue(needsRecon.reason.contains("lastMutationId"))
    }

    @Test
    fun `audit test 5 - pending 0-0 COMMENT dirty against modern remote rev 6 COMMENT 6 with diff mutation yields NeedsReconciliation`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL_EDIT",
            hasRemotePhotoUrl = false
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_REMOTE_OTHER",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.NeedsReconciliation)
        val needsRecon = classification as LegacyClassification.NeedsReconciliation
        assertEquals(SyncStatus.PENDING, needsRecon.localSyncStatus)
        assertEquals(true, needsRecon.remoteObserved)
    }

    @Test
    fun `audit test 6 - pending 0-0 COMMENT dirty against modern remote rev 6 with matching own mutation yields PotentialOwnMutationAck`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_OWN_MUTATION",
            hasRemotePhotoUrl = false
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_OWN_MUTATION",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.PotentialOwnMutationAck(
                matchedMutationId = "M_OWN_MUTATION",
                isModern = true,
                remoteRevision = 6L
            ),
            classification
        )
    }

    @Test
    fun `audit test 6b - pending 0-0 COMMENT dirty against modern remote rev 6 matching pastLocalMutationId yields PotentialOwnMutationAck`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LATER_LOCAL_MUTATION",
            hasRemotePhotoUrl = false
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 6L,
            isDeleted = false,
            lastMutationId = "M_PAST_LOCAL_MUTATION",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 6L)
        )
        val evidence = LegacyHistoricalEvidence(
            pastLocalMutationIds = setOf("M_PAST_LOCAL_MUTATION")
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertEquals(
            LegacyClassification.PotentialOwnMutationAck(
                matchedMutationId = "M_PAST_LOCAL_MUTATION",
                isModern = true,
                remoteRevision = 6L
            ),
            classification
        )
    }

    @Test
    fun `audit test 7a - contradictory evidence with priorSyncHistory yields NeedsReconciliation`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_CONTRADICT_1",
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = true,
            hasPriorSyncHistory = true // Contradicción directa
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertTrue(classification is LegacyClassification.NeedsReconciliation)
        val needsRecon = classification as LegacyClassification.NeedsReconciliation
        assertTrue(needsRecon.reason.contains("Contradictory evidence"))
    }

    @Test
    fun `audit test 7b - contradictory evidence with positive revisions yields NeedsReconciliation`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L, // Contradicción: revisiones positivas
            baseRevision = 2L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_CONTRADICT_2",
            hasRemotePhotoUrl = false
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = true,
            hasPriorSyncHistory = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertTrue(classification is LegacyClassification.NeedsReconciliation)
        val needsRecon = classification as LegacyClassification.NeedsReconciliation
        assertTrue(needsRecon.reason.contains("Contradictory evidence"))
    }

    @Test
    fun `audit test 7c - contradictory evidence with remotePhotoUrl yields NeedsReconciliation`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_CONTRADICT_3",
            hasRemotePhotoUrl = true // Contradicción: tiene URL remota en Storage
        )
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = true,
            hasPriorSyncHistory = false
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = LegacyRemoteSnapshot.Absent,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid,
            evidence = evidence
        )

        assertTrue(classification is LegacyClassification.NeedsReconciliation)
        val needsRecon = classification as LegacyClassification.NeedsReconciliation
        assertTrue(needsRecon.reason.contains("Contradictory evidence"))
    }

    @Test
    fun `audit test 8a - remoteOwnerUid mismatch in Present snapshot yields OwnershipMismatch`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_LOCAL_MATCH"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 2L,
            isDeleted = false,
            lastMutationId = "M_LOCAL_MATCH",
            fieldRevisions = mapOf(BeerDirtyField.PHOTO to 2L),
            remoteOwnerUid = "different_remote_user_uid_456" // Mismatch con activeUserUid
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertTrue(classification is LegacyClassification.OwnershipMismatch)
        val mismatch = classification as LegacyClassification.OwnershipMismatch
        assertEquals(activeUserUid, mismatch.localOwnerUid)
        assertEquals(activeUserUid, mismatch.expectedOwnerUid)
        assertTrue(mismatch.reason.contains("different_remote_user_uid_456"))
    }

    @Test
    fun `audit test 8b - matching remoteOwnerUid in Present snapshot continues normal classification`() {
        val local = LegacyLocalEntityContext(
            syncId = defaultSyncId,
            ownerUid = activeUserUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 0L,
            baseRevision = 0L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_SAME_OWNER"
        )
        val remote = LegacyRemoteSnapshot.Present(
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_SAME_OWNER",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 1L),
            remoteOwnerUid = activeUserUid // Coincide con expectedOwnerUid
        )

        val classification = LegacyBeerClassifier.classify(
            local = local,
            remote = remote,
            readScope = RemoteReadScope.AUTHORITATIVE_CONFIRMED,
            expectedOwnerUid = activeUserUid
        )

        assertEquals(
            LegacyClassification.PotentialOwnMutationAck(
                matchedMutationId = "M_SAME_OWNER",
                isModern = true,
                remoteRevision = 1L
            ),
            classification
        )
    }
}
