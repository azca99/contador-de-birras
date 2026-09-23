package com.example.contadordebirras.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class Migration6To7Test {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BeerDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate6To7() {
        // Create database in version 6
        var db = helper.createDatabase(TEST_DB, 6)

        // Insert a beer in version 6 schema
        db.execSQL("""
            INSERT INTO beers (id, type, timestamp, syncId, syncStatus, updatedAt) 
            VALUES (1, 'LATA', 123456789, 'sync-id-1', 'PENDING', 0)
        """)
        
        db.close()

        // Run migration to version 7
        db = helper.runMigrationsAndValidate(TEST_DB, 7, true, BeerDatabase.MIGRATION_6_7)

        // Query the migrated data
        val cursor = db.query("SELECT * FROM beers WHERE id = 1")
        assertTrue("No data found after migration", cursor.moveToFirst())

        val ownerUidIndex = cursor.getColumnIndex("ownerUid")
        assertTrue("Column ownerUid not found", ownerUidIndex != -1)
        
        val ownerUid = cursor.getString(ownerUidIndex)
        assertEquals("legacy_unassigned", ownerUid)

        // Test the new index index_beers_ownerUid_syncId
        // Try inserting a duplicate ownerUid and syncId -> should fail
        var uniqueConstraintFailed = false
        try {
            db.execSQL("""
                INSERT INTO beers (id, type, timestamp, syncId, syncStatus, updatedAt, ownerUid) 
                VALUES (2, 'LATA', 123456789, 'sync-id-1', 'PENDING', 0, 'legacy_unassigned')
            """)
        } catch (e: Exception) {
            uniqueConstraintFailed = true
        }
        assertTrue("Expected unique constraint failure for duplicate ownerUid+syncId", uniqueConstraintFailed)

        // Try inserting a duplicate syncId but DIFFERENT ownerUid -> should succeed
        db.execSQL("""
            INSERT INTO beers (id, type, timestamp, syncId, syncStatus, updatedAt, ownerUid) 
            VALUES (3, 'LATA', 123456789, 'sync-id-1', 'PENDING', 0, 'userA')
        """)
        
        val cursor2 = db.query("SELECT * FROM beers WHERE id = 3")
        assertTrue(cursor2.moveToFirst())

        cursor.close()
        cursor2.close()
        db.close()
    }
}
