package com.hazia.emivita.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One entry in a medicine's notes journal (spec §9 #26; Appendix F MT-04 --
 * MediTrak's timestamped notes, the adverse-effects record §1's clinician
 * export has no other slot for). Distinct from [Medicine.notes], which is
 * the one standing free-text field: a journal entry is *dated*, and the
 * date is the point -- "dizzy for two days after the dose went up".
 * Cascades with the medicine; an archived medicine keeps its journal.
 */
@Serializable
@Entity(
    tableName = "medicine_notes",
    foreignKeys = [
        ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("medicineId")],
)
data class MedicineNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val text: String,
    val createdAtEpochMillis: Long,
    /** Null until first edited; shown as "edited" beside the created stamp. */
    val editedAtEpochMillis: Long? = null,
)
