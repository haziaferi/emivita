package com.hazia.emivita.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// unique=true on name is required for MedicineRepository.addTag()'s
// OnConflictStrategy.IGNORE + getByName() dedupe pattern to actually work --
// without a real uniqueness constraint here, IGNORE has nothing to conflict
// against and duplicate tags with the same name (different ids) can be inserted.
@Serializable
@Entity(tableName = "tags", indices = [Index("name", unique = true)])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Serializable
@Entity(
    tableName = "medicine_tag_cross_ref",
    primaryKeys = ["medicineId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("medicineId"), Index("tagId")],
)
data class MedicineTagCrossRef(
    val medicineId: Long,
    val tagId: Long,
)
