package com.example.contadordebirras.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object para gestionar el historial durable de mutaciones locales (Fase C4.1B).
 *
 * Todas las consultas y actualizaciones se aíslan estrictamente por [ownerUid] y [syncId].
 */
@Dao
interface LocalMutationHistoryDao {

    /**
     * Inserta una nueva mutación en el historial. Falla si [mutationId] ya existe para evitar duplicados.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMutation(mutation: LocalMutationHistoryEntity): Long

    /**
     * Inserta una lista de mutaciones en lote.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMutations(mutations: List<LocalMutationHistoryEntity>): List<Long>

    /**
     * Obtiene todas las mutaciones registradas para una cerveza concreta, ordenadas cronológicamente.
     */
    @Query("""
        SELECT * FROM local_mutation_history
        WHERE syncId = :syncId AND ownerUid = :ownerUid
        ORDER BY timestamp ASC
    """)
    fun getMutationsForBeer(syncId: String, ownerUid: String): List<LocalMutationHistoryEntity>

    /**
     * Obtiene una mutación concreta por su identificador único y propietario.
     */
    @Query("""
        SELECT * FROM local_mutation_history
        WHERE mutationId = :mutationId AND ownerUid = :ownerUid
        LIMIT 1
    """)
    fun getMutationById(mutationId: String, ownerUid: String): LocalMutationHistoryEntity?

    /**
     * Obtiene todas las mutaciones que siguen en estado [MutationStatus.PENDING] para un usuario.
     */
    @Query("""
        SELECT * FROM local_mutation_history
        WHERE ownerUid = :ownerUid AND status = 'PENDING'
        ORDER BY timestamp ASC
    """)
    fun getPendingMutations(ownerUid: String): List<LocalMutationHistoryEntity>

    /**
     * Obtiene la lista ordenada de identificadores de mutación para una cerveza concreta.
     * Facilita la construcción de `pastLocalMutationIds` en operaciones de sincronización.
     */
    @Query("""
        SELECT mutationId FROM local_mutation_history
        WHERE syncId = :syncId AND ownerUid = :ownerUid
        ORDER BY timestamp ASC
    """)
    fun getAllMutationIdsForBeer(syncId: String, ownerUid: String): List<String>

    /**
     * Obtiene los identificadores de mutaciones anteriores (excluyendo la mutación activa) para una cerveza.
     */
    @Query("""
        SELECT mutationId FROM local_mutation_history
        WHERE syncId = :syncId AND ownerUid = :ownerUid AND mutationId != :activeMutationId
        ORDER BY timestamp ASC
    """)
    fun getPastMutationIdsForBeer(syncId: String, ownerUid: String, activeMutationId: String): List<String>

    /**
     * Marca una mutación pendiente o parcialmente confirmada como completamente confirmada.
     * Retorna el número de filas actualizadas (1 si estaba en PENDING o PARTIALLY_CONFIRMED, 0 en caso contrario).
     */
    @Query("""
        UPDATE local_mutation_history SET
            status = 'CONFIRMED',
            confirmedRevision = :confirmedRevision,
            confirmedAt = :confirmedAt
        WHERE mutationId = :mutationId
          AND syncId = :syncId
          AND ownerUid = :ownerUid
          AND (status = 'PENDING' OR status = 'PARTIALLY_CONFIRMED')
    """)
    fun markMutationConfirmed(
        mutationId: String,
        syncId: String,
        ownerUid: String,
        confirmedRevision: Long,
        confirmedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Sobrecarga de conveniencia para confirmación cuando solo se dispone de mutationId y ownerUid.
     */
    @Query("""
        UPDATE local_mutation_history SET
            status = 'CONFIRMED',
            confirmedRevision = :confirmedRevision,
            confirmedAt = :confirmedAt
        WHERE mutationId = :mutationId
          AND ownerUid = :ownerUid
          AND (status = 'PENDING' OR status = 'PARTIALLY_CONFIRMED')
    """)
    fun markMutationConfirmed(
        mutationId: String,
        ownerUid: String,
        confirmedRevision: Long,
        confirmedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Marca una mutación como parcialmente confirmada ([MutationStatus.PARTIALLY_CONFIRMED])
     * sin marcarla falsamente como CONFIRMED completa mientras mantenga dirtyFields pendientes.
     */
    @Query("""
        UPDATE local_mutation_history SET
            status = 'PARTIALLY_CONFIRMED',
            confirmedRevision = :confirmedRevision,
            confirmedAt = :confirmedAt
        WHERE mutationId = :mutationId
          AND syncId = :syncId
          AND ownerUid = :ownerUid
          AND (status = 'PENDING' OR status = 'PARTIALLY_CONFIRMED')
    """)
    fun markMutationPartiallyConfirmed(
        mutationId: String,
        syncId: String,
        ownerUid: String,
        confirmedRevision: Long,
        confirmedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Marca una mutación pendiente como sustituida ([MutationStatus.SUPERSEDED]) por una edición posterior.
     */
    @Query("""
        UPDATE local_mutation_history SET
            status = 'SUPERSEDED'
        WHERE mutationId = :mutationId
          AND ownerUid = :ownerUid
          AND status = 'PENDING'
    """)
    fun markMutationSuperseded(mutationId: String, ownerUid: String): Int

    /**
     * Marca como SUPERSEDED todas las mutaciones pendientes anteriores a [activeMutationId] para una cerveza.
     */
    @Query("""
        UPDATE local_mutation_history SET
            status = 'SUPERSEDED'
        WHERE syncId = :syncId
          AND ownerUid = :ownerUid
          AND mutationId != :activeMutationId
          AND status = 'PENDING'
    """)
    fun supersedePriorPendingMutations(syncId: String, ownerUid: String, activeMutationId: String): Int

    /**
     * Marca una mutación como rechazada explícitamente ([MutationStatus.REJECTED]).
     */
    @Query("""
        UPDATE local_mutation_history SET
            status = 'REJECTED'
        WHERE mutationId = :mutationId
          AND ownerUid = :ownerUid
          AND status = 'PENDING'
    """)
    fun markMutationRejected(mutationId: String, ownerUid: String): Int

    /**
     * Cuenta el número total de registros en el historial para un propietario.
     */
    @Query("""
        SELECT COUNT(*) FROM local_mutation_history
        WHERE ownerUid = :ownerUid
    """)
    fun getHistoryCount(ownerUid: String): Int

    /**
     * Elimina el historial completo de una cerveza. Solo para operaciones de borrado explícito.
     */
    @Query("""
        DELETE FROM local_mutation_history
        WHERE syncId = :syncId AND ownerUid = :ownerUid
    """)
    fun deleteHistoryForBeer(syncId: String, ownerUid: String): Int
}
