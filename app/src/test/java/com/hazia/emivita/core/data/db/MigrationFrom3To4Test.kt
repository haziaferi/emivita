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
 * MIGRATION_3_4 only adds `medicines.doseUnit` via a plain `ALTER TABLE ADD
 * COLUMN` (see the migration's own doc comment) -- same shape as
 * MIGRATION_2_3, same direct-fixture technique as [MigrationFrom2To3Test]
 * for the same reason (that class's doc comment explains the
 * MigrationTestHelper/Robolectric driver bug this sidesteps).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MigrationFrom3To4Test {

    @Test
    fun `migrate adds a nullable doseUnit column, existing rows read back null`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE medicines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        iconKey TEXT NOT NULL,
                        color TEXT,
                        notificationPriority TEXT NOT NULL,
                        remindersLockedUntilTaken INTEGER,
                        notes TEXT,
                        quantity REAL NOT NULL DEFAULT 0.0,
                        unit TEXT,
                        refillQuantity REAL,
                        productionDateEpochDay INTEGER,
                        expirationDateEpochDay INTEGER,
                        sortOrder REAL NOT NULL DEFAULT 0.0,
                        barcodeValue TEXT,
                        indication TEXT,
                        storageNote TEXT,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-3-4-test")
            .callback(callback)
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase

        db.execSQL(
            "INSERT INTO medicines (id, profileId, name, iconKey, color, notificationPriority, " +
                "remindersLockedUntilTaken, notes, quantity, unit, refillQuantity, productionDateEpochDay, " +
                "expirationDateEpochDay, sortOrder, barcodeValue, indication, storageNote, createdAtEpochMillis) " +
                "VALUES (1, 1, 'Vitamin D', 'pill', NULL, 'STANDARD', NULL, NULL, 0.0, NULL, NULL, NULL, " +
                "NULL, 1000.0, NULL, NULL, NULL, 1000)"
        )

        Migrations.MIGRATION_3_4.migrate(db)

        db.query("SELECT doseUnit FROM medicines WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
        }

        db.close()
    }
}
