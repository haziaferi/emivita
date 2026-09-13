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

/** v6 -> v7 (spec §9 #25): `reminders.preAlarmMinutes`, nullable, null for
 *  every existing row. Same hand-built-fixture shape as the other migration
 *  tests, for the reason they give. */
@RunWith(RobolectricTestRunner::class)
class MigrationFrom6To7Test {

    @Test
    fun `migrate adds a nullable preAlarmMinutes and leaves the existing row alone`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE reminders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        medicineId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        configJson TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        weekendModeOptIn INTEGER NOT NULL DEFAULT 0,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("migration-6-7-test").callback(callback).build(),
        ).writableDatabase
        db.execSQL("INSERT INTO reminders (id, medicineId, type, configJson, enabled, weekendModeOptIn, createdAtEpochMillis) VALUES (1, 1, 'FIXED_TIME', '{}', 1, 0, 1000)")

        Migrations.MIGRATION_6_7.migrate(db)

        db.query("SELECT type, enabled, preAlarmMinutes FROM reminders WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("FIXED_TIME", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("null means no pre-alarm, not zero minutes", true, cursor.isNull(2))
        }
        db.execSQL("UPDATE reminders SET preAlarmMinutes = 30 WHERE id = 1")
        db.query("SELECT preAlarmMinutes FROM reminders WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(30, cursor.getInt(0))
        }
        db.close()
    }
}
