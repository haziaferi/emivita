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
 * MIGRATION_4_5 adds `reminders.weekendModeOptIn` with a constant default,
 * tested with [MigrationFrom2To3Test]'s direct-fixture technique (its doc
 * comment explains the driver bug) against v4 DDL copied from the exported
 * `app/schemas/.../4.json`, not written from the entity. What is pinned is the
 * default: 0 (opted out), so a user with the old global weekend toggle on does
 * not silently carry a blanket delay onto every reminder (docs/SPEC.md §7 R36).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MigrationFrom4To5Test {

    @Test
    fun `migrate adds weekendModeOptIn and backfills existing rows opted out`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
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
                        enabled INTEGER NOT NULL,
                        autoResolve INTEGER NOT NULL,
                        activeFromEpochDay INTEGER,
                        activeUntilEpochDay INTEGER,
                        lastAlertNotifiedEpochDay INTEGER,
                        createdAtEpochMillis INTEGER NOT NULL,
                        repeat_delayMinutes INTEGER,
                        repeat_maxRepeats INTEGER
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-4-5-test")
            .callback(callback)
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase

        db.execSQL(
            "INSERT INTO reminders (id, medicineId, groupId, parentReminderId, type, configJson, " +
                "enabled, autoResolve, activeFromEpochDay, activeUntilEpochDay, " +
                "lastAlertNotifiedEpochDay, createdAtEpochMillis, repeat_delayMinutes, repeat_maxRepeats) " +
                "VALUES (1, 1, NULL, NULL, 'FIXED_TIME', '{}', 1, 0, NULL, NULL, NULL, 1000, NULL, NULL)"
        )

        Migrations.MIGRATION_4_5.migrate(db)

        db.query("SELECT weekendModeOptIn FROM reminders WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(false, cursor.isNull(0))
            assertEquals(0, cursor.getInt(0))
        }

        // The column has to be usable as a real flag afterwards, not just
        // present: a row written post-migration must round-trip 1.
        db.execSQL(
            "INSERT INTO reminders (id, medicineId, groupId, parentReminderId, type, configJson, " +
                "enabled, autoResolve, activeFromEpochDay, activeUntilEpochDay, " +
                "lastAlertNotifiedEpochDay, createdAtEpochMillis, repeat_delayMinutes, repeat_maxRepeats, " +
                "weekendModeOptIn) " +
                "VALUES (2, 1, NULL, NULL, 'FIXED_TIME', '{}', 1, 0, NULL, NULL, NULL, 2000, NULL, NULL, 1)"
        )
        db.query("SELECT weekendModeOptIn FROM reminders WHERE id = 2").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // The DEFAULT has to survive into the stored DDL, not just apply
        // to the backfill. Room compares column default values when it
        // validates a migrated database against the schema it would have
        // created itself, and Reminder.weekendModeOptIn carries an explicit
        // @ColumnInfo(defaultValue = "0") so the two agree. Dropping either
        // side is an IllegalStateException on the first launch after an
        // upgrade, on a real device, with nothing else in this suite to
        // catch it -- these tests drive the migration's raw SQL and never
        // run Room's validation.
        db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'reminders'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            val ddl = cursor.getString(0)
            assertEquals(
                "reminders DDL should carry the constant default, was: $ddl",
                true,
                ddl.contains("weekendModeOptIn INTEGER NOT NULL DEFAULT 0"),
            )
        }

        db.close()
    }
}
