package com.hazia.emivita.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // hex, replaces the old colorArgb: Int? -- same move as Medicine.color, same
    // continuous HSV-wheel+lightness-strip+hex-readout picker component (spec §3:
    // "not a second, simpler swatch-based widget built just for profiles").
    val color: String? = null,
    val createdAtEpochMillis: Long,
)
