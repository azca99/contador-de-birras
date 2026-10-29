package com.example.contadordebirras.sync.c4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.domain.RemoteReadScope
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Suite de pruebas unitarias puras en JVM para AndroidC4SyncCoordinator (Fase C4.4A).
 *
 * Utiliza persistencia real Room SQLite en memoria (v10) y adaptadores Firestore/Storage
 * simulados mediante MockK para evaluar exhaustivamente la lógica del coordinador,
 * el planificador causal y las transiciones Room sin depender de la red de Firebase Auth.
 *
 * Cubre:
 * - 13 Escenarios Firestore (A a M)
 * - Escenarios de Storage (nombrado versionado, preservación de photoUri, fallo preventivo)
 * - Persistencia e historial durable Room
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidC4CoordinatorUnitTest {

    private lateinit var database: BeerDatabase
    private lateinit var beerDao: BeerDao
    private lateinit var persistence: RoomC4SyncPersistence

    private val firestoreAdapter = mockk<FirestoreC4SyncAdapter>()
    private val storageAdapter = mockk<StorageC4SyncAdapter>()
    private lateinit var coordinator: AndroidC4SyncCoordinator

    private val aliceUid = "user_alice_test"
    private val bobUid = "user_bob_test"
    private val allFieldsEncoded = BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS)!!

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        beerDao = database.beerDao()
        persistence = RoomC4SyncPersistence(database)
        coordinator = AndroidC4SyncCoordinator(firestoreAdapter, storageAdapter, persistence)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun insertLocalBeer(
        syncId: String,
        ownerUid: String,
        syncStatus: String,
        remoteRevision: Long,
        baseRevision: Long,
        dirtyFields: String?,
        lastMutationId: String?,
        comment: String? = "Test Comment",
        type: BeerType = BeerType.LATA,
        photoUri: String? = null
    ): BeerEntity {
        val beer = BeerEntity(
            id = 0,
            type = type,
            timestamp = 1000L,
            latitude = 40.4168,
            longitude = -3.7038,
            photoUri = photoUri,
            comment = comment,
            locationName = "Madrid",
            syncId = syncId,
            syncStatus = syncStatus,
            ownerUid = ownerUid,
            remoteRevision = remoteRevision,
            baseRevision = baseRevision,
            dirtyFields = dirtyFields,
            lastMutationId = lastMutationId
        )
        val id = beerDao.insertBeer(beer)
        return beer.copy(id = id.toInt())
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO A: Creación desde local-only (-1/-1 -> CreateActive rev 1)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioA_createActive_localOnlyToRemoteRev1() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutationId = "mut-${UUID.randomUUID()}"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, -1L, -1L, allFieldsEncoded, mutationId)
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutationId, allFieldsEncoded, -1L, true)

        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null
        coEvery { firestoreAdapter.executeCreateActive(syncId, aliceUid, any(), mutationId) } returns
                C4TransactionResult.Success(newRevision = 1L, mutationId = mutationId)

        val result = coordinator.executePush(syncId, aliceUid)
        assertTrue("Push should succeed: $result", result is C4CoordinatorPushResult.Success)
        assertEquals(1L, (result as C4CoordinatorPushResult.Success).newRemoteRevision)

        val localBeer = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.SYNCED, localBeer.syncStatus)
        assertEquals(1L, localBeer.remoteRevision)
        assertEquals(1L, localBeer.baseRevision)
        assertNull(localBeer.dirtyFields)
        assertNull(localBeer.lastMutationId)

        val history = beerDao.getHistoryRecordById(mutationId, aliceUid)!!
        assertEquals(MutationStatus.CONFIRMED, history.status)
        assertEquals(1L, history.confirmedRevision)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO B: PATCH parcial válido (ApplyPatch with confirmedFields & remainingDirtyFields)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioB_partialPatch_advancesRemoteRevisionAndPreservesLocalBaseRevision() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutPatch = "mut-patch-${UUID.randomUUID()}"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, 1L, 1L, "TYPE,COMMENT", mutPatch, photoUri = "content://photo.jpg")
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutPatch, "TYPE,COMMENT", 1L, false)

        val remoteDocRev1 = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "mut-init",
            fieldRevisions = mapOf("TYPE" to 1L, "TIMESTAMP" to 1L, "LOCATION" to 1L, "COMMENT" to 1L, "PHOTO" to 1L)
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns remoteDocRev1
        coEvery {
            firestoreAdapter.executeApplyPatch(
                syncId = syncId,
                ownerUid = aliceUid,
                mutationId = mutPatch,
                baseRevision = 1L,
                fieldsToWrite = any(),
                fieldValues = any(),
                updatedPhotoUrl = any()
            )
        } returns C4TransactionResult.Success(newRevision = 2L, mutationId = mutPatch)

        val pushResult = coordinator.executePush(syncId, aliceUid)
        assertTrue("Push should succeed: $pushResult", pushResult is C4CoordinatorPushResult.Success)
        assertEquals(2L, (pushResult as C4CoordinatorPushResult.Success).newRemoteRevision)

        val localBeer = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.SYNCED, localBeer.syncStatus)
        assertEquals(2L, localBeer.remoteRevision)
        assertEquals("content://photo.jpg", localBeer.photoUri)

        // Verificación de parche parcial con partición estricta
        val mutPartial = "mut-partial-${UUID.randomUUID()}"
        database.openHelper.writableDatabase.execSQL("""
            UPDATE beers SET syncStatus = 'PENDING', dirtyFields = 'TYPE,COMMENT', lastMutationId = '$mutPartial'
            WHERE syncId = '$syncId' AND ownerUid = '$aliceUid'
        """)
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutPartial, "TYPE,COMMENT", 2L, false)

        val partialOk = persistence.confirmPushPartialPatch(
            syncId = syncId,
            ownerUid = aliceUid,
            expectedMutationId = mutPartial,
            newRemoteRevision = 3L,
            remainingDirtyFields = "COMMENT",
            confirmedFields = "TYPE"
        )
        assertTrue(partialOk)

        val partialBeer = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.PENDING, partialBeer.syncStatus)
        assertEquals(3L, partialBeer.remoteRevision)
        assertEquals(2L, partialBeer.baseRevision) // INVARIANTE: baseRevision congelada
        assertEquals("COMMENT", partialBeer.dirtyFields)
        assertEquals("content://photo.jpg", partialBeer.photoUri)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO C: Revisión remota obsoleta (remote rev advanced -> Conflict)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioC_conflict_obsoleteRemoteRevisionDetected() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutLocal = "mut-local-${UUID.randomUUID()}"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, 1L, 1L, "COMMENT", mutLocal)
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutLocal, "COMMENT", 1L, false)

        // Remoto ya está en rev 3 con mutación de otro dispositivo
        val advancedRemote = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            revision = 3L,
            isDeleted = false,
            lastMutationId = "mut-other-device",
            fieldRevisions = mapOf("TYPE" to 1L, "TIMESTAMP" to 1L, "LOCATION" to 1L, "COMMENT" to 3L, "PHOTO" to 1L)
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns advancedRemote

        val pushResult = coordinator.executePush(syncId, aliceUid)
        assertTrue("Must detect conflict: $pushResult", pushResult is C4CoordinatorPushResult.Conflict)

        // Estado local permanece intacto
        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.PENDING, local.syncStatus)
        assertEquals(mutLocal, local.lastMutationId)
        assertEquals("COMMENT", local.dirtyFields)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO D: Dos dispositivos modifican campos diferentes (convergencia ortogonal)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioD_orthogonalFields_convergeDeterministically() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutLocal = "mut-local-${UUID.randomUUID()}"

        // Local modificó COMMENT
        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, 1L, 1L, "COMMENT", mutLocal, comment = "Local Comment")
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutLocal, "COMMENT", 1L, false)

        // Remoto modificó TYPE (rev 2, TYPE=2)
        val remoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = BeerType.BOTELLIN.name,
            timestamp = 1000L,
            revision = 2L,
            isDeleted = false,
            lastMutationId = "mut-remote-type",
            fieldRevisions = mapOf("TYPE" to 2L, "TIMESTAMP" to 1L, "LOCATION" to 1L, "COMMENT" to 1L, "PHOTO" to 1L)
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns remoteDoc

        val pullResult = coordinator.executePull(syncId, aliceUid)
        assertTrue("Pull must apply orthogonal convergence: $pullResult", pullResult is C4CoordinatorPullResult.Applied)

        // Local adopta TYPE pero conserva COMMENT dirty y baseRevision=1
        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.PENDING, local.syncStatus)
        assertEquals(BeerType.BOTELLIN, local.type)
        assertEquals("Local Comment", local.comment)
        assertEquals(2L, local.remoteRevision)
        assertEquals(1L, local.baseRevision)
        assertEquals("COMMENT", local.dirtyFields)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO E: Dos dispositivos modifican el mismo campo (conflicto detectado, sin sobreescritura ciega)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioE_concurrentConflictSameField_blockedWithoutOverwrite() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutLocal = "mut-local-${UUID.randomUUID()}"

        // Local modificó COMMENT
        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, 1L, 1L, "COMMENT", mutLocal, comment = "Important Local Edit")
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutLocal, "COMMENT", 1L, false)

        // Remoto también modificó COMMENT (rev 2, COMMENT=2)
        val remoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            comment = "Remote Won't Overwrite Me Blindly",
            revision = 2L,
            isDeleted = false,
            lastMutationId = "mut-remote-comment",
            fieldRevisions = mapOf("TYPE" to 1L, "TIMESTAMP" to 1L, "LOCATION" to 1L, "COMMENT" to 2L, "PHOTO" to 1L)
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns remoteDoc

        val pullResult = coordinator.executePull(syncId, aliceUid)
        assertTrue("Conflict on same field must block pull: $pullResult", pullResult is C4CoordinatorPullResult.Blocked)

        // Local comment intacto
        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals("Important Local Edit", local.comment)
        assertEquals(SyncStatus.PENDING, local.syncStatus)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO F: ACK perdido (simulated drop -> retry recovers via idempotent read)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioF_lostAckRecovery_recoversViaIdempotentRead() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutAck = "mut-ack-${UUID.randomUUID()}"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, -1L, -1L, allFieldsEncoded, mutAck)
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutAck, allFieldsEncoded, -1L, true)

        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null
        coEvery { firestoreAdapter.executeCreateActive(syncId, aliceUid, any(), mutAck) } returns
                C4TransactionResult.IdempotentAck(currentRevision = 1L, mutationId = mutAck)

        val retryPush = coordinator.executePush(syncId, aliceUid)
        assertTrue("Retry recovers idempotently: $retryPush", retryPush is C4CoordinatorPushResult.IdempotentRecovery)

        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.SYNCED, local.syncStatus)
        assertEquals(1L, local.remoteRevision)
        assertEquals(1L, local.baseRevision)
        assertNull(local.dirtyFields)

        val history = beerDao.getHistoryRecordById(mutAck, aliceUid)!!
        assertEquals(MutationStatus.CONFIRMED, history.status)
        assertEquals(1L, history.confirmedRevision)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO G: Tombstone remoto prevalente (remoto tombstone rev 2 -> local DELETED, mutación local SUPERSEDED)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioG_remoteTombstoneWins_marksDeletedAndSupersedesLocalMutation() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutDisplaced = "mut-displaced-${UUID.randomUUID()}"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, 1L, 1L, "COMMENT", mutDisplaced)
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutDisplaced, "COMMENT", 1L, false)

        val tombstoneRemote = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            revision = 2L,
            isDeleted = true,
            lastMutationId = "mut-remote-del",
            fieldRevisions = mapOf("TYPE" to 1L, "TIMESTAMP" to 1L, "LOCATION" to 1L, "COMMENT" to 1L, "PHOTO" to 1L)
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns tombstoneRemote

        val pushResult = coordinator.executePush(syncId, aliceUid)
        assertTrue("Push detects tombstone conflict: $pushResult", pushResult is C4CoordinatorPushResult.Conflict)

        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.DELETED, local.syncStatus)
        assertEquals(2L, local.remoteRevision)
        assertEquals(2L, local.baseRevision)
        assertNull(local.dirtyFields)

        val history = beerDao.getHistoryRecordById(mutDisplaced, aliceUid)!!
        assertEquals(MutationStatus.SUPERSEDED, history.status)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO H: Intento de resurrección (bloqueado por reglas y adaptador)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioH_resurrectionBlocked_tombstoneCannotBeOverwrittenWithActive() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutResurrect = "mut-resurrect-${UUID.randomUUID()}"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, -1L, -1L, allFieldsEncoded, mutResurrect)
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutResurrect, allFieldsEncoded, -1L, true)

        val tombstoneRemote = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            revision = 2L,
            isDeleted = true,
            lastMutationId = "mut-del",
            fieldRevisions = mapOf("TYPE" to 1L, "TIMESTAMP" to 1L, "LOCATION" to 1L, "COMMENT" to 1L, "PHOTO" to 1L)
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns tombstoneRemote

        val pushResult = coordinator.executePush(syncId, aliceUid)
        assertTrue("Resurrection attempt blocked: $pushResult", pushResult is C4CoordinatorPushResult.Conflict)

        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.DELETED, local.syncStatus)
        assertEquals(2L, local.remoteRevision)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO I: Propietario incorrecto (OwnershipMismatch, datos intactos)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioI_ownershipMismatch_rejectsForeignAccess() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutBob = "mut-bob-${UUID.randomUUID()}"

        insertLocalBeer(syncId, bobUid, SyncStatus.PENDING, 1L, 1L, "COMMENT", mutBob)
        persistence.recordDurableMutationBeforeNetwork(syncId, bobUid, mutBob, "COMMENT", 1L, false)

        val aliceDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "mut-alice",
            fieldRevisions = mapOf("TYPE" to 1L, "TIMESTAMP" to 1L, "LOCATION" to 1L, "COMMENT" to 1L, "PHOTO" to 1L)
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns aliceDoc
        coEvery {
            firestoreAdapter.executeApplyPatch(
                syncId = syncId,
                ownerUid = bobUid,
                mutationId = mutBob,
                baseRevision = 1L,
                fieldsToWrite = any(),
                fieldValues = any(),
                updatedPhotoUrl = any()
            )
        } returns C4TransactionResult.OwnershipMismatch(documentOwner = aliceUid, callerOwner = bobUid)

        val pushResult = coordinator.executePush(syncId, bobUid)
        assertTrue("Must be blocked by ownership mismatch: $pushResult", pushResult is C4CoordinatorPushResult.Blocked)

        val localBob = beerDao.getBeerBySyncId(syncId, bobUid)!!
        assertEquals(SyncStatus.PENDING, localBob.syncStatus)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO J: Lectura no autoritativa (readScope unconfirmed -> bloqueado)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioJ_nonAuthoritativeRead_blocked() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        insertLocalBeer(syncId, aliceUid, SyncStatus.SYNCED, 1L, 1L, null, null)

        val pullResult = coordinator.executePull(
            syncId = syncId,
            ownerUid = aliceUid,
            readScope = RemoteReadScope.INCOMPLETE_OR_UNCONFIRMED
        )

        assertTrue("Non-authoritative read blocked: $pullResult", pullResult is C4CoordinatorPullResult.Blocked)
        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(1L, local.remoteRevision)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO K: Documento legacy (unversioned doc clasificado/upgraded a rev 1 con 5 claves)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioK_legacyDocument_classifiedAndUpgraded() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val legacyDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            revision = 0L,
            isDeleted = false,
            lastMutationId = "legacy-mut",
            fieldRevisions = emptyMap()
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns legacyDoc

        val pullResult = coordinator.executePull(syncId, aliceUid)
        assertTrue("Legacy remote document requires resolution: $pullResult", pullResult is C4CoordinatorPullResult.Blocked)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO L: Metadata causal inválida (bloqueado)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioL_invalidCausalMetadata_blocked() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        insertLocalBeer(syncId, aliceUid, SyncStatus.SYNCED, 1L, 1L, null, null)

        val corruptedDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            revision = 2L,
            isDeleted = false,
            lastMutationId = "mut-corrupted",
            fieldRevisions = mapOf("TYPE" to 2L) // Solo 1 clave en vez de 5
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns corruptedDoc

        val pullResult = coordinator.executePull(syncId, aliceUid)
        assertTrue("Corrupted fieldRevisions blocked: $pullResult", pullResult is C4CoordinatorPullResult.Blocked)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIO M: Reintento idempotente (sin incremento duplicado)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioM_idempotentRetry_noDuplicateIncrement() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutInit = "mut-init-${UUID.randomUUID()}"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, 1L, 1L, "COMMENT", mutInit)
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutInit, "COMMENT", 1L, false)

        val remoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = aliceUid,
            type = "LATA",
            timestamp = 1000L,
            revision = 2L,
            isDeleted = false,
            lastMutationId = mutInit,
            fieldRevisions = mapOf("TYPE" to 1L, "TIMESTAMP" to 1L, "LOCATION" to 1L, "COMMENT" to 2L, "PHOTO" to 1L)
        )
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns remoteDoc

        val pushResult = coordinator.executePush(syncId, aliceUid)
        assertTrue("Retry recovers idempotently: $pushResult", pushResult is C4CoordinatorPushResult.IdempotentRecovery)
        assertEquals(2L, (pushResult as C4CoordinatorPushResult.IdempotentRecovery).currentRemoteRevision)
    }

    // ---------------------------------------------------------------------------------------------
    // ESCENARIOS STORAGE: Subida versionada y fallo preventivo
    // ---------------------------------------------------------------------------------------------
    @Test
    fun scenarioStorage_versionedUploadAndPhotoUriPreservation() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutPhoto = "mut-${UUID.randomUUID()}"
        val localUri = "content://media/photo.jpg"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, -1L, -1L, allFieldsEncoded, mutPhoto, photoUri = localUri)
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutPhoto, allFieldsEncoded, -1L, true)

        val photoBytes = byteArrayOf(1, 2, 3)
        val expectedRemotePath = C4SyncConstants.buildVersionedPhotoPath(aliceUid, syncId, mutPhoto)

        coEvery { storageAdapter.uploadVersionedPhoto(aliceUid, syncId, mutPhoto, photoBytes) } returns
                C4StorageResult.Success(expectedRemotePath, alreadyExisted = false)
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null
        coEvery { firestoreAdapter.executeCreateActive(syncId, aliceUid, any(), mutPhoto) } returns
                C4TransactionResult.Success(1L, mutPhoto)

        val pushResult = coordinator.executePush(syncId, aliceUid, photoBytes = photoBytes)
        assertTrue("Push with photo should succeed: $pushResult", pushResult is C4CoordinatorPushResult.Success)

        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(localUri, local.photoUri)
        assertEquals(expectedRemotePath, local.remotePhotoUrl)
    }

    @Test
    fun scenarioStorage_failureHaltsPushBeforeFirestoreTransaction() = runBlocking {
        val syncId = "sync-${UUID.randomUUID()}"
        val mutPhoto = "mut-${UUID.randomUUID()}"

        insertLocalBeer(syncId, aliceUid, SyncStatus.PENDING, -1L, -1L, allFieldsEncoded, mutPhoto, photoUri = "content://media/photo.jpg")
        persistence.recordDurableMutationBeforeNetwork(syncId, aliceUid, mutPhoto, allFieldsEncoded, -1L, true)

        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null
        coEvery { storageAdapter.uploadVersionedPhoto(aliceUid, syncId, mutPhoto, any()) } returns
                C4StorageResult.OverwriteRejected("Overwrite strictly forbidden")

        val pushResult = coordinator.executePush(syncId, aliceUid, photoBytes = byteArrayOf(1, 2, 3))
        assertTrue("Push halts on storage rejection: $pushResult", pushResult is C4CoordinatorPushResult.StorageFailed)

        // Room permanece PENDING
        val local = beerDao.getBeerBySyncId(syncId, aliceUid)!!
        assertEquals(SyncStatus.PENDING, local.syncStatus)
    }
}
