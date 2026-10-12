package com.example.contadordebirras.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {

    private val TEST_DB = "migration-test-8-9"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BeerDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate8To9_preservesAllExistingFieldsAndAddsNewDefaults() {
        // 1. Crear base de datos en version 8
        var db = helper.createDatabase(TEST_DB, 8)

        // 2. Insertar una cerveza con valores no triviales en TODOS los campos antiguos
        db.execSQL("""
            INSERT INTO beers (
                id, type, timestamp, latitude, longitude, photoUri, comment, locationName,
                syncId, syncStatus, remotePhotoUrl, updatedAt, photoSource, ownerUid
            ) VALUES (
                1, 'LATA', 1700000000000, 40.416775, -3.703790,
                'content://media/external/images/media/42', 'Cerveza artesanal de prueba', 'Bar Central',
                'sync-uuid-1234', 'SYNCED', 'users/userA/beers/sync-uuid-1234.jpg', 1700000050000,
                'CAMERA', 'userA'
            )
        """)

        // También insertar en achievements para confirmar que permanece intacto
        db.execSQL("""
            INSERT INTO achievements (
                ownerUid, achievementId, unlockedAt, claimed, claimedAt, progressAtUnlock, points
            ) VALUES (
                'userA', 'ach_test_8_9', 12345, 1, 67890, 10, 25
            )
        """)

        db.close()

        // 3. Ejecutar migración 8 -> 9
        db = helper.runMigrationsAndValidate(TEST_DB, 9, true, BeerDatabase.MIGRATION_8_9)

        // 4. Verificar cero pérdida de datos en campos antiguos
        val cursor = db.query("SELECT * FROM beers WHERE id = 1")
        assertTrue("No se encontró el registro tras la migración", cursor.moveToFirst())

        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("id")))
        assertEquals("LATA", cursor.getString(cursor.getColumnIndexOrThrow("type")))
        assertEquals(1700000000000L, cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")))
        assertEquals(40.416775, cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")), 0.000001)
        assertEquals(-3.703790, cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")), 0.000001)
        assertEquals("content://media/external/images/media/42", cursor.getString(cursor.getColumnIndexOrThrow("photoUri")))
        assertEquals("Cerveza artesanal de prueba", cursor.getString(cursor.getColumnIndexOrThrow("comment")))
        assertEquals("Bar Central", cursor.getString(cursor.getColumnIndexOrThrow("locationName")))
        assertEquals("sync-uuid-1234", cursor.getString(cursor.getColumnIndexOrThrow("syncId")))
        assertEquals("SYNCED", cursor.getString(cursor.getColumnIndexOrThrow("syncStatus")))
        assertEquals("users/userA/beers/sync-uuid-1234.jpg", cursor.getString(cursor.getColumnIndexOrThrow("remotePhotoUrl")))
        assertEquals(1700000050000L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))
        assertEquals("CAMERA", cursor.getString(cursor.getColumnIndexOrThrow("photoSource")))
        assertEquals("userA", cursor.getString(cursor.getColumnIndexOrThrow("ownerUid")))

        // 5. Verificar nuevos campos y defaults esperados
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("remoteRevision")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("baseRevision")))

        val dirtyFieldsIndex = cursor.getColumnIndexOrThrow("dirtyFields")
        assertTrue("dirtyFields debe ser null", cursor.isNull(dirtyFieldsIndex))

        val lastMutationIdIndex = cursor.getColumnIndexOrThrow("lastMutationId")
        assertTrue("lastMutationId debe ser null", cursor.isNull(lastMutationIdIndex))

        val deletedAtIndex = cursor.getColumnIndexOrThrow("deletedAt")
        assertTrue("deletedAt debe ser null", cursor.isNull(deletedAtIndex))

        cursor.close()

        // 6. Verificar que achievements sigue intacto
        val achCursor = db.query("SELECT * FROM achievements WHERE achievementId = 'ach_test_8_9'")
        assertTrue(achCursor.moveToFirst())
        assertEquals("userA", achCursor.getString(achCursor.getColumnIndexOrThrow("ownerUid")))
        assertEquals(25, achCursor.getInt(achCursor.getColumnIndexOrThrow("points")))
        achCursor.close()

        // 7. Verificar índice único (ownerUid, syncId)
        // Duplicado con mismo ownerUid y mismo syncId -> DEBE FALLAR
        var uniqueConstraintFailed = false
        try {
            db.execSQL("""
                INSERT INTO beers (
                    id, type, timestamp, syncId, syncStatus, updatedAt, ownerUid
                ) VALUES (
                    2, 'LATA', 123456789, 'sync-uuid-1234', 'PENDING', 0, 'userA'
                )
            """)
        } catch (e: Exception) {
            uniqueConstraintFailed = true
        }
        assertTrue("Se esperaba fallo por unique constraint (ownerUid, syncId)", uniqueConstraintFailed)

        // Duplicado de syncId pero con DISTINTO ownerUid -> DEBE FUNCIONAR
        db.execSQL("""
            INSERT INTO beers (
                id, type, timestamp, syncId, syncStatus, updatedAt, ownerUid
            ) VALUES (
                3, 'LATA', 123456789, 'sync-uuid-1234', 'PENDING', 0, 'userB'
            )
        """)
        val cursorUserB = db.query("SELECT * FROM beers WHERE id = 3")
        assertTrue(cursorUserB.moveToFirst())
        assertEquals("userB", cursorUserB.getString(cursorUserB.getColumnIndexOrThrow("ownerUid")))
        cursorUserB.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate7To9_chainMigrationSucceedsWithoutDataLoss() {
        // Crear base de datos en version 7
        var db = helper.createDatabase(TEST_DB, 7)

        // Insertar cerveza en esquema 7
        db.execSQL("""
            INSERT INTO beers (
                id, type, timestamp, photoUri, comment, locationName,
                syncId, syncStatus, remotePhotoUrl, updatedAt, photoSource, ownerUid
            ) VALUES (
                10, 'BOTELLIN', 1690000000000, 'file:///path/to/beer.jpg', 'Cadena 7 a 9', 'Terraza',
                'sync-chain-7-9', 'PENDING', null, 1690000010000, 'GALLERY', 'userChain'
            )
        """)

        // Insertar logro en esquema 7
        db.execSQL("""
            INSERT INTO achievements (
                achievementId, unlockedAt, claimed, claimedAt, progressAtUnlock, points
            ) VALUES (
                'ach_chain', 11111, 0, null, 5, 10
            )
        """)

        db.close()

        // Ejecutar migración encadenada 7 -> 8 -> 9
        db = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            BeerDatabase.MIGRATION_7_8,
            BeerDatabase.MIGRATION_8_9
        )

        // Verificar cerveza migrada a v9
        val cursor = db.query("SELECT * FROM beers WHERE id = 10")
        assertTrue(cursor.moveToFirst())
        assertEquals("BOTELLIN", cursor.getString(cursor.getColumnIndexOrThrow("type")))
        assertEquals("file:///path/to/beer.jpg", cursor.getString(cursor.getColumnIndexOrThrow("photoUri")))
        assertEquals("userChain", cursor.getString(cursor.getColumnIndexOrThrow("ownerUid")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("remoteRevision")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("baseRevision")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("dirtyFields")))
        cursor.close()

        // Verificar achievement migrado a v9
        val achCursor = db.query("SELECT * FROM achievements WHERE achievementId = 'ach_chain'")
        assertTrue(achCursor.moveToFirst())
        assertEquals("legacy_unassigned", achCursor.getString(achCursor.getColumnIndexOrThrow("ownerUid")))
        assertEquals(10, achCursor.getInt(achCursor.getColumnIndexOrThrow("points")))
        achCursor.close()

        db.close()
    }
}
