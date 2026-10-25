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

    // =========================================================================
    // PRUEBAS DE AUDITORÍA CORRECTIVA C4.2 (Casos A a I)
    // =========================================================================

    /**
     * Test A: Mismo mutationId pero propietario remoto diferente: bloqueo inmediato.
     * Un documento ajeno jamás puede producir una confirmación de creación.
     */
    @Test
    fun testAuditA_sameMutationIdDifferentRemoteOwner_blocks() {
        val syncId = "beer-audit-a"
        val foreignOwner = "attacker_uid_999"
        val sharedMutationId = "M_SHARED_COLLISION"

        // Firestore tiene documento con mutationId M_SHARED_COLLISION pero de foreignOwner
        val existingForeign = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = foreignOwner,
            revision = 1L,
            isDeleted = false,
            lastMutationId = sharedMutationId
        )
        firestore.seedDocument(existingForeign)

        // D1 (userUid) tiene una cerveza local-only con el mismo mutationId
        device1.createBeerLocalOnly(
            syncId = syncId,
            mutationId = sharedMutationId
        )

        // Intento directo en FakeFirestore
        val txResult = firestore.executeCreateActiveTransaction(
            syncId = syncId,
            callerUid = userUid,
            newDocument = FakeFirestoreBeerDocument(
                syncId = syncId,
                userId = userUid,
                revision = 1L,
                lastMutationId = sharedMutationId
            )
        )
        assertTrue("CreateActive debe fallar por precondición de ownership", txResult is TransactionResult.PreconditionFailed)
        assertTrue((txResult as TransactionResult.PreconditionFailed).reason.contains("Ownership mismatch"))

        // Push vía coordinador debe quedar bloqueado
        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue("Push debe ser bloqueado por precondición fallida", pushResult is PushExecutionResult.Blocked)
        assertTrue((pushResult as PushExecutionResult.Blocked).reason.contains("Ownership mismatch"))
    }

    /**
     * Test B: mutationId null en creación existente: no reconocer ACK.
     * No considerar null == null como confirmación ni inventar ACKs sin mutationId verificado.
     */
    @Test
    fun testAuditB_mutationIdNullOnExistingCreation_doesNotRecognizeAck() {
        val syncId = "beer-audit-b"

        val existingWithoutMutation = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 1L,
            isDeleted = false,
            lastMutationId = null
        )
        firestore.seedDocument(existingWithoutMutation)

        // Intento de CreateActive con mutationId null/blank
        val txResultNull = firestore.executeCreateActiveTransaction(
            syncId = syncId,
            callerUid = userUid,
            newDocument = FakeFirestoreBeerDocument(
                syncId = syncId,
                userId = userUid,
                revision = 1L,
                lastMutationId = null
            )
        )
        assertTrue("CreateActive con mutationId null debe fallar", txResultNull is TransactionResult.PreconditionFailed)

        // Intento de CreateActive con mutationId "M_NEW" contra el documento que tiene null
        val txResultNew = firestore.executeCreateActiveTransaction(
            syncId = syncId,
            callerUid = userUid,
            newDocument = FakeFirestoreBeerDocument(
                syncId = syncId,
                userId = userUid,
                revision = 1L,
                lastMutationId = "M_NEW"
            )
        )
        assertTrue("CreateActive debe resultar en Conflict y no reconocer ACK ficticio", txResultNew is TransactionResult.Conflict)
    }

    /**
     * Test C: Present con lectura no confirmada: no aplicar actualizaciones.
     * Si readScope no es AUTHORITATIVE_CONFIRMED, bloquear snapshot completo incluso si existe Present.
     */
    @Test
    fun testAuditC_presentWithUnconfirmedRead_doesNotApplyUpdates() {
        val syncId = "beer-audit-c"

        val localBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            comment = "Local original",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            lastMutationId = "M1"
        )
        device1.insertBeerDirect(localBeer)

        val remoteDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 2L,
            isDeleted = false,
            lastMutationId = "M2_REMOTE",
            comment = "Comentario remoto más reciente"
        )
        firestore.seedDocument(remoteDoc)

        val pullResult = coordinator.executePull(
            device = device1,
            syncId = syncId,
            scope = RemoteReadScope.INCOMPLETE_OR_UNCONFIRMED
        )
        assertEquals("Lectura no confirmada debe ser ignorada", PullExecutionResult.IncompleteReadIgnored, pullResult)

        val beerPreserved = device1.getBeer(syncId)!!
        assertEquals("Local original", beerPreserved.comment)
        assertEquals(1L, beerPreserved.remoteRevision)
    }

    /**
     * Test D: Tombstone Present no confirmado: no aplicar borrado local.
     */
    @Test
    fun testAuditD_tombstonePresentUnconfirmedRead_doesNotApplyLocalDelete() {
        val syncId = "beer-audit-d"

        val localBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            comment = "Cerveza activa en local",
            syncStatus = SyncStatus.SYNCED,
            remoteRevision = 1L,
            baseRevision = 1L,
            lastMutationId = "M1"
        )
        device1.insertBeerDirect(localBeer)

        val remoteTombstone = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 2L,
            isDeleted = true,
            lastMutationId = "M_DEL_REMOTE"
        )
        firestore.seedDocument(remoteTombstone)

        val pullResult = coordinator.executePull(
            device = device1,
            syncId = syncId,
            scope = RemoteReadScope.INCOMPLETE_OR_UNCONFIRMED
        )
        assertEquals(PullExecutionResult.IncompleteReadIgnored, pullResult)

        val beerPreserved = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.SYNCED, beerPreserved.syncStatus)
        assertEquals(1L, beerPreserved.remoteRevision)
    }

    /**
     * Test E: Retry fotográfico end-to-end:
     * - Upload correcto en Storage.
     * - Fallo real simulado de transacción Firestore después del upload.
     * - Entidad local permanece PENDING sin ACK.
     * - Reintento vía SimulationCoordinator con el mismo mutationId.
     * - Recuperación idempotente sin sobrescritura en Storage (exactamente 1 objeto almacenado).
     * - Una sola referencia remota definitiva coherente.
     * - Resultado local SYNCED solo tras recibir el ACK confirmado.
     */
    @Test
    fun testAuditE_uploadSuccessFirestoreFailureRetry_idempotentRecovery() {
        val syncId = "beer-audit-e"
        val mutationId = "M_PHOTO_RETRY"
        val localPhotoUri = "content://media/external/images/media/1234"

        // 1. Entidad local-only creada en D1 con fotografía local
        device1.createBeerLocalOnly(
            syncId = syncId,
            photoUri = localPhotoUri,
            mutationId = mutationId
        )
        val initialBeer = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.PENDING, initialBeer.syncStatus)
        assertEquals(localPhotoUri, initialBeer.photoUri)
        assertNull(initialBeer.remotePhotoUrl)

        // 2. Inyectamos fallo simulado en la transacción Firestore posterior a la subida de foto
        firestore.simulateTransactionError = true

        val pushResult1 = coordinator.executePush(device1, syncId)
        assertTrue("El primer push debe fallar en Firestore", pushResult1 is PushExecutionResult.Blocked)

        // Verificamos que la foto ya fue subida a Storage
        val photoPath = "users/$userUid/beers/${syncId}_$mutationId.jpg"
        assertTrue("La foto debe haberse subido a Storage antes del fallo de Firestore", storage.hasObject(photoPath))
        assertEquals("Debe existir exactamente 1 objeto en Storage", 1, storage.getObjectCount())

        // La entidad local permanece PENDING y no tiene ACK
        val beerAfterFail = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.PENDING, beerAfterFail.syncStatus)
        assertEquals(LOCAL_ONLY_REVISION, beerAfterFail.remoteRevision)
        assertNull(beerAfterFail.remotePhotoUrl)
        assertEquals(localPhotoUri, beerAfterFail.photoUri)

        // 3. Restauramos la red y reintentamos vía SimulationCoordinator con el mismo mutationId
        firestore.simulateTransactionError = false

        val pushResult2 = coordinator.executePush(device1, syncId)
        assertTrue("El reintento debe ser exitoso", pushResult2 is PushExecutionResult.Success)

        // Recuperación sin sobrescritura Storage: sigue habiendo exactamente 1 objeto
        assertEquals("No debe haber duplicados ni sobrescrituras en Storage", 1, storage.getObjectCount())

        // Una sola referencia remota definitiva
        val remoteDoc = firestore.getDocument(syncId)!!
        assertNotNull(remoteDoc.photoUrl)
        assertTrue(remoteDoc.photoUrl!!.contains(photoPath.replace("/", "%2F")))

        // Resultado local SYNCED solo tras ACK
        val finalBeer = device1.getBeer(syncId)!!
        assertEquals(SyncStatus.SYNCED, finalBeer.syncStatus)
        assertEquals(1L, finalBeer.remoteRevision)
        assertEquals(1L, finalBeer.baseRevision)
        assertEquals(remoteDoc.photoUrl, finalBeer.remotePhotoUrl)
        assertEquals("photoUri local debe preservarse intacto", localPhotoUri, finalBeer.photoUri)
        assertTrue(finalBeer.dirtyFields.isEmpty())
    }

    /**
     * Test de idempotencia CreateActive con contradicción de payload:
     * Si un documento ya existe con el mismo mutationId y propietario, pero el nuevo intento
     * presenta un payload incompatible, debe bloquearse (PreconditionFailed).
     */
    @Test
    fun testAuditE2_createActiveSameMutationIdContradictoryPayload_blocked() {
        val syncId = "beer-payload-mismatch"
        val mutationId = "M_CREATE_CONTRADICT"

        val originalDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 1L,
            lastMutationId = mutationId,
            type = "LATA",
            timestamp = 1000L,
            comment = "Cerveza original"
        )
        firestore.seedDocument(originalDoc)

        // Intento directo con payload contradictorio (type = JARRA vs LATA)
        val contradictoryDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 1L,
            lastMutationId = mutationId,
            type = "JARRA",
            timestamp = 1000L,
            comment = "Cerveza original"
        )
        val directTx = firestore.executeCreateActiveTransaction(syncId, userUid, contradictoryDoc)
        assertTrue("Debe fallar por contradicción de payload", directTx is TransactionResult.PreconditionFailed)
        assertTrue((directTx as TransactionResult.PreconditionFailed).reason.contains("Payload contradiction"))

        // Intento vía coordinator: D1 tiene cerveza local con payload incompatible
        device1.insertBeerDirect(SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            type = "JARRA",
            timestamp = 1000L,
            comment = "Cerveza original",
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = mutationId
        ))
        device1.insertMutationRecord(SimulatedMutationRecord(
            mutationId = mutationId,
            syncId = syncId,
            ownerUid = userUid,
            timestamp = 1000L,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            baseRevision = LOCAL_ONLY_REVISION,
            status = MutationStatus.PENDING
        ))

        // Si se intenta CreateActive en Firestore, la precondición falla y el push se bloquea
        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue("El push debe quedar bloqueado por contradicción de payload", pushResult is PushExecutionResult.Blocked)
    }

    /**
     * Test F: Misma ruta fotográfica con bytes diferentes: rechazo estricto.
     */
    @Test
    fun testAuditF_samePhotoPathDifferentBytes_rejected() {
        val photoPath = "users/$userUid/beers/beer_collision_M1.jpg"
        val originalBytes = "foto_original_v1".toByteArray()
        val corruptedBytes = "foto_distinta_v2".toByteArray()

        val upload1 = storage.uploadPhoto(photoPath, userUid, originalBytes)
        assertTrue(upload1.isSuccess)

        // Intento de escribir bytes diferentes en la misma ruta
        val upload2 = storage.uploadPhoto(photoPath, userUid, corruptedBytes)
        assertTrue("Sobrescritura con bytes diferentes debe fallar", upload2.isFailure)
        assertTrue(upload2.exceptionOrNull() is IllegalStateException)
        assertTrue(upload2.exceptionOrNull()!!.message!!.contains("Infracción de inmutabilidad"))
    }

    /**
     * Test G: Tombstone existente con mutationId diferente: no confirmar revisión falsa.
     */
    @Test
    fun testAuditG_existingTombstoneDifferentMutationId_doesNotConfirmFakeRevision() {
        val syncId = "beer-audit-g"

        // Caso a: Nueva escritura de tombstone confirmada (rev 4 -> rev 5)
        val docActive = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 4L,
            isDeleted = false,
            lastMutationId = "M_INIT"
        )
        firestore.seedDocument(docActive)

        val txNew = firestore.executeWriteTombstoneTransaction(
            syncId = syncId,
            callerUid = userUid,
            expectedRevision = 4L,
            mutationId = "M_FOREIGN_DEL"
        )
        assertTrue("Caso a: debe confirmar nueva escritura de tombstone", txNew is TransactionResult.Success)
        assertEquals(5L, (txNew as TransactionResult.Success).document.revision)
        assertTrue(txNew.document.isDeleted)

        // Caso b: Reintento de la misma mutación sobre tombstone ya confirmado (idempotencia)
        val txRetry = firestore.executeWriteTombstoneTransaction(
            syncId = syncId,
            callerUid = userUid,
            expectedRevision = 4L,
            mutationId = "M_FOREIGN_DEL"
        )
        assertTrue("Caso b: reintento debe ser idempotente", txRetry is TransactionResult.Success)
        assertEquals(5L, (txRetry as TransactionResult.Success).document.revision) // No incrementa a 6

        // Caso c: Tombstone ya existente originado por otra mutación (M_MY_DEL vs M_FOREIGN_DEL)
        val txConflict = firestore.executeWriteTombstoneTransaction(
            syncId = syncId,
            callerUid = userUid,
            expectedRevision = 4L,
            mutationId = "M_MY_DEL"
        )
        assertTrue("Caso c: no debe devolver éxito de nueva escritura", txConflict is TransactionResult.TombstoneAlreadyExists)
        assertEquals(5L, (txConflict as TransactionResult.TombstoneAlreadyExists).currentDocument.revision)

        // Verificación en SimulatedDevice: aplicar DELETE WINS sin alterar la revisión histórica real
        val localBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            syncStatus = SyncStatus.DELETED,
            remoteRevision = 4L,
            baseRevision = 4L,
            lastMutationId = "M_MY_DEL"
        )
        device1.insertBeerDirect(localBeer)
        device1.insertMutationRecord(SimulatedMutationRecord(
            mutationId = "M_MY_DEL",
            syncId = syncId,
            ownerUid = userUid,
            timestamp = System.currentTimeMillis(),
            dirtyFields = emptySet(),
            baseRevision = 4L,
            status = MutationStatus.PENDING
        ))

        device1.applyTombstonePrevailed(syncId, 5L, "M_MY_DEL")

        val beerOnD1 = device1.getBeer(syncId)!!
        assertEquals("Debe quedar en DELETED", SyncStatus.DELETED, beerOnD1.syncStatus)
        assertEquals("Debe adoptar la revisión remota existente 5L (no 6L)", 5L, beerOnD1.remoteRevision)
        assertEquals("baseRevision debe adoptar 5L", 5L, beerOnD1.baseRevision)

        val myMutation = device1.getMutationHistory().last { it.mutationId == "M_MY_DEL" }
        assertEquals("Mutación local debe marcarse como SUPERSEDED", MutationStatus.SUPERSEDED, myMutation.status)
        assertNull("confirmedRevision no debe ser asignada a una mutación no escrita", myMutation.confirmedRevision)
        assertEquals("Firestore no debe haber incrementado su revisión", 5L, firestore.getDocument(syncId)!!.revision)
    }

    /**
     * Test H: mutationId local ausente: operación bloqueada.
     */
    @Test
    fun testAuditH_localMutationIdAbsent_operationBlocked() {
        val syncId = "beer-audit-h"

        val invalidBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = setOf(BeerDirtyField.COMMENT),
            lastMutationId = null
        )
        device1.insertBeerDirect(invalidBeer)

        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue("Push debe ser bloqueado por falta de mutationId durable", pushResult is PushExecutionResult.Blocked)
        assertTrue((pushResult as PushExecutionResult.Blocked).reason.contains("Mutación no durable"))
        assertNull(firestore.getDocument(syncId))
    }

    /**
     * Test I: Crash sin shutdown: recuperación de mutaciones durables.
     */
    @Test
    fun testAuditI_crashWithoutShutdown_recoversDurableMutations() {
        val syncId = "beer-audit-i"

        device1.createBeerLocalOnly(
            syncId = syncId,
            comment = "Cerveza antes del crash abrupto",
            mutationId = "M_DURABLE_CRASH"
        )

        // Muerte de proceso inesperada (crash) SIN llamar a shutdown()
        device1.crash()

        // Memoria volátil vacía
        assertNull(device1.getBeer(syncId))

        // Reinicio leyendo del almacenamiento persistente en disco
        device1.restart()

        val recoveredBeer = device1.getBeer(syncId)
        assertNotNull("La mutación durable debió sobrevivir al crash sin shutdown", recoveredBeer)
        assertEquals("Cerveza antes del crash abrupto", recoveredBeer!!.comment)
        assertEquals("M_DURABLE_CRASH", recoveredBeer.lastMutationId)
        assertEquals(SyncStatus.PENDING, recoveredBeer.syncStatus)

        val history = device1.getMutationHistory()
        assertEquals(1, history.size)
        assertEquals("M_DURABLE_CRASH", history[0].mutationId)
        assertEquals(MutationStatus.PENDING, history[0].status)
    }

    /**
     * Test de Regresión 3A: ACK perdido de ApplyPatch:
     * COMMENT confirmado, TYPE ganado por remoto.
     * El resultado local debe coincidir con los campos remotos autorizados y no conservar TYPE obsoleto,
     * preservando además photoUri intacto.
     */
    @Test
    fun testRegression3A_lostAckApplyPatch_commentConfirmedTypeRemoteWins() {
        val syncId = "beer-regr-3a"
        val mutationId = "M_D1_PATCH"
        val localPhoto = "content://media/external/images/local_beer.jpg"

        // Estado remoto: D2 ya escribió TYPE="IPA" en revision 2
        val remoteDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 3L,
            lastMutationId = mutationId,
            type = "IPA",
            comment = "Nuevo comentario confirmado D1",
            photoUrl = "https://storage.googleapis.com/photo.jpg",
            fieldRevisions = mapOf(
                BeerDirtyField.TYPE to 2L,
                BeerDirtyField.COMMENT to 3L
            )
        )
        firestore.seedDocument(remoteDoc)

        // D1 tenía baseRevision=1, remoteRevision=1, y modificó TYPE="STOUT" y COMMENT
        val localBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            type = "STOUT",
            comment = "Nuevo comentario confirmado D1",
            photoUri = localPhoto,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.TYPE),
            lastMutationId = mutationId
        )
        device1.insertBeerDirect(localBeer)
        device1.insertMutationRecord(SimulatedMutationRecord(
            mutationId = mutationId,
            syncId = syncId,
            ownerUid = userUid,
            timestamp = System.currentTimeMillis(),
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.TYPE),
            baseRevision = 1L,
            status = MutationStatus.PENDING
        ))

        // D1 ejecuta PULL para recuperar el ACK perdido
        val pullResult = coordinator.executePull(device1, syncId)
        assertTrue("PULL debe aplicar ConfirmPendingMutation", pullResult is PullExecutionResult.Applied)
        val plan = (pullResult as PullExecutionResult.Applied).plan
        assertTrue("El plan debe ser ConfirmPendingMutation", plan is PullPlan.ConfirmPendingMutation)

        val updatedBeer = device1.getBeer(syncId)!!
        assertEquals("Debe incorporar el TYPE ganador de remoto ('IPA') en lugar del local obsoleto ('STOUT')", "IPA", updatedBeer.type)
        assertEquals("Debe confirmar el COMMENT local", "Nuevo comentario confirmado D1", updatedBeer.comment)
        assertEquals("photoUri local debe preservarse intacto", localPhoto, updatedBeer.photoUri)
        assertEquals("https://storage.googleapis.com/photo.jpg", updatedBeer.remotePhotoUrl)
        assertEquals(SyncStatus.SYNCED, updatedBeer.syncStatus)
        assertEquals(3L, updatedBeer.remoteRevision)
        assertEquals(3L, updatedBeer.baseRevision)
        assertTrue("Los dirtyFields deben quedar limpios", updatedBeer.dirtyFields.isEmpty())

        val mutation = device1.getMutationHistory().last { it.mutationId == mutationId }
        assertEquals(MutationStatus.CONFIRMED, mutation.status)
        assertEquals(3L, mutation.confirmedRevision)
    }

    /**
     * Test de Regresión 3B: PUSH con campos remotos ganadores:
     * Ningún campo puede desaparecer sin incorporar su valor definitivo.
     */
    @Test
    fun testRegression3B_pushWithRemoteWinsFields_incorporatesDefinitiveRemoteValue() {
        val syncId = "beer-regr-3b"
        val mutationId = "M_D1_PATCH_B"

        // Remoto tiene TYPE="IPA" escrito en revision 2
        val remoteDoc = FakeFirestoreBeerDocument(
            syncId = syncId,
            userId = userUid,
            revision = 2L,
            lastMutationId = "M_D2_INIT",
            type = "IPA",
            comment = "Comentario antiguo",
            fieldRevisions = mapOf(
                BeerDirtyField.TYPE to 2L,
                BeerDirtyField.COMMENT to 1L
            )
        )
        firestore.seedDocument(remoteDoc)

        // D1 tiene baseRevision=1 y pretende actualizar COMMENT y TYPE="LAGER"
        val localBeer = SimulatedBeer(
            syncId = syncId,
            ownerUid = userUid,
            type = "LAGER",
            comment = "Comentario definitivo D1",
            syncStatus = SyncStatus.PENDING,
            remoteRevision = 2L,
            baseRevision = 1L,
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.TYPE),
            lastMutationId = mutationId
        )
        device1.insertBeerDirect(localBeer)
        device1.insertMutationRecord(SimulatedMutationRecord(
            mutationId = mutationId,
            syncId = syncId,
            ownerUid = userUid,
            timestamp = System.currentTimeMillis(),
            dirtyFields = setOf(BeerDirtyField.COMMENT, BeerDirtyField.TYPE),
            baseRevision = 1L,
            status = MutationStatus.PENDING
        ))

        // D1 hace PUSH: TYPE pierde ante remoto (rev 2 > base 1), COMMENT gana y se escribe
        val pushResult = coordinator.executePush(device1, syncId)
        assertTrue("PUSH debe ser exitoso", pushResult is PushExecutionResult.Success)

        val updatedBeer = device1.getBeer(syncId)!!
        assertEquals("Debe incorporar el valor ganador definitivo de remoto ('IPA')", "IPA", updatedBeer.type)
        assertEquals("Debe conservar su valor confirmado para COMMENT", "Comentario definitivo D1", updatedBeer.comment)
        assertEquals(SyncStatus.SYNCED, updatedBeer.syncStatus)
        assertEquals(3L, updatedBeer.remoteRevision)
        assertEquals(3L, updatedBeer.baseRevision)
        assertTrue("dirtyFields deben quedar vacíos tras incorporar remoto y confirmar push", updatedBeer.dirtyFields.isEmpty())

        val finalRemote = firestore.getDocument(syncId)!!
        assertEquals("IPA", finalRemote.type)
        assertEquals("Comentario definitivo D1", finalRemote.comment)
        assertEquals(3L, finalRemote.revision)
    }

    /**
     * Test de Regresión 3C: Falta de registro local necesario para confirmar un ACK:
     * Si no existe el registro en el historial local, no devolver Applied ni Success falsamente.
     */
    @Test
    fun testRegression3C_missingLocalMutationRecord_doesNotFalselyReturnSuccessOrApplied() {
        val syncIdPush = "beer-regr-3c-push"
        val ghostPushMutation = "M_GHOST_PUSH"

        // 1. PUSH con registro local faltante en historial
        device1.insertBeerDirect(SimulatedBeer(
            syncId = syncIdPush,
            ownerUid = userUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = ghostPushMutation
        ))
        // NO insertamos registro en mutationHistory

        val pushResult = coordinator.executePush(device1, syncIdPush)
        assertTrue("Push no debe devolver Success sin registro local", pushResult is PushExecutionResult.Blocked)
        assertTrue((pushResult as PushExecutionResult.Blocked).reason.contains("falta registro local"))
        val beerAfterPush = device1.getBeer(syncIdPush)!!
        assertEquals("La entidad debe conservar PENDING", SyncStatus.PENDING, beerAfterPush.syncStatus)

        // 2. PULL con registro local faltante en historial
        val syncIdPull = "beer-regr-3c-pull"
        val ghostPullMutation = "M_GHOST_PULL"

        firestore.seedDocument(FakeFirestoreBeerDocument(
            syncId = syncIdPull,
            userId = userUid,
            revision = 1L,
            lastMutationId = ghostPullMutation,
            fieldRevisions = BeerDirtyFieldsCodec.ALL_FIELDS.associateWith { 1L }
        ))

        device1.insertBeerDirect(SimulatedBeer(
            syncId = syncIdPull,
            ownerUid = userUid,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = ghostPullMutation
        ))
        // NO insertamos registro en mutationHistory

        val pullResult = coordinator.executePull(device1, syncIdPull)
        assertTrue("Pull no debe devolver Applied sin registro local", pullResult is PullExecutionResult.Blocked)
        assertTrue((pullResult as PullExecutionResult.Blocked).reason.contains("registro local ausente"))
        val beerAfterPull = device1.getBeer(syncIdPull)!!
        assertEquals("La entidad debe conservar PENDING", SyncStatus.PENDING, beerAfterPull.syncStatus)
    }

    /**
     * Test de Regresión 3D: Sincronización entre dos dispositivos:
     * Comprobar igualdad estricta de valores, revisiones y estado de sincronización entre D1 y D2.
     */
    @Test
    fun testRegression3D_twoDevicesSync_checksDeepEqualityRevisionsAndSyncStatus() {
        val syncId = "beer-regr-3d"

        // 1. D1 crea cerveza y sincroniza a rev 1
        device1.createBeerLocalOnly(
            syncId = syncId,
            type = "TRIPLE",
            comment = "Inicial de D1",
            latitude = 40.4168,
            longitude = -3.7038,
            mutationId = "M_D1_CREATE"
        )
        val push1 = coordinator.executePush(device1, syncId)
        assertTrue(push1 is PushExecutionResult.Success)

        // 2. D2 descarga la cerveza vía PULL
        val pullD2 = coordinator.executePull(device2, syncId)
        assertTrue(pullD2 is PullExecutionResult.Applied)

        // 3. D1 edita comentario y hace PUSH a rev 2
        device1.updateBeerLocal(
            syncId = syncId,
            comment = "Comentario actualizado por D1",
            modifiedFields = setOf(BeerDirtyField.COMMENT),
            mutationId = "M_D1_EDIT"
        )
        val pushD1 = coordinator.executePush(device1, syncId)
        assertTrue(pushD1 is PushExecutionResult.Success)

        // 4. D2 (desconectado) edita ubicación localmente
        device2.updateBeerLocal(
            syncId = syncId,
            latitude = 41.3851,
            longitude = 2.1734,
            modifiedFields = setOf(BeerDirtyField.LOCATION),
            mutationId = "M_D2_EDIT"
        )

        // D2 se reconecta: hace PULL (MergeWithPending) y PUSH a rev 3
        val pullMergeD2 = coordinator.executePull(device2, syncId)
        assertTrue(pullMergeD2 is PullExecutionResult.Applied)
        val pushD2 = coordinator.executePush(device2, syncId)
        assertTrue(pushD2 is PushExecutionResult.Success)

        // 5. D1 hace PULL para recibir los cambios de D2
        val pullD1 = coordinator.executePull(device1, syncId)
        assertTrue(pullD1 is PullExecutionResult.Applied)

        // 6. Verificación de convergencia total e igualdad estricta campo por campo
        val b1 = device1.getBeer(syncId)!!
        val b2 = device2.getBeer(syncId)!!
        val remoteDoc = firestore.getDocument(syncId)!!

        assertEquals("Ambos dispositivos deben tener el mismo tipo", b1.type, b2.type)
        assertEquals("Ambos dispositivos deben tener el mismo comentario", b1.comment, b2.comment)
        assertEquals("Ambos dispositivos deben tener la misma latitud", b1.latitude, b2.latitude)
        assertEquals("Ambos dispositivos deben tener la misma longitud", b1.longitude, b2.longitude)
        assertEquals("Ambos dispositivos deben tener la misma remoteRevision", b1.remoteRevision, b2.remoteRevision)
        assertEquals("Ambos dispositivos deben tener la misma baseRevision", b1.baseRevision, b2.baseRevision)
        assertEquals("Ambos dispositivos deben estar SYNCED", SyncStatus.SYNCED, b1.syncStatus)
        assertEquals("Ambos dispositivos deben estar SYNCED", SyncStatus.SYNCED, b2.syncStatus)
        assertTrue("D1 debe tener dirtyFields limpios", b1.dirtyFields.isEmpty())
        assertTrue("D2 debe tener dirtyFields limpios", b2.dirtyFields.isEmpty())

        // Coherencia con el documento remoto
        assertEquals(remoteDoc.type, b1.type)
        assertEquals(remoteDoc.comment, b1.comment)
        assertEquals(remoteDoc.latitude, b1.latitude)
        assertEquals(remoteDoc.longitude, b1.longitude)
        assertEquals(remoteDoc.revision, b1.remoteRevision)
        assertEquals(3L, b1.remoteRevision)
    }
}
