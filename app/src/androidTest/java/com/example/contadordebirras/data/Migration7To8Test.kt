package com.example.contadordebirras.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration7To8Test {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BeerDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate7To8() {
        var db = helper.createDatabase(TEST_DB, 7)

        // Insert legacy data
        db.execSQL("INSERT INTO achievements (achievementId, unlockedAt, claimed, claimedAt, progressAtUnlock, points) VALUES ('ach1', 12345, 0, null, 10, 50)")
        
        db.close()

        // Re-open at version 8 and run migration
        db = helper.runMigrationsAndValidate(TEST_DB, 8, true, BeerDatabase.MIGRATION_7_8)

        // Verify
        val cursor = db.query("SELECT * FROM achievements")
        assertEquals(1, cursor.count)
        cursor.moveToFirst()

        val ownerUid = cursor.getString(cursor.getColumnIndexOrThrow("ownerUid"))
        val achievementId = cursor.getString(cursor.getColumnIndexOrThrow("achievementId"))
        val points = cursor.getInt(cursor.getColumnIndexOrThrow("points"))

        assertEquals("legacy_unassigned", ownerUid)
        assertEquals("ach1", achievementId)
        assertEquals(50, points)

        cursor.close()
    }
}
