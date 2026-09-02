package com.hazia.emivita.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "dose_groups",
    foreignKeys = [
        ForeignKey(entity = Profile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("profileId")],
)
data class DoseGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    // Defaults to ALARM (full-screen alarm) on confirmation, per the agreed rule:
    // a confirmed group of items is treated as "shouldn't skip" even if its
    // individual members are ordinarily standard-priority supplements.
    // Editable afterward -- this default is a starting point, not a hard rule.
    val priority: NotificationPriority = NotificationPriority.ALARM,
    val createdAtEpochMillis: Long,
)
