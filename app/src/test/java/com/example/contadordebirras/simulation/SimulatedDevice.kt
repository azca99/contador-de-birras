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

    // Almacenamiento local en disco simulado
    private val localBeers = mutableMapOf<String, SimulatedBeer>()
    private val mutationHistory = mutableListOf<SimulatedMutationRecord>()

    // Copia de seguridad para simular reinicio de proceso
    private var persistedBeersSnapshot: Map<String, SimulatedBeer>? = null
    private var persistedHistorySnapshot: List<SimulatedMutationRecord>? = null

    // --- Ciclo de Vida y Recuperación ante Reinicios ---

    /**
     * Simula el cierre o crash de la aplicación, guardando en disco el estado actual.
     */
    fun shutdown() {
        persistedBeersSnapshot = localBeers.mapValues { it.value.copy() }
        persistedHistorySnapshot = mutationHistory.map { it.copy() }
    }

    /**
     * Simula el reinicio de la aplicación desde el disco, recuperando mutaciones pendientes e historial.
     */
    fun restart() {
        localBeers.clear()
        persistedBeersSnapshot?.let { snapshot ->
            for ((k, v) in snapshot) {
                localBeers[k] = v.copy()
            }
        }
        mutationHistory.clear()
        persistedHistorySnapshot?.let { history ->
            for (rec in history) {
                mutationHistory.add(rec.copy())
            }
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
        mutationId: String = "mut_${deviceId}_${System.currentTimeMillis()}"
    ): SimulatedBeer {
        val beer = SimulatedBeer(
            syncId = syncId,
            ownerUid = ownerUid,
            type = type,
            comment = comment,
            photoUri = photoUri,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
            lastMutationId = mutationId
        )
        localBeers[syncId] = beer
        mutationHistory.add(
            SimulatedMutationRecord(
                mutationId = mutationId,
                syncId = syncId,
                ownerUid = ownerUid,
                timestamp = System.currentTimeMillis(),
                dirtyFields = BeerDirtyFieldsCodec.ALL_FIELDS,
                baseRevision = LOCAL_ONLY_REVISION,
                status = MutationStatus.PENDING
            )
        )
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
        modifiedFields: Set<BeerDirtyField>,
        mutationId: String = "mut_${deviceId}_${System.currentTimeMillis()}"
    ): SimulatedBeer {
        val existing = localBeers[syncId] ?: throw IllegalArgumentException("Cerveza no existe en local: $syncId")
        if (type != null) existing.type = type
        if (comment != null) existing.comment = comment
        if (photoUri != null) existing.photoUri = photoUri

        // Acumulación canónica de dirty fields
        val updatedDirty = existing.dirtyFields + modifiedFields
        existing.dirtyFields = updatedDirty
        existing.syncStatus = SyncStatus.PENDING
        existing.lastMutationId = mutationId

        mutationHistory.add(
            SimulatedMutationRecord(
                mutationId = mutationId,
                syncId = syncId,
                ownerUid = ownerUid,
                timestamp = System.currentTimeMillis(),
                dirtyFields = modifiedFields,
                baseRevision = existing.baseRevision,
                status = MutationStatus.PENDING
            )
        )
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

        mutationHistory.add(
            SimulatedMutationRecord(
                mutationId = mutationId,
                syncId = syncId,
                ownerUid = ownerUid,
                timestamp = System.currentTimeMillis(),
                dirtyFields = emptySet(),
                baseRevision = existing.baseRevision,
                status = MutationStatus.PENDING
            )
        )
        return existing.copy()
    }

    /**
     * Inserta directamente una cerveza (ej. para configurar estado inicial o simular inserción PULL).
     */
    fun insertBeerDirect(beer: SimulatedBeer) {
        localBeers[beer.syncId] = beer.copy()
    }

    /**
     * Inserta un registro de mutación en el historial.
     */
    fun insertMutationRecord(record: SimulatedMutationRecord) {
        mutationHistory.add(record.copy())
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
        beer.syncStatus = SyncStatus.SYNCED
        beer.remoteRevision = newRemoteRevision
        beer.baseRevision = newRemoteRevision
        beer.dirtyFields = emptySet()
        if (remotePhotoUrl != null) {
            beer.remotePhotoUrl = remotePhotoUrl
        }

        // Actualizar historial local
        val record = mutationHistory.lastOrNull { it.mutationId == expectedMutationId }
        record?.let {
            it.status = MutationStatus.CONFIRMED
            it.confirmedRevision = newRemoteRevision
        }
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
        beer.remoteRevision = newRemoteRevision
        beer.baseRevision = newRemoteRevision
        beer.syncStatus = SyncStatus.DELETED

        val record = mutationHistory.lastOrNull { it.mutationId == expectedMutationId }
        record?.let {
            it.status = MutationStatus.CONFIRMED
            it.confirmedRevision = newRemoteRevision
        }
        return true
    }

    /**
     * Aplica una confirmación parcial (algunos campos confirmados, otros continúan dirty o ganados por remoto).
     *
     * INVARIANTE CRÍTICA:
     * Si remainingDirtyFields no está vacío, baseRevision NO avanza y el estado de la mutación en
     * el historial se marca como PARTIALLY_CONFIRMED (nunca CONFIRMED).
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

        val record = mutationHistory.lastOrNull { it.mutationId == expectedMutationId }
        record?.let {
            if (remainingDirtyFields.isEmpty()) {
                it.status = MutationStatus.CONFIRMED
                it.confirmedRevision = newRemoteRevision
            } else {
                it.status = MutationStatus.PARTIALLY_CONFIRMED
                it.confirmedRevision = newRemoteRevision
            }
        }
        return true
    }
}
