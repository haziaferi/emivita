package com.hazia.emivita.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: lands the full §3 "gap-resolution" schema (sortOrder, barcodeValue,
 * indication, storageNote, Medicine/Profile color-as-hex, Reminder autoResolve/
 * active-range/repeatOnUnanswered/parentReminderId, DoseEvent nullable reminderId
 * + denormalized medicineId + stock snapshot + repeatCount + DoseStatus.REFILLED)
 * ahead of the Add/Edit Medicine + Add/Edit Reminder UI (spec §9 item 1) that
 * writes to it. SQLite's ALTER TABLE can't add a column mid-table, change
 * nullability, or add a foreign key, so the four touched tables are recreated
 * rather than patched column-by-column -- the standard SQLite migration shape,
 * not a shortcut.
 *
 * Covered by MigrationFrom1To2Test, which builds the v1 tables from SQL and
 * asserts the colour rewrite, the sortOrder backfill and the dose-event
 * medicineId denormalisation against real rows. That is host-JVM (Robolectric)
 * coverage of the data movement, not of Room's own schema validator on a real
 * device -- so a first run against a debug build with real data is still worth
 * watching, since the validator is strict about name, type affinity, notNull
 * and default value.
 */
object Migrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys=OFF")

            // ---- profiles: colorArgb (INTEGER) -> color (TEXT hex) ----
            db.execSQL(
                """
                CREATE TABLE profiles_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    color TEXT,
                    createdAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO profiles_new (id, name, color, createdAtEpochMillis)
                SELECT id, name,
                    CASE WHEN colorArgb IS NULL THEN NULL ELSE printf('#%06X', colorArgb & 16777215) END,
                    createdAtEpochMillis
                FROM profiles
                """.trimIndent()
            )
            db.execSQL("DROP TABLE profiles")
            db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")

            // ---- medicines: colorArgb -> color, + sortOrder/barcodeValue/indication/
            //      storageNote, remindersLockedUntilTaken widened to nullable ----
            db.execSQL(
                """
                CREATE TABLE medicines_new (
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
                    createdAtEpochMillis INTEGER NOT NULL,
                    FOREIGN KEY(profileId) REFERENCES profiles(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO medicines_new (
                    id, profileId, name, iconKey, color, notificationPriority,
                    remindersLockedUntilTaken, notes, quantity, unit, refillQuantity,
                    productionDateEpochDay, expirationDateEpochDay, sortOrder,
                    barcodeValue, indication, storageNote, createdAtEpochMillis
                )
                SELECT
                    id, profileId, name, iconKey,
                    CASE WHEN colorArgb IS NULL THEN NULL ELSE printf('#%06X', colorArgb & 16777215) END,
                    notificationPriority, remindersLockedUntilTaken, notes, quantity, unit,
                    refillQuantity, productionDateEpochDay, expirationDateEpochDay,
                    -- append-at-end for pre-existing rows, matches the new-medicine
                    -- convention (sortOrder = createdAtEpochMillis)
                    CAST(createdAtEpochMillis AS REAL),
                    NULL, NULL, NULL, createdAtEpochMillis
                FROM medicines
                """.trimIndent()
            )
            db.execSQL("DROP TABLE medicines")
            db.execSQL("ALTER TABLE medicines_new RENAME TO medicines")
            db.execSQL("CREATE INDEX index_medicines_profileId ON medicines(profileId)")

            // ---- reminders: + parentReminderId, autoResolve, active-range, repeat_* ----
            db.execSQL(
                """
                CREATE TABLE reminders_new (
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
                    createdAtEpochMillis INTEGER NOT NULL,
                    FOREIGN KEY(medicineId) REFERENCES medicines(id) ON DELETE CASCADE,
                    FOREIGN KEY(groupId) REFERENCES dose_groups(id) ON DELETE SET NULL,
                    FOREIGN KEY(parentReminderId) REFERENCES reminders(id) ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO reminders_new (
                    id, medicineId, groupId, parentReminderId, type, configJson, enabled,
                    autoResolve, activeFromEpochDay, activeUntilEpochDay,
                    repeat_delayMinutes, repeat_maxRepeats, createdAtEpochMillis
                )
                SELECT
                    id, medicineId, groupId, NULL, type, configJson, enabled,
                    0, NULL, NULL, NULL, NULL, createdAtEpochMillis
                FROM reminders
                """.trimIndent()
            )
            db.execSQL("DROP TABLE reminders")
            db.execSQL("ALTER TABLE reminders_new RENAME TO reminders")
            db.execSQL("CREATE INDEX index_reminders_medicineId ON reminders(medicineId)")
            db.execSQL("CREATE INDEX index_reminders_groupId ON reminders(groupId)")
            db.execSQL("CREATE INDEX index_reminders_parentReminderId ON reminders(parentReminderId)")

            // ---- dose_events: reminderId now nullable, + medicineId, stock
            //      snapshot, repeatCount; DoseStatus.REFILLED needs no column change,
            //      it's just a new string value the existing `status` TEXT column
            //      already accepts ----
            db.execSQL(
                """
                CREATE TABLE dose_events_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    reminderId INTEGER,
                    medicineId INTEGER NOT NULL,
                    scheduledEpochMillis INTEGER NOT NULL,
                    doseAmount REAL NOT NULL,
                    status TEXT NOT NULL,
                    resolvedAtEpochMillis INTEGER,
                    stockBefore REAL NOT NULL DEFAULT -1.0,
                    stockAfter REAL NOT NULL DEFAULT -1.0,
                    repeatCount INTEGER NOT NULL DEFAULT 0,
                    createdAtEpochMillis INTEGER NOT NULL,
                    FOREIGN KEY(reminderId) REFERENCES reminders(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO dose_events_new (
                    id, reminderId, medicineId, scheduledEpochMillis, doseAmount, status,
                    resolvedAtEpochMillis, stockBefore, stockAfter, repeatCount, createdAtEpochMillis
                )
                SELECT
                    de.id, de.reminderId,
                    (SELECT r.medicineId FROM reminders r WHERE r.id = de.reminderId),
                    de.scheduledEpochMillis, de.doseAmount, de.status, de.resolvedAtEpochMillis,
                    -1.0, -1.0, 0, de.createdAtEpochMillis
                FROM dose_events de
                WHERE de.reminderId IN (SELECT id FROM reminders)
                """.trimIndent()
            )
            db.execSQL("DROP TABLE dose_events")
            db.execSQL("ALTER TABLE dose_events_new RENAME TO dose_events")
            db.execSQL("CREATE UNIQUE INDEX index_dose_events_reminderId_scheduledEpochMillis ON dose_events(reminderId, scheduledEpochMillis)")
            db.execSQL("CREATE INDEX index_dose_events_scheduledEpochMillis ON dose_events(scheduledEpochMillis)")
            db.execSQL("CREATE INDEX index_dose_events_medicineId ON dose_events(medicineId)")

            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    /** v2 -> v3: adds `reminders.lastAlertNotifiedEpochDay` for
     *  LowStockAlert/ExpirationAlert triggering (spec §9 #8). A single
     *  nullable column with no default expression and no FK -- one of the
     *  narrow cases SQLite's ALTER TABLE ADD COLUMN actually supports, so
     *  this doesn't need the recreate-the-table dance MIGRATION_1_2 needed. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN lastAlertNotifiedEpochDay INTEGER")
        }
    }

    /** v3 -> v4: adds `medicines.doseUnit` (spec: "Measurement" field on
     *  Add/Edit Medicine, a display-only dose-unit label kept deliberately
     *  separate from the existing `unit` column, whose nullability already
     *  gates stock-tracking behavior elsewhere -- see Medicine.kt's doc
     *  comment on the field). Same shape as MIGRATION_2_3: one nullable
     *  column, no default expression, no FK -- ALTER TABLE ADD COLUMN
     *  handles this case directly, no recreate-the-table dance needed. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE medicines ADD COLUMN doseUnit TEXT")
        }
    }
}
