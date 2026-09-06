package com.hazia.emivita.feature.history

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.get
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The regression guard for spec §7 R54/R55.
 *
 * R53 recorded that coherence and distinguishability are in opposition, and
 * named the condition that would overturn its judgement: any surface conveying
 * state by colour with no icon or label beside it. R54 tested that condition
 * against `AdherenceChart` and found it met -- the taken/missed pair falls
 * under the categorical floor in 4 of 16 registers once colour-vision
 * deficiency is simulated.
 *
 * The fix is a second channel, so the test has to check the second channel.
 * Asserting the three series are *different colours* would pass on the exact
 * code that failed R54. These assertions discard colour entirely and measure
 * what is left, which is the only thing that proves hue is no longer
 * load-bearing.
 */
// Native graphics, not the legacy shadow: the shadow implements drawColor but
// not drawLine, so every tile came back flat and the pattern assertions failed
// against code that draws correctly on a device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SeriesPatternTest {

    private val surface = Color(0.96f, 0.94f, 0.90f)

    /** The worst case R54 measured: taken and missed almost the same colour. */
    private val nearIdenticalA = Color(0.72f, 0.50f, 0.22f)
    private val nearIdenticalB = Color(0.70f, 0.52f, 0.24f)

    private fun tile(p: SeriesPattern, c: Color) = seriesPatternTile(p, c, surface, density = 3f)

    @Test
    fun eachSeriesGetsADistinctTexture() {
        val taken = tile(SeriesPattern.SOLID, nearIdenticalA)
        val skipped = tile(SeriesPattern.DIAGONAL, nearIdenticalA)
        val missed = tile(SeriesPattern.CROSS_HATCH, nearIdenticalA)
        // Same colour for all three, so any difference measured here is texture.
        assertTrue("solid vs diagonal", patternLuminanceDistance(taken, skipped) > 0.02)
        assertTrue("solid vs cross-hatch", patternLuminanceDistance(taken, missed) > 0.02)
        assertTrue("diagonal vs cross-hatch", patternLuminanceDistance(skipped, missed) > 0.02)
    }

    /**
     * The assertion that would have caught R54. Two series whose colours are
     * nearly identical -- the state the palettes are actually in for four
     * registers -- must still be separable, and they are, because the channel
     * doing the work is not colour.
     */
    @Test
    fun seriesStaySeparableWhenTheirColoursAreNearlyIdentical() {
        val taken = tile(SeriesPattern.SOLID, nearIdenticalA)
        val missed = tile(SeriesPattern.CROSS_HATCH, nearIdenticalB)
        assertTrue(
            "two near-identical colours must still differ by texture",
            patternLuminanceDistance(taken, missed) > 0.02,
        )
    }

    /** Cross-hatch is for missed, the outcome that matters most, so it must be
     *  the heaviest texture -- not merely a different one. */
    @Test
    fun crossHatchCarriesMoreInkThanDiagonal() {
        val c = Color(0.4f, 0.5f, 0.6f)
        val solid = tile(SeriesPattern.SOLID, c)
        val diagonal = tile(SeriesPattern.DIAGONAL, c)
        val cross = tile(SeriesPattern.CROSS_HATCH, c)
        assertTrue(
            "cross-hatch should depart from solid more than diagonal does",
            patternLuminanceDistance(cross, solid) > patternLuminanceDistance(diagonal, solid),
        )
    }

    /** Taken is the expected outcome; the plain bar should stay plain. */
    @Test
    fun solidHasNoMarks() {
        val c = Color(0.4f, 0.5f, 0.6f)
        assertEquals(0.0, patternLuminanceDistance(tile(SeriesPattern.SOLID, c), tile(SeriesPattern.SOLID, c)), 0.0)
        val t = tile(SeriesPattern.SOLID, c)
        val first = t[0, 0]
        var uniform = true
        for (y in 0 until t.height) {
            for (x in 0 until t.width) {
                if (t[x, y] != first) uniform = false
            }
        }
        assertTrue("solid tile should be one flat colour", uniform)
    }

    /** The tile is sized in device pixels so the texture is the same physical
     *  size on a dense screen rather than shrinking away. */
    @Test
    fun tileScalesWithDensity() {
        val c = Color(0.4f, 0.5f, 0.6f)
        val low = seriesPatternTile(SeriesPattern.DIAGONAL, c, surface, density = 1f)
        val high = seriesPatternTile(SeriesPattern.DIAGONAL, c, surface, density = 3f)
        assertTrue("a denser screen gets a bigger tile", high.width > low.width)
    }

    /** The marks stay inside the palette: they are the series colour blended
     *  toward surface, never a fourth colour introduced into the chart. */
    @Test
    fun marksStayWithinThePalette() {
        val series = Color(0.72f, 0.50f, 0.22f)
        val t = tile(SeriesPattern.CROSS_HATCH, series)
        val seen = mutableSetOf<Int>()
        for (y in 0 until t.height) {
            for (x in 0 until t.width) seen += t[x, y]
        }
        // Anti-aliasing puts intermediate tones between the two, so this bounds
        // the family rather than demanding exactly two values: every pixel must
        // sit between the series colour and the blended mark on each channel.
        val lo = minOf(series.red, surface.red) * 255 - 6
        val hi = maxOf(series.red, surface.red) * 255 + 6
        assertTrue(
            "every red channel value should sit between series and surface",
            seen.all { ((it shr 16) and 0xFF).toFloat() in lo..hi },
        )
    }
}
