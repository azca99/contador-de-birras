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

/**
 * Prueba instrumentada para la migración Room 9 -> 10.
 *
 * NOTA DE SEGURIDAD: Esta prueba está preparada para entornos CI / emulador controlado,
 * y NO debe ejecutarse sobre dispositivos físicos en producción ni Xiaomi.
 */
@RunWith(AndroidJUnit4::class)
class Migration9To10Test {

    private val TEST_DB = "migration-test-9-10"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BeerDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate9To10_preservesBeersAndCreatesLocalMutationHistory() {
        // 1. Crear base de datos en versión 9
        var db = helper.createDatabase(TEST_DB, 9)

        // 2. Insertar registros en beers con valores representativos
        db.execSQL("""
            INSERT INTO beers (
                id, type, timestamp, latitude, longitude, photoUri, comment, locationName,
                syncId, syncStatus, remotePhotoUrl, updatedAt, photoSource, ownerUid,
                remoteRevision, baseRevision, dirtyFields, lastMutationId, deletedAt
            ) VALUES (
                1, 'LATA', 1700000000000, 40.416775, -3.703790,
                'content://media/external/images/media/42', 'Cerveza de prueba', 'Bar Central',
                'sync-uuid-9-10', 'PENDING', 'users/userA/beers/sync-uuid-9-10.jpg', 1700000050000,
                'CAMERA', 'userA', 0, 0, 'TYPE,COMMENT', 'mut-test-1', NULL
            )
        """)

        db.execSQL("""
            INSERT INTO achievements (
                ownerUid, achievementId, unlockedAt, claimed, claimedAt, progressAtUnlock, points
            ) VALUES (
                'userA', 'ach_test_9_10', 12345, 1, 67890, 10, 25
            )
        """)

        db.close()

        // 3. Ejecutar migración 9 -> 10 con validación estricta de Room Schema
        db = helper.runMigrationsAndValidate(TEST_DB, 10, true, BeerDatabase.MIGRATION_9_10)

        // 4. Verificar preservación íntegra de la tabla beers
        val cursor = db.query("SELECT * FROM beers WHERE id = 1")
        assertTrue("La cerveza con id=1 debe existir", cursor.moveToFirst())

        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("id")))
        assertEquals("LATA", cursor.getString(cursor.getColumnIndexOrThrow("type")))
        assertEquals(1700000000000L, cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")))
        assertEquals(40.416775, cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")), 0.0001)
        assertEquals(-3.703790, cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")), 0.0001)
        assertEquals("content://media/external/images/media/42", cursor.getString(cursor.getColumnIndexOrThrow("photoUri")))
        assertEquals("Cerveza de prueba", cursor.getString(cursor.getColumnIndexOrThrow("comment")))
        assertEquals("Bar Central", cursor.getString(cursor.getColumnIndexOrThrow("locationName")))
        assertEquals("sync-uuid-9-10", cursor.getString(cursor.getColumnIndexOrThrow("syncId")))
        assertEquals("PENDING", cursor.getString(cursor.getColumnIndexOrThrow("syncStatus")))
        assertEquals("users/userA/beers/sync-uuid-9-10.jpg", cursor.getString(cursor.getColumnIndexOrThrow("remotePhotoUrl")))
        assertEquals(1700000050000L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))
        assertEquals("CAMERA", cursor.getString(cursor.getColumnIndexOrThrow("photoSource")))
        assertEquals("userA", cursor.getString(cursor.getColumnIndexOrThrow("ownerUid")))
        assertEquals("remoteRevision 0/0 debe conservarse intacto", 0L, cursor.getLong(cursor.getColumnIndexOrThrow("remoteRevision")))
        assertEquals("baseRevision 0/0 debe conservarse intacto", 0L, cursor.getLong(cursor.getColumnIndexOrThrow("baseRevision")))
        assertEquals("TYPE,COMMENT", cursor.getString(cursor.getColumnIndexOrThrow("dirtyFields")))
        assertEquals("mut-test-1", cursor.getString(cursor.getColumnIndexOrThrow("lastMutationId")))
        assertTrue("deletedAt debe ser null", cursor.isNull(cursor.getColumnIndexOrThrow("deletedAt")))
        cursor.close()

        // 5. Verificar que achievements sigue intacto
        val achCursor = db.query("SELECT * FROM achievements WHERE ownerUid = 'userA' AND achievementId = 'ach_test_9_10'")
        assertTrue(achCursor.moveToFirst())
        assertEquals(1, achCursor.getInt(achCursor.getColumnIndexOrThrow("claimed")))
        assertEquals(25, achCursor.getInt(achCursor.getColumnIndexOrThrow("points")))
        achCursor.close()

        // 6. Verificar que la tabla local_mutation_history fue creada correctamente
        val histCursor = db.query("SELECT COUNT(*) FROM local_mutation_history")
        assertTrue(histCursor.moveToFirst())
        assertEquals("El historial debe inicializarse vacío sin evidencia ficticia", 0, histCursor.getInt(0))
        histCursor.close()

        // 7. Probar inserción en local_mutation_history
        db.execSQL("""
            INSERT INTO local_mutation_history (
                mutationId, syncId, ownerUid, timestamp, dirtyFields, baseRevision, status, isLocalOnlyCreation
            ) VALUES (
                'mut-test-1', 'sync-uuid-9-10', 'userA', 1700000050000, 'TYPE,COMMENT', 0, 'PENDING', 0
            )
        """)

        val verifyHist = db.query("SELECT * FROM local_mutation_history WHERE mutationId = 'mut-test-1'")
        assertTrue(verifyHist.moveToFirst())
        assertEquals("mut-test-1", verifyHist.getString(verifyHist.getColumnIndexOrThrow("mutationId")))
        assertEquals("PENDING", verifyHist.getString(verifyHist.getColumnIndexOrThrow("status")))
        verifyHist.close()

        db.close()
    }
}
