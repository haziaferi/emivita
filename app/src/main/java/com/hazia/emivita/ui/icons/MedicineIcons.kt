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
import androidx.annotation.StringRes
import com.hazia.emivita.R

/**
 * Placeholder icon set (spec §3/§6 "icon-per-type grid"): stock Material
 * Symbols, deliberately, because `Medicine.iconKey` stores only the stable
 * [MedicineIconOption.key], so a custom line-art set later is a rendering-layer
 * change with no migration. [iconFor] is the one seam that would need to start
 * returning a painter/drawable resource instead of an ImageVector.
 */
data class MedicineIconOption(
    /** Persisted in `Medicine.iconKey`. NEVER localised: translating this
     *  would orphan every stored medicine's icon (spec §9 #19 Stage A). */
    val key: String,
    /** @StringRes, not String -- this list is a top-level `val`, so there is
     *  no composable scope here to call stringResource() in. */
    @StringRes val label: Int,
    val icon: ImageVector,
    /** The unit a dose of this form is counted in (§9 #27, HM-05's
     *  `DrugType.doseType`, re-thought as the icon's own default). Filled
     *  into the form's unit field only while that field is blank; null for
     *  the forms that say nothing about a unit. @StringRes for the same
     *  reason as [label]. */
    @StringRes val defaultUnit: Int? = null,
)

object MedicineIcons {
    val options: List<MedicineIconOption> = listOf(
        MedicineIconOption("tablet", R.string.icon_tablet, Icons.Filled.Medication, R.string.unit_tablet),
        MedicineIconOption("capsule", R.string.icon_capsule, Icons.Filled.Science, R.string.unit_capsule),
        MedicineIconOption("liquid", R.string.icon_liquid, Icons.Filled.LocalDrink, R.string.unit_ml),
        MedicineIconOption("injection", R.string.icon_injection, Icons.Filled.Vaccines, R.string.unit_injection),
        MedicineIconOption("drops", R.string.icon_drops, Icons.Filled.WaterDrop, R.string.unit_drop),
        MedicineIconOption("inhaler", R.string.icon_inhaler, Icons.Filled.Air, R.string.unit_puff),
        MedicineIconOption("topical", R.string.icon_topical, Icons.Filled.Healing, R.string.unit_application),
        MedicineIconOption("cardiac", R.string.icon_cardiac, Icons.Filled.MonitorHeart),
        MedicineIconOption("supplement", R.string.icon_supplement, Icons.Filled.Bloodtype),
        MedicineIconOption("pharmacy", R.string.icon_pharmacy, Icons.Filled.LocalPharmacy),
        MedicineIconOption("other", R.string.icon_other, Icons.Filled.MedicalServices),
    )

    private val byKey = options.associateBy { it.key }
    val default: MedicineIconOption = options.first()

    /** Falls back to [default] for an unknown key -- e.g. a key written by a
     *  future custom set this build predates, or corrupt data. Never throws. */
    fun iconFor(key: String): ImageVector = byKey[key]?.icon ?: default.icon

    /** The unit the form fills in when its unit field is blank (§9 #27). */
    @StringRes fun defaultUnitFor(key: String): Int? = byKey[key]?.defaultUnit

    /** The rule itself, pure: a blank field takes the form's unit, a typed
     *  one is never overwritten, a form without a unit changes nothing. */
    fun unitAfterPickingForm(currentUnit: String, formUnit: String?): String =
        if (currentUnit.isBlank() && formUnit != null) formUnit else currentUnit
}
