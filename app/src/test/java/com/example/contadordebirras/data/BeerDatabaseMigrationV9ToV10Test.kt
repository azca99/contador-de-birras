package com.example.contadordebirras.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.domain.BeerType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pruebas unitarias en JVM (Robolectric) para la migración de Room v9 a v10 y regresión de migraciones.
 */
@RunWith(RobolectricTestRunner::class)
class BeerDatabaseMigrationV9ToV10Test {

    private lateinit var context: Context
    private val dbName = "test_beer_database_migration.db"
    private var database: BeerDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(dbName)
    }

    private fun createHelper(version: Int, callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

    /**
     * Valida que la migración v9 -> v10:
     * 1. Preserva íntegramente la tabla `beers` sin pérdida de registros.
     * 2. Conserva exactamente todos los estados: SYNCED, PENDING y DELETED.
     * 3. Conserva exactamente las revisiones 0L / 0L sin convertirlas a -1L/-1L.
     * 4. Conserva intactos dirtyFields y lastMutationId.
     * 5. Conserva todos los campos de fotos: photoUri, remotePhotoUrl y photoSource.
     * 6. Conserva el aislamiento por ownerUid (incluyendo cuentas normales, guest_local y legacy_unassigned).
     * 7. Crea correctamente la tabla `local_mutation_history` y sus índices.
     * 8. Pasa la validación estricta de Room Schema v10 al abrir con Room.databaseBuilder.
     */
    @Test
    fun migration_9_to_10_preservesDataAndPassesRoomValidation() {
        // 1. Crear la base de datos directamente en versión 9 con el esquema exacto de 9.json
        val v9Callback = object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // Crear tabla beers exactamente como en v9
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `beers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL,
                        `photoUri` TEXT,
                        `comment` TEXT,
                        `locationName` TEXT,
                        `syncId` TEXT NOT NULL,
                        `syncStatus` TEXT NOT NULL,
                        `remotePhotoUrl` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        `photoSource` TEXT,
                        `ownerUid` TEXT NOT NULL,
                        `remoteRevision` INTEGER NOT NULL,
                        `baseRevision` INTEGER NOT NULL,
                        `dirtyFields` TEXT,
                        `lastMutationId` TEXT,
                        `deletedAt` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_beers_ownerUid_syncId` ON `beers` (`ownerUid`, `syncId`)")

                // Crear tabla achievements como en v9
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `achievements` (
                        `ownerUid` TEXT NOT NULL,
                        `achievementId` TEXT NOT NULL,
                        `unlockedAt` INTEGER,
                        `claimed` INTEGER NOT NULL,
                        `claimedAt` INTEGER,
                        `progressAtUnlock` INTEGER NOT NULL,
                        `points` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerUid`, `achievementId`)
                    )
                """.trimIndent())

                // Insertar registros representativos en beers
                // Registro 1: SYNCED con revisiones remotas positivas, foto local y remota
                db.execSQL("""
                    INSERT INTO beers (
                        id, type, timestamp, latitude, longitude, photoUri, comment, locationName,
                        syncId, syncStatus, remotePhotoUrl, updatedAt, photoSource, ownerUid,
                        remoteRevision, baseRevision, dirtyFields, lastMutationId, deletedAt
                    ) VALUES (
                        1, 'LATA', 1000, 40.4168, -3.7038, 'file:///local/photo1.jpg', 'Gran IPA', 'Madrid',
                        'sync-1', 'SYNCED', 'https://storage/photo1.jpg', 2000, 'camera', 'user_alpha',
                        5, 5, NULL, NULL, NULL
                    )
                """.trimIndent())

                // Registro 2: PENDING con revisiones exactas 0/0 (legacy), dirtyFields y mutationId
                db.execSQL("""
                    INSERT INTO beers (
                        id, type, timestamp, latitude, longitude, photoUri, comment, locationName,
                        syncId, syncStatus, remotePhotoUrl, updatedAt, photoSource, ownerUid,
                        remoteRevision, baseRevision, dirtyFields, lastMutationId, deletedAt
                    ) VALUES (
                        2, 'BOTELLIN', 1050, NULL, NULL, NULL, 'Stout pendiente', NULL,
                        'sync-2', 'PENDING', NULL, 2050, NULL, 'user_alpha',
                        0, 0, 'TYPE,COMMENT', 'mut-alpha-1', NULL
                    )
                """.trimIndent())

                // Registro 3: DELETED con revisiones positivas y deletedAt
                db.execSQL("""
                    INSERT INTO beers (
                        id, type, timestamp, latitude, longitude, photoUri, comment, locationName,
                        syncId, syncStatus, remotePhotoUrl, updatedAt, photoSource, ownerUid,
                        remoteRevision, baseRevision, dirtyFields, lastMutationId, deletedAt
                    ) VALUES (
                        3, 'CANA', 1100, NULL, NULL, NULL, NULL, NULL,
                        'sync-3', 'DELETED', NULL, 2100, NULL, 'user_alpha',
                        4, 4, NULL, 'mut-del-1', 2100
                    )
                """.trimIndent())

                // Registro 4: guest_local con revisiones 0/0
                db.execSQL("""
                    INSERT INTO beers (
                        id, type, timestamp, latitude, longitude, photoUri, comment, locationName,
                        syncId, syncStatus, remotePhotoUrl, updatedAt, photoSource, ownerUid,
                        remoteRevision, baseRevision, dirtyFields, lastMutationId, deletedAt
                    ) VALUES (
                        4, 'COPA', 1200, NULL, NULL, 'file:///local/guest.jpg', 'Guest beer', NULL,
                        'sync-4', 'PENDING', NULL, 2200, 'gallery', 'guest_local',
                        0, 0, 'ALL_FIELDS', 'mut-guest-1', NULL
                    )
                """.trimIndent())

                // Registro 5: legacy_unassigned con revisiones 0/0
                db.execSQL("""
                    INSERT INTO beers (
                        id, type, timestamp, latitude, longitude, photoUri, comment, locationName,
                        syncId, syncStatus, remotePhotoUrl, updatedAt, photoSource, ownerUid,
                        remoteRevision, baseRevision, dirtyFields, lastMutationId, deletedAt
                    ) VALUES (
                        5, 'JARRA', 1300, NULL, NULL, NULL, 'Legacy unassigned', NULL,
                        'sync-5', 'SYNCED', NULL, 2300, NULL, 'legacy_unassigned',
                        0, 0, NULL, NULL, NULL
                    )
                """.trimIndent())

                // Registro en achievements
                db.execSQL("""
                    INSERT INTO achievements (ownerUid, achievementId, unlockedAt, claimed, claimedAt, progressAtUnlock, points)
                    VALUES ('user_alpha', 'first_beer', 1000, 1, 1050, 1, 10)
                """.trimIndent())
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

        val helper = createHelper(9, v9Callback)
        val v9Db = helper.writableDatabase
        v9Db.close()
        helper.close()

        // 2. Abrir la base de datos con RoomDatabase v10 aplicando MIGRATION_9_10
        val roomDb = Room.databaseBuilder(context, BeerDatabase::class.java, dbName)
            .addMigrations(BeerDatabase.MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()
        database = roomDb

        val beerDao = roomDb.beerDao()
        val historyDao = roomDb.localMutationHistoryDao()

        // 3. Verificar que los 5 registros de beers se conservaron íntegramente
        val alphaBeers = beerDao.getPendingSyncBeers("user_alpha")
        assertEquals("user_alpha debe tener 2 cervezas pendientes/borradas", 2, alphaBeers.size)

        // Verificar Registro 1: SYNCED
        val beer1 = beerDao.getBeerBySyncId("sync-1", "user_alpha")
        assertNotNull("sync-1 debe existir", beer1)
        beer1!!
        assertEquals(BeerType.LATA, beer1.type)
        assertEquals(SyncStatus.SYNCED, beer1.syncStatus)
        assertEquals(5L, beer1.remoteRevision)
        assertEquals(5L, beer1.baseRevision)
        assertEquals("file:///local/photo1.jpg", beer1.photoUri)
        assertEquals("https://storage/photo1.jpg", beer1.remotePhotoUrl)
        assertEquals("camera", beer1.photoSource)
        assertEquals("Gran IPA", beer1.comment)
        assertEquals("Madrid", beer1.locationName)
        assertNull("dirtyFields debe ser null", beer1.dirtyFields)
        assertNull("lastMutationId debe ser null", beer1.lastMutationId)

        // Verificar Registro 2: PENDING con 0/0 estricto
        val beer2 = beerDao.getBeerBySyncId("sync-2", "user_alpha")
        assertNotNull("sync-2 debe existir", beer2)
        beer2!!
        assertEquals(BeerType.BOTELLIN, beer2.type)
        assertEquals(SyncStatus.PENDING, beer2.syncStatus)
        assertEquals("PROHIBIDO cambiar 0/0 a -1/-1: remoteRevision debe seguir siendo 0", 0L, beer2.remoteRevision)
        assertEquals("PROHIBIDO cambiar 0/0 a -1/-1: baseRevision debe seguir siendo 0", 0L, beer2.baseRevision)
        assertEquals("TYPE,COMMENT", beer2.dirtyFields)
        assertEquals("mut-alpha-1", beer2.lastMutationId)

        // Verificar Registro 3: DELETED
        val beer3 = beerDao.getBeerBySyncId("sync-3", "user_alpha")
        assertNotNull("sync-3 debe existir", beer3)
        beer3!!
        assertEquals(SyncStatus.DELETED, beer3.syncStatus)
        assertEquals(4L, beer3.remoteRevision)
        assertEquals(4L, beer3.baseRevision)
        assertEquals(2100L, beer3.deletedAt)
        assertEquals("mut-del-1", beer3.lastMutationId)

        // Verificar Registro 4: guest_local
        val beerGuest = beerDao.getBeerBySyncId("sync-4", "guest_local")
        assertNotNull("sync-4 de guest_local debe existir", beerGuest)
        assertEquals(0L, beerGuest!!.remoteRevision)
        assertEquals(0L, beerGuest.baseRevision)
        assertEquals("file:///local/guest.jpg", beerGuest.photoUri)
        assertEquals("gallery", beerGuest.photoSource)

        // Verificar Registro 5: legacy_unassigned
        val beerUnassigned = beerDao.getBeerBySyncId("sync-5", "legacy_unassigned")
        assertNotNull("sync-5 de legacy_unassigned debe existir", beerUnassigned)
        assertEquals(0L, beerUnassigned!!.remoteRevision)
        assertEquals(0L, beerUnassigned.baseRevision)

        // 4. Verificar que la tabla local_mutation_history fue creada y está inicialmente vacía
        assertEquals(0, historyDao.getHistoryCount("user_alpha"))
        assertEquals(0, historyDao.getHistoryCount("guest_local"))

        // 5. Verificar que se pueden registrar mutaciones en local_mutation_history
        val mut1 = LocalMutationHistoryEntity(
            mutationId = "new-mut-1",
            syncId = "sync-2",
            ownerUid = "user_alpha",
            timestamp = 3000L,
            dirtyFields = "COMMENT",
            baseRevision = 0L,
            status = MutationStatus.PENDING
        )
        historyDao.insertMutation(mut1)

        val historyRecords = historyDao.getMutationsForBeer("sync-2", "user_alpha")
        assertEquals(1, historyRecords.size)
        assertEquals("new-mut-1", historyRecords[0].mutationId)
        assertEquals(MutationStatus.PENDING, historyRecords[0].status)
    }

    /**
     * Valida que una base de datos recién instalada en versión 10 se inicializa limpiamente
     * con todas sus tablas e índices.
     */
    @Test
    fun freshlyInstalledV10Database_initializesCleanly() {
        val emptyDb = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val beerDao = emptyDb.beerDao()
            val historyDao = emptyDb.localMutationHistoryDao()

            val beer = BeerEntity(
                type = BeerType.LATA,
                timestamp = 5000L,
                syncId = "fresh-sync-1",
                ownerUid = "user_beta"
            )
            val rowId = beerDao.insertBeer(beer)
            assertTrue("La inserción en beers debe ser exitosa", rowId > 0)

            val mutation = LocalMutationHistoryEntity(
                mutationId = "fresh-mut-1",
                syncId = "fresh-sync-1",
                ownerUid = "user_beta",
                timestamp = 5000L,
                dirtyFields = "ALL_FIELDS",
                baseRevision = -1L,
                status = MutationStatus.PENDING,
                isLocalOnlyCreation = true
            )
            val mutRowId = historyDao.insertMutation(mutation)
            assertTrue("La inserción en historial debe ser exitosa", mutRowId > 0)

            val retrievedBeer = beerDao.getBeerBySyncId("fresh-sync-1", "user_beta")
            assertNotNull(retrievedBeer)

            val retrievedHistory = historyDao.getMutationsForBeer("fresh-sync-1", "user_beta")
            assertEquals(1, retrievedHistory.size)
            assertEquals("fresh-mut-1", retrievedHistory[0].mutationId)
            assertTrue(retrievedHistory[0].isLocalOnlyCreation)
        } finally {
            emptyDb.close()
        }
    }

    /**
     * Valida la regresión secuencial de todas las migraciones históricas (1->2->...->9->10).
     */
    @Test
    fun sequentialMigrationChain_1_to_10_executesWithoutError() {
        val v1Callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // Esquema inicial versión 1
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `beers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL
                    )
                """.trimIndent())
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

        val helper = createHelper(1, v1Callback)
        val db = helper.writableDatabase

        // Ejecutar secuencialmente cada migración
        BeerDatabase.MIGRATION_1_2.migrate(db)
        BeerDatabase.MIGRATION_2_3.migrate(db)
        BeerDatabase.MIGRATION_3_4.migrate(db)
        BeerDatabase.MIGRATION_4_5.migrate(db)
        BeerDatabase.MIGRATION_5_6.migrate(db)
        BeerDatabase.MIGRATION_6_7.migrate(db)
        BeerDatabase.MIGRATION_7_8.migrate(db)
        BeerDatabase.MIGRATION_8_9.migrate(db)
        BeerDatabase.MIGRATION_9_10.migrate(db)

        // Verificar que ambas tablas finales existen y se pueden consultar
        val beersCursor = db.query("SELECT COUNT(*) FROM beers")
        assertTrue(beersCursor.moveToFirst())
        assertEquals(0, beersCursor.getInt(0))
        beersCursor.close()

        val historyCursor = db.query("SELECT COUNT(*) FROM local_mutation_history")
        assertTrue(historyCursor.moveToFirst())
        assertEquals(0, historyCursor.getInt(0))
        historyCursor.close()

        db.close()
        helper.close()
    }
}
