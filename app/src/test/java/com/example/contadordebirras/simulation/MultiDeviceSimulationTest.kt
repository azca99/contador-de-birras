package com.example.contadordebirras.simulation

import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Banco de pruebas hermético para la simulación multidispositivo (Fase C4.2).
 *
 * Ejecuta en JVM sin Firebase real la matriz completa de escenarios obligatorios (A a N)
 * y modelos de fallos de red, persistencia y concurrencia.
 */
class MultiDeviceSimulationTest {

    private lateinit var firestore: FakeFirestore
    private lateinit var storage: FakeStorage
    private lateinit var coordinator: SimulationCoordinator

    private val userUid = "test_user_multidevice"
    private lateinit var device1: SimulatedDevice
    private lateinit var device2: SimulatedDevice

    @Before
    fun setUp() {
        firestore = FakeFirestore()
        storage = FakeStorage()
        coordinator = SimulationCoordinator(firestore, storage)

        device1 = SimulatedDevice(deviceId = "DEVICE_1", ownerUid = userUid)
        device2 = SimulatedDevice(deviceId = "DEVICE_2", ownerUid = userUid)
    }

    // =========================================================================
    // MATRIZ DE ESCENARIOS OBLIGATORIOS (A - N)
    // =========================================================================

    /**
     * Escenario A: Dos dispositivos modifican campos diferentes.
     * D1 modifica COMMENT, D2 modifica TYPE.
     * Se conservan ambos cambios mediante merge causal a nivel de campo.
     */
    @Test
    fun scenarioA_twoDevicesModifyDifferentFields_bothPreservedViaFieldLevelMerge() {
        val syncId = "beer-scenario-a"

        // Estado inicial sincronizado en ambos dispositivos con revision 1
        val initialDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_INIT",
            fieldRevisions = mapOf(
                BeerDirtyField.TYPE to 1L,
                BeerDirtyField.TIMESTAMP to 1L,
                BeerDirtyField.COMMENT to 1L
            ),
            type = BeerType.LATA.name,
            comment = "Comentario inicial"
        )
        firestore.seedDocument(initialDoc)

        val syncedBeerD1 = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            type = BeerType.LATA.name,
            comment = "Comentario inicial",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = emptySet(),
            lastMutationId = "M_INIT"
        )
        device1.insertBeerDirect(syncedBeerD1)
        device2.insertBeerDirect(syncedBeerD1.copy())

        // D1 modifica COMMENT (dirty: COMMENT, base: 1)
        device1.updateBeerLocal(
            syncId = syncId,
            comment = "Comentario exclusivo de D1",
            modifiedFields = setOf(BeerDirtyField.COMMENT),
            mutationId = "M_D1_COMMENT"
        )

        // D2 modifica TYPE (dirty: TYPE, base: 1)
        device2.updateBeerLocal(
            syncId = syncId,
            type = BeerType.BOTELLIN.name,
            modifiedFields = setOf(BeerDirtyField.TYPE),
            mutationId = "M_D2_TYPE"
        )

        // 1. D1 ejecuta PUSH primero -> avanza a revision 2
        val pushD1Result = coordinator.executePush(device1, syncId)
        assertTrue("D1 push debe tener éxito", pushD1Result is PushExecutionResult.Success)
        assertEquals(2L, (pushD1Result as PushExecutionResult.Success).newRemoteRevision)

        val docAfterD1 = firestore.getDocument(syncId)!!
        assertEquals(2L, docAfterD1.revision)
        assertEquals("Comentario exclusivo de D1", docAfterD1.comment)
        assertEquals(2L, docAfterD1.fieldRevisions[BeerDirtyField.COMMENT])
        assertEquals(1L, docAfterD1.fieldRevisions[BeerDirtyField.TYPE])

        // 2. D2 ejecuta PULL antes de su push
        // Proporcionamos evidencia de que COMMENT fue modificado externamente en rev 2 por D1
        val fieldEvidence = mapOf(
            BeerDirtyField.COMMENT to FieldOriginEvidence(
                field = BeerDirtyField.COMMENT,
                externalRevision = 2L,
                sourceDeviceId = "DEVICE_1"
            )
        )
        val pullD2Result = coordinator.executePull(
            device = device2,
            syncId = syncId,
            fieldEvidence = fieldEvidence
        )
        assertTrue("D2 pull debe aplicar MergeWithPending", pullD2Result is PullExecutionResult.Applied)
        val pullPlan = (pullD2Result as PullExecutionResult.Applied).plan
        assertTrue("El plan debe ser MergeWithPending", pullPlan is PullPlan.MergeWithPending)

        // Verificamos que D2 adoptó el comentario de D1, conservando su propio TYPE dirty con baseRevision congelada
        val d2LocalBeer = device2.getBeer(syncId)!!
        assertEquals("Comentario exclusivo de D1", d2LocalBeer.comment)
        assertEquals(BeerType.BOTELLIN.name, d2LocalBeer.type)
        assertEquals(setOf(BeerDirtyField.TYPE), d2LocalBeer.dirtyFields)
        assertEquals(2L, d2LocalBeer.remoteRevision)
        assertEquals(1L, d2LocalBeer.baseRevision) // Base congelada para el siguiente push

        // 3. D2 ejecuta PUSH de su cambio (TYPE)
        val pushD2Result = coordinator.executePush(device2, syncId)
        assertTrue("D2 push debe tener éxito", pushD2Result is PushExecutionResult.Success)
        assertEquals(3L, (pushD2Result as PushExecutionResult.Success).newRemoteRevision)

        val docFinal = firestore.getDocument(syncId)!!
        assertEquals(3L, docFinal.revision)
        assertEquals("Comentario exclusivo de D1", docFinal.comment)
        assertEquals(BeerType.BOTELLIN.name, docFinal.type)
        assertEquals(2L, docFinal.fieldRevisions[BeerDirtyField.COMMENT])
        assertEquals(3L, docFinal.fieldRevisions[BeerDirtyField.TYPE])

        // 4. D1 ejecuta PULL final para sincronizar el TYPE
        val pullD1Result = coordinator.executePull(device1, syncId)
        assertTrue("D1 pull debe tener éxito", pullD1Result is PullExecutionResult.Applied)
        val d1FinalBeer = device1.getBeer(syncId)!!
        assertEquals("Comentario exclusivo de D1", d1FinalBeer.comment)
        assertEquals(BeerType.BOTELLIN.name, d1FinalBeer.type)
        assertEquals(3L, d1FinalBeer.remoteRevision)
        assertEquals(SyncStatus.SYNCED, d1FinalBeer.syncStatus)
    }

    /**
     * Escenario B: Dos dispositivos modifican el mismo campo.
     * Ambos modifican COMMENT. Se bloquea o resuelve según el contrato, nunca se sobrescribe ciegamente.
     */
    @Test
    fun scenarioB_twoDevicesModifySameField_conflictResolvedWithoutBlindOverwrite() {
        val syncId = "beer-scenario-b"

        val initialDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_INIT",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 1L),
            comment = "Original"
        )
        firestore.seedDocument(initialDoc)

        val syncedBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            comment = "Original",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            lastMutationId = "M_INIT"
        )
        device1.insertBeerDirect(syncedBeer)
        device2.insertBeerDirect(syncedBeer.copy())

        // Ambos editan COMMENT concurrentemente
        device1.updateBeerLocal(syncId = syncId, comment = "D1 Edit", modifiedFields = setOf(BeerDirtyField.COMMENT))
        device2.updateBeerLocal(syncId = syncId, comment = "D2 Edit", modifiedFields = setOf(BeerDirtyField.COMMENT))

        // D1 sube primero a rev 2
        val pushD1 = coordinator.executePush(device1, syncId)
        assertTrue(pushD1 is PushExecutionResult.Success)

        // Caso B.1: Si D2 intenta PUSH directo sin hacer pull previo -> Conflicto CAS rechazado
        val pushD2Conflict = coordinator.executePush(device2, syncId)
        assertTrue("D2 push sin pull debe resultar en conflicto CAS", pushD2Conflict is PushExecutionResult.Conflict)

        // Caso B.2: Si D2 hace PULL con procedencia indeterminada -> Bloqueo seguro, sin sobrescritura
        val pullIndeterminate = coordinator.executePull(
            device = device2,
            syncId = syncId,
            overrideOrigin = RemoteOrigin.INDETERMINATE
        )
        assertTrue("PULL con origen indeterminado debe bloquearse de forma segura", pullIndeterminate is PullExecutionResult.Blocked)
        assertEquals("D2 Edit", device2.getBeer(syncId)!!.comment) // Preservado intacto localmente

        // Caso B.3: Si se acredita evidencia externa confirmada -> REMOTE CONFIRMED WINS
        val fieldEvidence = mapOf(
            BeerDirtyField.COMMENT to FieldOriginEvidence(
                field = BeerDirtyField.COMMENT,
                externalRevision = 2L,
                sourceDeviceId = "DEVICE_1"
            )
        )
        val pullConfirmed = coordinator.executePull(
            device = device2,
            syncId = syncId,
            overrideOrigin = RemoteOrigin.EXTERNAL_CONFIRMED,
            fieldEvidence = fieldEvidence
        )
        assertTrue("PULL con evidencia externa debe aplicar plan", pullConfirmed is PullExecutionResult.Applied)
        val plan = (pullConfirmed as PullExecutionResult.Applied).plan
        assertTrue("Plan debe ser MergeWithPending con rendición de conflicto", plan is PullPlan.MergeWithPending)
        val mergePlan = plan as PullPlan.MergeWithPending
        assertTrue(mergePlan.remoteWinsConflictFields.contains(BeerDirtyField.COMMENT))

        // Verificamos que se adoptó el comentario remoto de D1 sin pérdida involuntaria
        val d2BeerResolved = device2.getBeer(syncId)!!
        assertEquals("D1 Edit", d2BeerResolved.comment)
        assertTrue("COMMENT ya no es dirty en D2", d2BeerResolved.dirtyFields.isEmpty())
    }

    /**
     * Escenario C: Creación local-only con -1/-1: verificación de creación idempotente.
     */
    @Test
    fun scenarioC_localOnlyCreation_verifiedIdempotentCreation() {
        val syncId = "beer-scenario-c"
        val mutationId = "M_CREATE_C"

        val localBeer = device1.createBeerLocalOnly(
            syncId = syncId,
            type = BeerType.CANA.name,
            comment = "Birra local-only",
            mutationId = mutationId
        )
        assertEquals(LOCAL_ONLY_REVISION, localBeer.remoteRevision)
        assertEquals(LOCAL_ONLY_REVISION, localBeer.baseRevision)

        // Primer push: debe crear documento con revision 1
        val pushResult1 = coordinator.executePush(device1, syncId)
        assertTrue(pushResult1 is PushExecutionResult.Success)
        assertEquals(1L, (pushResult1 as PushExecutionResult.Success).newRemoteRevision)

        val docInRemote = firestore.getDocument(syncId)!!
        assertEquals(1L, docInRemote.revision)
        assertEquals(mutationId, docInRemote.lastMutationId)

        val d1Beer = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.SYNCED, d1Beer.syncStatus)
        assertEquals(1L, d1Beer.remoteRevision)
        assertEquals(1L, d1Beer.baseRevision)
        assertTrue(d1Beer.dirtyFields.isEmpty())

        // Reintento idéntico mientras la entidad sigue en PENDING con la misma mutación: debe ser AlreadyApplied idempotente
        device1.insertBeerDirect(d1Beer.copy(syncStatus = SyncStatus.PENDING, dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS, lastMutationId = mutationId))
        val pushResult2 = coordinator.executePush(device1, syncId)
        assertTrue("Reintento debe ser Success idempotente", pushResult2 is PushExecutionResult.Success)
        assertTrue((pushResult2 as PushExecutionResult.Success).plan is PushPlan.AlreadyApplied)
        assertEquals(1, firestore.getDocumentCount())
    }

    /**
     * Escenario D: Documento remoto creado, ACK de red perdido:
     * Evita duplicados y falsas confirmaciones.
     */
    @Test
    fun scenarioD_remoteCreatedAckLost_avoidsDuplicatesAndFalseConfirmations() {
        val syncId = "beer-scenario-d"
        val mutationId = "M_CREATE_D_LOST_ACK"

        device1.createBeerLocalOnly(
            syncId = syncId,
            type = BeerType.JARRA.name,
            comment = "Cerveza con ACK perdido",
            mutationId = mutationId
        )

        // Simulamos pérdida de ACK en la respuesta de Firestore
        coordinator.dropPushAck = true
        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue(pushResult is PushExecutionResult.AckLost)

        // Firestore tiene el documento creado
        val remoteDoc = firestore.getDocument(syncId)
        assertNotNull(remoteDoc)
        assertEquals(1L, remoteDoc!!.revision)
        assertEquals(mutationId, remoteDoc.lastMutationId)

        // El dispositivo 1 permanece en PENDING porque no recibió el ACK
        val localBeerBeforeRetry = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.PENDING, localBeerBeforeRetry.syncStatus)
        assertEquals(LOCAL_ONLY_REVISION, localBeerBeforeRetry.remoteRevision)

        // Al restablecer la red, el siguiente PULL o PUSH detecta la coincidencia exacta de mutationId
        coordinator.dropPushAck = false
        val pullRecovery = coordinator.executePull(device1, syncId)
        assertTrue("Pull recovery debe aplicar ConfirmPendingMutation", pullRecovery is PullExecutionResult.Applied)
        assertTrue((pullRecovery as PullExecutionResult.Applied).plan is PullPlan.ConfirmPendingMutation)

        val localBeerRecovered = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.SYNCED, localBeerRecovered.syncStatus)
        assertEquals(1L, localBeerRecovered.remoteRevision)
        assertEquals(1L, localBeerRecovered.baseRevision)
        assertTrue(localBeerRecovered.dirtyFields.isEmpty())
        assertEquals(1, firestore.getDocumentCount()) // Sin duplicados
    }

    /**
     * Escenario E: Borrado remoto frente a edición local:
     * DELETE WINS, conservando la evidencia necesaria para reconciliación.
     */
    @Test
    fun scenarioE_remoteDeletionVsLocalEdit_deleteWins() {
        val syncId = "beer-scenario-e"

        val initialDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_INIT",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 1L),
            comment = "Inicial"
        )
        firestore.seedDocument(initialDoc)

        val syncedBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            lastMutationId = "M_INIT"
        )
        device1.insertBeerDirect(syncedBeer)
        device2.insertBeerDirect(syncedBeer.copy())

        // D1 borra la cerveza y sube el tombstone a rev 2
        device1.deleteBeerLocal(syncId, mutationId = "M_DEL_D1")
        val pushDelete = coordinator.executePush(device1, syncId)
        assertTrue(pushDelete is PushExecutionResult.Success)
        assertTrue(firestore.getDocument(syncId)!!.isDeleted)

        // D2 realiza una edición local en su dispositivo desconectado
        device2.updateBeerLocal(
            syncId = syncId,
            comment = "Edición concurrente en D2",
            modifiedFields = setOf(BeerDirtyField.COMMENT),
            mutationId = "M_EDIT_D2"
        )

        // D2 se reconecta y hace PULL -> DELETE WINS (ApplyRemoteTombstone)
        val pullResult = coordinator.executePull(device2, syncId)
        assertTrue("PULL debe aplicar el tombstone remoto", pullResult is PullExecutionResult.Applied)
        val plan = (pullResult as PullExecutionResult.Applied).plan
        assertTrue("El plan debe ser ApplyRemoteTombstone", plan is PullPlan.ApplyRemoteTombstone)

        val d2Beer = device2.getBeer(syncId)!!
        assertEquals("La fila local debe transicionar a DELETED", SyncStatus.DELETED, d2Beer.syncStatus)
        assertEquals(2L, d2Beer.remoteRevision)
        assertTrue("Los dirty fields se limpian para no resucitar la cerveza", d2Beer.dirtyFields.isEmpty())

        // Si D2 intenta PUSH posterior, se reconoce DeleteAlreadySatisfied o RemoteTombstoneWins
        val pushAfterTombstone = coordinator.executePush(device2, syncId)
        assertTrue(pushAfterTombstone is PushExecutionResult.Success)
        assertFalse("La cerveza jamás resucita", firestore.getDocument(syncId)!!.isDeleted.not())
    }

    /**
     * Escenario F: Dispositivo desconectado durante semanas que vuelve a sincronizar:
     * Conserva tombstones, sin TTL arbitrario.
     */
    @Test
    fun scenarioF_deviceOfflineWeeks_tombstonesPreservedWithoutArbitraryTTL() {
        val syncId = "beer-scenario-f"

        // Creación y borrado hace semanas en D1
        val initialDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_INIT"
        )
        firestore.seedDocument(initialDoc)

        val tombstoneDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 2L,
            isDeleted = true,
            lastMutationId = "M_DEL_OLD"
        )
        firestore.seedDocument(tombstoneDoc) // Persistido en remoto de forma indefinida

        // D2 estuvo offline durante semanas
        device2.isOnline = false
        val beerOnD2 = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            lastMutationId = "M_INIT"
        )
        device2.insertBeerDirect(beerOnD2)

        // Intento de pull offline: falla controladamente
        val offlinePull = coordinator.executePull(device2, syncId)
        assertEquals(PullExecutionResult.Offline, offlinePull)

        // D2 se reconecta
        device2.isOnline = true
        val pullWeeksLater = coordinator.executePull(device2, syncId)
        assertTrue(pullWeeksLater is PullExecutionResult.Applied)
        assertTrue((pullWeeksLater as PullExecutionResult.Applied).plan is PullPlan.ApplyRemoteTombstone)

        // Verificamos que el tombstone no fue purgado por TTL y la cerveza no resucitó
        val d2FinalBeer = device2.getBeer(syncId)!!
        assertEquals(SyncStatus.DELETED, d2FinalBeer.syncStatus)
        assertEquals(2L, d2FinalBeer.remoteRevision)
        assertTrue(firestore.getDocument(syncId)!!.isDeleted)
    }

    /**
     * Escenario G: PENDING legacy 0/0 ALL_FIELDS:
     * No promocionar sin prueba positiva (NeedsReconciliation).
     */
    @Test
    fun scenarioG_pendingLegacyAllFieldsWithoutPositiveProof_blocksPromotion() {
        val syncId = "beer-legacy-scenario-g"

        // Fila legacy 0/0 en Room
        val legacyBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LEGACY_REMOTE_REVISION,
            baseRevision = LEGACY_REMOTE_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = "M_LEGACY_AMBIGUOUS"
        )
        device1.insertBeerDirect(legacyBeer)

        // Remoto ausente
        assertNull(firestore.getDocument(syncId))

        // Evidencia SIN prueba concluyente de creación no sincronizada
        val unprovenEvidence = LegacyHistoricalEvidence(isProvenNeverSyncedCreation = false)

        val pushResult = coordinator.executePush(device1, syncId, historicalEvidence = unprovenEvidence)
        assertTrue("Push debe ser bloqueado por clasificación ambigua", pushResult is PushExecutionResult.Blocked)
        assertTrue((pushResult as PushExecutionResult.Blocked).reason.contains("Clasificación legacy ambigua"))

        // La fila local no fue mutada a -1/-1; conserva 0/0
        val preservedLocal = device1.getBeer(syncId)!!
        assertEquals(LEGACY_REMOTE_REVISION, preservedLocal.remoteRevision)
        assertEquals(LEGACY_REMOTE_REVISION, preservedLocal.baseRevision)
        assertEquals(SyncStatus.PENDING, preservedLocal.syncStatus)
        assertNull(firestore.getDocument(syncId))
    }

    /**
     * Escenario H: Documento moderno con base local legacy ambigua:
     * NeedsReconciliation.
     */
    @Test
    fun scenarioH_modernRemoteWithAmbiguousLegacyLocalBase_needsReconciliation() {
        val syncId = "beer-legacy-scenario-h"

        // Documento remoto moderno revision 2 con mutationId M_MODERN
        val modernRemote = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 2L,
            isDeleted = false,
            lastMutationId = "M_MODERN",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 2L)
        )
        firestore.seedDocument(modernRemote)

        // Fila local legacy 0/0 con mutationId M_LOCAL_LEGACY
        val localLegacy = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LEGACY_REMOTE_REVISION,
            baseRevision = LEGACY_REMOTE_REVISION,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_LOCAL_LEGACY"
        )
        device1.insertBeerDirect(localLegacy)

        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue(pushResult is PushExecutionResult.Blocked)
        assertTrue((pushResult as PushExecutionResult.Blocked).reason.contains("Clasificación legacy ambigua"))

        // Datos locales preservados sin corrupción
        val localPreserved = device1.getBeer(syncId)!!
        assertEquals(LEGACY_REMOTE_REVISION, localPreserved.remoteRevision)
        assertEquals(LEGACY_REMOTE_REVISION, localPreserved.baseRevision)
    }

    /**
     * Escenario I: Documento remoto de otro propietario:
     * Bloqueo completo por OwnershipMismatch.
     */
    @Test
    fun scenarioI_remoteDocumentAnotherOwner_completeBlock() {
        val syncId = "beer-scenario-i"
        val attackerUid = "attacker_uid_999"

        // Documento remoto creado por otro usuario
        val foreignDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = attackerUid,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_FOREIGN"
        )
        firestore.seedDocument(foreignDoc)

        // D1 intenta hacer PULL del syncId con su usuario legítimo
        val pullResult = coordinator.executePull(device1, syncId)
        assertTrue("Pull debe bloquearse por Ownership Mismatch", pullResult is PullExecutionResult.Blocked)
        assertTrue((pullResult as PullExecutionResult.Blocked).reason.contains("Ownership Mismatch"))

        // D1 no incorpora la cerveza en su base de datos local
        assertNull(device1.getBeer(syncId))

        // D1 tampoco puede hacer PUSH para sobrescribir el documento ajeno
        val localAttempt = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_ATTEMPT"
        )
        device1.insertBeerDirect(localAttempt)
        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue("Push debe bloquearse por precondición fallida en ownership", pushResult is PushExecutionResult.Blocked)
        assertTrue((pushResult as PushExecutionResult.Blocked).reason.contains("Ownership mismatch"))
    }

    /**
     * Escenario J: Lectura incompleta / timeout:
     * Sin ACK, sin borrado y sin escritura.
     */
    @Test
    fun scenarioJ_incompleteRead_noAckNoDeleteNoWrite() {
        val syncId = "beer-scenario-j"

        val localBeer = device1.createBeerLocalOnly(syncId = syncId, comment = "Local only beer")

        // Simulamos timeout de red durante lectura
        firestore.simulateTimeout = true

        val pullResult = coordinator.executePull(
            device = device1,
            syncId = syncId,
            scope = RemoteReadScope.INCOMPLETE_OR_UNCONFIRMED
        )
        assertEquals("Lectura incompleta debe ser ignorada sin modificar nada", PullExecutionResult.IncompleteReadIgnored, pullResult)

        // Verificamos que la fila local sigue intacta en PENDING y no fue purgada
        val preserved = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.PENDING, preserved.syncStatus)
        assertEquals(LOCAL_ONLY_REVISION, preserved.remoteRevision)

        firestore.simulateTimeout = false
        assertNull(firestore.getDocument(syncId))
    }

    /**
     * Escenario K: Fotografía:
     * La referencia local photoUri se conserva y nunca se sube como URI del dispositivo a Firestore.
     */
    @Test
    fun scenarioK_photography_photoUriPreservedAndNeverUploadedAsDeviceUri() {
        val syncId = "beer-scenario-k"
        val localPhotoUri = "file:///data/user/0/com.example.contadordebirras/cache/beer_123.jpg"

        val beer = device1.createBeerLocalOnly(
            syncId = syncId,
            comment = "Cerveza con foto",
            photoUri = localPhotoUri,
            mutationId = "M_PHOTO_K"
        )

        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue(pushResult is PushExecutionResult.Success)

        // Verificamos en Firestore: el campo photoUrl es una URL HTTPS de Storage, NUNCA la URI local
        val remoteDoc = firestore.getDocument(syncId)!!
        assertNotNull(remoteDoc.photoUrl)
        assertTrue("Debe ser URL pública de Firebase Storage", remoteDoc.photoUrl!!.startsWith("https://firebasestorage.googleapis.com"))
        assertFalse("Jamás debe contener file://", remoteDoc.photoUrl!!.contains("file://"))

        // Verificamos en Storage: el objeto fue persistido en la ruta versionada inmutable
        val expectedStoragePath = "users/$userUid/beers/${syncId}_M_PHOTO_K.jpg"
        assertTrue(storage.hasObject(expectedStoragePath))

        // Verificamos en el dispositivo local: su photoUri local se preserva intacta
        val d1Beer = device1.getBeer(syncId)!!
        assertEquals(localPhotoUri, d1Beer.photoUri)
        assertEquals(remoteDoc.photoUrl, d1Beer.remotePhotoUrl)
        assertEquals(SyncStatus.SYNCED, d1Beer.syncStatus)
    }

    /**
     * Escenario L: Fallo de upload fotográfico:
     * No publicar en remoto una referencia inexistente como si estuviera confirmada.
     */
    @Test
    fun scenarioL_photoUploadFailure_abortsPushWithoutPublishingPhantomReference() {
        val syncId = "beer-scenario-l"

        device1.createBeerLocalOnly(
            syncId = syncId,
            comment = "Foto con fallo de subida",
            photoUri = "content://media/external/images/media/456",
            mutationId = "M_PHOTO_FAIL"
        )

        // Inyectamos error 500 en Storage
        storage.failUpload = true

        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue("Push debe abortarse por fallo de Storage", pushResult is PushExecutionResult.StorageUploadFailed)

        // En Firestore NO debe haberse creado ningún documento con foto fantasma
        assertNull("Firestore no debe contener el documento", firestore.getDocument(syncId))

        // El dispositivo conserva su estado PENDING y su photoUri local intacta
        val d1Beer = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.PENDING, d1Beer.syncStatus)
        assertEquals(LOCAL_ONLY_REVISION, d1Beer.remoteRevision)
        assertNull(d1Beer.remotePhotoUrl)

        storage.failUpload = false
    }

    /**
     * Escenario M: Dos mutaciones consecutivas M1 y M2:
     * ACK tardío de M1 no borra ni confirma M2.
     */
    @Test
    fun scenarioM_twoConsecutiveMutations_lateAckOfM1DoesNotEraseM2() {
        val syncId = "beer-scenario-m"

        // 1. D1 crea la cerveza con M1
        device1.createBeerLocalOnly(
            syncId = syncId,
            comment = "Mutación 1",
            mutationId = "M1"
        )

        // D1 sube M1 pero el ACK de red se retrasa
        coordinator.dropPushAck = true
        coordinator.executePush(device1, syncId)
        coordinator.dropPushAck = false

        // 2. El usuario edita localmente la cerveza antes de recibir el ACK, generando M2
        device1.updateBeerLocal(
            syncId = syncId,
            comment = "Mutación 2 editada localmente",
            modifiedFields = setOf(BeerDirtyField.COMMENT),
            mutationId = "M2"
        )
        val beerWithM2 = device1.getBeer(syncId)!!
        assertEquals("M2", beerWithM2.lastMutationId)
        assertEquals(SyncStatus.PENDING, beerWithM2.syncStatus)

        // 3. Llega tardíamente el ACK de M1 (que confirma revision 1)
        val ackM1Applied = device1.applyFullPushConfirmation(
            syncId = syncId,
            expectedMutationId = "M1",
            newRemoteRevision = 1L
        )
        assertFalse("El ACK de M1 debe ser rechazado atómicamente porque lastMutationId es M2", ackM1Applied)

        // Verificamos que M2 no fue borrada, no se limpiaron los dirtyFields y la fila sigue en PENDING
        val beerPreserved = device1.getBeer(syncId)!!
        assertEquals("M2", beerPreserved.lastMutationId)
        assertEquals("Mutación 2 editada localmente", beerPreserved.comment)
        assertEquals(SyncStatus.PENDING, beerPreserved.syncStatus)
        assertTrue(beerPreserved.dirtyFields.contains(BeerDirtyField.COMMENT))
    }

    /**
     * Escenario N: Conflicto sin procedencia verificable:
     * Preservar datos y bloquear de forma segura.
     */
    @Test
    fun scenarioN_conflictWithoutVerifiableProvenance_preservesDataAndBlocks() {
        val syncId = "beer-scenario-n"

        // Documento remoto con revisión 2 y mutationId desconocido
        val remoteDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 2L,
            isDeleted = false,
            lastMutationId = "M_UNKNOWN_EXTERNAL",
            fieldRevisions = mapOf(BeerDirtyField.COMMENT to 2L),
            comment = "Comentario de origen desconocido"
        )
        firestore.seedDocument(remoteDoc)

        // D1 tiene una mutación local pendiente sobre COMMENT desde base 1
        val localBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            comment = "Mi comentario local crítico",
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = "M_D1_LOCAL"
        )
        device1.insertBeerDirect(localBeer)

        // Origen indeterminado y sin evidencia de procedencia externa por campo
        val pullResult = coordinator.executePull(
            device = device1,
            syncId = syncId,
            overrideOrigin = RemoteOrigin.INDETERMINATE,
            fieldEvidence = emptyMap()
        )

        assertTrue("Pull debe bloquearse de forma segura", pullResult is PullExecutionResult.Blocked)
        assertTrue((pullResult as PullExecutionResult.Blocked).reason.contains("Bloqueo seguro"))

        // Los datos locales críticos permanecen 100% intactos sin ser sobrescritos
        val preservedBeer = device1.getBeer(syncId)!!
        assertEquals("Mi comentario local crítico", preservedBeer.comment)
        assertEquals(1L, preservedBeer.baseRevision)
        assertEquals(SyncStatus.PENDING, preservedBeer.syncStatus)
    }

    // =========================================================================
    // MODELO DE PERSISTENCIA SIMULADA Y FALLOS ADICIONALES
    // =========================================================================

    /**
     * Simulación de cierre y reinicio de aplicación:
     * Mantiene las mutaciones pendientes y el historial en disco simulado.
     */
    @Test
    fun testPersistence_shutdownAndRestartPreservesPendingMutationsAndHistory() {
        val syncId = "beer-persist-test"

        device1.createBeerLocalOnly(
            syncId = syncId,
            comment = "Antes de cerrar la app",
            mutationId = "M_PERSIST_1"
        )

        // Simular cierre de la aplicación (proceso termina)
        device1.shutdown()

        // Simular reinicio de la aplicación
        device1.restart()

        val recoveredBeer = device1.getBeer(syncId)
        assertNotNull("La cerveza debe existir tras reinicio", recoveredBeer)
        assertEquals("Antes de cerrar la app", recoveredBeer!!.comment)
        assertEquals(SyncStatus.PENDING, recoveredBeer.syncStatus)
        assertEquals(LOCAL_ONLY_REVISION, recoveredBeer.baseRevision)

        val history = device1.getMutationHistory()
        assertEquals(1, history.size)
        assertEquals("M_PERSIST_1", history[0].mutationId)
        assertEquals(MutationStatus.PENDING, history[0].status)
    }

    /**
     * Invariante de historial:
     * No marcar una mutación completamente CONFIRMED si tiene campos pendientes.
     */
    @Test
    fun testHistory_partialConfirmationMarksPartiallyConfirmedNotConfirmed() {
        val syncId = "beer-partial-history-test"

        device1.createBeerLocalOnly(
            syncId = syncId,
            comment = "Cerveza múltiple",
            mutationId = "M_PARTIAL_HIST"
        )

        val applied = device1.applyPartialConfirmation(
            syncId = syncId,
            expectedMutationId = "M_PARTIAL_HIST",
            newRemoteRevision = 2L,
            confirmedFields = setOf(BeerDirtyField.TYPE),
            remainingDirtyFields = setOf(BeerDirtyField.COMMENT)
        )
        assertTrue(applied)

        val beer = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.PENDING, beer.syncStatus)
        assertEquals(setOf(BeerDirtyField.COMMENT), beer.dirtyFields)
        assertEquals(LOCAL_ONLY_REVISION, beer.baseRevision) // Base no avanza

        val record = device1.getMutationHistory().last()
        assertEquals(MutationStatus.PARTIALLY_CONFIRMED, record.status)
        assertEquals(2L, record.confirmedRevision)
    }

    /**
     * Protección contra borrado físico:
     * Prohibición de hard delete para documentos modernos revision >= 1.
     */
    @Test
    fun testSecurity_prohibitHardDeleteForModernDocuments() {
        val syncId = "beer-prohibit-hard-delete"

        val modernDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "M_MODERN_HARD"
        )
        firestore.seedDocument(modernDoc)

        val deleteResult = firestore.executePhysicalDelete(syncId, userUid)
        assertTrue("Hard delete en documento moderno debe fallar", deleteResult is TransactionResult.PreconditionFailed)
        assertTrue((deleteResult as TransactionResult.PreconditionFailed).reason.contains("HARD DELETE PROHIBITED"))

        // El documento sigue existiendo en Firestore
        assertNotNull(firestore.getDocument(syncId))
    }

    /**
     * Inmutabilidad de Storage:
     * No se permite sobrescribir un objeto fotográfico versionado ya existente.
     */
    @Test
    fun testStorage_immutabilityOfVersionedPaths() {
        val versionedPath = "users/$userUid/beers/beer_123_M1.jpg"

        val result1 = storage.uploadPhoto(versionedPath, userUid, "bytes_v1".toByteArray())
        assertTrue("Primera subida debe tener éxito", result1.isSuccess)

        val result2 = storage.uploadPhoto(versionedPath, userUid, "bytes_v2_overwrite_attempt".toByteArray())
        assertTrue("Sobrescritura debe fallar por infracción de inmutabilidad", result2.isFailure)
        assertTrue(result2.exceptionOrNull() is IllegalStateException)
    }
}
