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
 * MIGRATION_5_6 adds the seven Medical ID columns to `profiles` (spec section
 * 7 R48), tested with [MigrationFrom4To5Test]'s direct-fixture technique (see
 * [MigrationFrom2To3Test] for why), with the v5 DDL copied from the exported
 * schema JSON so the fixture is what an installed v5 database really holds.
 *
 * It pins that existing profile rows, the parents of every medicine, survive
 * intact with the new columns NULL, and that the columns are *nullable*,
 * because EmergencyCardExporter omits a contact block that is "not filled in"
 * and a NOT NULL DEFAULT '' would collapse that into "filled in as empty".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MigrationFrom5To6Test {

    private val newColumns = listOf(
        "bloodType", "allergies",
        "doctorName", "doctorPhone", "doctorAddress",
        "pharmacyName", "pharmacyPhone",
    )

    @Test
    fun `migrate adds the medical id columns without disturbing the existing row`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        color TEXT,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-5-6-test")
            .callback(callback)
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase

        db.execSQL(
            "INSERT INTO profiles (id, name, color, createdAtEpochMillis) " +
                "VALUES (1, 'Me', '#B9722E', 1000)"
        )

        Migrations.MIGRATION_5_6.migrate(db)

        // The pre-existing row is the whole profile -- every medicine, reminder
        // and dose event hangs off it -- so it has to come through untouched.
        db.query("SELECT name, color, createdAtEpochMillis FROM profiles WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Me", cursor.getString(0))
            assertEquals("#B9722E", cursor.getString(1))
            assertEquals(1000L, cursor.getLong(2))
        }

        // Null, not empty string: the exporter reads the difference.
        db.query("SELECT ${newColumns.joinToString()} FROM profiles WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            newColumns.forEachIndexed { index, column ->
                assertEquals("$column should be NULL after migration", true, cursor.isNull(index))
            }
        }

        db.close()
    }

    @Test
    fun `the new columns are writable and round-trip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        color TEXT,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-5-6-roundtrip-test")
            .callback(callback)
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase

        Migrations.MIGRATION_5_6.migrate(db)

        db.execSQL(
            "INSERT INTO profiles (id, name, color, createdAtEpochMillis, bloodType, allergies, " +
                "doctorName, doctorPhone, doctorAddress, pharmacyName, pharmacyPhone) " +
                "VALUES (2, 'Ada Lovelace', NULL, 2000, 'O+', 'Penicillin', " +
                "'Dr Somerville', '01865 000000', '1 Museum Road, Oxford', 'Boots High St', '01865 111111')"
        )

        db.query("SELECT bloodType, allergies, doctorName, doctorPhone, doctorAddress, pharmacyName, pharmacyPhone " +
            "FROM profiles WHERE id = 2").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("O+", cursor.getString(0))
            assertEquals("Penicillin", cursor.getString(1))
            assertEquals("Dr Somerville", cursor.getString(2))
            assertEquals("01865 000000", cursor.getString(3))
            assertEquals("1 Museum Road, Oxford", cursor.getString(4))
            assertEquals("Boots High St", cursor.getString(5))
            assertEquals("01865 111111", cursor.getString(6))
        }

        db.close()
    }
}
