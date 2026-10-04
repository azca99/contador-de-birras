package com.example.contadordebirras.data.migration

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object PhotoRecoveryHelper {

    /**
     * Executes the recovery of historical photos from 'legacy_unassigned'.
     *
     * @param db The writable SQLite database.
     * @param activeUid The user ID that should own the recovered photos.
     * @return true if exactly 5 rows were updated or if it was already updated (idempotent 0 rows).
     *         false if the update touched an unexpected number of rows (rollback occurs).
     */
    fun recoverPhotos(db: SupportSQLiteDatabase, activeUid: String): Boolean {
        var success = false
        db.beginTransaction()
        try {
            val updateSql = """
                UPDATE beers
                SET photoUri = (SELECT legacy.photoUri FROM beers legacy WHERE legacy.syncId = beers.syncId AND legacy.ownerUid = 'legacy_unassigned'),
                    photoSource = (SELECT legacy.photoSource FROM beers legacy WHERE legacy.syncId = beers.syncId AND legacy.ownerUid = 'legacy_unassigned')
                WHERE ownerUid = ?
                  AND syncStatus != 'DELETED'
                  AND (photoUri IS NULL OR photoUri = '')
                  AND syncId IN (
                      SELECT b1.syncId FROM beers b1
                      JOIN beers b2 ON b1.syncId = b2.syncId
                      WHERE b1.ownerUid = ? AND b2.ownerUid = 'legacy_unassigned'
                        AND b1.type = b2.type 
                        AND b1.timestamp = b2.timestamp
                        AND b2.photoUri IS NOT NULL AND b2.photoUri != ''
                      GROUP BY b1.syncId
                      HAVING COUNT(*) = 1
                  )
            """
            
            val stmt = db.compileStatement(updateSql)
            stmt.bindString(1, activeUid)
            stmt.bindString(2, activeUid)
            
            val rowsAffected = stmt.executeUpdateDelete()
            
            if (rowsAffected == 5 || rowsAffected == 0) {
                db.setTransactionSuccessful()
                success = true
            } else {
                // If it's not 5 or 0 (idempotent), we rollback.
                success = false
                android.util.Log.e("PhotoRecoveryHelper", "ROLLBACK: Expected 5 or 0 updates, got ${rowsAffected}")
            }
        } finally {
            db.endTransaction()
        }
        return success
    }
}
