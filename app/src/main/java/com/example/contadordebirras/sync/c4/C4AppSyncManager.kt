package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.room.Room
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.data.LocalMutationHistoryEntity
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.domain.LOCAL_ONLY_REVISION
import com.example.contadordebirras.domain.LegacyHistoricalEvidence
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.UUID

/**
 * Gestor funcional de sincronización bajo el Protocolo C4 para la aplicación (Fase C4.4B).
 *
 * RESPONSABILIDADES:
 * 1. Conecta el motor C4 con las operaciones habituales de crear, editar, consultar y borrar cervezas.
 * 2. Registra mutaciones durables en Room ([LocalMutationHistoryEntity]) antes de intentar cualquier sincronización de red.
 * 3. Ejecuta sincronizaciones PUSH y PULL mediante [AndroidC4SyncCoordinator].
 * 4. Utiliza exclusivamente una base de datos Room descartable y el Firebase Emulator local (demo-contador-c44).
 * 5. Mantiene estricto aislamiento de producción y del motor legacy ([com.example.contadordebirras.domain.BeerSyncEngine]).
 */
class C4AppSyncManager(
    val database: BeerDatabase,
    val persistence: RoomC4SyncPersistence,
    val coordinator: AndroidC4SyncCoordinator,
    val ownerUid: String,
    val auth: FirebaseAuth? = null
) : Closeable {

    private val allFieldsEncoded = BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS)!!

    /**
     * 1. CREACIÓN:
     * - Genera syncId y mutationId universales (UUID).
     * - Registra previamente la mutación durable en local_mutation_history con estado PENDING.
     * - Inserta la entidad en la base descartable Room con revisión 0/0 y estado PENDING.
     */
    suspend fun createBeer(
        type: BeerType = BeerType.CANA,
        comment: String? = null,
        photoUri: String? = null,
        photoBytes: ByteArray? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        locationName: String? = null,
        now: Long = System.currentTimeMillis()
    ): String = withContext(Dispatchers.IO) {
        val syncId = UUID.randomUUID().toString()
        val mutationId = UUID.randomUUID().toString()

        // 1. Registro durable previo en Room
        val recorded = persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = ownerUid,
            mutationId = mutationId,
            dirtyFields = allFieldsEncoded,
            baseRevision = LOCAL_ONLY_REVISION,
            isLocalOnlyCreation = true,
            timestamp = now
        )
        check(recorded) { "Fallo al registrar la mutación durable previa para creación $syncId" }

        // 2. Inserción de la cerveza en Room
        val beerEntity = BeerEntity(
            syncId = syncId,
            ownerUid = ownerUid,
            type = type,
            comment = comment,
            timestamp = now,
            photoUri = photoUri,
            latitude = latitude,
            longitude = longitude,
            locationName = locationName,
            syncStatus = SyncStatus.PENDING,
            remoteRevision = LOCAL_ONLY_REVISION,
            baseRevision = LOCAL_ONLY_REVISION,
            dirtyFields = allFieldsEncoded,
            lastMutationId = mutationId,
            updatedAt = now,
            deletedAt = null
        )
        val rowId = persistence.beerDao.insertBeer(beerEntity)
        check(rowId > 0) { "Fallo al insertar la entidad BeerEntity en la base Room descartable" }

        syncId
    }

    /**
     * 2. EDICIÓN DE COMENTARIO:
     * - Genera nuevo mutationId durable.
     * - Actualiza local_mutation_history marcando la mutación anterior como SUPERSEDED.
     * - Actualiza la entidad en Room con dirtyFields = COMMENT y estado PENDING.
     */
    suspend fun editBeerComment(
        syncId: String,
        newComment: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val current = persistence.beerDao.getBeerBySyncId(syncId, ownerUid) ?: return@withContext false
        if (current.syncStatus == SyncStatus.DELETED) return@withContext false

        val mutationId = UUID.randomUUID().toString()
        val changedFields = setOf(BeerDirtyField.COMMENT)
        val isCreation = current.remoteRevision == LOCAL_ONLY_REVISION || current.remoteRevision == 0L
        val dirtyFields = if (isCreation) {
            allFieldsEncoded
        } else if (current.syncStatus == SyncStatus.PENDING) {
            BeerDirtyFieldsCodec.merge(current.dirtyFields, changedFields)
        } else {
            BeerDirtyFieldsCodec.encode(changedFields)
        }
        val baseRevision = if (current.syncStatus == SyncStatus.PENDING) current.baseRevision else current.remoteRevision

        // 1. Registro durable previo en Room
        val recorded = persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = ownerUid,
            mutationId = mutationId,
            dirtyFields = dirtyFields,
            baseRevision = baseRevision,
            isLocalOnlyCreation = isCreation,
            timestamp = now
        )
        if (!recorded) return@withContext false

        // 2. Actualización de la entidad en Room
        val rows = persistence.beerDao.updateBeerDetailsLocalMutation(
            id = current.id,
            type = current.type,
            comment = newComment,
            updatedAt = now,
            ownerUid = ownerUid,
            baseRevision = baseRevision,
            dirtyFields = dirtyFields,
            lastMutationId = mutationId
        )
        rows > 0
    }

    /**
     * 3. BORRADO (TOMBSTONE LOCAL):
     * - Marca la cerveza como SyncStatus.DELETED.
     * - Registra la mutación durable de borrado.
     */
    suspend fun deleteBeer(
        syncId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val current = persistence.beerDao.getBeerBySyncId(syncId, ownerUid) ?: return@withContext false
        if (current.syncStatus == SyncStatus.DELETED) return@withContext false

        val mutationId = UUID.randomUUID().toString()
        val baseRevision = if (current.syncStatus == SyncStatus.PENDING) current.baseRevision else current.remoteRevision

        // 1. Registro durable previo
        val recorded = persistence.recordDurableMutationBeforeNetwork(
            syncId = syncId,
            ownerUid = ownerUid,
            mutationId = mutationId,
            dirtyFields = "",
            baseRevision = baseRevision,
            isLocalOnlyCreation = false,
            timestamp = now
        )
        if (!recorded) return@withContext false

        // 2. Soft delete en Room
        val rows = persistence.beerDao.softDeleteBeerLocalMutation(
            id = current.id,
            updatedAt = now,
            deletedAt = now,
            ownerUid = ownerUid,
            baseRevision = baseRevision,
            lastMutationId = mutationId
        )
        rows > 0
    }

    /**
     * 4. CONSULTA:
     * Observa las cervezas activas (excluye tombstones DELETED) del usuario actual.
     */
    fun observeActiveBeers(): Flow<List<BeerEntity>> {
        return persistence.beerDao.getAllBeers(ownerUid).map { list ->
            list.filter { it.syncStatus != SyncStatus.DELETED }
        }
    }

    /**
     * Consulta síncrona de todas las cervezas activas para el usuario actual.
     */
    suspend fun getActiveBeers(): List<BeerEntity> = withContext(Dispatchers.IO) {
        persistence.beerDao.getActiveBeersSync(ownerUid)
    }

    /**
     * Consulta síncrona de una cerveza por syncId.
     */
    suspend fun getBeer(syncId: String): BeerEntity? = withContext(Dispatchers.IO) {
        persistence.beerDao.getBeerBySyncId(syncId, ownerUid)
    }

    /**
     * Consulta el historial de mutaciones pendientes.
     */
    suspend fun getPendingMutations(): List<LocalMutationHistoryEntity> = withContext(Dispatchers.IO) {
        persistence.historyDao.getPendingMutations(ownerUid)
    }

    /**
     * Consulta el historial durable de una cerveza concreta.
     */
    suspend fun getHistoryForBeer(syncId: String): List<LocalMutationHistoryEntity> = withContext(Dispatchers.IO) {
        persistence.historyDao.getMutationsForBeer(syncId, ownerUid)
    }

    /**
     * 5. SINCRONIZACIÓN PUSH:
     * Empuja una cerveza pendiente hacia Firestore y Storage emulator.
     */
    suspend fun pushBeer(
        syncId: String,
        photoBytes: ByteArray? = null
    ): C4CoordinatorPushResult = withContext(Dispatchers.IO) {
        val history = persistence.historyDao.getMutationsForBeer(syncId, ownerUid)
        val isProvenCreation = history.any { it.isLocalOnlyCreation }
        val pastMutationIds = history.map { it.mutationId }.toSet()
        val evidence = LegacyHistoricalEvidence(
            isProvenNeverSyncedCreation = isProvenCreation,
            pastLocalMutationIds = pastMutationIds
        )
        coordinator.executePush(syncId, ownerUid, photoBytes, evidence)
    }

    /**
     * Empuja todas las cervezas que tienen mutaciones pendientes.
     */
    suspend fun pushAllPending(
        photoBytesMap: Map<String, ByteArray> = emptyMap()
    ): Map<String, C4CoordinatorPushResult> = withContext(Dispatchers.IO) {
        val pendingBeers = persistence.beerDao.getPendingSyncBeers(ownerUid)
        val results = mutableMapOf<String, C4CoordinatorPushResult>()
        for (beer in pendingBeers) {
            val history = persistence.historyDao.getMutationsForBeer(beer.syncId, ownerUid)
            val isProvenCreation = history.any { it.isLocalOnlyCreation }
            val pastMutationIds = history.map { it.mutationId }.toSet()
            val evidence = LegacyHistoricalEvidence(
                isProvenNeverSyncedCreation = isProvenCreation,
                pastLocalMutationIds = pastMutationIds
            )
            results[beer.syncId] = coordinator.executePush(
                syncId = beer.syncId,
                ownerUid = ownerUid,
                photoBytes = photoBytesMap[beer.syncId],
                historicalEvidence = evidence
            )
        }
        results
    }

    /**
     * 6. SINCRONIZACIÓN PULL:
     * Recupera un documento de Firestore emulator y reconcilia localmente en Room.
     */
    suspend fun pullBeer(
        syncId: String
    ): C4CoordinatorPullResult = withContext(Dispatchers.IO) {
        coordinator.executePull(syncId, ownerUid)
    }

    override fun close() {
        try {
            database.close()
        } catch (_: Exception) {}
    }

    companion object {
        /**
         * Fábrica para crear entornos de prueba funcionales aislados.
         */
        fun createForTesting(
            context: Context,
            ownerUid: String = "test_user_c4",
            host: String = C4SyncConstants.EMULATOR_HOST_ANDROID,
            inMemory: Boolean = true,
            dbName: String = "c4_disposable_${System.nanoTime()}.db",
            instances: C4TestEnvironmentFactory.IsolatedFirebaseInstances? = null
        ): C4AppSyncManager {
            val actualInstances = instances ?: C4TestEnvironmentFactory.createIsolatedInstances(
                context = context,
                host = host,
                authPort = 9099,
                firestorePort = C4SyncConstants.FIRESTORE_PORT,
                storagePort = C4SyncConstants.STORAGE_PORT,
                projectId = C4SyncConstants.DEMO_PROJECT_ID
            )

            val database = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            } else {
                Room.databaseBuilder(context, BeerDatabase::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            }

            val persistence = RoomC4SyncPersistence(database)
            val firestoreAdapter = FirestoreC4SyncAdapter(
                actualInstances.firestore,
                expectedProjectId = C4SyncConstants.DEMO_PROJECT_ID
            )
            val storageAdapter = StorageC4SyncAdapter(
                actualInstances.storage,
                expectedBucket = "${C4SyncConstants.DEMO_PROJECT_ID}.appspot.com"
            )
            val coordinator = AndroidC4SyncCoordinator(firestoreAdapter, storageAdapter, persistence)

            return C4AppSyncManager(
                database = database,
                persistence = persistence,
                coordinator = coordinator,
                ownerUid = ownerUid,
                auth = actualInstances.auth
            )
        }
    }
}
