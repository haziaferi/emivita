package com.hazia.emivita.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hazia.emivita.core.data.config.ReminderType
import kotlinx.serialization.Serializable

/** Nag-until-answered behavior for an unresolved dose. Embedded (not part of the
 *  per-type ReminderConfig JSON) because it's cross-cutting -- every reminder
 *  type can opt into it, independent of its schedule shape (spec §3). Null =
 *  disabled; all-columns-null is how Room persists a null @Embedded object.
 *  Read by ReminderAlarmReceiver's nag scheduling. */
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
        // dose" follows. SET_NULL, not CASCADE -- deleting a parent detaches its
        // children, and ReminderRepository.delete() switches each detached child
        // off in the same transaction so none is left enabled and unfirable.
        //
        // This comment used to justify SET_NULL by a Low-Stock/Expiration alert
        // "spawned from and tied to the medicine's primary dosing reminder", and
        // cited spec §3 for a rule §3 did not state -- §3 specified CASCADE. No
        // alert reminder ever sets this column; only the LinkedFollowing form
        // writes it, so that rationale described a feature that does not exist.
        // Settled in favour of SET_NULL and §3 corrected to match: see
        // docs/SPEC.md §7 R25.
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
    // Weekend mode is opt-in per reminder, not a blanket global (spec §3).
    // SettingsRepository still owns *what* the weekend is -- which days, and
    // the start/end of the quiet window -- because that describes the user's
    // week, not any one reminder; this flag decides whether this reminder is
    // shifted by it. Both the global toggle and this flag must be on.
    //
    // Ported deliberately *unlike* the reference: medTimer's SchedulingFactory
    // wraps every scheduler it builds in WeekendModeSchedulingDecorator with no
    // per-reminder gate, so the setting there is all-or-nothing across every
    // medicine. That is the wrong default for a schedule that mixes a
    // lie-in-friendly supplement with a drug that has to be taken on time: the
    // global switch made sleeping in on Saturday cost a dose of the second to
    // buy a lie-in on the first. Defaults to false, so nothing is shifted
    // until it is asked for.
    //
    // @ColumnInfo(defaultValue = "0") is load-bearing, not decoration. A
    // Kotlin default only affects object construction; without this
    // annotation Room's generated CREATE TABLE emits `weekendModeOptIn
    // INTEGER NOT NULL` with no DEFAULT, while MIGRATION_4_5 has to write
    // `NOT NULL DEFAULT 0` because ALTER TABLE ADD COLUMN cannot add a NOT
    // NULL column without one. Room compares column default values when it
    // validates a migrated database against the schema it would have created
    // itself, so those two spellings diverging is an IllegalStateException on
    // the first launch after an upgrade -- and one nothing in the test suite
    // would catch, since the migration tests drive raw SQL and never run
    // Room's validation. Declared so a migrated table and a freshly-created
    // one are identical.
    @ColumnInfo(defaultValue = "0") val weekendModeOptIn: Boolean = false,
    // LOW_STOCK_ALERT / EXPIRATION_ALERT only (spec §9 #8) -- the epoch day this
    // alert last posted a notification, so Once/Daily trigger modes don't
    // re-notify on every cache recompute. Null = never notified (or, for
    // LowStockAlert, restocked back above threshold since the last notify --
    // see LowStockExpirationAlertEvaluator for the reset rule). Unused by the
    // other four reminder types.
    val lastAlertNotifiedEpochDay: Long? = null,
    val createdAtEpochMillis: Long,
)
