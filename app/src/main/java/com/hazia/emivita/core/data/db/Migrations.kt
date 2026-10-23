package com.hazia.emivita.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: lands the full §3 "gap-resolution" schema (sortOrder, barcodeValue,
 * indication, storageNote, Medicine/Profile color-as-hex, Reminder autoResolve/
 * active-range/repeatOnUnanswered/parentReminderId, DoseEvent nullable reminderId
 * + denormalized medicineId + stock snapshot + repeatCount + DoseStatus.REFILLED)
 * ahead of the Add/Edit Medicine + Add/Edit Reminder UI (spec §9 item 1) that
 * writes to it. SQLite's ALTER TABLE can't change nullability or add a foreign
 * key, so the four touched tables are recreated rather than patched.
 *
 * MigrationFrom1To2Test covers the data movement on the host JVM, not Room's
 * schema validator on a device, which is strict about name, type affinity,
 * notNull and default value.
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

    /** v4 -> v5: adds `reminders.weekendModeOptIn`, turning weekend mode from
     *  a global that shifted every reminder into a per-reminder opt-in
     *  (spec §3, §7 R36). The constant `DEFAULT 0` is required by ALTER TABLE
     *  for a NOT NULL column and must match Room's fresh-install schema, so
     *  `Reminder.weekendModeOptIn` carries `@ColumnInfo(defaultValue = "0")`:
     *  remove that annotation and this migration fails schema validation.
     *
     *  Existing rows default to 0 even for users who had the global toggle on,
     *  because weekend mode delays a dose and a migration must not assume each
     *  reminder's consent; opting back in is one switch per reminder. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN weekendModeOptIn INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v10 -> v11 (§7 R111, Appendix E C-15): a one-off dose of a medicine not
     *  on the list makes `dose_events.medicineId` nullable and adds `customName`
     *  and `customProfileId` (with its FK to `profiles` and index). SQLite cannot
     *  drop NOT NULL in place, so the table is rebuilt with every row, id, index
     *  and the reminders FK's CASCADE as Room's own v11 DDL declares them. */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `dose_events_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reminderId` INTEGER, `medicineId` INTEGER, " +
                    "`scheduledEpochMillis` INTEGER NOT NULL, `doseAmount` REAL NOT NULL, `status` TEXT NOT NULL, " +
                    "`resolvedAtEpochMillis` INTEGER, `stockBefore` REAL NOT NULL, `stockAfter` REAL NOT NULL, " +
                    "`repeatCount` INTEGER NOT NULL, `notes` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, " +
                    "`customName` TEXT, `customProfileId` INTEGER, " +
                    "FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`customProfileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            val columns = "id, reminderId, medicineId, scheduledEpochMillis, doseAmount, status, resolvedAtEpochMillis, " +
                "stockBefore, stockAfter, repeatCount, notes, createdAtEpochMillis"
            db.execSQL("INSERT INTO dose_events_new ($columns) SELECT $columns FROM dose_events")
            db.execSQL("DROP TABLE dose_events")
            db.execSQL("ALTER TABLE dose_events_new RENAME TO dose_events")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dose_events_reminderId_scheduledEpochMillis` ON `dose_events` (`reminderId`, `scheduledEpochMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_scheduledEpochMillis` ON `dose_events` (`scheduledEpochMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_medicineId` ON `dose_events` (`medicineId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_customProfileId` ON `dose_events` (`customProfileId`)")
        }
    }

    /** v11 -> v12 (§7 R116, the owner's switch): `medicines.asNeeded`. A medicine
     *  with no enabled dosing reminder (alert reminders schedule no dose) and a
     *  dose already logged gets it on, so what Today listed as as-needed stays
     *  there with its Log button. `NOT NULL DEFAULT 0` matches the entity's
     *  `@ColumnInfo(defaultValue = "0")`, as MIGRATION_4_5 does. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE medicines ADD COLUMN asNeeded INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE medicines SET asNeeded = 1 WHERE " +
                    "NOT EXISTS (SELECT 1 FROM reminders WHERE reminders.medicineId = medicines.id AND reminders.enabled = 1 " +
                    "AND reminders.type NOT IN ('LOW_STOCK_ALERT', 'EXPIRATION_ALERT')) " +
                    "AND EXISTS (SELECT 1 FROM dose_events WHERE dose_events.medicineId = medicines.id AND dose_events.status = 'TAKEN')",
            )
        }
    }

    /** v12 -> v13 (§7 R118, R119): the `dose_events.reminderId` FK goes from
     *  CASCADE to SET NULL, with `planned` (backfilled `reminderId IS NOT NULL`)
     *  and `missedAfterEpochMillis` (a pending dose of a repeating reminder: its
     *  last repeat under the reminder's present settings, else null) added.
     *  SQLite cannot change an FK in place, so the table is rebuilt as
     *  MIGRATION_10_11 rebuilt it, with Room's own v13 DDL. */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `dose_events_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reminderId` INTEGER, `medicineId` INTEGER, " +
                    "`scheduledEpochMillis` INTEGER NOT NULL, `doseAmount` REAL NOT NULL, `status` TEXT NOT NULL, " +
                    "`resolvedAtEpochMillis` INTEGER, `stockBefore` REAL NOT NULL, `stockAfter` REAL NOT NULL, " +
                    "`repeatCount` INTEGER NOT NULL, `notes` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, " +
                    "`customName` TEXT, `customProfileId` INTEGER, `planned` INTEGER NOT NULL, `missedAfterEpochMillis` INTEGER, " +
                    "FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , " +
                    "FOREIGN KEY(`customProfileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            val columns = "id, reminderId, medicineId, scheduledEpochMillis, doseAmount, status, resolvedAtEpochMillis, " +
                "stockBefore, stockAfter, repeatCount, notes, createdAtEpochMillis, customName, customProfileId"
            db.execSQL(
                "INSERT INTO dose_events_new ($columns, planned, missedAfterEpochMillis) " +
                    "SELECT $columns, reminderId IS NOT NULL, " +
                    "CASE WHEN status = 'PENDING' THEN (SELECT dose_events.scheduledEpochMillis + reminders.repeat_delayMinutes * reminders.repeat_maxRepeats * 60000 " +
                    "FROM reminders WHERE reminders.id = dose_events.reminderId AND reminders.repeat_delayMinutes IS NOT NULL " +
                    "AND reminders.repeat_maxRepeats IS NOT NULL) END " +
                    "FROM dose_events",
            )
            db.execSQL("DROP TABLE dose_events")
            db.execSQL("ALTER TABLE dose_events_new RENAME TO dose_events")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dose_events_reminderId_scheduledEpochMillis` ON `dose_events` (`reminderId`, `scheduledEpochMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_scheduledEpochMillis` ON `dose_events` (`scheduledEpochMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_medicineId` ON `dose_events` (`medicineId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_events_customProfileId` ON `dose_events` (`customProfileId`)")
        }
    }

    /** Every migration, oldest first: the database and its migration tests open
     *  through the same list. */
    val ALL: Array<Migration> by lazy {
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
    }

    /** v9 -> v10 (§7 R109, Appendix E B-42 and B-45): two per-reminder flags,
     *  off for every existing reminder. `NOT NULL DEFAULT 0` matches the
     *  entity's `@ColumnInfo(defaultValue = "0")`, as MIGRATION_4_5 does. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN stickyUntilAnswered INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE reminders ADD COLUMN variableAmount INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v8 -> v9 (§9 #27, the medicine cabinet, §7 R66): four nullable columns
     *  on `medicines` -- opened date, use-within days, strength, Rx number --
     *  so plain `ALTER TABLE ADD COLUMN` matches Room's fresh-install schema. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE medicines ADD COLUMN openedDateEpochDay INTEGER")
            db.execSQL("ALTER TABLE medicines ADD COLUMN useWithinDaysAfterOpening INTEGER")
            db.execSQL("ALTER TABLE medicines ADD COLUMN strength TEXT")
            db.execSQL("ALTER TABLE medicines ADD COLUMN rxNumber TEXT")
        }
    }

    /** v7 -> v8 (§9 #26, history as a record): six nullable columns, one
     *  NOT NULL DEFAULT 0 flag and the notes-journal table. The flag needs
     *  `@ColumnInfo(defaultValue = "0")` on the entity so Room's own CREATE
     *  TABLE matches this ALTER on a fresh install. The journal's FK cascades
     *  with the medicine and carries the index on medicineId Room requires. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE dose_events ADD COLUMN notes TEXT")
            db.execSQL("ALTER TABLE reminders ADD COLUMN instructions TEXT")
            db.execSQL("ALTER TABLE reminders ADD COLUMN dosesRemaining INTEGER")
            db.execSQL("ALTER TABLE medicines ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE medicines ADD COLUMN prnMaxDoses INTEGER")
            db.execSQL("ALTER TABLE medicines ADD COLUMN prnWindowHours INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS medicine_notes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "medicineId INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, " +
                    "createdAtEpochMillis INTEGER NOT NULL, " +
                    "editedAtEpochMillis INTEGER, " +
                    "FOREIGN KEY(medicineId) REFERENCES medicines(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_medicine_notes_medicineId ON medicine_notes (medicineId)")
        }
    }

    /** v6 -> v7 (§9 #25): the optional pre-alarm lead on a reminder. Nullable
     *  with no default, like the medical-id columns: absent is "no pre-alarm",
     *  which a NOT NULL 0 would have to mean by convention instead. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN preAlarmMinutes INTEGER")
        }
    }

    /** v5 -> v6: adds the seven emergency/medical-card columns to `profiles`
     *  (spec §9 parking lot, promoted in §7 R48): seven nullable TEXT columns
     *  with no default and no FK, which plain `ALTER TABLE ADD COLUMN` handles
     *  and which matches Room's fresh-install schema without a
     *  `@ColumnInfo(defaultValue = ...)` pairing.
     *
     *  Nullable rather than NOT NULL DEFAULT '' on purpose, because
     *  EmergencyCardExporter omits a whole section when its fields are absent
     *  rather than printing an empty heading, and null carries "not filled in"
     *  without every call site testing for blankness too. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (column in listOf(
                "bloodType", "allergies",
                "doctorName", "doctorPhone", "doctorAddress",
                "pharmacyName", "pharmacyPhone",
            )) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN $column TEXT")
            }
        }
    }
}
