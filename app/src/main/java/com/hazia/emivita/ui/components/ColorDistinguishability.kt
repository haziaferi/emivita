package com.hazia.emivita.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * "Is this new medicine colour too close to one already in use?" -- spec §3's
 * open question on the colour picker, answered in §7 R47.
 *
 * §3 phrases the test as the candidate's *contrast* against every other
 * assigned colour dropping too low. That metric is wrong for this question and
 * would have shipped a hint that fires on the wrong pairs. Contrast ratio is
 * defined on relative luminance alone, so it is blind to hue: a pure red and a
 * mid green sit at almost identical luminance and score about 1.0:1 while
 * being impossible to confuse, and a slightly darker grey beside a lighter one
 * scores comfortably while being genuinely hard to tell apart on a card edge.
 * Contrast ratio is the right tool for text on a background -- which is what
 * §6 and Appendix A use it for -- and the wrong one for two adjacent fills.
 *
 * What is actually being asked is perceptual distance, so this uses CIELAB
 * and ΔE*ab (CIE76). ΔE76 is the simplest of the ΔE family and is known to
 * under-report differences in saturated blues; that error is in the safe
 * direction here, since it makes the hint slightly more eager rather than
 * quietly missing a collision. ΔE2000 would be more accurate and needs about
 * forty lines of trigonometry to earn a threshold nobody can perceive the
 * difference in.
 *
 * The hint never blocks. §3 left the choice between "surface a hint" and
 * "leave it entirely to the user's judgment"; a non-blocking hint is both --
 * it tells the user what it noticed and then gets out of the way. Two
 * medicines the user genuinely wants in similar colours (a morning and an
 * evening dose of the same drug, say) is a real case, not a mistake to
 * prevent.
 */
data class ColorConflict(val otherName: String, val deltaE: Double)

/**
 * Below this the two fills read as "the same colour, maybe" at a glance on a
 * list. ΔE76 of 1.0 is nominally a just-noticeable difference under lab
 * conditions on adjacent patches; medicine cards are separated by whitespace,
 * seen in passing, often in bad light and sometimes by someone who is unwell,
 * so the useful threshold is far above the JND. 15 puts it near the boundary
 * of "clearly a different colour" while leaving the ~40-50 ΔE gaps a spread of
 * distinct hues produces well clear.
 */
const val COLOR_CONFLICT_DELTA_E = 15.0

/** CIELAB under D65, the illuminant sRGB is defined against. */
private fun Color.toLab(): Triple<Double, Double, Double> {
    fun linear(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    val r = linear(red)
    val g = linear(green)
    val b = linear(blue)
    // sRGB -> XYZ, then normalised by the D65 white point.
    val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / 1.00000
    val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
    fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (841.0 / 108.0) * t + 4.0 / 29.0
    val fx = f(x)
    val fy = f(y)
    val fz = f(z)
    return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

/** ΔE*ab (CIE76): plain Euclidean distance in CIELAB. */
fun colorDeltaE(a: Color, b: Color): Double {
    val (l1, a1, b1) = a.toLab()
    val (l2, a2, b2) = b.toLab()
    return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
}

/**
 * The closest already-assigned colour to [candidate], if any of them is close
 * enough to be confusing. Returns the *nearest* rather than a list: naming one
 * medicine is a hint, and naming four is a scolding.
 */
fun nearestColorConflict(
    candidate: Color,
    inUse: List<Pair<String, Color>>,
    threshold: Double = COLOR_CONFLICT_DELTA_E,
): ColorConflict? = inUse
    .map { (name, color) -> ColorConflict(name, colorDeltaE(candidate, color)) }
    .filter { it.deltaE < threshold }
    .minByOrNull { it.deltaE }
