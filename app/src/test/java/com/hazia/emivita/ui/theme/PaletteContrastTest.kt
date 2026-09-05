package com.hazia.emivita.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Makes Appendix A's contrast audit executable (spec §7 R47).
 *
 * Every ratio in Appendix A was computed once, by hand, and written into a
 * table. That is why T1 and T2 could both be marked resolved while §7 R47
 * found the failing technique still live at 27 call sites: the audit proved a
 * *token* was safe and nothing proved the app used it. These assertions run on
 * every build against the ColorScheme the app actually constructs, including
 * the roles Theme.kt derives rather than reads from a table (§7 R44).
 *
 * Pure JVM -- no Robolectric. Compose's Color and ColorScheme are ordinary
 * Kotlin, so nothing here needs an Android runtime.
 *
 * WCAG 2.1 relative luminance and contrast, per the spec's own definition in
 * §7 §4.4. Thresholds: 4.5:1 for normal text, 3:1 for UI boundaries.
 */
class PaletteContrastTest {

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun eachScheme(check: (String, androidx.compose.material3.ColorScheme) -> Unit) {
        for (palette in MedPalette.entries) {
            for (dark in listOf(false, true)) {
                check("${palette.name} ${if (dark) "dark" else "light"}", colorSchemeFor(palette, dark))
            }
        }
    }

    private fun assertAtLeast(min: Double, actual: Double, label: String) {
        assertTrue(
            "$label = ${"%.2f".format(actual)}:1, needs ${min}:1",
            actual >= min,
        )
    }

    /**
     * T2's muted-text token. This is the assertion that would have failed on
     * the technique it replaced: `onSurface.copy(alpha = 0.6f)` composited over
     * surface lands at 3.18-4.12:1 across the eight light palettes.
     */
    @Test
    fun mutedTextClearsAaAgainstSurface() = eachScheme { name, s ->
        assertAtLeast(4.5, ratio(s.onSurfaceVariant, s.surface), "$name onSurfaceVariant/surface")
    }

    @Test
    fun bodyTextClearsAaAgainstSurfaceAndBackground() = eachScheme { name, s ->
        assertAtLeast(4.5, ratio(s.onSurface, s.surface), "$name onSurface/surface")
        assertAtLeast(4.5, ratio(s.onBackground, s.background), "$name onBackground/background")
    }

    /** T1: the rule is "3:1 against Surface, not just Background" (Appendix C). */
    @Test
    fun outlineClearsUiContrastAgainstBothSurfaceAndBackground() = eachScheme { name, s ->
        assertAtLeast(3.0, ratio(s.outline, s.surface), "$name outline/surface")
        assertAtLeast(3.0, ratio(s.outline, s.background), "$name outline/background")
    }

    /** R42's fixed error hue, and the on-colour picked against it. */
    @Test
    fun onErrorClearsAaAgainstError() = eachScheme { name, s ->
        assertAtLeast(4.5, ratio(s.onError, s.error), "$name onError/error")
    }

    /**
     * R44's derived surface ladder. Body text sits on these containers -- the
     * navigation bar, dialogs, dropdowns -- so blending surface toward
     * onSurface must not walk a container far enough to break its own text.
     */
    @Test
    fun derivedSurfaceContainersKeepBodyTextReadable() = eachScheme { name, s ->
        assertAtLeast(4.5, ratio(s.onSurface, s.surfaceContainerLowest), "$name onSurface/surfaceContainerLowest")
        assertAtLeast(4.5, ratio(s.onSurface, s.surfaceContainerHighest), "$name onSurface/surfaceContainerHighest")
        assertAtLeast(4.5, ratio(s.onSurface, s.surfaceVariant), "$name onSurface/surfaceVariant")
    }

    /**
     * The measurement §7 R47 was found by, kept as a test so the technique
     * cannot come back quietly: if a future palette edit ever made
     * `onSurface` at 60% opacity legal, this assertion is the one to delete
     * deliberately rather than a call site to reintroduce by habit.
     */
    @Test
    fun alphaMutedTextIsNotAViableSubstituteInLightPalettes() {
        for (palette in MedPalette.entries) {
            val s = colorSchemeFor(palette, dark = false)
            val composited = Color(
                red = s.onSurface.red * 0.6f + s.surface.red * 0.4f,
                green = s.onSurface.green * 0.6f + s.surface.green * 0.4f,
                blue = s.onSurface.blue * 0.6f + s.surface.blue * 0.4f,
            )
            assertTrue(
                "${palette.name}: onSurface@60% now clears AA (${"%.2f".format(ratio(composited, s.surface))}:1) -- " +
                    "re-read §7 R47 before treating the alpha shortcut as safe again",
                ratio(composited, s.surface) < 4.5,
            )
        }
    }
}
