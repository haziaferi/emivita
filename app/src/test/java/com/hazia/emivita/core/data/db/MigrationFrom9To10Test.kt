package com.hazia.emivita.core.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v9 -> v10 (§7 R109, Appendix E B-42 and B-45): two per-reminder flags, off
 * for every existing reminder, against the v9 `reminders` DDL from the
 * exported `9.json` (foreign keys left out: the migration does not touch them).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationFrom9To10Test {

    @Test
    fun `migrate adds both flags as 0 and keeps the row`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, medicineId INTEGER NOT NULL, groupId INTEGER, " +
                        "parentReminderId INTEGER, type TEXT NOT NULL, configJson TEXT NOT NULL, enabled INTEGER NOT NULL, autoResolve INTEGER NOT NULL, " +
                        "activeFromEpochDay INTEGER, activeUntilEpochDay INTEGER, weekendModeOptIn INTEGER NOT NULL DEFAULT 0, preAlarmMinutes INTEGER, " +
                        "instructions TEXT, dosesRemaining INTEGER, lastAlertNotifiedEpochDay INTEGER, createdAtEpochMillis INTEGER NOT NULL, " +
                        "repeat_delayMinutes INTEGER, repeat_maxRepeats INTEGER)",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("migration-9-10-test").callback(callback).build(),
        ).writableDatabase
        db.execSQL(
            "INSERT INTO reminders (id, medicineId, type, configJson, enabled, autoResolve, instructions, dosesRemaining, createdAtEpochMillis) " +
                "VALUES (1, 1, 'FIXED_TIME', '{}', 1, 0, 'with food', 5, 1000)",
        )

        Migrations.MIGRATION_9_10.migrate(db)

        db.query("SELECT stickyUntilAnswered, variableAmount, instructions, dosesRemaining FROM reminders WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("stickyUntilAnswered", 0, c.getInt(0))
            assertEquals("variableAmount", 0, c.getInt(1))
            assertEquals("with food", c.getString(2))
            assertEquals(5, c.getInt(3))
        }
        // The default must be in the stored DDL, where Room's validation compares it (see MigrationFrom4To5Test).
        db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'reminders'").use { c ->
            assertTrue(c.moveToFirst())
            val ddl = c.getString(0)
            assertTrue(ddl, ddl.contains("stickyUntilAnswered INTEGER NOT NULL DEFAULT 0"))
            assertTrue(ddl, ddl.contains("variableAmount INTEGER NOT NULL DEFAULT 0"))
        }
        db.close()
    }
}
