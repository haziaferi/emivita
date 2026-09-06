package com.hazia.emivita.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Six palettes, light and dark, replacing the previous eight outright.
 *
 * They are drawn for the Health / Consumer-Wellbeing register -- patient-facing
 * rather than practitioner-facing -- whose governing rule is that red is
 * reserved for genuine emergencies and must never be an accent, a brand colour,
 * or the marker of an ordinary event. Grounds stay warm even under the two cool
 * palettes: the clinical blue-grey that reassures a professional reads as
 * institutional to someone managing their own medication.
 *
 * TWO ROLES ARE NEW, and they exist to end an overload rather than to decorate.
 * `error` was doing four jobs, three of which are not errors: it painted the
 * missed-dose chart series (AdherenceChart), the past-due day dot
 * (HistoryCalendarView, where PENDING is displayed as "Missed"), and a note
 * saying an OPTIONAL barcode-scanner app is not installed.
 *
 *   attention    a missed dose, low stock, an approaching expiry. Warm clay or
 *                amber -- a hazard light, not a fire alarm. Deliberately NOT red.
 *   neutralFill  a skipped dose and other deliberate non-events. Near-achromatic
 *                on purpose: a skip is a choice the user made, not an outcome to
 *                flag, and it should not compete with the states that matter.
 *   error        now narrowed to genuine failures only -- form validation, a
 *                failed backup, a destructive confirmation.
 *
 * The near-achromatic `neutralFill` is also what holds the chart apart under
 * colour-vision deficiency. Every state in this register is warm and
 * desaturated, and that is exactly the band where deuteranopia and protanopia
 * collapse hues together; an earlier draft had taken and missed at dE 0.9 --
 * indistinguishable. Chromatic / neutral / chromatic survives it, because
 * lightness and chroma do the separating rather than hue alone.
 *
 * HOW THE VALUES WERE CHOSEN. The token assignment and the two new colours came
 * out of an optimisation run (optimization-engines portfolio, fully computable
 * spec, zero model judgements) over four measured components: register_calm
 * 1.000, state_separation 0.988, legibility 0.809, error_reserve 0.852, total
 * fitness 0.9265. Saturation was pinned to the register's band and only
 * lightness searched -- lightness is the axis colour-vision deficiency spares,
 * chroma is the axis that reads as alarm, so optimising the one that is
 * actually free beats letting a search trade away the constraint. The clay hue
 * offset (+4 degrees off each palette's own anchor) is shared across all six on
 * purpose: tuned per palette it drifted to wine and gold, and the family
 * stopped looking related.
 *
 * on* colours are computed, not specified: whichever of pure black or white
 * clears more WCAG contrast against the base, the same rule the previous eight
 * used. Every pair here is verified in PaletteContrastTest against the
 * ColorScheme the app actually builds.
 */

// ---------- Daylight ----------
// warm bone + deep sea green. The quiet default. Bone paper, a green that reads as calm rather than clinical.
object DaylightPalette {
    // Light
    val LightBackground = Color(0xFFFBF9F5)
    val LightSurface = Color(0xFFF1EEE5)
    val LightOutline = Color(0xFF847E70)
    val LightOnBackground = Color(0xFF22211D)
    val LightOnSurfaceVariant = Color(0xFF5E5A51)
    val LightPrimary = Color(0xFF2C6050)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF4F7365)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFF7E5E3C)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightAttention = Color(0xFFB97B34)
    val LightOnAttention = Color(0xFF000000)
    val LightNeutralFill = Color(0xFFA69C89)
    val LightOnNeutralFill = Color(0xFF000000)
    val LightError = Color(0xFFA32F29)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark
    val DarkBackground = Color(0xFF121412)
    val DarkSurface = Color(0xFF1C201D)
    val DarkOutline = Color(0xFF787D75)
    val DarkOnBackground = Color(0xFFE9E7E1)
    val DarkOnSurfaceVariant = Color(0xFF9A978F)
    val DarkPrimary = Color(0xFF7CBAA2)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFF9AB4A9)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFCDA97E)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkAttention = Color(0xFFCA8C44)
    val DarkOnAttention = Color(0xFF000000)
    val DarkNeutralFill = Color(0xFF8C7F69)
    val DarkOnNeutralFill = Color(0xFF000000)
    val DarkError = Color(0xFFE8837B)
    val DarkOnError = Color(0xFF000000)
}

// ---------- Sage ----------
// herbal grey-green. The colour of a windowsill herb pot. Softest of the six.
object SagePalette {
    // Light
    val LightBackground = Color(0xFFF6F6F0)
    val LightSurface = Color(0xFFEBEDE2)
    val LightOutline = Color(0xFF7C856A)
    val LightOnBackground = Color(0xFF23261C)
    val LightOnSurfaceVariant = Color(0xFF585E4C)
    val LightPrimary = Color(0xFF4F6733)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF6B7D4A)
    val LightOnSecondary = Color(0xFF000000)
    val LightTertiary = Color(0xFF6E583F)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightAttention = Color(0xFFC69762)
    val LightOnAttention = Color(0xFF000000)
    val LightNeutralFill = Color(0xFF999692)
    val LightOnNeutralFill = Color(0xFF000000)
    val LightError = Color(0xFFA32F29)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark
    val DarkBackground = Color(0xFF12140F)
    val DarkSurface = Color(0xFF1C1F18)
    val DarkOutline = Color(0xFF767C6B)
    val DarkOnBackground = Color(0xFFE7E9DF)
    val DarkOnSurfaceVariant = Color(0xFF95998A)
    val DarkPrimary = Color(0xFFA3BE7B)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFAFBC94)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFC6A987)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkAttention = Color(0xFFA4743C)
    val DarkOnAttention = Color(0xFF000000)
    val DarkNeutralFill = Color(0xFFC9C7C5)
    val DarkOnNeutralFill = Color(0xFF000000)
    val DarkError = Color(0xFFE8837B)
    val DarkOnError = Color(0xFF000000)
}

// ---------- Honeycomb ----------
// honey and beeswax. Warm without glare. A cool green tertiary keeps it from going sticky.
object HoneycombPalette {
    // Light
    val LightBackground = Color(0xFFFDF8EE)
    val LightSurface = Color(0xFFF6EFDE)
    val LightOutline = Color(0xFF8C7B62)
    val LightOnBackground = Color(0xFF2A2318)
    val LightOnSurfaceVariant = Color(0xFF655B49)
    val LightPrimary = Color(0xFF7E5A1B)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF9C7B36)
    val LightOnSecondary = Color(0xFF000000)
    val LightTertiary = Color(0xFF3F5A4E)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightAttention = Color(0xFFB28875)
    val LightOnAttention = Color(0xFF000000)
    val LightNeutralFill = Color(0xFF696763)
    val LightOnNeutralFill = Color(0xFFFFFFFF)
    val LightError = Color(0xFFA32F29)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark
    val DarkBackground = Color(0xFF15120B)
    val DarkSurface = Color(0xFF201C13)
    val DarkOutline = Color(0xFF867D64)
    val DarkOnBackground = Color(0xFFF0E9DA)
    val DarkOnSurfaceVariant = Color(0xFF9D9480)
    val DarkPrimary = Color(0xFFD8B26A)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFBFA57C)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFF86B39F)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkAttention = Color(0xFF906350)
    val DarkOnAttention = Color(0xFFFFFFFF)
    val DarkNeutralFill = Color(0xFF918F8B)
    val DarkOnNeutralFill = Color(0xFF000000)
    val DarkError = Color(0xFFE8837B)
    val DarkOnError = Color(0xFF000000)
}

// ---------- Linen ----------
// clay and unbleached cloth. Warmest of the six. Its error goes oxblood so it never blurs into the clay.
object LinenPalette {
    // Light
    val LightBackground = Color(0xFFF8F5F1)
    val LightSurface = Color(0xFFEFE8E0)
    val LightOutline = Color(0xFF877364)
    val LightOnBackground = Color(0xFF26221E)
    val LightOnSurfaceVariant = Color(0xFF645A4F)
    val LightPrimary = Color(0xFF8E4826)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFFA05F3C)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFF3E5B5B)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightAttention = Color(0xFFC47464)
    val LightOnAttention = Color(0xFF000000)
    val LightNeutralFill = Color(0xFF696763)
    val LightOnNeutralFill = Color(0xFFFFFFFF)
    val LightError = Color(0xFF8E1F38)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark
    val DarkBackground = Color(0xFF191614)
    val DarkSurface = Color(0xFF231E1A)
    val DarkOutline = Color(0xFF857569)
    val DarkOnBackground = Color(0xFFEAE5DF)
    val DarkOnSurfaceVariant = Color(0xFF9E948A)
    val DarkPrimary = Color(0xFFD9906A)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFC29B80)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFF86B0AC)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkAttention = Color(0xFFA24F3E)
    val DarkOnAttention = Color(0xFFFFFFFF)
    val DarkNeutralFill = Color(0xFF878480)
    val DarkOnNeutralFill = Color(0xFF000000)
    val DarkError = Color(0xFFEE7E93)
    val DarkOnError = Color(0xFF000000)
}

// ---------- Mallow ----------
// dried plum and mallow flower. Soft, not sweet. The only palette whose brand colour is a true violet-red.
object MallowPalette {
    // Light
    val LightBackground = Color(0xFFFAF6F8)
    val LightSurface = Color(0xFFF2EAEF)
    val LightOutline = Color(0xFF87737F)
    val LightOnBackground = Color(0xFF251E23)
    val LightOnSurfaceVariant = Color(0xFF615560)
    val LightPrimary = Color(0xFF7A3F63)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF8E5C7C)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFF6E5A32)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightAttention = Color(0xFF7C5A2E)
    val LightOnAttention = Color(0xFFFFFFFF)
    val LightNeutralFill = Color(0xFF948C80)
    val LightOnNeutralFill = Color(0xFF000000)
    val LightError = Color(0xFFA32F29)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark
    val DarkBackground = Color(0xFF161215)
    val DarkSurface = Color(0xFF201A1F)
    val DarkOutline = Color(0xFF7E6F79)
    val DarkOnBackground = Color(0xFFEDE5EA)
    val DarkOnSurfaceVariant = Color(0xFF9A8D98)
    val DarkPrimary = Color(0xFFC595B4)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFB79FB0)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFC3B07A)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkAttention = Color(0xFFBC8B4A)
    val DarkOnAttention = Color(0xFF000000)
    val DarkNeutralFill = Color(0xFF898173)
    val DarkOnNeutralFill = Color(0xFF000000)
    val DarkError = Color(0xFFE8837B)
    val DarkOnError = Color(0xFF000000)
}

// ---------- Periwinkle ----------
// dusty indigo on a warm ground. The one cool option. A tan tertiary stops it reading institutional.
object PeriwinklePalette {
    // Light
    val LightBackground = Color(0xFFF8F7FB)
    val LightSurface = Color(0xFFECEBF3)
    val LightOutline = Color(0xFF77758C)
    val LightOnBackground = Color(0xFF1F1E2A)
    val LightOnSurfaceVariant = Color(0xFF565469)
    val LightPrimary = Color(0xFF4E4590)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF6A6296)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFF7A5A3E)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightAttention = Color(0xFFB98952)
    val LightOnAttention = Color(0xFF000000)
    val LightNeutralFill = Color(0xFFB4AFA6)
    val LightOnNeutralFill = Color(0xFF000000)
    val LightError = Color(0xFFA32F29)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark
    val DarkBackground = Color(0xFF131319)
    val DarkSurface = Color(0xFF1D1D25)
    val DarkOutline = Color(0xFF73728A)
    val DarkOnBackground = Color(0xFFE6E5EE)
    val DarkOnSurfaceVariant = Color(0xFF918FA0)
    val DarkPrimary = Color(0xFFA3A0DC)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFA8A5C2)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFC9A57E)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkAttention = Color(0xFFA57742)
    val DarkOnAttention = Color(0xFF000000)
    val DarkNeutralFill = Color(0xFFBCB7AF)
    val DarkOnNeutralFill = Color(0xFF000000)
    val DarkError = Color(0xFFE8837B)
    val DarkOnError = Color(0xFF000000)
}

/**
 * Fallback accent for a medicine or profile with no colour of its own -- the
 * flat neutral spec §3/§6 calls for. Palette-independent on purpose: it is
 * what a medicine wearing *no* colour looks like, so it must not shift when
 * the user switches palette. Survives the six-palette rebuild unchanged for
 * exactly that reason: it belongs to no palette, so no palette can retire it.
 */
val NeutralMedicineAccent = Color(0xFFB8ABA9)
