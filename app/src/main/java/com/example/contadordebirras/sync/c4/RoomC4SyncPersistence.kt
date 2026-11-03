package com.example.contadordebirras.sync.c4

import androidx.room.withTransaction
import com.example.contadordebirras.data.*
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType

/**
 * Adaptador de persistencia durable en Room v10 bajo el Protocolo C4.
 *
 * Resuelve formalmente las operaciones transaccionales aplazadas en C4.1B:
 * - Registro durable previo a cualquier intento de red.
 * - Confirmación atómica de PUSH completo con historial.
 * - Confirmación atómica de PATCH parcial con validación estricta de partición y preservación de baseRevision.
 * - Aplicación de Tombstone remoto prevalente (transición a DELETED y marcado SUPERSEDED de mutación local).
 * - Aislamiento estricto por ownerUid e inmutabilidad de photoUri.
 */
class RoomC4SyncPersistence(
    val database: BeerDatabase
) {
    val beerDao: BeerDao = database.beerDao()
    val historyDao: LocalMutationHistoryDao = database.localMutationHistoryDao()

    /**
     * 1. Registro durable previo a red:
     * Toda mutación local DEBE quedar persistida en local_mutation_history con status PENDING
     * antes de emitir cualquier petición hacia Firestore o Storage.
     */
    fun recordDurableMutationBeforeNetwork(
        syncId: String,
        ownerUid: String,
        mutationId: String,
        dirtyFields: String?,
        baseRevision: Long,
        isLocalOnlyCreation: Boolean,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        database.beginTransaction()
        try {
            // Marcar mutaciones previas pendientes como SUPERSEDED
            beerDao.supersedePriorPendingHistoryRecords(syncId, ownerUid, mutationId)

            val record = LocalMutationHistoryEntity(
                mutationId = mutationId,
                syncId = syncId,
                ownerUid = ownerUid,
                timestamp = timestamp,
                dirtyFields = dirtyFields ?: "",
                baseRevision = baseRevision,
                status = MutationStatus.PENDING,
                isLocalOnlyCreation = isLocalOnlyCreation
            )
            val rowId = beerDao.insertHistoryRecord(record)
            if (rowId <= 0) return false

            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    /**
     * 2. Confirmación atómica de PUSH completo (CreateActive o ApplyPatch con todos los campos confirmados).
     */
    fun confirmPushFullSuccess(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        newRemotePhotoUrl: String? = null,
        newPhotoSource: String? = null,
        clearRemotePhoto: Boolean = false,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        return beerDao.confirmPushSuccessWithHistory(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = expectedMutationId,
            newRemoteRevision = newRemoteRevision,
            newRemotePhotoUrl = newRemotePhotoUrl,
            newPhotoSource = newPhotoSource,
            clearRemotePhoto = clearRemotePhoto,
            updatedAt = updatedAt
        )
    }

    /**
     * 3. Confirmación atómica de PATCH parcial (operación C4.4):
     *
     * Valida formalmente la partición exacta entre confirmedFields y remainingDirtyFields frente
     * al dirtyFields previo en Room.
     * Avanza remoteRevision a newRemoteRevision pero PRESERVA baseRevision en su valor original.
     * Marca local_mutation_history como PARTIALLY_CONFIRMED.
     * Garantiza rollback atómico si cualquiera de las actualizaciones falla.
     */
    fun confirmPushPartialPatch(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        remainingDirtyFields: String,
        confirmedFields: String,
        newRemotePhotoUrl: String? = null,
        newPhotoSource: String? = null,
        clearRemotePhoto: Boolean = false,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        database.beginTransaction()
        try {
            val beer = beerDao.getBeerBySyncId(syncId, ownerUid) ?: return false
            if (beer.syncStatus != SyncStatus.PENDING ||
                beer.lastMutationId != expectedMutationId ||
                beer.remoteRevision > newRemoteRevision
            ) {
                return false
            }

            // Validación estricta de partición causal matemática
            val isPartitionValid = beerDao.validatePartialPatchSpecification(
                originalDirtyFields = beer.dirtyFields,
                confirmedFields = confirmedFields,
                remainingDirtyFields = remainingDirtyFields
            )
            if (!isPartitionValid) return false

            val history = beerDao.getHistoryRecordById(expectedMutationId, ownerUid) ?: return false
            if (history.syncId != syncId ||
                (history.status != MutationStatus.PENDING && history.status != MutationStatus.PARTIALLY_CONFIRMED)
            ) {
                return false
            }

            // Ejecutar actualización directa de los campos restantes y avance de remoteRevision
            val updatedPhotoUrl = when {
                clearRemotePhoto -> null
                newRemotePhotoUrl != null -> newRemotePhotoUrl
                else -> beer.remotePhotoUrl
            }
            val updatedPhotoSource = when {
                clearRemotePhoto -> null
                newPhotoSource != null -> newPhotoSource
                else -> beer.photoSource
            }

            // Actualización en tabla beers preservando baseRevision y photoUri
            val updatedBeer = beer.copy(
                remoteRevision = newRemoteRevision,
                // INVARIANTE CAUSAL: baseRevision NO se modifica en parche parcial
                baseRevision = beer.baseRevision,
                dirtyFields = remainingDirtyFields,
                remotePhotoUrl = updatedPhotoUrl,
                photoSource = updatedPhotoSource,
                updatedAt = updatedAt
            )

            // Actualizar entidad beers (comprobación estricta de 1 fila afectada)
            val updateCount = updateBeerEntityDirect(updatedBeer)
            if (updateCount != 1) return false

            // Actualizar historial durable a PARTIALLY_CONFIRMED
            val histUpdated = beerDao.markHistoryRecordPartiallyConfirmed(
                mutationId = expectedMutationId,
                syncId = syncId,
                ownerUid = ownerUid,
                confirmedRevision = newRemoteRevision,
                confirmedAt = updatedAt
            )
            if (histUpdated != 1) return false

            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Confirmación atómica de WriteTombstone:
     * El documento remoto ya fue borrado en Firestore. En Room:
     * - Permanece en DELETED (sin borrado físico).
     * - Adopta la nueva remoteRevision y baseRevision remota.
     * - Limpia dirtyFields y lastMutationId para no volver a emitir un PUSH redundante.
     * - Transita el registro de mutación en historial a CONFIRMED.
     */
    fun confirmTombstonePushSuccess(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        database.beginTransaction()
        try {
            val beer = beerDao.getBeerBySyncId(syncId, ownerUid) ?: return false
            if (beer.syncStatus != SyncStatus.DELETED || beer.lastMutationId != expectedMutationId) {
                return false
            }
            if (beer.remoteRevision > newRemoteRevision) {
                return false
            }

            // Validar el historial antes de modificar la entidad en beers
            val history = beerDao.getHistoryRecordById(expectedMutationId, ownerUid) ?: return false
            if (history.syncId != syncId ||
                (history.status != MutationStatus.PENDING && history.status != MutationStatus.PARTIALLY_CONFIRMED)
            ) {
                return false
            }

            val updatedBeer = beer.copy(
                syncStatus = SyncStatus.DELETED,
                remoteRevision = newRemoteRevision,
                baseRevision = newRemoteRevision,
                dirtyFields = null,
                lastMutationId = null,
                deletedAt = beer.deletedAt ?: timestamp,
                updatedAt = timestamp
            )
            val updateCount = updateBeerEntityDirect(updatedBeer)
            if (updateCount != 1) return false

            val histUpdated = beerDao.confirmHistoryRecord(
                mutationId = expectedMutationId,
                syncId = syncId,
                ownerUid = ownerUid,
                confirmedRevision = newRemoteRevision,
                confirmedAt = timestamp
            )
            if (histUpdated != 1) {
                return false
            }

            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    /**
     * 4. Tombstone remoto prevalente frente a edición local:
     * Transita a DELETED, adopta revisión remota, limpia campos dirty y marca la mutación local desplazada como SUPERSEDED.
     */
    fun applyTombstonePrevailed(
        syncId: String,
        ownerUid: String,
        displacedMutationId: String,
        remoteTombstoneRevision: Long,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        database.beginTransaction()
        try {
            val beer = beerDao.getBeerBySyncId(syncId, ownerUid) ?: return false

            val updatedBeer = beer.copy(
                syncStatus = SyncStatus.DELETED,
                deletedAt = timestamp,
                remoteRevision = remoteTombstoneRevision,
                baseRevision = remoteTombstoneRevision,
                dirtyFields = null,
                lastMutationId = null,
                updatedAt = timestamp
            )
            val updateCount = updateBeerEntityDirect(updatedBeer)
            if (updateCount != 1) return false

            // Marcar la mutación como SUPERSEDED en el historial (nunca CONFIRMED)
            beerDao.supersedePriorPendingHistoryRecords(syncId, ownerUid, activeMutationId = "none")

            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    /**
     * 5. Aplicar actualización remota autorizada sobre documento SYNCED localmente.
     */
    fun applyRemoteUpdateToSynced(
        syncId: String,
        ownerUid: String,
        newRemoteRevision: Long,
        type: BeerType,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?,
        comment: String?,
        locationName: String?,
        remotePhotoUrl: String?,
        photoSource: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val rows = beerDao.applyRemoteUpdateToSyncedBeer(
            syncId = syncId,
            ownerUid = ownerUid,
            newRemoteRevision = newRemoteRevision,
            type = type,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            comment = comment,
            locationName = locationName,
            remotePhotoUrl = remotePhotoUrl,
            photoSource = photoSource,
            updatedAt = updatedAt
        )
        return rows == 1
    }

    /**
     * 6. Aplicar tombstone remoto sobre documento SYNCED localmente.
     */
    fun applyRemoteTombstoneToSynced(
        syncId: String,
        ownerUid: String,
        remoteRevision: Long,
        deletedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val rows = beerDao.applyRemoteTombstoneToSyncedBeer(
            syncId = syncId,
            ownerUid = ownerUid,
            newRemoteRevision = remoteRevision,
            deletedAt = deletedAt
        )
        return rows == 1
    }

    /**
     * 7. Insertar cerveza remota recién descubierta en estado SYNCED.
     */
    fun insertRemoteBeerAsSynced(
        syncId: String,
        ownerUid: String,
        remoteRevision: Long,
        type: BeerType,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?,
        comment: String?,
        locationName: String?,
        remotePhotoUrl: String?,
        photoSource: String?,
        createdAt: Long = System.currentTimeMillis()
    ): Long {
        val entity = BeerEntity(
            id = 0,
            type = type,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            photoUri = null,
            comment = comment,
            locationName = locationName,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            remotePhotoUrl = remotePhotoUrl,
            updatedAt = createdAt,
            photoSource = photoSource,
            ownerUid = ownerUid,
            remoteRevision = remoteRevision,
            baseRevision = remoteRevision,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null
        )
        return beerDao.insertBeer(entity)
    }

    fun updateBeerEntityDirect(beer: BeerEntity): Int {
        val sql = """
            UPDATE beers SET
                type = ?,
                timestamp = ?,
                latitude = ?,
                longitude = ?,
                photoUri = ?,
                comment = ?,
                locationName = ?,
                syncStatus = ?,
                remotePhotoUrl = ?,
                updatedAt = ?,
                photoSource = ?,
                remoteRevision = ?,
                baseRevision = ?,
                dirtyFields = ?,
                lastMutationId = ?,
                deletedAt = ?
            WHERE syncId = ? AND ownerUid = ?
        """.trimIndent()

        val statement = database.openHelper.writableDatabase.compileStatement(sql)
        return try {
            statement.bindString(1, beer.type.name)
            statement.bindLong(2, beer.timestamp)
            if (beer.latitude != null) statement.bindDouble(3, beer.latitude) else statement.bindNull(3)
            if (beer.longitude != null) statement.bindDouble(4, beer.longitude) else statement.bindNull(4)
            if (beer.photoUri != null) statement.bindString(5, beer.photoUri) else statement.bindNull(5)
            if (beer.comment != null) statement.bindString(6, beer.comment) else statement.bindNull(6)
            if (beer.locationName != null) statement.bindString(7, beer.locationName) else statement.bindNull(7)
            statement.bindString(8, beer.syncStatus)
            if (beer.remotePhotoUrl != null) statement.bindString(9, beer.remotePhotoUrl) else statement.bindNull(9)
            statement.bindLong(10, beer.updatedAt)
            if (beer.photoSource != null) statement.bindString(11, beer.photoSource) else statement.bindNull(11)
            statement.bindLong(12, beer.remoteRevision)
            statement.bindLong(13, beer.baseRevision)
            if (beer.dirtyFields != null) statement.bindString(14, beer.dirtyFields) else statement.bindNull(14)
            if (beer.lastMutationId != null) statement.bindString(15, beer.lastMutationId) else statement.bindNull(15)
            if (beer.deletedAt != null) statement.bindLong(16, beer.deletedAt) else statement.bindNull(16)
            statement.bindString(17, beer.syncId)
            statement.bindString(18, beer.ownerUid)

            statement.executeUpdateDelete()
        } finally {
            statement.close()
        }
    }

    /**
     * Aplica convergencia ortogonal cuando un PullPlan.MergeWithPending determina que el remoto
     * avanzó en campos no conflictivos y local conserva campos dirty locales.
     * Actualiza remoteRevision a newRemoteRevision pero preserva baseRevision y los campos locales pendientes.
     */
    fun applyMergeWithPending(
        syncId: String,
        ownerUid: String,
        newRemoteRevision: Long,
        fieldsToApplyFromRemote: Set<BeerDirtyField>,
        localFieldsToPreserve: Set<BeerDirtyField>,
        remoteDoc: C4FirestoreDocument
    ): Boolean {
        database.beginTransaction()
        try {
            val beer = beerDao.getBeerBySyncId(syncId, ownerUid) ?: return false
            if (beer.syncStatus != com.example.contadordebirras.data.SyncStatus.PENDING) return false

            var updatedType = beer.type
            var updatedTimestamp = beer.timestamp
            var updatedLatitude = beer.latitude
            var updatedLongitude = beer.longitude
            var updatedLocationName = beer.locationName
            var updatedComment = beer.comment
            var updatedRemotePhotoUrl = beer.remotePhotoUrl
            var updatedPhotoSource = beer.photoSource

            if (fieldsToApplyFromRemote.contains(BeerDirtyField.TYPE)) {
                updatedType = try {
                    com.example.contadordebirras.domain.BeerType.valueOf(remoteDoc.type)
                } catch (_: Exception) {
                    beer.type
                }
            }
            if (fieldsToApplyFromRemote.contains(BeerDirtyField.TIMESTAMP)) {
                updatedTimestamp = remoteDoc.timestamp
            }
            if (fieldsToApplyFromRemote.contains(BeerDirtyField.LOCATION)) {
                updatedLatitude = remoteDoc.latitude
                updatedLongitude = remoteDoc.longitude
                updatedLocationName = remoteDoc.locationName
            }
            if (fieldsToApplyFromRemote.contains(BeerDirtyField.COMMENT)) {
                updatedComment = remoteDoc.comment
            }
            if (fieldsToApplyFromRemote.contains(BeerDirtyField.PHOTO)) {
                updatedRemotePhotoUrl = remoteDoc.remotePhotoUrl
                updatedPhotoSource = remoteDoc.photoSource
            }

            val updatedBeer = beer.copy(
                type = updatedType,
                timestamp = updatedTimestamp,
                latitude = updatedLatitude,
                longitude = updatedLongitude,
                locationName = updatedLocationName,
                comment = updatedComment,
                remotePhotoUrl = updatedRemotePhotoUrl,
                photoSource = updatedPhotoSource,
                remoteRevision = newRemoteRevision,
                baseRevision = beer.baseRevision,
                dirtyFields = BeerDirtyFieldsCodec.encode(localFieldsToPreserve),
                updatedAt = System.currentTimeMillis()
            )

            val updatedCount = updateBeerEntityDirect(updatedBeer)
            if (updatedCount != 1) return false

            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }
}
