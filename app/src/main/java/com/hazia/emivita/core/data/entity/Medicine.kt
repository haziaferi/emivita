package com.hazia.emivita.core.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import androidx.room.ColumnInfo

// @Serializable: backup/restore JSON snapshot (spec §9 #9) serializes entities
// directly rather than through a parallel DTO layer.
@Serializable
@Entity(
    tableName = "medicines",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val iconKey: String, // key into the fixed icon set -- see ui/icons/MedicineIcons.kt
    val color: String? = null, // hex, null = flat #B8ABA9 neutral fallback (spec §3/§6).
    // Replaces the old `colorArgb: Int?` -- dropped in favor of this hex-string field
    // per spec §3 ("Confirmed: the hex picker above replaces Medicine.colorArgb, it
    // doesn't sit alongside it"). See Migrations.kt MIGRATION_1_2 for the column swap.
    val notificationPriority: NotificationPriority = NotificationPriority.STANDARD,
    // Free-text label for what a dose is measured in ("capsules", "drops",
    // "tablet") -- purely a display hint appended next to a formatted dose
    // amount wherever one's shown (Today, History List/Table/CSV/PDF), and
    // shown as a read-only suffix next to every dose-amount entry field.
    // Deliberately a SEPARATE field from StockInfo.unit below, not a reuse of
    // it: that field's nullability is load-bearing elsewhere (DoseEventRepository,
    // LowStockExpirationAlertEvaluator, OccurrenceProjectionCache all read
    // `stockInfo.unit == null` as "this medicine has stock tracking off") --
    // typing a display label here must never silently flip stock-tracking
    // behavior on for a medicine that never asked for it.
    val doseUnit: String? = null,
    // Boolean? not Boolean -- null = inherit the global
    // lockRemindersUntilTakenByDefault preference, non-null = explicit
    // per-medicine override (spec §3). SettingsRepository.isLockedUntilTaken()
    // is the one place that resolution happens; the notification builder, the
    // action receiver and the alarm screen all go through it. TodayScreen
    // resolves the same two inputs inline instead -- composition cannot call a
    // suspend function, so it collects the global default as state and applies
    // this override at each row. It is named here because it *is* an
    // enforcement point: this list read as exhaustive while Today shipped an
    // unconditional Skip chip, which is how the gate stayed open on the app's
    // start destination.
    val remindersLockedUntilTaken: Boolean? = null,
    val notes: String? = null,
    @Embedded val stockInfo: StockInfo = StockInfo(),
    // Manual drag-to-reorder (spec §3). A Double so reordering one item only ever
    // needs a value between its new neighbors, never a renumbering pass. New medicines
    // (post-migration): sortOrder = createdAtEpochMillis, i.e. append at the end --
    // deliberately not alphabetical-insertion.
    val sortOrder: Double,
    // Scanned barcode/QR content for quick re-identification only -- no local drug
    // database, no online lookup (spec §1 local-first). See feature/medicines'
    // barcode scan action (delegates to an installed scanner app via Intent).
    val barcodeValue: String? = null,
    // Short free-text, deliberately low visual weight in the Add form (spec §6).
    val indication: String? = null,
    val storageNote: String? = null,
    /** Archived (§9 #26, the owner's reversal of the delete cascade): hidden
     *  from Medicines, Today and the projection, reminders removed, dose
     *  history kept for History and the exports. Restorable from Settings >
     *  Data; "Delete permanently" there is the old cascade. */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** The as-needed ceiling (§9 #26; Pill Time PT-01): at most [prnMaxDoses]
     *  in any rolling [prnWindowHours]. Both null = no ceiling. Warns with a
     *  confirm at the ceiling, never refuses (the owner's choice). */
    val prnMaxDoses: Int? = null,
    val prnWindowHours: Int? = null,
    val createdAtEpochMillis: Long,
)
