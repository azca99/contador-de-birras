package com.example.contadordebirras.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec

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
     * Confirma un PUSH exitoso completo (CreateActive o ApplyPatch con todos los campos dirty
     * confirmados), transitando el documento local a [SyncStatus.SYNCED].
     *
     * PROTECCIONES:
     * - Aislamiento por [ownerUid] e identidad [syncId].
     * - [syncStatus] esperado: 'PENDING'.
     * - [lastMutationId] esperado: debe coincidir exactamente con [expectedMutationId]. Si el usuario
     *   volvió a editar el documento antes de recibir el ACK, el mutationId local habrá cambiado, la
     *   condición WHERE no coincidirá y se retornará 0 filas actualizadas (sin sobrescribir ni limpiar cambios).
     * - Monotonía: la revisión remota actual debe ser menor o igual a [newRemoteRevision].
     * - Referencias fotográficas explícitas:
     *   a) Si [clearRemotePhoto] es true: se elimina explícitamente la referencia remota (NULL).
     *   b) Si [newRemotePhotoUrl] no es null: se asigna la nueva referencia remota.
     *   c) Si [newRemotePhotoUrl] es null y [clearRemotePhoto] es false: se preserva intacta la referencia actual.
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
            remotePhotoUrl = CASE
                WHEN :clearRemotePhoto = 1 THEN NULL
                WHEN :newRemotePhotoUrl IS NOT NULL THEN :newRemotePhotoUrl
                ELSE remotePhotoUrl
            END,
            photoSource = CASE
                WHEN :clearRemotePhoto = 1 THEN NULL
                WHEN :newPhotoSource IS NOT NULL THEN :newPhotoSource
                ELSE photoSource
            END,
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
        newRemotePhotoUrl: String? = null,
        newPhotoSource: String? = null,
        clearRemotePhoto: Boolean = false,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Confirma un PUSH parcial (ApplyPatch donde solo una parte de los dirtyFields fueron confirmados,
     * o algunos perdieron conflicto contra remoto), manteniendo el documento en [SyncStatus.PENDING].
     *
     * INVARIANTE CAUSAL CRÍTICA:
     * [remoteRevision] avanza a [newRemoteRevision] porque el documento remoto progresó, PERO
     * [baseRevision] se preserva INTACTA en su valor original (sin actualizar a [newRemoteRevision]),
     * ya que los [remainingDirtyFields] continúan pendientes sobre la base causal previa en la que
     * fueron modificados. Actualizar [baseRevision] en un patch parcial ocultaría conflictos remotos
     * anteriores y falsearía la precedencia causal.
     *
     * PROTECCIONES:
     * - No limpia [dirtyFields] a ciegas: preserva los campos restantes en [remainingDirtyFields].
     * - Protegido por [lastMutationId], [ownerUid], [syncId] y [syncStatus] = 'PENDING'.
     * - Preserva [photoUri] intacto.
     * - Manejo explícito de fotos con [clearRemotePhoto] y [newRemotePhotoUrl].
     *
     * @return Número de filas actualizadas (1 si fue aplicado, 0 si fue sustituido por otra edición).
     */
    @Query("""
        UPDATE beers SET
            remoteRevision = :newRemoteRevision,
            dirtyFields = :remainingDirtyFields,
            remotePhotoUrl = CASE
                WHEN :clearRemotePhoto = 1 THEN NULL
                WHEN :newRemotePhotoUrl IS NOT NULL THEN :newRemotePhotoUrl
                ELSE remotePhotoUrl
            END,
            photoSource = CASE
                WHEN :clearRemotePhoto = 1 THEN NULL
                WHEN :newPhotoSource IS NOT NULL THEN :newPhotoSource
                ELSE photoSource
            END,
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
        newRemotePhotoUrl: String? = null,
        newPhotoSource: String? = null,
        clearRemotePhoto: Boolean = false,
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
     * OPERACIÓN DE HARD DELETE APLAZADA HASTA C4.3 / C4.4:
     *
     * La comparación `remoteRevision <= newRemoteRevision` NO demuestra por sí sola que Firestore
     * haya confirmado un tombstone. No se permite que un número entregado por el llamador sea
     * prueba de borrado remoto.
     *
     * Como C4.3/C4.4 todavía no existen, se aplazan las nuevas primitivas de hard delete y se
     * preservan localmente las filas DELETED junto con su historial durable en Room.
     * No se modifica el borrado legacy existente (hardDeleteDeletedBySyncId).
     *
     * @return 0 incondicionalmente para impedir la eliminación física sin prueba real de tombstone.
     */
    fun confirmDeletePushSuccess(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long
    ): Int = 0

    // -----------------------------------------------------------------------------------------
    // COORDINACIÓN ATÓMICA CON EL HISTORIAL DURABLE (TRANSACCIONES ROOM)
    // -----------------------------------------------------------------------------------------

    @Query("""
        SELECT * FROM local_mutation_history
        WHERE mutationId = :mutationId
          AND ownerUid = :ownerUid
        LIMIT 1
    """)
    fun getHistoryRecordById(mutationId: String, ownerUid: String): LocalMutationHistoryEntity?

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
    fun confirmHistoryRecord(
        mutationId: String,
        syncId: String,
        ownerUid: String,
        confirmedRevision: Long,
        confirmedAt: Long
    ): Int

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
    fun markHistoryRecordPartiallyConfirmed(
        mutationId: String,
        syncId: String,
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
          AND (status = 'PENDING' OR status = 'PARTIALLY_CONFIRMED')
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
     * EXIGENCIAS DE COHERENCIA:
     * - Si el historial obligatorio falta, pertenece a otro [syncId] o no está en un estado válido,
     *   la función retorna false inmediatamente sin modificar ninguna tabla.
     * - Si la mutación activa en `beers` fue sustituida (lastMutationId diferente) o está en conflicto,
     *   retorna false sin tocar los datos.
     * - Si ocurre un desajuste concurrente durante la escritura, se fuerza un rollback total de la transacción.
     */
    @androidx.room.Transaction
    fun confirmPushSuccessWithHistory(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        newRemotePhotoUrl: String? = null,
        newPhotoSource: String? = null,
        clearRemotePhoto: Boolean = false,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val beer = getBeerBySyncId(syncId, ownerUid) ?: return false
        if (beer.syncStatus != SyncStatus.PENDING ||
            beer.lastMutationId != expectedMutationId ||
            beer.remoteRevision > newRemoteRevision
        ) {
            return false
        }

        val history = getHistoryRecordById(expectedMutationId, ownerUid) ?: return false
        if (history.syncId != syncId ||
            (history.status != MutationStatus.PENDING && history.status != MutationStatus.PARTIALLY_CONFIRMED)
        ) {
            return false
        }

        val beerUpdated = confirmPushMutationFullSuccess(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = expectedMutationId,
            newRemoteRevision = newRemoteRevision,
            newRemotePhotoUrl = newRemotePhotoUrl,
            newPhotoSource = newPhotoSource,
            clearRemotePhoto = clearRemotePhoto,
            updatedAt = updatedAt
        )

        val histUpdated = confirmHistoryRecord(
            mutationId = expectedMutationId,
            syncId = syncId,
            ownerUid = ownerUid,
            confirmedRevision = newRemoteRevision,
            confirmedAt = updatedAt
        )

        if (beerUpdated != 1 || histUpdated != 1) {
            throw IllegalStateException(
                "Atomic confirmation failed: beerUpdated=$beerUpdated, histUpdated=$histUpdated for mutation $expectedMutationId"
            )
        }

        return true
    }

    /**
     * Transacción Room atómica: confirma un ApplyPatch parcial en `beers` y marca el registro
     * en `local_mutation_history` como [MutationStatus.PARTIALLY_CONFIRMED] (NUNCA como CONFIRMED completo
     * mientras queden [remainingDirtyFields] pendientes).
     *
     * EXIGENCIAS DE VALIDACIÓN ESTRICTA (Auditoría C4.1B):
     * 1. [remainingDirtyFields] no se acepta arbitrariamente: debe ser no vacío, no blanco, estar bien codificado
     *    en orden canónico y contener únicamente campos válidos de [BeerDirtyField].
     * 2. [confirmedFields] debe especificarse explícitamente y estar bien codificado. No se autoriza eliminar campos
     *    sin confirmación o resolución explícita. Si no se dispone de evidencia de confirmación, la operación se
     *    rechaza (retorna false) y se aplaza a C4.4.
     * 3. Relación verificable exacta: [remainingDirtyFields] y [confirmedFields] deben ser disjuntos
     *    y su unión debe ser idéntica al conjunto original de dirtyFields de la entidad.
     *    Cualquier campo nuevo ficticio, campo no presente originalmente o eliminación arbitraria de campos
     *    provoca el rechazo inmediato (retorna false).
     * 4. Invariante causal: preserva la [baseRevision] causal original intacta en `beers`.
     * 5. Preservación inmutable de [photoUri] estrictamente local.
     */
    @androidx.room.Transaction
    fun confirmPartialPatchWithHistory(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        remainingDirtyFields: String,
        confirmedFields: String? = null,
        newRemotePhotoUrl: String? = null,
        newPhotoSource: String? = null,
        clearRemotePhoto: Boolean = false,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        // 1. Verificación de campos restantes no vacíos
        if (remainingDirtyFields.isBlank()) {
            return false
        }

        // 2. Validación de codificación y campos válidos en remainingDirtyFields
        val rawRemainingTokens = remainingDirtyFields.split(',')
        val remainingSet = LinkedHashSet<BeerDirtyField>()
        for (token in rawRemainingTokens) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return false
            val field = try {
                BeerDirtyField.valueOf(trimmed)
            } catch (e: IllegalArgumentException) {
                return false // Campo ficticio o desconocido
            }
            if (!remainingSet.add(field)) return false // Duplicado
        }
        if (remainingSet.isEmpty()) return false
        if (BeerDirtyFieldsCodec.encode(remainingSet) != remainingDirtyFields) {
            return false // No cumple formato canónico CSV
        }

        // 3. Verificación de evidencia explícita de campos confirmados
        if (confirmedFields.isNullOrBlank()) {
            // Sin evidencia explícita de los campos confirmados, no se permite eliminar campos arbitrariamente.
            return false
        }
        val rawConfirmedTokens = confirmedFields.split(',')
        val confirmedSet = LinkedHashSet<BeerDirtyField>()
        for (token in rawConfirmedTokens) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return false
            val field = try {
                BeerDirtyField.valueOf(trimmed)
            } catch (e: IllegalArgumentException) {
                return false
            }
            if (!confirmedSet.add(field)) return false
        }
        if (confirmedSet.isEmpty()) return false
        if (BeerDirtyFieldsCodec.encode(confirmedSet) != confirmedFields) {
            return false
        }

        // 4. Verificación de disyunción: ningún campo puede estar confirmado y pendiente a la vez
        if (remainingSet.any { confirmedSet.contains(it) }) {
            return false
        }

        // 5. Carga y verificación del documento en beers
        val beer = getBeerBySyncId(syncId, ownerUid) ?: return false
        if (beer.syncStatus != SyncStatus.PENDING ||
            beer.lastMutationId != expectedMutationId ||
            beer.remoteRevision > newRemoteRevision
        ) {
            return false
        }

        // 6. Verificación de partición exacta sobre los dirtyFields originales
        val originalDirtyRaw = beer.dirtyFields ?: return false
        val originalSet = BeerDirtyFieldsCodec.decode(originalDirtyRaw)
        if (originalSet.isEmpty()) return false

        // La unión de confirmed y remaining debe coincidir exactamente con los originales
        if (remainingSet + confirmedSet != originalSet) {
            return false // Eliminación arbitraria o campos añadidos no presentes originalmente
        }

        // 7. Verificación del registro en local_mutation_history
        val history = getHistoryRecordById(expectedMutationId, ownerUid) ?: return false
        if (history.syncId != syncId ||
            (history.status != MutationStatus.PENDING && history.status != MutationStatus.PARTIALLY_CONFIRMED)
        ) {
            return false
        }

        // 8. Aplicación en beers preservando baseRevision y actualizando remoteRevision
        val beerUpdated = confirmPushMutationPartialPatch(
            syncId = syncId,
            ownerUid = ownerUid,
            expectedMutationId = expectedMutationId,
            newRemoteRevision = newRemoteRevision,
            remainingDirtyFields = remainingDirtyFields,
            newRemotePhotoUrl = newRemotePhotoUrl,
            newPhotoSource = newPhotoSource,
            clearRemotePhoto = clearRemotePhoto,
            updatedAt = updatedAt
        )

        // 9. Actualización en historial durable a PARTIALLY_CONFIRMED
        val histUpdated = markHistoryRecordPartiallyConfirmed(
            mutationId = expectedMutationId,
            syncId = syncId,
            ownerUid = ownerUid,
            confirmedRevision = newRemoteRevision,
            confirmedAt = updatedAt
        )

        if (beerUpdated != 1 || histUpdated != 1) {
            throw IllegalStateException(
                "Atomic partial patch failed: beerUpdated=$beerUpdated, histUpdated=$histUpdated for mutation $expectedMutationId"
            )
        }

        return true
    }

    /**
     * OPERACIÓN DE HARD DELETE APLAZADA HASTA C4.3 / C4.4:
     *
     * Rechaza incondicionalmente la eliminación física (retorna false) porque una revisión numérica
     * entregada por el llamador (`newRemoteRevision`) NO demuestra que Firestore haya confirmado un tombstone.
     *
     * Las entidades en estado [SyncStatus.DELETED] y su historial durable se preservan íntegramente
     * en Room para evitar la pérdida irreversible de datos antes de C4.3/C4.4.
     *
     * @return false incondicionalmente (borrado físico rechazado).
     */
    fun confirmDeletePushSuccessWithHistory(
        syncId: String,
        ownerUid: String,
        expectedMutationId: String,
        newRemoteRevision: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        // Bloqueado / Aplazado a C4.3/C4.4: se rechaza el borrado físico sin prueba real de tombstone confirmado
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
