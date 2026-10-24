package com.example.contadordebirras.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow
import com.example.contadordebirras.domain.BeerType

@Dao
interface BeerDao {
    @Insert
    fun insertBeer(beer: BeerEntity): Long

    @Query("""
        UPDATE beers SET
            type = :type, comment = :comment,
            syncStatus = 'PENDING', updatedAt = :updatedAt, baseRevision = :baseRevision,
            dirtyFields = :dirtyFields, lastMutationId = :lastMutationId, deletedAt = NULL
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus != 'DELETED'
    """)
    fun updateBeerDetailsLocalMutation(
        id: Int, type: BeerType, comment: String?,
        updatedAt: Long, ownerUid: String,
        baseRevision: Long, dirtyFields: String?, lastMutationId: String
    ): Int


    @Query("""
        UPDATE beers SET
            syncStatus = 'SYNCED',
            remotePhotoUrl = :remoteUrl,
            baseRevision = remoteRevision,
            dirtyFields = NULL,
            lastMutationId = NULL,
            deletedAt = NULL
        WHERE id = :id
          AND ownerUid = :ownerUid
          AND syncStatus = 'PENDING'
          AND (
              (lastMutationId IS NULL AND :expectedMutationId IS NULL)
              OR lastMutationId = :expectedMutationId
          )
    """)
    fun markAsSyncedIfMutationMatches(id: Int, remoteUrl: String?, ownerUid: String, expectedMutationId: String?): Int

    @Query("""
        UPDATE beers SET
            type = :type, timestamp = :timestamp, latitude = :latitude, longitude = :longitude,
            photoUri = :photoUri, comment = :comment, locationName = :locationName,
            remotePhotoUrl = :remotePhotoUrl, updatedAt = :updatedAt,
            photoSource = :photoSource
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus = 'SYNCED'
    """)
    fun applyRemoteUpdateIfSynced(
        id: Int, type: BeerType, timestamp: Long, latitude: Double?, longitude: Double?,
        photoUri: String?, comment: String?, locationName: String?,
        remotePhotoUrl: String?, updatedAt: Long, photoSource: String?, ownerUid: String
    ): Int


    @Query("""
        UPDATE beers SET 
            latitude = :latitude, longitude = :longitude, locationName = :locationName, 
            syncStatus = 'PENDING', updatedAt = :updatedAt, baseRevision = :baseRevision, 
            dirtyFields = :dirtyFields, lastMutationId = :lastMutationId, deletedAt = NULL 
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus != 'DELETED'
    """)
    fun updateBeerLocationLocalMutation(
        id: Int, latitude: Double, longitude: Double, locationName: String?,
        updatedAt: Long, ownerUid: String,
        baseRevision: Long, dirtyFields: String?, lastMutationId: String
    ): Int

    @Query("""
        UPDATE beers SET 
            syncStatus = 'DELETED', updatedAt = :updatedAt, deletedAt = :deletedAt, 
            baseRevision = :baseRevision, dirtyFields = NULL, lastMutationId = :lastMutationId 
        WHERE id = :id AND ownerUid = :ownerUid AND syncStatus != 'DELETED'
    """)
    fun softDeleteBeerLocalMutation(
        id: Int, updatedAt: Long, deletedAt: Long, ownerUid: String,
        baseRevision: Long, lastMutationId: String
    ): Int

    @Query("UPDATE beers SET syncStatus = 'DELETED' WHERE id = (SELECT id FROM beers WHERE syncStatus != 'DELETED' AND ownerUid = :ownerUid ORDER BY timestamp DESC LIMIT 1) AND ownerUid = :ownerUid")
    fun deleteLastBeer(ownerUid: String): Int

    @Query("SELECT * FROM beers WHERE syncStatus != 'DELETED' AND ownerUid = :ownerUid ORDER BY timestamp DESC")
    fun getAllBeers(ownerUid: String): Flow<List<BeerEntity>>

    @Query("SELECT COUNT(*) FROM beers WHERE syncStatus != 'DELETED' AND ownerUid = :ownerUid")
    fun getTotalCount(ownerUid: String): Flow<Int>

    @Query("SELECT * FROM beers WHERE syncStatus != 'DELETED' AND ownerUid = :ownerUid ORDER BY timestamp DESC LIMIT 1")
    fun getLastBeer(ownerUid: String): Flow<BeerEntity?>

    @Query("SELECT * FROM beers WHERE (syncStatus = 'PENDING' OR syncStatus = 'DELETED') AND ownerUid = :ownerUid")
    fun getPendingSyncBeers(ownerUid: String): List<BeerEntity>
    @Query("SELECT * FROM beers WHERE syncId = :syncId AND ownerUid = :ownerUid LIMIT 1")
    fun getBeerBySyncId(syncId: String, ownerUid: String): BeerEntity?

    @Query("DELETE FROM beers WHERE syncId = :syncId AND ownerUid = :ownerUid AND syncStatus = 'DELETED'")
    fun hardDeleteDeletedBySyncId(syncId: String, ownerUid: String): Int

    @Query("DELETE FROM beers WHERE syncId = :syncId AND ownerUid = :ownerUid AND syncStatus = 'SYNCED'")
    fun hardDeleteSyncedBySyncId(syncId: String, ownerUid: String): Int


    @Query("SELECT * FROM beers WHERE id = :id AND ownerUid = :ownerUid LIMIT 1")
    fun getBeerById(id: Int, ownerUid: String): BeerEntity?

    @Query("SELECT syncId FROM beers WHERE syncStatus = 'SYNCED' AND ownerUid = :ownerUid")
    fun getAllSyncedIds(ownerUid: String): List<String>

    // =========================================================================================
    // PRIMITIVAS DAO SEGURAS PARA EL PROTOCOLO MULTIDISPOSITIVO (FASE C4.1B)
    // =========================================================================================

    /**
     * Confirma atómicamente un PUSH exitoso completo (CreateActive o ApplyPatch con todos los campos dirty
     * confirmados), transitando el documento local a [SyncStatus.SYNCED].
     *
     * PROTECCIONES:
     * - Aislamiento por [ownerUid] e identidad [syncId].
     * - [syncStatus] esperado: 'PENDING'.
     * - [lastMutationId] esperado: debe coincidir exactamente con [expectedMutationId]. Si el usuario
     *   volvió a editar el documento antes de recibir el ACK, el mutationId local habrá cambiado, la
     *   condición WHERE no coincidirá y se retornará 0 filas actualizadas (sin sobrescribir ni limpiar cambios).
     * - Monotonía: la revisión remota actual debe ser menor o igual a [newRemoteRevision].
     * - Protección de foto local: [photoUri] permanece inmutable y nunca es alterado por el ACK remoto.
     *
     * @return Número de filas actualizadas (1 si el ACK fue aplicado de forma segura, 0 en caso de conflicto/carrera).
     */
    @Query("""
        UPDATE beers SET
            syncStatus = 'SYNCED',
            remoteRevision = :newRemoteRevision,
            baseRevision = :newRemoteRevision,
            dirtyFields = NULL,
            lastMutationId = NULL,
            deletedAt = NULL,
            remotePhotoUrl = CASE WHEN :remotePhotoUrl IS NOT NULL THEN :remotePhotoUrl ELSE remotePhotoUrl END,
            photoSource = CASE WHEN :photoSource IS NOT NULL THEN :photoSource ELSE photoSource END,
            updatedAt = :updatedAt
        WHERE syncId = :syncId
          AND ownerUid = :ownerUid
          AND syncStatus = 'PENDING'
          AND lastMutationId = :expectedMutationId
          AND remoteRevision <= :newRemoteRevision
    """)
    fun confirmPushMutationFullSuccess(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        remotePhotoUrl: String? = null,
        photoSource: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Confirma un PUSH parcial (ApplyPatch donde solo una parte de los dirtyFields fueron confirmados,
     * o algunos perdieron conflicto contra remoto), manteniendo el documento en [SyncStatus.PENDING].
     *
     * PROTECCIONES:
     * - No limpia [dirtyFields] a ciegas: preserva los campos restantes en [remainingDirtyFields].
     * - Protegido por [lastMutationId], [ownerUid], [syncId] y [syncStatus] = 'PENDING'.
     * - Preserva [photoUri] intacto.
     *
     * @return Número de filas actualizadas (1 si fue aplicado, 0 si fue sustituido por otra edición).
     */
    @Query("""
        UPDATE beers SET
            remoteRevision = :newRemoteRevision,
            baseRevision = :newRemoteRevision,
            dirtyFields = :remainingDirtyFields,
            remotePhotoUrl = CASE WHEN :remotePhotoUrl IS NOT NULL THEN :remotePhotoUrl ELSE remotePhotoUrl END,
            photoSource = CASE WHEN :photoSource IS NOT NULL THEN :photoSource ELSE photoSource END,
            updatedAt = :updatedAt
        WHERE syncId = :syncId
          AND ownerUid = :ownerUid
          AND syncStatus = 'PENDING'
          AND lastMutationId = :expectedMutationId
          AND remoteRevision <= :newRemoteRevision
    """)
    fun confirmPushMutationPartialPatch(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        remainingDirtyFields: String,
        remotePhotoUrl: String? = null,
        photoSource: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Aplica una actualización remota más reciente (PULL) sobre un documento localmente [SyncStatus.SYNCED].
     *
     * PROTECCIONES:
     * - Exige [syncStatus] = 'SYNCED'. Si la entidad local tiene cambios pendientes ([SyncStatus.PENDING]),
     *   esta consulta no coincide y retorna 0 (evitando sobrescribir ediciones del usuario a ciegas).
     * - Exige estricta monotonía: [remoteRevision] < [newRemoteRevision].
     * - Preserva [photoUri] estrictamente local sin sobrescribirlo.
     *
     * @return Número de filas actualizadas (1 si se aplicó, 0 si no era elegible).
     */
    @Query("""
        UPDATE beers SET
            type = :type,
            timestamp = :timestamp,
            latitude = :latitude,
            longitude = :longitude,
            comment = :comment,
            locationName = :locationName,
            remotePhotoUrl = :remotePhotoUrl,
            photoSource = :photoSource,
            remoteRevision = :newRemoteRevision,
            baseRevision = :newRemoteRevision,
            updatedAt = :updatedAt
        WHERE syncId = :syncId
          AND ownerUid = :ownerUid
          AND syncStatus = 'SYNCED'
          AND remoteRevision < :newRemoteRevision
    """)
    fun applyRemoteUpdateToSyncedBeer(
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
    ): Int

    /**
     * Aplica un tombstone remoto (PULL) sobre un documento localmente [SyncStatus.SYNCED], marcándolo
     * como [SyncStatus.DELETED] de forma protegida.
     *
     * PROTECCIONES:
     * - Exige [syncStatus] = 'SYNCED'. Si la entidad local estaba en [SyncStatus.PENDING], no se aplica
     *   monolíticamente (el conflicto debe resolverse mediante el PullPlanner/MergeCoordinator de C4.4).
     * - Exige [remoteRevision] <= [newRemoteRevision].
     *
     * @return Número de filas actualizadas.
     */
    @Query("""
        UPDATE beers SET
            syncStatus = 'DELETED',
            deletedAt = :deletedAt,
            remoteRevision = :newRemoteRevision,
            baseRevision = :newRemoteRevision,
            dirtyFields = NULL,
            lastMutationId = NULL,
            updatedAt = :deletedAt
        WHERE syncId = :syncId
          AND ownerUid = :ownerUid
          AND syncStatus = 'SYNCED'
          AND remoteRevision <= :newRemoteRevision
    """)
    fun applyRemoteTombstoneToSyncedBeer(
        syncId: String,
        ownerUid: String,
        newRemoteRevision: Long,
        deletedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Confirma la eliminación física local de una entidad cuyo borrado ([SyncStatus.DELETED]) ya fue
     * confirmado en remoto mediante tombstone.
     *
     * PROTECCIONES:
     * - Solo elimina físicamente si el documento local sigue en estado 'DELETED'.
     * - Si se proporciona [expectedMutationId], valida que coincida.
     *
     * @return Número de filas eliminadas.
     */
    @Query("""
        DELETE FROM beers
        WHERE syncId = :syncId
          AND ownerUid = :ownerUid
          AND syncStatus = 'DELETED'
          AND (
              :expectedMutationId IS NULL
              OR lastMutationId = :expectedMutationId
          )
    """)
    fun confirmDeletePushSuccess(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String? = null
    ): Int

    // -----------------------------------------------------------------------------------------
    // COORDINACIÓN ATÓMICA CON EL HISTORIAL DURABLE (TRANSACCIONES ROOM)
    // -----------------------------------------------------------------------------------------

    @Query("""
        UPDATE local_mutation_history SET
            status = 'CONFIRMED',
            confirmedRevision = :confirmedRevision,
            confirmedAt = :confirmedAt
        WHERE mutationId = :mutationId
          AND ownerUid = :ownerUid
          AND status = 'PENDING'
    """)
    fun confirmHistoryRecord(
        mutationId: String,
        ownerUid: String,
        confirmedRevision: Long,
        confirmedAt: Long
    ): Int

    @Query("""
        UPDATE local_mutation_history SET
            status = 'SUPERSEDED'
        WHERE syncId = :syncId
          AND ownerUid = :ownerUid
          AND mutationId != :activeMutationId
          AND status = 'PENDING'
    """)
    fun supersedePriorPendingHistoryRecords(
        syncId: String,
        ownerUid: String,
        activeMutationId: String
    ): Int

    @Insert
    fun insertHistoryRecord(record: LocalMutationHistoryEntity): Long

    /**
     * Transacción Room atómica: confirma el PUSH completo en `beers` y simultáneamente marca
     * el registro correspondiente en `local_mutation_history` como CONFIRMED.
     *
     * Si la entidad local fue modificada con posterioridad (lastMutationId sustituido), ninguna
     * tabla es alterada y la función retorna false sin pérdida de consistencia.
     */
    @androidx.room.Transaction
    fun confirmPushSuccessWithHistory(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        remotePhotoUrl: String? = null,
        photoSource: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val updatedRows = confirmPushMutationFullSuccess(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = expectedMutationId,
            newRemoteRevision = newRemoteRevision,
            remotePhotoUrl = remotePhotoUrl,
            photoSource = photoSource,
            updatedAt = updatedAt
        )
        if (updatedRows > 0) {
            confirmHistoryRecord(
                mutationId = expectedMutationId,
                ownerUid = ownerUid,
                confirmedRevision = newRemoteRevision,
                confirmedAt = updatedAt
            )
            return true
        }
        return false
    }

    /**
     * Transacción Room atómica: confirma un ApplyPatch parcial en `beers` y simultáneamente marca
     * el registro en `local_mutation_history` como CONFIRMED.
     */
    @androidx.room.Transaction
    fun confirmPartialPatchWithHistory(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        remainingDirtyFields: String,
        remotePhotoUrl: String? = null,
        photoSource: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val updatedRows = confirmPushMutationPartialPatch(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = expectedMutationId,
            newRemoteRevision = newRemoteRevision,
            remainingDirtyFields = remainingDirtyFields,
            remotePhotoUrl = remotePhotoUrl,
            photoSource = photoSource,
            updatedAt = updatedAt
        )
        if (updatedRows > 0) {
            confirmHistoryRecord(
                mutationId = expectedMutationId,
                ownerUid = ownerUid,
                confirmedRevision = newRemoteRevision,
                confirmedAt = updatedAt
            )
            return true
        }
        return false
    }

    /*
     * CONTRATO DE OPERACIONES COMPLEJAS DE MERGE (FASE C4.4):
     *
     * La resolución de conflictos concurrentes donde la entidad local tiene campos dirty
     * no confirmados y el remoto introduce cambios concurrentes (3-way merge, ApplyPatch parcial
     * con campos remotos ganadores, o remoto tombstone vs local PENDING) NO debe implementarse
     * como un UPDATE monolítico ni resolverse a ciegas en el DAO.
     *
     * Dicha resolución requiere las decisiones semánticas de [PullPlan] (C3B) y la orquestación
     * del MergeCoordinator en la Fase C4.4:
     * 1. Preservar campos dirty locales no confirmados sin sobrescritura ciega.
     * 2. Aplicar únicamente los campos remotos autorizados (remoteFieldsToApply).
     * 3. Preservar inmutablemente photoUri como dato estrictamente local.
     * 4. Ajustar el historial causal y las dirtyFields resultantes de forma atómica.
     */
}
