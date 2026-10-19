package com.hazia.emivita.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.ui.components.parseHexColor

/**
 * A medicine's own colour, and what to draw on top of it (§7 R76, Appendix C
 * action #5, `asOnSurfaceSafe`).
 *
 * The free HSV picker can deliver any hue and lightness, so `onAccent` picks
 * by relative luminance -- dark ink above the crossover, near-white below -- the
 * one rule that clears 4.5:1 against any colour except the narrow mid-band,
 * which the picker's distinguishability hint already steers away from.
 */
fun medicineAccent(hex: String?): Color = hex?.let { parseHexColor(it) }?.let(::withoutPureExtremes) ?: NeutralMedicineAccent

/** A colour saved before §7 R98 can be pure white or black; it is drawn near. */
internal fun withoutPureExtremes(c: Color): Color = when (c.copy(alpha = 1f)) {
    Color.White -> NearWhite
    Color.Black -> NearBlack
    else -> c
}

fun medicineAccent(medicine: Medicine): Color = medicineAccent(medicine.color)

val AccentInk = Color(0xFF1E1B18)

/** The luminance at which the dark ink starts clearing more contrast than
 *  the light one: (L + 0.05) / (L_dark + 0.05) and (L_light + 0.05) / (L + 0.05)
 *  cross at sqrt((L_light + 0.05)(L_dark + 0.05)) - 0.05, computed from the two
 *  inks, the light one [NearWhite] since §7 R98 (about 0.19, both near 4.0:1).
 *  `MedicineAccentTest` sweeps the whole cube. */
private val INK_CROSSOVER_LUMINANCE = (kotlin.math.sqrt((NearWhite.luminance() + 0.05) * (AccentInk.luminance() + 0.05)) - 0.05).toFloat()

fun onAccent(accent: Color): Color = if (accent.luminance() > INK_CROSSOVER_LUMINANCE) AccentInk else NearWhite
