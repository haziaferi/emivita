package com.hazia.emivita.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Placeholder icon set (spec §3/§6 "icon-per-type grid" -- no fixed set existed
 * anywhere before this batch). Stock Material Symbols, chosen per the user's own
 * call to keep this batch unblocked rather than wait on a custom line-art set.
 *
 * `Medicine.iconKey` only ever stores [MedicineIconOption.key] (a stable string,
 * never the ImageVector itself), so swapping this file's contents for a custom
 * SVG/line-art set later -- e.g. one built with the svg-illustration skill --
 * is a rendering-layer change only: no schema migration, no repository change,
 * no form-logic change. [iconFor] is the one seam that would need to start
 * returning a painter/drawable resource instead of an ImageVector.
 */
data class MedicineIconOption(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

object MedicineIcons {
    val options: List<MedicineIconOption> = listOf(
        MedicineIconOption("tablet", "Tablet", Icons.Filled.Medication),
        MedicineIconOption("capsule", "Capsule", Icons.Filled.Science),
        MedicineIconOption("liquid", "Liquid / Syrup", Icons.Filled.LocalDrink),
        MedicineIconOption("injection", "Injection", Icons.Filled.Vaccines),
        MedicineIconOption("drops", "Drops", Icons.Filled.WaterDrop),
        MedicineIconOption("inhaler", "Inhaler", Icons.Filled.Air),
        MedicineIconOption("topical", "Topical / Bandage", Icons.Filled.Healing),
        MedicineIconOption("cardiac", "Cardiac", Icons.Filled.MonitorHeart),
        MedicineIconOption("supplement", "Supplement", Icons.Filled.Bloodtype),
        MedicineIconOption("pharmacy", "Rx Bottle", Icons.Filled.LocalPharmacy),
        MedicineIconOption("other", "Other", Icons.Filled.MedicalServices),
    )

    private val byKey = options.associateBy { it.key }
    val default: MedicineIconOption = options.first()

    /** Falls back to [default] for an unknown key -- e.g. a key written by a
     *  future custom set this build predates, or corrupt data. Never throws. */
    fun iconFor(key: String): ImageVector = byKey[key]?.icon ?: default.icon
}
