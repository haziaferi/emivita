package com.hazia.emivita.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hazia.emivita.core.data.config.ReminderType
import kotlinx.serialization.Serializable

/** Nag-until-answered behavior for an unresolved dose, read by
 *  ReminderAlarmReceiver's nag scheduling. Embedded (not part of the per-type
 *  ReminderConfig JSON) because every reminder type can opt into it (spec §3).
 *  Null = disabled, which Room persists as all columns null. */
@Serializable
data class RepeatConfig(
    val delayMinutes: Int,
    val maxRepeats: Int,
)

@Serializable
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DoseGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.SET_NULL),
        // Self-referencing: carries which reminder a LinkedFollowing "following
        // dose" follows, and only that form writes it. SET_NULL, not CASCADE
        // (docs/SPEC.md §7 R25): deleting a parent detaches its children, and
        // ReminderRepository.delete() switches each detached child off in the
        // same transaction so none is left enabled and unfirable.
        ForeignKey(entity = Reminder::class, parentColumns = ["id"], childColumns = ["parentReminderId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("medicineId"), Index("groupId"), Index("parentReminderId")],
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val groupId: Long? = null, // null = not part of any confirmed group
    val parentReminderId: Long? = null,
    val type: ReminderType,
    val configJson: String, // serialized ReminderConfig matching `type`
    val enabled: Boolean = true,
    // Auto-resolve a fired dose as Taken without a user tap (e.g. a passive
    // supplement reminder, or one tracking someone else). Honored by
    // ReminderAlarmReceiver, which resolves silently and posts no
    // notification at all (spec §3).
    val autoResolve: Boolean = false,
    // Inclusive epoch-day bounds clamping when this reminder is considered
    // active at all (e.g. a course of antibiotics) -- independent of, and
    // narrower than, whatever its per-type schedule config computes. Null =
    // unbounded on that side. Applied by SchedulingEngine.nextOccurrenceFor()
    // after the type-specific calculator, before weekend mode (spec §3/§4).
    val activeFromEpochDay: Long? = null,
    val activeUntilEpochDay: Long? = null,
    @Embedded(prefix = "repeat_") val repeatOnUnanswered: RepeatConfig? = null,
    // Weekend mode is opt-in per reminder, default off, and needs the global
    // toggle too (spec §3), while SettingsRepository owns which days and hours
    // the weekend is. Deliberately unlike medTimer's all-or-nothing decorator:
    // one global switch made a lie-in on a supplement cost a dose of a drug
    // that must be taken on time.
    // @ColumnInfo(defaultValue = "0") is load-bearing: MIGRATION_4_5 must write
    // `NOT NULL DEFAULT 0`, and Room's validation of a migrated database throws
    // IllegalStateException on first launch if CREATE TABLE lacks the same
    // default, which the raw-SQL migration tests never exercise.
    @ColumnInfo(defaultValue = "0") val weekendModeOptIn: Boolean = false,
    /** A silent "coming up" notification this many minutes before the dose
     *  (§9 #25, Appendix F HM-19), null = none. Read by RealSchedulingCoordinator
     *  when it arms the regular slot; the pre-alarm has its own slot. */
    val preAlarmMinutes: Int? = null,
    /** Per-reminder instructions (§9 #26; medTimer's `instructions`) --
     *  "with food", "not with the iron" -- shown in the notification body and
     *  on the Today row. The one text that belongs on the notification. */
    val instructions: String? = null,
    /** A dose-count-limited course (§9 #26, Appendix F MT-07): "N doses then
     *  stop", null = unlimited. Decremented on TAKEN, and at 0 the engine arms
     *  nothing more, next to the activeUntil clamp (MT finding 3). */
    val dosesRemaining: Int? = null,
    /** Keep the notification on screen until answered (§7 R109, Appendix E
     *  B-42): posted `setOngoing`, so neither the shade nor the lock screen
     *  can swipe it away, and it keeps every button (B-10's guard). The
     *  default must match MIGRATION_9_10's `NOT NULL DEFAULT 0`. */
    @ColumnInfo(defaultValue = "0") val stickyUntilAnswered: Boolean = false,
    /** Ask the amount on Take (§7 R109, Appendix E B-45, medTimer's
     *  `variableAmount`): the notification's Take opens TakeAmountActivity
     *  instead of recording the planned dose. Same default rule as above. */
    @ColumnInfo(defaultValue = "0") val variableAmount: Boolean = false,
    /** This reminder's own notification tier (§7 R127, B-38), null = its medicine's (or its
     *  group's). The form offers only tiers above the medicine's. Nullable with no default
     *  expression, so MIGRATION_13_14 is a plain ADD COLUMN and older backups decode as null. */
    val importance: NotificationPriority? = null,
    // LOW_STOCK_ALERT / EXPIRATION_ALERT only (spec §9 #8) -- the epoch day this
    // alert last posted a notification, so Once/Daily trigger modes don't
    // re-notify on every cache recompute. Null = never notified (or, for
    // LowStockAlert, restocked back above threshold since the last notify --
    // see LowStockExpirationAlertEvaluator for the reset rule). Unused by the
    // other four reminder types.
    val lastAlertNotifiedEpochDay: Long? = null,
    val createdAtEpochMillis: Long,
)
