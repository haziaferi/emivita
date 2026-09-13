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

/** v8 -> v9 (spec §9 #27): four nullable medicine columns. */
@RunWith(RobolectricTestRunner::class)
class MigrationFrom8To9Test {

    @Test
    fun `migrate adds the four columns as null and keeps the row`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE medicines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, expirationDateEpochDay INTEGER)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("migration-8-9-test").callback(callback).build(),
        ).writableDatabase
        db.execSQL("INSERT INTO medicines (id, name, expirationDateEpochDay) VALUES (1, 'M', 20000)")

        Migrations.MIGRATION_8_9.migrate(db)

        db.query("SELECT strength, rxNumber, openedDateEpochDay, useWithinDaysAfterOpening, expirationDateEpochDay FROM medicines WHERE id = 1").use { c ->
            assertEquals(true, c.moveToFirst())
            (0..3).forEach { assertEquals("column $it is null", true, c.isNull(it)) }
            assertEquals("the printed expiry is untouched", 20000L, c.getLong(4))
        }
        db.close()
    }
}
