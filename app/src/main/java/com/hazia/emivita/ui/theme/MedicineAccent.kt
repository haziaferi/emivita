package com.hazia.emivita.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.ui.components.parseHexColor

/**
 * A medicine's own colour, and what to draw on top of it (§7 R76; Appendix C
 * action #5, `asOnSurfaceSafe`, open since R47 and closed here).
 *
 * The colour comes from a free HSV picker, so any hue and any lightness can
 * arrive: a pale yellow with white on it is unreadable, a navy with dark ink
 * on it the same. `onAccent` picks by relative luminance -- dark ink above
 * the crossover, white below -- the one rule that clears 4.5:1 against any
 * colour except the narrow mid-band, which the picker's distinguishability
 * hint already steers away from. Until R76 every glyph was white at seven
 * call sites, each resolving the fallback inline.
 */
fun medicineAccent(hex: String?): Color = hex?.let { parseHexColor(it) } ?: NeutralMedicineAccent

fun medicineAccent(medicine: Medicine): Color = medicineAccent(medicine.color)

/** The luminance at which this ink starts clearing more contrast than
 *  white: (L + 0.05) / (L_ink + 0.05) against 1.05 / (L + 0.05) cross at
 *  L ≈ 0.20 for an ink of luminance 0.011, where both read at 4.2:1 -- the
 *  floor of the rule, and the mid band the picker's hint steers away from.
 *  `MedicineAccentTest` sweeps the whole cube. */
private const val INK_CROSSOVER_LUMINANCE = 0.20f
val AccentInk = Color(0xFF1E1B18)

fun onAccent(accent: Color): Color = if (accent.luminance() > INK_CROSSOVER_LUMINANCE) AccentInk else Color.White
