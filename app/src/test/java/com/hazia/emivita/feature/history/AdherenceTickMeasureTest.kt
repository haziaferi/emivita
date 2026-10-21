package com.hazia.emivita.feature.history

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.compose.ui.unit.Density
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.ui.theme.EmivitaTheme
import com.hazia.emivita.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Renders the adherence chart under each Y-tick rule (§7 R95) for days whose
 * busiest day holds 1, 2, 3 and 6 doses, at font scale 1.0, 1.3 and 2.0, on
 * the 6T's screen in Italian, and writes one JSON line per render with the
 * axes the owner chose: fractional ticks on counts, numbers to read, labels
 * equal to their values, clutter (Sci-Fin's edge density, inner variant), the
 * empty band in the plot (Factotum's void) and label collisions. A measuring
 * run is gated, the first test is not: `EMIVITA_MEASURE=1 ./gradlew testDebugUnitTest --tests '*AdherenceTickMeasureTest'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-rIT-w411dp-h891dp-xxhdpi")
class AdherenceTickMeasureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Case(val ticks: YTicks, val max: Int, val fontScale: Float)

    private fun days(max: Int): List<DayAdherenceCounts> {
        val start = LocalDate.of(2026, 9, 19)
        return (0 until 30).map { i ->
            val total = if (i % 7 == 3) max else 1 + (i * 5) % max
            val missed = if (i % 9 == 4 && total > 1) 1 else 0
            val skipped = if (i % 6 == 2 && total - missed > 1) 1 else 0
            DayAdherenceCounts(start.plusDays(i.toLong()), taken = total - missed - skipped, skipped = skipped, missed = missed)
        }
    }

    /** Renders each case in one composition and hands over what the axis drew and the host's pixels. */
    private fun render(cases: List<Case>, each: (Case, TickProbe, Bitmap) -> Unit) {
        Locale.setDefault(Locale.ITALY)
        val case = mutableStateOf(cases.first())
        val attempt = mutableStateOf(0)
        var probe: TickProbe? = null
        compose.setContent {
            val c = case.value
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, c.fontScale)) {
                EmivitaTheme(themeMode = ThemeMode.LIGHT) {
                    Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                        // A fresh chart per case and per attempt (§7 R104, R108): on a slow
                        // CI runner Vico's off-thread model sometimes never reached a drawn
                        // frame, even for a fresh chart; a new one is then started again.
                        key(c, attempt.value) { AdherenceChart(days(c.max), c.ticks) { probe = it } }
                    }
                }
            }
        }
        for (c in cases) {
            probe = null
            case.value = c
            // Vico draws, and so reports its ticks, only when a frame is rendered,
            // and applies its model off the main thread: capture until it has, for
            // up to 20 s of real time, then start the chart again, three times at most.
            for (tries in 0 until ATTEMPTS) {
                if (tries > 0) attempt.value++
                val deadline = System.nanoTime() + 20_000_000_000L
                capture()
                while (probe == null && System.nanoTime() < deadline) {
                    Thread.sleep(50)
                    compose.mainClock.advanceTimeBy(100)
                    capture()
                }
                if (probe != null) break
            }
            // One more frame after the first report, so the bars have landed.
            Thread.sleep(100); compose.mainClock.advanceTimeBy(500)
            val image = capture()
            each(c, checkNotNull(probe) { "no tick report for $c in $ATTEMPTS charts of 20 s each" }, image)
        }
    }

    /**
     * The owner's choice (§7 R95): the chart's default draws whole-number ticks
     * on dose counts, up to the busiest day, at normal and large text. Vico 3's
     * automatic step drew 0,3 … 2,7 here and never labelled 3.
     */
    @Test
    fun `the chart's ticks are whole numbers up to the busiest day`() {
        val cases = listOf(3, 6).flatMap { max -> listOf(1f, 2f).map { Case(DEFAULT_Y_TICKS, max, it) } }
        render(cases) { c, p, _ ->
            assertTrue("$c drew ${p.values}", p.values.all { abs(it - Math.rint(it)) < 1e-9 })
            assertEquals("$c drew ${p.values}", c.max.toDouble(), p.values.max(), 1e-9)
        }
    }

    @Test
    fun measure() {
        assumeTrue("measuring run only", System.getenv("EMIVITA_MEASURE") == "1")
        val out = File("build/chart_ticks").apply { mkdirs() }
        val lines = File(out, "results.jsonl").apply { writeText("") }
        val cases = YTicks.entries.flatMap { t -> listOf(1, 2, 3, 6).flatMap { m -> listOf(1f, 1.3f, 2f).map { Case(t, m, it) } } }
        render(cases) { c, p, image ->
            val name = "${c.ticks.name.lowercase()}_max${c.max}_fs${(c.fontScale * 10).toInt()}"
            File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val px = image.asImageBitmap().toPixelMap()
            lines.appendText(json(name, c.ticks, c.max, c.fontScale, p, edgeDensityInner(px), voidDp(px, compose.density.density)) + "\n")
        }
    }

    /** The chart host, drawn in software from the activity's view tree: PixelCopy, which captureToImage needs, is not available here. */
    private fun capture(): Bitmap {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val full = root.drawToBitmap()
        val b = compose.onNode(hasContentDescription(compose.activity.getString(com.hazia.emivita.R.string.a11y_adherence_chart, "").trim(), substring = true)).fetchSemanticsNode().boundsInWindow
        return Bitmap.createBitmap(full, b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
    }

    /** Sci-Fin tools/borrowed_axes.py:48 (from Factotum screen_score.py:152): grey, PIL FIND_EDGES, share >= 40, inner ring dropped. */
    private fun edgeDensityInner(px: androidx.compose.ui.graphics.PixelMap): Double {
        val w = px.width; val h = px.height
        val l = Array(h) { y -> IntArray(w) { x -> val c = px[x, y]; ((c.red * 255).toInt() * 299 + (c.green * 255).toInt() * 587 + (c.blue * 255).toInt() * 114) / 1000 } }
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var s = 8 * l[y][x]
            for (dy in -1..1) for (dx in -1..1) if (dx != 0 || dy != 0) s -= l[y + dy][x + dx]
            if (s.coerceIn(0, 255) >= 40) n++
        }
        return n.toDouble() / ((w - 2) * (h - 2))
    }

    /**
     * Factotum t3_graphs.py:1788: the tallest run of rows inside the graph box
     * whose sampled pixels (every second column) sit within 10 levels of the
     * box's modal grey, beyond a 24 dp allowance. The box is the host less its
     * Y-label column (the left 18 %) and its date band (the bottom 28 dp),
     * the same for every rule.
     */
    private fun voidDp(px: androidx.compose.ui.graphics.PixelMap, density: Float): Double {
        val x0 = (px.width * 0.18).toInt() + 4; val x1 = px.width - 4
        val y0 = 2; val y1 = px.height - (28 * density).toInt() - 2
        fun grey(x: Int, y: Int) = px[x, y].let { ((it.red * 255).toInt() * 299 + (it.green * 255).toInt() * 587 + (it.blue * 255).toInt() * 114) / 1000 }
        val hist = IntArray(256); for (y in y0 until y1) for (x in x0 until x1) hist[grey(x, y)]++
        val ground = hist.indices.maxBy { hist[it] }
        var run = 0; var best = 0
        for (y in y0 until y1) {
            if ((x0 until x1 step 2).all { abs(grey(it, y) - ground) < 10 }) { run++; best = maxOf(best, run) } else run = 0
        }
        return maxOf(0.0, best / density - 24.0)
    }

    private fun json(name: String, ticks: YTicks, max: Int, fs: Float, p: TickProbe, clutter: Double, void: Double): String {
        val v = p.values.sorted()
        val fractional = v.count { abs(it - Math.rint(it)) > 1e-9 }
        val steps = v.zipWithNext { a, b -> b - a }
        val nice = steps.all { s -> val e = 10.0.pow(floor(log10(s))); listOf(1.0, 2.0, 5.0, 10.0).any { abs(s / e - it) < 1e-6 } }
        val labelMismatch = v.count { val text = formatDecimalLocalized(it); abs((text.replace(',', '.').toDoubleOrNull() ?: Double.NaN) - it) > 1e-9 }
        val span = (p.maxY - p.minY).takeIf { it > 0 } ?: 1.0
        val gaps = v.zipWithNext { a, b -> p.axisHeight * (b - a) / span }
        val collisions = gaps.count { it < p.labelHeight }
        val minGapRatio = gaps.minOrNull()?.div(p.labelHeight) ?: 0f
        return """{"name":"$name","ticks":"$ticks","max":$max,"fontScale":$fs,"values":${v.joinToString(",", "[", "]")},"rangeMax":${p.maxY},"fractional":$fractional,"niceSteps":$nice,"numbers":${v.size},"labelMismatch":$labelMismatch,"collisions":$collisions,"minGapOverLabel":$minGapRatio,"axisHeightPx":${p.axisHeight},"labelHeightPx":${p.labelHeight},"clutter":$clutter,"voidDp":$void}"""
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}
