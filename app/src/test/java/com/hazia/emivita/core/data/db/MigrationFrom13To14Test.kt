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
 * v13 -> v14 (§7 R127, B-38): `reminders.importance`, null on every existing row ("as the
 * medicine"), against the v13 `reminders` DDL from the exported `13.json` (foreign keys left out:
 * the migration does not touch them). The list the database opens through ends with it.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationFrom13To14Test {

    @Test
    fun `migrate adds importance as null and keeps the row`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(13) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, medicineId INTEGER NOT NULL, groupId INTEGER, " +
                        "parentReminderId INTEGER, type TEXT NOT NULL, configJson TEXT NOT NULL, enabled INTEGER NOT NULL, autoResolve INTEGER NOT NULL, " +
                        "activeFromEpochDay INTEGER, activeUntilEpochDay INTEGER, weekendModeOptIn INTEGER NOT NULL DEFAULT 0, preAlarmMinutes INTEGER, " +
                        "instructions TEXT, dosesRemaining INTEGER, stickyUntilAnswered INTEGER NOT NULL DEFAULT 0, variableAmount INTEGER NOT NULL DEFAULT 0, " +
                        "lastAlertNotifiedEpochDay INTEGER, createdAtEpochMillis INTEGER NOT NULL, repeat_delayMinutes INTEGER, repeat_maxRepeats INTEGER)",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("migration-13-14-test").callback(callback).build(),
        ).writableDatabase
        db.execSQL(
            "INSERT INTO reminders (id, medicineId, type, configJson, enabled, autoResolve, instructions, variableAmount, createdAtEpochMillis) " +
                "VALUES (1, 1, 'FIXED_TIME', '{}', 1, 0, 'with food', 1, 1000)",
        )

        Migrations.MIGRATION_13_14.migrate(db)

        db.query("SELECT importance, instructions, variableAmount FROM reminders WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("importance is null, as the medicine", c.isNull(0))
            assertEquals("with food", c.getString(1))
            assertEquals(1, c.getInt(2))
        }
        db.execSQL("UPDATE reminders SET importance = 'HIGH' WHERE id = 1")
        db.query("SELECT importance FROM reminders WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals("HIGH", c.getString(0)) }
        db.close()
        assertEquals("the database opens through it", Migrations.MIGRATION_13_14, Migrations.ALL.last())
    }
}
