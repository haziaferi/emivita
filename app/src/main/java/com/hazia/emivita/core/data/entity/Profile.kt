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
    // --- Emergency / medical card (spec §9 parking lot, promoted in §7 R48) ---
    //
    // Every one of these is informational text and nothing else. Nothing in
    // this app parses, validates or algorithmically checks any of them --
    // there is no interaction checking, no allergy cross-reference, no phone
    // number format enforcement -- which is exactly what the parking-lot entry
    // specifies and is the boundary that keeps this a document-printing
    // feature rather than a clinical one. `allergies` is free text for the
    // same reason: a picker implies a vocabulary the app would then be
    // claiming to understand.
    val bloodType: String? = null,
    val allergies: String? = null,
    val doctorName: String? = null,
    val doctorPhone: String? = null,
    val doctorAddress: String? = null,
    // The parking lot lists "pharmacy phone" only. A phone number printed on a
    // card with nothing naming whose it is cannot be used by the person
    // holding the card, so the name comes with it.
    val pharmacyName: String? = null,
    val pharmacyPhone: String? = null,
    val createdAtEpochMillis: Long,
)
