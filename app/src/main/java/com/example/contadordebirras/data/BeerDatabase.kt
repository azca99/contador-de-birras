package com.example.contadordebirras.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BeerEntity::class, com.example.contadordebirras.data.achievements.AchievementEntity::class], version = 6, exportSchema = false)
abstract class BeerDatabase : RoomDatabase() {
    abstract fun beerDao(): BeerDao
    abstract fun achievementDao(): com.example.contadordebirras.data.achievements.AchievementDao

    companion object {
        @Volatile
        private var INSTANCE: BeerDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE beers ADD COLUMN photoUri TEXT")
                db.execSQL("ALTER TABLE beers ADD COLUMN comment TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE beers ADD COLUMN locationName TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE beers ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE beers ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE beers ADD COLUMN remotePhotoUrl TEXT")
                db.execSQL("ALTER TABLE beers ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE beers SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE beers ADD COLUMN photoSource TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `achievements` (`achievementId` TEXT NOT NULL, `unlockedAt` INTEGER, `claimed` INTEGER NOT NULL, `claimedAt` INTEGER, `progressAtUnlock` INTEGER NOT NULL, `points` INTEGER NOT NULL, PRIMARY KEY(`achievementId`))")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM beers WHERE id NOT IN (SELECT MIN(id) FROM beers GROUP BY syncId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_beers_syncId` ON `beers` (`syncId`)")
            }
        }

        fun getDatabase(context: Context): BeerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BeerDatabase::class.java,
                    "beer_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
