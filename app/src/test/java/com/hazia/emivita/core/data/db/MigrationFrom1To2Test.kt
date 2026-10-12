package com.hazia.emivita.core.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MIGRATION_1_2 recreates all four touched tables rather than patching them
 * in place (§comment on the migration itself), the shape most likely to
 * corrupt data silently, since a dropped column, a wrong SELECT ordering, or a
 * bad CASE expression only shows up against real rows. No v1 schema JSON
 * exists (`app/schemas` starts at v2), so instead of MigrationTestHelper it
 * builds the v1 tables in SQL, column-for-column what the migration's own
 * SELECT clauses read, which describe v1 exactly. See [MigrationFrom2To3Test]
 * for the schema-JSON-backed alternative, used where a baseline exists.
 */
// sdk pinned below the app's targetSdk (36) -- Robolectric 4.14.1 only ships
// framework jars up to SDK 35, and `includeAndroidResources` (needed so
// MigrationTestHelper can find the exported schema JSON as an asset, see
// MigrationFrom2To3Test) makes Robolectric actually read the real merged
// manifest's targetSdk and reject it otherwise. `application` overridden to
// a plain Application rather than the real `EmivitaApplication`, whose
// `onCreate()` calls `System.loadLibrary("sqlcipher")` -- fine on-device,
// fatal under Robolectric's host-JVM SQLite (there's no Windows/host build
// of SQLCipher's native lib). This test never touches SQLCipher at all, so
// there's nothing lost by not running the real Application here.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MigrationFrom1To2Test {

    private fun openV1Database(name: String): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        colorArgb INTEGER,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE medicines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        iconKey TEXT NOT NULL,
                        colorArgb INTEGER,
                        notificationPriority TEXT NOT NULL,
                        remindersLockedUntilTaken INTEGER NOT NULL,
                        notes TEXT,
                        quantity REAL NOT NULL DEFAULT 0.0,
                        unit TEXT,
                        refillQuantity REAL,
                        productionDateEpochDay INTEGER,
                        expirationDateEpochDay INTEGER,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE reminders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        medicineId INTEGER NOT NULL,
                        groupId INTEGER,
                        type TEXT NOT NULL,
                        configJson TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE dose_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        reminderId INTEGER NOT NULL,
                        scheduledEpochMillis INTEGER NOT NULL,
                        doseAmount REAL NOT NULL,
                        status TEXT NOT NULL,
                        resolvedAtEpochMillis INTEGER,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `migrate rewrites colorArgb to hex, backfills sortOrder, denormalizes dose event medicineId`() {
        val db = openV1Database("migration-1-2-test")

        // 0xFF3F51B5 as a signed Int (as Color.toArgb() would produce) --
        // exercises the migration's `colorArgb & 16777215` masking against a
        // negative Int, not just a small positive test value.
        val profileColorArgb = 0xFF3F51B5.toInt()
        db.execSQL(
            "INSERT INTO profiles (id, name, colorArgb, createdAtEpochMillis) VALUES (1, 'Me', $profileColorArgb, 1000)"
        )

        val medicineColorArgb = 0xFFE91E63.toInt()
        db.execSQL(
            """
            INSERT INTO medicines (
                id, profileId, name, iconKey, colorArgb, notificationPriority,
                remindersLockedUntilTaken, notes, quantity, unit, refillQuantity,
                productionDateEpochDay, expirationDateEpochDay, createdAtEpochMillis
            ) VALUES (
                1, 1, 'Ibuprofen', 'pill', $medicineColorArgb, 'STANDARD',
                0, NULL, 12.0, 'tablet', 30.0,
                NULL, NULL, 2000
            )
            """.trimIndent()
        )
        // A second medicine with a NULL colorArgb -- the migration's CASE
        // expression must pass NULL through, not crash or coerce it to a color.
        db.execSQL(
            """
            INSERT INTO medicines (
                id, profileId, name, iconKey, colorArgb, notificationPriority,
                remindersLockedUntilTaken, notes, quantity, unit, refillQuantity,
                productionDateEpochDay, expirationDateEpochDay, createdAtEpochMillis
            ) VALUES (
                2, 1, 'Paracetamol', 'pill', NULL, 'STANDARD',
                0, NULL, 0.0, NULL, NULL,
                NULL, NULL, 2500
            )
            """.trimIndent()
        )

        db.execSQL(
            "INSERT INTO reminders (id, medicineId, groupId, type, configJson, enabled, createdAtEpochMillis) " +
                "VALUES (1, 1, NULL, 'FIXED_TIME', '{}', 1, 3000)"
        )
        db.execSQL(
            "INSERT INTO dose_events (id, reminderId, scheduledEpochMillis, doseAmount, status, resolvedAtEpochMillis, createdAtEpochMillis) " +
                "VALUES (1, 1, 4000, 1.0, 'PENDING', NULL, 4000)"
        )

        Migrations.MIGRATION_1_2.migrate(db)

        db.query("SELECT color FROM profiles WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("#3F51B5", cursor.getString(0))
        }

        db.query("SELECT color, sortOrder, barcodeValue, indication, storageNote FROM medicines WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("#E91E63", cursor.getString(0))
            assertEquals(2000.0, cursor.getDouble(1), 0.0001)
            assertEquals(true, cursor.isNull(2))
            assertEquals(true, cursor.isNull(3))
            assertEquals(true, cursor.isNull(4))
        }

        db.query("SELECT color FROM medicines WHERE id = 2").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertNull(cursor.getString(0))
        }

        db.query("SELECT parentReminderId, autoResolve, activeFromEpochDay, activeUntilEpochDay, repeat_delayMinutes, repeat_maxRepeats FROM reminders WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(true, cursor.isNull(2))
            assertEquals(true, cursor.isNull(3))
            assertEquals(true, cursor.isNull(4))
            assertEquals(true, cursor.isNull(5))
        }

        db.query("SELECT medicineId, stockBefore, stockAfter, repeatCount FROM dose_events WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(-1.0, cursor.getDouble(1), 0.0001)
            assertEquals(-1.0, cursor.getDouble(2), 0.0001)
            assertEquals(0, cursor.getInt(3))
        }

        db.close()
    }
}
