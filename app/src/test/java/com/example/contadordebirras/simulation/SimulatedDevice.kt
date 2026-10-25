package com.example.contadordebirras.simulation

import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.LOCAL_ONLY_REVISION
import com.example.contadordebirras.domain.LocalMutationContext

/**
 * Representación en memoria de la entidad Beer local en el dispositivo simulado (espejo de BeerEntity en Room).
 */
data class SimulatedBeer(
    val syncId: String,
    val ownerUid: String,
    var type: String = "LATA",
    var timestamp: Long = System.currentTimeMillis(),
    var comment: String? = null,
    var photoUri: String? = null,
    var remotePhotoUrl: String? = null,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var syncStatus: String = SyncStatus.PENDING,
    var remoteRevision: Long = LOCAL_ONLY_REVISION,
    var baseRevision: Long = LOCAL_ONLY_REVISION,
    var dirtyFields: Set<BeerDirtyField> = emptySet(),
    var lastMutationId: String? = null
) {
    fun toLocalMutationContext(): LocalMutationContext =
        LocalMutationContext(
            syncStatus = syncStatus,
            remoteRevision = remoteRevision,
            baseRevision = baseRevision,
            dirtyFields = dirtyFields,
            lastMutationId = lastMutationId
        )
}

/**
 * Registro de mutación local en el dispositivo simulado (espejo de LocalMutationHistoryEntity en Room v10).
 */
data class SimulatedMutationRecord(
    val mutationId: String,
    val syncId: String,
    val ownerUid: String,
    val timestamp: Long,
    val dirtyFields: Set<BeerDirtyField>,
    val baseRevision: Long,
    var status: String = MutationStatus.PENDING,
    var confirmedRevision: Long? = null
)

/**
 * Dispositivo móvil simulado para pruebas de sincronización multidispositivo (Fase C4.2).
 *
 * Mantiene:
 * - Tabla local simulada de cervezas (`beers`).
 * - Historial durable local de mutaciones (`local_mutation_history`).
 * - Estado de conectividad (`isOnline`).
 * - Persistencia simulada ante reinicios / cierres de app (`shutdown` y `restart`).
 * - Invariantes causales estrictas: nunca avanza `baseRevision` si quedan campos dirty pendientes.
 * - Invariante de historial: nunca marca `CONFIRMED` una mutación si quedan campos pendientes.
 */
class SimulatedDevice(
    val deviceId: String,
    val ownerUid: String
) {
    var isOnline: Boolean = true

    // Almacén en disco simulado (persistencia durable que sobrevive a crash sin shutdown)
    private val diskStorageBeers = mutableMapOf<String, SimulatedBeer>()
    private val diskStorageHistory = mutableListOf<SimulatedMutationRecord>()

    // Caché en memoria del proceso activo
    private val localBeers = mutableMapOf<String, SimulatedBeer>()
    private val mutationHistory = mutableListOf<SimulatedMutationRecord>()

    // --- Ciclo de Vida y Recuperación ante Reinicios y Crashes ---

    /**
     * Simula el cierre ordenado de la aplicación (destruye el proceso en memoria tras flush).
     */
    fun shutdown() {
        // En un cierre ordenado, los buffers de memoria se han vaciado a disco
        localBeers.clear()
        mutationHistory.clear()
    }

    /**
     * Simula la muerte inesperada de proceso (crash / SIGKILL) sin ejecutar shutdown.
     * Toda la memoria RAM se pierde instantáneamente, pero los datos durables comprometidos
     * en disco permanecen intactos.
     */
    fun crash() {
        localBeers.clear()
        mutationHistory.clear()
    }

    /**
     * Simula el reinicio de la aplicación leyendo el estado durable desde disco.
     */
    fun restart() {
        localBeers.clear()
        for ((k, v) in diskStorageBeers) {
            localBeers[k] = v.copy()
        }
        mutationHistory.clear()
        for (rec in diskStorageHistory) {
            mutationHistory.add(rec.copy())
        }
    }

    private fun commitToDisk(beer: SimulatedBeer) {
        diskStorageBeers[beer.syncId] = beer.copy()
    }

    private fun commitHistoryToDisk(record: SimulatedMutationRecord) {
        val idx = diskStorageHistory.indexOfFirst { it.mutationId == record.mutationId }
        if (idx >= 0) {
            diskStorageHistory[idx] = record.copy()
        } else {
            diskStorageHistory.add(record.copy())
        }
    }

    // --- Operaciones Locales del Usuario ---

    /**
     * Crea una cerveza nueva en local en estado PENDING local-only (-1/-1).
     */
    fun createBeerLocalOnly(
        syncId: String,
        type: String = "LATA",
        comment: String? = null,
        photoUri: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        mutationId: String = "mut_${deviceId}_${System.currentTimeMillis()}"
    ): SimulatedBeer {
        val beer = SimulatedBeer(
            syncId = syncId,
            ownerUid = ownerUid,
            type = type,
            comment = comment,
            photoUri = photoUri,
            latitude = latitude,
            longitude = longitude,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = mutationId
        )
        localBeers[syncId] = beer
        commitToDisk(beer)

        val record = SimulatedMutationRecord(
            mutationId = mutationId,
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = System.currentTimeMillis(),
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            baseRevision = LOCAL_ONLY_REVISION,
            status = MutationStatus.PENDING
        )
        mutationHistory.add(record)
        commitHistoryToDisk(record)
        return beer.copy()
    }

    /**
     * Edita campos de una cerveza existente en local.
     */
    fun updateBeerLocal(
        syncId: String,
        type: String? = null,
        comment: String? = null,
        photoUri: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        modifiedFields: Set<BeerDirtyField>,
        mutationId: String = "mut_${deviceId}_${System.currentTimeMillis()}"
    ): SimulatedBeer {
        val existing = localBeers[syncId] ?: throw IllegalArgumentException("Cerveza no existe en local: $syncId")
        if (type != null) existing.type = type
        if (comment != null) existing.comment = comment
        if (photoUri != null) existing.photoUri = photoUri
        if (latitude != null) existing.latitude = latitude
        if (longitude != null) existing.longitude = longitude

        // Acumulación canónica de dirty fields
        val updatedDirty = existing.dirtyFields + modifiedFields
        existing.dirtyFields = updatedDirty
        existing.syncStatus = SyncStatus.PENDING
        existing.lastMutationId = mutationId
        commitToDisk(existing)

        val record = SimulatedMutationRecord(
            mutationId = mutationId,
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = System.currentTimeMillis(),
            dirtyFields = modifiedFields,
            baseRevision = existing.baseRevision,
            status = MutationStatus.PENDING
        )
        mutationHistory.add(record)
        commitHistoryToDisk(record)
        return existing.copy()
    }

    /**
     * Marca una cerveza como DELETED en local (eliminación lógica).
     */
    fun deleteBeerLocal(
        syncId: String,
        mutationId: String = "mut_del_${deviceId}_${System.currentTimeMillis()}"
    ): SimulatedBeer {
        val existing = localBeers[syncId] ?: throw IllegalArgumentException("Cerveza no existe en local: $syncId")
        existing.syncStatus = SyncStatus.DELETED
        existing.lastMutationId = mutationId
        commitToDisk(existing)

        val record = SimulatedMutationRecord(
            mutationId = mutationId,
            syncId = syncId,
            ownerUid = ownerUid,
            timestamp = System.currentTimeMillis(),
            dirtyFields = emptySet(),
            baseRevision = existing.baseRevision,
            status = MutationStatus.PENDING
        )
        mutationHistory.add(record)
        commitHistoryToDisk(record)
        return existing.copy()
    }

    /**
     * Inserta directamente una cerveza (ej. para configurar estado inicial o simular inserción PULL).
     */
    fun insertBeerDirect(beer: SimulatedBeer) {
        localBeers[beer.syncId] = beer.copy()
        commitToDisk(beer)
    }

    /**
     * Inserta un registro de mutación en el historial.
     */
    fun insertMutationRecord(record: SimulatedMutationRecord) {
        mutationHistory.add(record.copy())
        commitHistoryToDisk(record)
    }

    // --- Consultas Locales ---

    fun getBeer(syncId: String): SimulatedBeer? = localBeers[syncId]?.copy()

    fun getAllBeers(): List<SimulatedBeer> = localBeers.values.map { it.copy() }

    fun getMutationHistory(): List<SimulatedMutationRecord> = mutationHistory.map { it.copy() }

    fun getPastLocalMutationIds(syncId: String): Set<String> {
        val currentLastMutation = localBeers[syncId]?.lastMutationId
        return mutationHistory
            .filter { it.syncId == syncId && it.mutationId != currentLastMutation }
            .map { it.mutationId }
            .toSet()
    }

    // --- Aplicación Segura de Confirmaciones Locales ---

    /**
     * Aplica la confirmación total de un push (todos los dirty fields confirmados).
     */
    fun applyFullPushConfirmation(
        syncId: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        remotePhotoUrl: String? = null
    ): Boolean {
        val beer = localBeers[syncId] ?: return false
        if (beer.lastMutationId != expectedMutationId) {
            // Concurrencia: el usuario editó nuevamente mientras el push estaba en vuelo
            return false
        }
        // Actualizar historial local: exige existencia del registro durable antes de mutar la entidad
        val record = mutationHistory.lastOrNull { it.mutationId == expectedMutationId } ?: return false

        beer.syncStatus = SyncStatus.SYNCED
        beer.remoteRevision = newRemoteRevision
        beer.baseRevision = newRemoteRevision
        beer.dirtyFields = emptySet()
        if (remotePhotoUrl != null) {
            beer.remotePhotoUrl = remotePhotoUrl
        }
        commitToDisk(beer)

        record.status = MutationStatus.CONFIRMED
        record.confirmedRevision = newRemoteRevision
        commitHistoryToDisk(record)
        return true
    }

    /**
     * Aplica confirmación de borrado (WriteTombstone).
     */
    fun applyDeleteConfirmation(
        syncId: String,
        expectedMutationId: String,
        newRemoteRevision: Long
    ): Boolean {
        val beer = localBeers[syncId] ?: return false
        if (beer.lastMutationId != expectedMutationId) return false
        val record = mutationHistory.lastOrNull { it.mutationId == expectedMutationId } ?: return false

        beer.remoteRevision = newRemoteRevision
        beer.baseRevision = newRemoteRevision
        beer.syncStatus = SyncStatus.DELETED
        commitToDisk(beer)

        record.status = MutationStatus.CONFIRMED
        record.confirmedRevision = newRemoteRevision
        commitHistoryToDisk(record)
        return true
    }

    /**
     * Aplica un tombstone preexistente en remoto originado por otra mutación (DELETE WINS).
     *
     * INVARIANTE:
     * La entidad local transiciona a DELETED adoptando la revisión histórica real [currentRemoteRevision].
     * La mutación local pendiente [supersededMutationId] NO se confirma; se marca como SUPERSEDED
     * en el historial local sin asignar revisión confirmada.
     */
    fun applyTombstonePrevailed(
        syncId: String,
        currentRemoteRevision: Long,
        supersededMutationId: String?
    ) {
        val beer = localBeers[syncId] ?: return
        beer.syncStatus = SyncStatus.DELETED
        beer.remoteRevision = currentRemoteRevision
        beer.baseRevision = currentRemoteRevision
        beer.dirtyFields = emptySet()
        commitToDisk(beer)

        if (supersededMutationId != null) {
            val record = mutationHistory.lastOrNull { it.mutationId == supersededMutationId }
            record?.let {
                it.status = MutationStatus.SUPERSEDED
                it.confirmedRevision = null
                commitHistoryToDisk(it)
            }
        }
    }

    /**
     * Aplica la confirmación de una mutación pendiente recibida durante PULL (ConfirmPendingMutation).
     *
     * INVARIANTES:
     * - Exige existencia de la cerveza y coincidencia estricta con expectedMutationId.
     * - Exige existencia del registro durable en mutationHistory.
     * - Aplica los campos remotos autorizados (remoteFieldsToApply) desde remoteDoc sin perder photoUri.
     * - Resta confirmedFields y remoteWinsFields de dirtyFields.
     * - Si remainingDirtyFields está vacío -> SYNCED, baseRevision avanza a newRemoteRevision.
     * - Si remainingDirtyFields NO está vacío -> PENDING, baseRevision permanece congelada.
     */
    fun applyConfirmPendingMutation(
        syncId: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        confirmedFields: Set<BeerDirtyField>,
        remoteWinsFields: Set<BeerDirtyField>,
        remoteFieldsToApply: Set<BeerDirtyField>,
        remoteDoc: FakeFirestoreBeerDocument?
    ): Boolean {
        val beer = localBeers[syncId] ?: return false
        if (beer.lastMutationId != expectedMutationId) return false
        val record = mutationHistory.lastOrNull { it.mutationId == expectedMutationId } ?: return false

        if (remoteFieldsToApply.isNotEmpty()) {
            if (remoteDoc == null) return false
            if (remoteFieldsToApply.contains(BeerDirtyField.TYPE)) beer.type = remoteDoc.type
            if (remoteFieldsToApply.contains(BeerDirtyField.TIMESTAMP)) beer.timestamp = remoteDoc.timestamp
            if (remoteFieldsToApply.contains(BeerDirtyField.COMMENT)) beer.comment = remoteDoc.comment
            if (remoteFieldsToApply.contains(BeerDirtyField.PHOTO)) {
                beer.remotePhotoUrl = remoteDoc.photoUrl
                // Preservar photoUri local intacto
            }
            if (remoteFieldsToApply.contains(BeerDirtyField.LOCATION)) {
                beer.latitude = remoteDoc.latitude
                beer.longitude = remoteDoc.longitude
            }
        }
        if (remoteDoc?.photoUrl != null) {
            beer.remotePhotoUrl = remoteDoc.photoUrl
        }

        val remainingDirtyFields = beer.dirtyFields - confirmedFields - remoteWinsFields
        beer.remoteRevision = newRemoteRevision
        beer.dirtyFields = remainingDirtyFields

        if (remainingDirtyFields.isEmpty()) {
            beer.syncStatus = SyncStatus.SYNCED
            beer.baseRevision = newRemoteRevision
            record.status = MutationStatus.CONFIRMED
            record.confirmedRevision = newRemoteRevision
        } else {
            beer.syncStatus = SyncStatus.PENDING
            // baseRevision permanece congelada
            record.status = MutationStatus.PARTIALLY_CONFIRMED
            record.confirmedRevision = newRemoteRevision
        }

        commitToDisk(beer)
        commitHistoryToDisk(record)
        return true
    }

    /**
     * Aplica confirmación de ApplyPatch durante PUSH, incorporando campos remotos ganadores.
     *
     * INVARIANTES:
     * - Exige existencia de la cerveza y coincidencia con expectedMutationId.
     * - Exige existencia del registro durable en mutationHistory.
     * - Si remoteWinsFields no está vacío, exige remoteDoc e incorpora sus valores antes de declarar éxito.
     * - Preserva photoUri local intacto.
     * - Si remainingDirtyFields está vacío -> SYNCED, baseRevision avanza a newRemoteRevision.
     * - Si remainingDirtyFields NO está vacío -> PENDING, baseRevision permanece congelada.
     */
    fun applyPartialPushConfirmation(
        syncId: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        confirmedFields: Set<BeerDirtyField>,
        remoteWinsFields: Set<BeerDirtyField>,
        remoteDoc: FakeFirestoreBeerDocument?,
        remotePhotoUrl: String? = null
    ): Boolean {
        val beer = localBeers[syncId] ?: return false
        if (beer.lastMutationId != expectedMutationId) return false
        val record = mutationHistory.lastOrNull { it.mutationId == expectedMutationId } ?: return false

        if (remoteWinsFields.isNotEmpty()) {
            if (remoteDoc == null) return false
            if (remoteWinsFields.contains(BeerDirtyField.TYPE)) beer.type = remoteDoc.type
            if (remoteWinsFields.contains(BeerDirtyField.TIMESTAMP)) beer.timestamp = remoteDoc.timestamp
            if (remoteWinsFields.contains(BeerDirtyField.COMMENT)) beer.comment = remoteDoc.comment
            if (remoteWinsFields.contains(BeerDirtyField.PHOTO)) {
                beer.remotePhotoUrl = remoteDoc.photoUrl
                // Preservar photoUri local intacto
            }
            if (remoteWinsFields.contains(BeerDirtyField.LOCATION)) {
                beer.latitude = remoteDoc.latitude
                beer.longitude = remoteDoc.longitude
            }
        }

        if (remotePhotoUrl != null) {
            beer.remotePhotoUrl = remotePhotoUrl
        }

        val remainingDirtyFields = beer.dirtyFields - confirmedFields - remoteWinsFields
        beer.remoteRevision = newRemoteRevision
        beer.dirtyFields = remainingDirtyFields

        if (remainingDirtyFields.isEmpty()) {
            beer.syncStatus = SyncStatus.SYNCED
            beer.baseRevision = newRemoteRevision
            record.status = MutationStatus.CONFIRMED
            record.confirmedRevision = newRemoteRevision
        } else {
            beer.syncStatus = SyncStatus.PENDING
            // baseRevision permanece congelada
            record.status = MutationStatus.PARTIALLY_CONFIRMED
            record.confirmedRevision = newRemoteRevision
        }

        commitToDisk(beer)
        commitHistoryToDisk(record)
        return true
    }

    /**
     * Aplica una confirmación parcial directa (algunos campos confirmados, otros continúan dirty).
     */
    fun applyPartialConfirmation(
        syncId: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        confirmedFields: Set<BeerDirtyField>,
        remainingDirtyFields: Set<BeerDirtyField>,
        remotePhotoUrl: String? = null
    ): Boolean {
        val beer = localBeers[syncId] ?: return false
        if (beer.lastMutationId != expectedMutationId) return false
        val record = mutationHistory.lastOrNull { it.mutationId == expectedMutationId } ?: return false

        beer.remoteRevision = newRemoteRevision
        beer.dirtyFields = remainingDirtyFields
        if (remainingDirtyFields.isEmpty()) {
            beer.syncStatus = SyncStatus.SYNCED
            beer.baseRevision = newRemoteRevision
        } else {
            // Permanece PENDING con baseRevision congelada
            beer.syncStatus = SyncStatus.PENDING
        }
        if (remotePhotoUrl != null) {
            beer.remotePhotoUrl = remotePhotoUrl
        }
        commitToDisk(beer)

        if (remainingDirtyFields.isEmpty()) {
            record.status = MutationStatus.CONFIRMED
            record.confirmedRevision = newRemoteRevision
        } else {
            record.status = MutationStatus.PARTIALLY_CONFIRMED
            record.confirmedRevision = newRemoteRevision
        }
        commitHistoryToDisk(record)
        return true
    }
}
