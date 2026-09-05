package com.hazia.emivita.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The 8 palettes below (spec section 6 / Appendix A) replace the original 4
 * (Clinical/Cozy/Neutral/Playful) entirely -- this is a straight swap, not an
 * addition (spec section 6, section 9 item 0b).
 *
 * Every hex is copied verbatim from the spec's Appendix A -- background,
 * surface, outline, primary, secondary, tertiary (Appendix A's "accent"),
 * text (-> onBackground/onSurface), and muted (-> onSurfaceVariant) all come
 * from there directly, not re-derived. Unlike the old 4-palette scheme, there
 * is no separate "container" tone per role: primary/secondary/tertiary/error
 * are each reused as their own Container color too when wired into
 * ColorScheme in Theme.kt, since Appendix A only audited one tone per role
 * (framed there as "large-element/UI use," i.e. the old scheme's container
 * tier, not a small-text-safe tier). Flag if a second, lighter container tone
 * per role is ever wanted -- it isn't specified anywhere yet.
 *
 * onPrimary/onSecondary/onTertiary/onError are computed here (not spec'd
 * directly): whichever of pure black/white clears more WCAG contrast against
 * the base color, the same rule section 6's own selected/active state-token
 * formula already uses.
 *
 * Error is a FIXED red -- #B8322E, one value shared by all 8 palettes, with
 * white as its onError (5.97:1, against 3.52:1 for black) -- and is
 * deliberately NOT computed from the accent (spec section 7 R42).
 *
 * It used to follow section 6's semantic-state-token rule, "Accent hue
 * +150-165 degrees (compound/near-complementary), S60/L45", implemented at
 * +158 degrees. That rule is right for the three accent roles and wrong for
 * this one, because a hue rotated to stay in harmony with the accent cannot
 * also carry the meaning "something is wrong". Rotating each palette's accent
 * produced, across the eight: teal, cyan, magenta, blue -- and green in four
 * of them. This colour is what AdherenceChart paints its `missed` series with,
 * and what R34's own doc comment calls "the app's reserved alarm-adjacent
 * color", so a missed medication dose was rendering green in half the
 * palettes.
 *
 * The spec had already recorded the rule failing once: section 7 T4 notes it
 * "has no hue to rotate from against Neutral's fully-desaturated Accent". Two
 * independent failures of one rule is the rule being wrong, not the palettes.
 *
 * #B8322E keeps the rule's own S60/L45 discipline and only fixes the hue at
 * 2 degrees, so it sits at the same value and saturation as the computed set
 * it replaces (note the shared 0xB8 max channel) and stays coherent beside
 * every accent -- while reading as an error to anyone who has ever seen one.
 * Ink & Ledger's old #B8472E was already within a few degrees of this, which
 * is why that one palette looked correct.
 */

// ---------- Golden Hour ----------
// Hex values are copied verbatim from the spec's Appendix A palette reference
// (WCAG contrast ratios computed there, not re-derived here). On* colors are
// auto-picked black/white, whichever clears more contrast against their base
// (same rule as §6's selected/active state-token formula).
//
// Error is NOT computed. It is a fixed #B8322E shared by all eight palettes
// (§7 R42). This comment used to describe the rule R42 removed -- "Accent hue
// +158°, S60/L45" -- directly above the literal that replaced it, which is
// the kind of drift §7 R47 was opened to clear. Rotating each accent produced
// green in four palettes for the role AdherenceChart paints missed doses with;
// a hue chosen to harmonise with the accent cannot also mean "something is
// wrong". The rotation rule still governs the three accent roles, where it
// belongs. PaletteContrastTest asserts onError clears AA against it.
object GoldenHourPalette {
    // Light
    val LightBackground = Color(0xFFFBF3E6)
    val LightSurface = Color(0xFFF5EAD6)
    val LightOutline = Color(0xFF9C7A54)
    val LightOnBackground = Color(0xFF3A2415) // Appendix A "text"
    val LightOnSurfaceVariant = Color(0xFF7A654E) // Appendix A "muted" -- the audited mutedText token (T2)
    val LightPrimary = Color(0xFFB9722E)
    val LightOnPrimary = Color(0xFF000000)
    val LightSecondary = Color(0xFFD9A868)
    val LightOnSecondary = Color(0xFF000000)
    val LightTertiary = Color(0xFF5C3D2E) // Appendix A "accent"
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFB8322E)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark (OLED-tuned background per Appendix A's phone-specific notes)
    val DarkBackground = Color(0xFF140D08)
    val DarkSurface = Color(0xFF1F150E)
    val DarkOutline = Color(0xFF816545)
    val DarkOnBackground = Color(0xFFF1E4D2)
    val DarkOnSurfaceVariant = Color(0xFF927E66)
    val DarkPrimary = Color(0xFFD98F45)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFB98A5A)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFE8C79A)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkError = Color(0xFFB8322E)
    val DarkOnError = Color(0xFFFFFFFF)
}

// ---------- Sunlit Focus ----------
// Hex values are copied verbatim from the spec's Appendix A palette reference
// (WCAG contrast ratios computed there, not re-derived here). On* colors are
// auto-picked black/white, whichever clears more contrast against their base
// (same rule as §6's selected/active state-token formula). Error is
// computed per §6's rule: Accent hue +158° (within the spec's
// 150-165° range), S60/L45 -- not an audited Appendix A value, since the
// spec defines Error as a formula, not a fixed hex.
object SunlitFocusPalette {
    // Light
    val LightBackground = Color(0xFFF7F4EC)
    val LightSurface = Color(0xFFEFE9D8)
    val LightOutline = Color(0xFF8E7D50)
    val LightOnBackground = Color(0xFF3B2E18) // Appendix A "text"
    val LightOnSurfaceVariant = Color(0xFF706751) // Appendix A "muted" -- the audited mutedText token (T2)
    val LightPrimary = Color(0xFFA97D2E)
    val LightOnPrimary = Color(0xFF000000)
    val LightSecondary = Color(0xFFC9A661)
    val LightOnSecondary = Color(0xFF000000)
    val LightTertiary = Color(0xFF6B4A1E) // Appendix A "accent"
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFB8322E)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark (OLED-tuned background per Appendix A's phone-specific notes)
    val DarkBackground = Color(0xFF14120A)
    val DarkSurface = Color(0xFF1E1B10)
    val DarkOutline = Color(0xFF7A6C44)
    val DarkOnBackground = Color(0xFFEFE7D6)
    val DarkOnSurfaceVariant = Color(0xFF8D846A)
    val DarkPrimary = Color(0xFFD9A94A)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFB79A66)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFE9CE94)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkError = Color(0xFFB8322E)
    val DarkOnError = Color(0xFFFFFFFF)
}

// ---------- Forest Floor ----------
// Hex values are copied verbatim from the spec's Appendix A palette reference
// (WCAG contrast ratios computed there, not re-derived here). On* colors are
// auto-picked black/white, whichever clears more contrast against their base
// (same rule as §6's selected/active state-token formula). Error is
// computed per §6's rule: Accent hue +158° (within the spec's
// 150-165° range), S60/L45 -- not an audited Appendix A value, since the
// spec defines Error as a formula, not a fixed hex.
object ForestFloorPalette {
    // Light
    val LightBackground = Color(0xFFF2EFD9)
    val LightSurface = Color(0xFFE6E2C0)
    val LightOutline = Color(0xFF707F42)
    val LightOnBackground = Color(0xFF23280F) // Appendix A "text"
    val LightOnSurfaceVariant = Color(0xFF5F654B) // Appendix A "muted" -- the audited mutedText token (T2)
    val LightPrimary = Color(0xFF5E7A34)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF93A85E)
    val LightOnSecondary = Color(0xFF000000)
    val LightTertiary = Color(0xFF7A5A3A) // Appendix A "accent"
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFB8322E)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark (OLED-tuned background per Appendix A's phone-specific notes)
    val DarkBackground = Color(0xFF101508) // OLED-adjusted, was #0E1207 (L 4.9 -> 5.7)
    val DarkSurface = Color(0xFF171C0D)
    val DarkOutline = Color(0xFF64723A)
    val DarkOnBackground = Color(0xFFE7E3CC)
    val DarkOnSurfaceVariant = Color(0xFF7F876A)
    val DarkPrimary = Color(0xFF8CA857)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFA6AE7C)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFC79868)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkError = Color(0xFFB8322E)
    val DarkOnError = Color(0xFFFFFFFF)
}

// ---------- Sage Clinical ----------
// Hex values are copied verbatim from the spec's Appendix A palette reference
// (WCAG contrast ratios computed there, not re-derived here). On* colors are
// auto-picked black/white, whichever clears more contrast against their base
// (same rule as §6's selected/active state-token formula). Error is
// computed per §6's rule: Accent hue +158° (within the spec's
// 150-165° range), S60/L45 -- not an audited Appendix A value, since the
// spec defines Error as a formula, not a fixed hex.
object SageClinicalPalette {
    // Light
    val LightBackground = Color(0xFFE4ECE6)
    val LightSurface = Color(0xFFD5E2DA)
    val LightOutline = Color(0xFF4E826D)
    val LightOnBackground = Color(0xFF223530) // Appendix A "text"
    val LightOnSurfaceVariant = Color(0xFF53655E) // Appendix A "muted" -- the audited mutedText token (T2)
    val LightPrimary = Color(0xFF3F6B57)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF6E8F80)
    val LightOnSecondary = Color(0xFF000000)
    val LightTertiary = Color(0xFF567863) // Appendix A "accent"
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFB8322E)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark (OLED-tuned background per Appendix A's phone-specific notes)
    val DarkBackground = Color(0xFF0A120E)
    val DarkSurface = Color(0xFF131F18)
    val DarkOutline = Color(0xFF477763)
    val DarkOnBackground = Color(0xFFDCE7E1)
    val DarkOnSurfaceVariant = Color(0xFF758A81)
    val DarkPrimary = Color(0xFF7DAE93)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFF92AA9E)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFB8D6C4)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkError = Color(0xFFB8322E)
    val DarkOnError = Color(0xFFFFFFFF)
}

// ---------- Ink & Ledger ----------
// Hex values are copied verbatim from the spec's Appendix A palette reference
// (WCAG contrast ratios computed there, not re-derived here). On* colors are
// auto-picked black/white, whichever clears more contrast against their base
// (same rule as §6's selected/active state-token formula). Error is
// computed per §6's rule: Accent hue +158° (within the spec's
// 150-165° range), S60/L45 -- not an audited Appendix A value, since the
// spec defines Error as a formula, not a fixed hex.
object InkLedgerPalette {
    // Light
    val LightBackground = Color(0xFFF1F2F6)
    val LightSurface = Color(0xFFE4E6EC)
    val LightOutline = Color(0xFF6579C4)
    val LightOnBackground = Color(0xFF181D30) // Appendix A "text"
    val LightOnSurfaceVariant = Color(0xFF5E6581) // Appendix A "muted" -- the audited mutedText token (T2)
    val LightPrimary = Color(0xFF1C2D69)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF5364A2)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFFB33A3A) // Appendix A "accent"
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFB8322E)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark (OLED-tuned background per Appendix A's phone-specific notes)
    val DarkBackground = Color(0xFF0A0C13) // OLED-adjusted, was #080A10 (L 4.7 -> 5.7)
    val DarkSurface = Color(0xFF11141F)
    val DarkOutline = Color(0xFF4E64B3)
    val DarkOnBackground = Color(0xFFDCDEE3)
    val DarkOnSurfaceVariant = Color(0xFF777F99)
    val DarkPrimary = Color(0xFF5E72BA)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFF7A88B8)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFC2564F)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkError = Color(0xFFB8322E)
    val DarkOnError = Color(0xFFFFFFFF)
}

// ---------- Cobalt Porcelain ----------
// Hex values are copied verbatim from the spec's Appendix A palette reference
// (WCAG contrast ratios computed there, not re-derived here). On* colors are
// auto-picked black/white, whichever clears more contrast against their base
// (same rule as §6's selected/active state-token formula). Error is
// computed per §6's rule: Accent hue +158° (within the spec's
// 150-165° range), S60/L45 -- not an audited Appendix A value, since the
// spec defines Error as a formula, not a fixed hex.
object CobaltPorcelainPalette {
    // Light
    val LightBackground = Color(0xFFF5F5F6)
    val LightSurface = Color(0xFFE8ECF1)
    val LightOutline = Color(0xFF6681B0)
    val LightOnBackground = Color(0xFF1E3350) // Appendix A "text"
    val LightOnSurfaceVariant = Color(0xFF5D6A81) // Appendix A "muted" -- the audited mutedText token (T2)
    val LightPrimary = Color(0xFF24518C)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF4A6B92)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFF163A66) // Appendix A "accent"
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFB8322E)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark (OLED-tuned background per Appendix A's phone-specific notes)
    val DarkBackground = Color(0xFF070C16) // OLED-adjusted, was #060A12 (L 4.7 -> 5.7)
    val DarkSurface = Color(0xFF121D31)
    val DarkOutline = Color(0xFF526F9F)
    val DarkOnBackground = Color(0xFFE3E7EC)
    val DarkOnSurfaceVariant = Color(0xFF7C8798)
    val DarkPrimary = Color(0xFF6E90B8)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFF8098B4)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFC9D4E2)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkError = Color(0xFFB8322E)
    val DarkOnError = Color(0xFFFFFFFF)
}

// ---------- Rose Petal ----------
// Hex values are copied verbatim from the spec's Appendix A palette reference
// (WCAG contrast ratios computed there, not re-derived here). On* colors are
// auto-picked black/white, whichever clears more contrast against their base
// (same rule as §6's selected/active state-token formula). Error is
// computed per §6's rule: Accent hue +158° (within the spec's
// 150-165° range), S60/L45 -- not an audited Appendix A value, since the
// spec defines Error as a formula, not a fixed hex.
object RosePetalPalette {
    // Light
    val LightBackground = Color(0xFFF3E6E9)
    val LightSurface = Color(0xFFEBD3D9)
    val LightOutline = Color(0xFFA55F71)
    val LightOnBackground = Color(0xFF4A2E38) // Appendix A "text"
    val LightOnSurfaceVariant = Color(0xFF73565E) // Appendix A "muted" -- the audited mutedText token (T2)
    val LightPrimary = Color(0xFF9C4F6B)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFFC98096)
    val LightOnSecondary = Color(0xFF000000)
    val LightTertiary = Color(0xFF7C6178) // Appendix A "accent"
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFB8322E)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark (OLED-tuned background per Appendix A's phone-specific notes)
    val DarkBackground = Color(0xFF130A0E) // OLED-adjusted, was #0F080B (L 4.5 -> 5.7)
    val DarkSurface = Color(0xFF221419)
    val DarkOutline = Color(0xFF9A5767)
    val DarkOnBackground = Color(0xFFEEDEE2)
    val DarkOnSurfaceVariant = Color(0xFF937C81)
    val DarkPrimary = Color(0xFFD998AC)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFB97F92)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFC7B06B)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkError = Color(0xFFB8322E)
    val DarkOnError = Color(0xFFFFFFFF)
}

// ---------- Velvet Ribbon ----------
// Hex values are copied verbatim from the spec's Appendix A palette reference
// (WCAG contrast ratios computed there, not re-derived here). On* colors are
// auto-picked black/white, whichever clears more contrast against their base
// (same rule as §6's selected/active state-token formula). Error is
// computed per §6's rule: Accent hue +158° (within the spec's
// 150-165° range), S60/L45 -- not an audited Appendix A value, since the
// spec defines Error as a formula, not a fixed hex.
object VelvetRibbonPalette {
    // Light
    val LightBackground = Color(0xFFEDE0D8)
    val LightSurface = Color(0xFFE3C9BC)
    val LightOutline = Color(0xFF9A5C53)
    val LightOnBackground = Color(0xFF34211A) // Appendix A "text"
    val LightOnSurfaceVariant = Color(0xFF6D504B) // Appendix A "muted" -- the audited mutedText token (T2)
    val LightPrimary = Color(0xFF9E3540)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFFB06B62)
    val LightOnSecondary = Color(0xFF000000)
    val LightTertiary = Color(0xFF6E5570) // Appendix A "accent"
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFB8322E)
    val LightOnError = Color(0xFFFFFFFF)

    // Dark (OLED-tuned background per Appendix A's phone-specific notes)
    val DarkBackground = Color(0xFF120A08)
    val DarkSurface = Color(0xFF241811)
    val DarkOutline = Color(0xFF995C52)
    val DarkOnBackground = Color(0xFFEAD9D0)
    val DarkOnSurfaceVariant = Color(0xFF9A7D79)
    val DarkPrimary = Color(0xFFD96B5C)
    val DarkOnPrimary = Color(0xFF000000)
    val DarkSecondary = Color(0xFFC4917D)
    val DarkOnSecondary = Color(0xFF000000)
    val DarkTertiary = Color(0xFFA5859E)
    val DarkOnTertiary = Color(0xFF000000)
    val DarkError = Color(0xFFB8322E)
    val DarkOnError = Color(0xFFFFFFFF)
}

/**
 * Fallback accent for a medicine or profile with no colour of its own -- the
 * flat neutral spec §3/§6 calls for. Palette-independent on purpose: it is
 * what a medicine wearing *no* colour looks like, so it must not shift when
 * the user switches palette.
 */
val NeutralMedicineAccent = Color(0xFFB8ABA9)
