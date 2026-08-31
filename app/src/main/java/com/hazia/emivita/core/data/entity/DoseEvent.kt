package com.hazia.emivita.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class DoseStatus { PENDING, TAKEN, SKIPPED, REFILLED }

@Serializable
@Entity(
    tableName = "dose_events",
    foreignKeys = [
        // ON DELETE CASCADE only applies when reminderId is non-null; SQLite FK
        // enforcement is a no-op for NULL FK columns, which is exactly what a PRN/
        // ad-hoc-logged event (reminderId = null) needs -- it isn't tied to any
        // reminder's lifecycle at all.
        ForeignKey(entity = Reminder::class, parentColumns = ["id"], childColumns = ["reminderId"], onDelete = ForeignKey.CASCADE),
    ],
    // The compound unique index is what actually makes materializeIfAbsent()/
    // insert()'s OnConflictStrategy.IGNORE idempotent -- without it, two
    // concurrent materialization passes for the same reminder+time (e.g. a
    // WorkManager pass racing an alarm receiver) could each pass the
    // check-then-insert race and create duplicate dose events. SQLite treats each
    // NULL in a unique index as distinct from every other NULL, so this still
    // allows any number of PRN events (reminderId = null) at the same instant --
    // correct, since PRN logging isn't racing a materialization pass at all.
    indices = [
        Index(value = ["reminderId", "scheduledEpochMillis"], unique = true),
        Index("scheduledEpochMillis"), // supports observeInRange()'s range scan
        Index("medicineId"),
    ],
)
data class DoseEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Nullable -- null marks a manually-logged (PRN) dose not tied to any scheduled
    // occurrence. See DoseEventRepository.logAdhocDose (spec §3, deferred to §9 #2,
    // schema landed now so the Add/Edit Reminder UI doesn't need a retrofit later).
    val reminderId: Long? = null,
    // Denormalized. Scheduled events keep this in sync with reminder.medicineId at
    // materialization time; PRN events have no reminder to derive it from, so it's
    // stored directly here instead.
    val medicineId: Long,
    val scheduledEpochMillis: Long,
    // Snapshotted at materialization time so editing a reminder's dose later
    // doesn't retroactively change historical records -- mirrors the reference
    // app's "color change only affects future events" behavior.
    val doseAmount: Double,
    val status: DoseStatus = DoseStatus.PENDING,
    val resolvedAtEpochMillis: Long? = null,
    // Sentinel, not nullable -- same reasoning as medTimer's ADR-0004 (spec §3):
    // Room AutoMigration handles non-null defaulted columns more predictably than
    // nullable Double, and "was stock tracked for this event" collapses to one
    // check (stockBefore != stockAfter) that also correctly covers old migrated
    // rows and un-tracked medicines. Written by resolve()/logRefill() -- not yet
    // (§9 #3), just the columns landing now.
    val stockBefore: Double = -1.0,
    val stockAfter: Double = -1.0,
    // Incremented per "nag" fire, capped at Reminder.repeatOnUnanswered.maxRepeats
    // (spec §3, §9 #-adjacent -- engine behavior not built this batch either).
    val repeatCount: Int = 0,
    val createdAtEpochMillis: Long,
)
