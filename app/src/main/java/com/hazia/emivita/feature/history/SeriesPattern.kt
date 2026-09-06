package com.hazia.emivita.feature.history

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

/**
 * The non-colour channel for [AdherenceChart]'s three series (spec §7 R54).
 *
 * The chart paints taken/skipped/missed as stacked bars read through a legend,
 * so hue was the only thing telling them apart. Measured across the candidate
 * palettes (Appendix D), the taken/missed pair falls under the ΔE 10
 * categorical floor in 4 of 16 registers once a colour-vision deficiency is
 * simulated -- which is the condition §7 R53 named as overturning its
 * judgement, and R54 confirmed.
 *
 * R54 offered two fixes and recommended this one: give the chart a second
 * channel rather than reserving a hue for `missed`. Reserving a hue fixes one
 * palette's arithmetic; fixing the channel holds for any palette, now or later,
 * and is what WCAG 1.4.1 asks for regardless of how far apart the colours are.
 *
 * Each series gets a tiled fill: solid, diagonal hatch, cross-hatch. The marks
 * are drawn in the series' own colour blended toward the surface, so the
 * texture stays inside the palette's family instead of introducing a fourth
 * colour -- the coherence §7 R49-R52 was spent buying is not spent again here.
 *
 * Bars are about 6dp wide at the 30-day window, so the tile is deliberately
 * coarse: a finer weave disappears at that size, which would put the app back
 * to relying on hue while appearing to have fixed it.
 */
enum class SeriesPattern {
    /** Taken. No marks -- the expected outcome reads as the plain one. */
    SOLID,

    /** Skipped. Single diagonal, the lighter texture of the two marked series. */
    DIAGONAL,

    /** Missed. Cross-hatch: the densest texture for the outcome that matters most. */
    CROSS_HATCH,
}

/** Tile edge in pixels at 1x density. Coarse on purpose -- see the class note. */
private const val TILE_DP = 8f

/** How far the marks are blended toward [surface]; enough to read at 6dp, not
 *  so far that the bar stops looking like one colour. */
private const val MARK_BLEND = 0.55f

private fun blend(a: Color, b: Color, t: Float) = Color(
    red = a.red * (1 - t) + b.red * t,
    green = a.green * (1 - t) + b.green * t,
    blue = a.blue * (1 - t) + b.blue * t,
)

/**
 * Builds the repeating tile for [pattern]. [density] is `LocalDensity.density`;
 * the tile is sized in device pixels so the texture is the same physical size
 * on every screen rather than shrinking on a dense one.
 */
fun seriesPatternTile(
    pattern: SeriesPattern,
    seriesColor: Color,
    surfaceColor: Color,
    density: Float = 1f,
): Bitmap {
    val size = (TILE_DP * density).toInt().coerceAtLeast(4)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    canvas.drawColor(seriesColor.toArgb())

    if (pattern == SeriesPattern.SOLID) return bitmap

    val mark = Paint().apply {
        isAntiAlias = true
        color = blend(seriesColor, surfaceColor, MARK_BLEND).toArgb()
        strokeWidth = (size / 4f).coerceAtLeast(1f)
    }
    val s = size.toFloat()
    // Three parallels per direction, at x+y = -s, 0, +s relative to the tile's
    // own diagonal. The middle one crosses the tile; the outer two only clip
    // the corners, and exist so the stroke joins up across the seam when the
    // tile repeats. Drawing only the outer two -- as this first did -- puts
    // almost no ink inside the tile at all: solid and diagonal came out 0.007
    // apart on the luminance measure, which is to say indistinguishable, on
    // code that looked correct.
    for (k in -1..1) {
        val o = k * s
        canvas.drawLine(o, s + o, s + o, o, mark)                  // "/" direction
        if (pattern == SeriesPattern.CROSS_HATCH) {
            canvas.drawLine(o, o, s + o, s + o, mark)              // "\" direction
        }
    }
    return bitmap
}

/**
 * How different two tiles are once colour is discarded -- the mean absolute
 * difference in luminance per pixel, 0..1.
 *
 * This is the measurement that makes R54's requirement testable. Two series can
 * be far apart in hue and identical here, which is exactly the failure being
 * fixed; a value above zero means the pattern alone distinguishes them, with no
 * colour information at all.
 */
fun patternLuminanceDistance(a: Bitmap, b: Bitmap): Double {
    require(a.width == b.width && a.height == b.height) { "tiles must be the same size" }
    fun lumaOf(p: Int): Double {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val bl = p and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * bl) / 255.0
    }
    var total = 0.0
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            total += kotlin.math.abs(lumaOf(a[x, y]) - lumaOf(b[x, y]))
        }
    }
    return total / (a.width * a.height)
}
