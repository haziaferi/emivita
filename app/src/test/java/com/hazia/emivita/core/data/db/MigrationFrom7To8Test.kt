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

/** v7 -> v8 (spec §9 #26): six columns and the notes-journal table. */
@RunWith(RobolectricTestRunner::class)
class MigrationFrom7To8Test {

    @Test
    fun `migrate adds the columns with null or zero and creates the journal with its index`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE medicines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, medicineId INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE dose_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, medicineId INTEGER NOT NULL, status TEXT NOT NULL)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("migration-7-8-test").callback(callback).build(),
        ).writableDatabase
        db.execSQL("INSERT INTO medicines (id, name) VALUES (1, 'M')")
        db.execSQL("INSERT INTO reminders (id, medicineId) VALUES (1, 1)")
        db.execSQL("INSERT INTO dose_events (id, medicineId, status) VALUES (1, 1, 'TAKEN')")

        Migrations.MIGRATION_7_8.migrate(db)

        db.query("SELECT archived, prnMaxDoses, prnWindowHours FROM medicines WHERE id = 1").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("an existing medicine is not archived", 0, c.getInt(0))
            assertEquals(true, c.isNull(1)); assertEquals(true, c.isNull(2))
        }
        db.query("SELECT instructions, dosesRemaining FROM reminders WHERE id = 1").use { c ->
            assertEquals(true, c.moveToFirst()); assertEquals(true, c.isNull(0)); assertEquals(true, c.isNull(1))
        }
        db.query("SELECT notes, status FROM dose_events WHERE id = 1").use { c ->
            assertEquals(true, c.moveToFirst()); assertEquals(true, c.isNull(0)); assertEquals("TAKEN", c.getString(1))
        }
        db.execSQL("INSERT INTO medicine_notes (medicineId, text, createdAtEpochMillis) VALUES (1, 'n', 5)")
        db.query("SELECT text, editedAtEpochMillis FROM medicine_notes").use { c ->
            assertEquals(true, c.moveToFirst()); assertEquals("n", c.getString(0)); assertEquals(true, c.isNull(1))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_medicine_notes_medicineId'").use { c ->
            assertEquals("the index Room's entity declares", true, c.moveToFirst())
        }
        db.close()
    }
}
