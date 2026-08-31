package com.hazia.emivita.core.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MIGRATION_2_3 only adds `reminders.lastAlertNotifiedEpochDay` via a plain
 * `ALTER TABLE ADD COLUMN` (see the migration's own doc comment). Originally
 * written against `MigrationTestHelper` (the exported `app/schemas/.../2.json`
 * and `3.json` make that possible in principle, unlike MIGRATION_1_2 -- see
 * [MigrationFrom1To2Test]), but that combination hits a Room/Robolectric
 * driver bug under this project's setup: `SupportSQLiteMigrationTestHelper`
 * throws `IllegalArgumentException: This driver is configured to open a
 * database named 'X' but '<robolectric temp dir>/X' was requested` before
 * any migration logic even runs -- a path-vs-bare-name mismatch inside
 * Room's newer connection-manager layer, not a real assertion about our
 * schema. Rather than fight that, this uses the same direct-fixture
 * technique as [MigrationFrom1To2Test]: build the v2 `reminders` shape from
 * raw SQL (it's exactly MIGRATION_1_2's own `reminders_new` definition),
 * run the real `Migration` object against it, assert on the result. Still a
 * real regression test of the actual migration; just not schema-JSON-backed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MigrationFrom2To3Test {

    @Test
    fun `migrate adds a nullable lastAlertNotifiedEpochDay column, existing rows read back null`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE reminders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        medicineId INTEGER NOT NULL,
                        groupId INTEGER,
                        parentReminderId INTEGER,
                        type TEXT NOT NULL,
                        configJson TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        autoResolve INTEGER NOT NULL DEFAULT 0,
                        activeFromEpochDay INTEGER,
                        activeUntilEpochDay INTEGER,
                        repeat_delayMinutes INTEGER,
                        repeat_maxRepeats INTEGER,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-2-3-test")
            .callback(callback)
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase

        db.execSQL(
            "INSERT INTO reminders (id, medicineId, groupId, parentReminderId, type, configJson, enabled, " +
                "autoResolve, activeFromEpochDay, activeUntilEpochDay, repeat_delayMinutes, repeat_maxRepeats, createdAtEpochMillis) " +
                "VALUES (1, 1, NULL, NULL, 'FIXED_TIME', '{}', 1, 0, NULL, NULL, NULL, NULL, 1000)"
        )

        Migrations.MIGRATION_2_3.migrate(db)

        db.query("SELECT lastAlertNotifiedEpochDay FROM reminders WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
        }

        db.close()
    }
}
