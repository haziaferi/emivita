package com.hazia.emivita.ui.form

import android.graphics.Bitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.settings.StartPage
import com.hazia.emivita.core.data.settings.TodayLayout
import com.hazia.emivita.navigation.EmivitaNavHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/**
 * Every top-level screen on the narrowest phone, at font 1.0 and 2.0 (§7 R131, the owner's standing rule of
 * 2026-10-30: "Gate 2.0 + 360 dp in CI"): no text is drawn short of itself, and no one-word text breaks across
 * lines. R129 and R130 found both by hand on the phone ("Farmac/i", "Tabella", "W / alk / R1 / 30"); the form
 * tests saw neither, because they read forms and sheets, not the screens around them.
 *
 * With EMIVITA_MEASURE=1 each stop also writes its first screen (PNG) and every node it swept (JSON) under
 * `build/measure/screens` (or `-Dmeasure.out`), the input of `tools/borrowed_axes.py`.
 */
open class ScreenSweepTest : FormScreenTestBase() {

    private val measureDir: File? =
        if (System.getenv("EMIVITA_MEASURE") == "1") File(System.getProperty("measure.out") ?: "build/measure/screens") else null

    /** A phone the gate does not run on, kept for measuring: its tests are skipped unless EMIVITA_MEASURE=1. */
    protected open val measureOnly = false

    private fun seed(): Long {
        val med = h.medicine("Metformina", stock = 60.0, unit = "compresse")
        // An hour from now, so today has a dose due at any hour the suite runs: a reminder made after its slot starts
        // tomorrow, and a fixed 08:00 left Today without one from 08:00 on.
        val clock = java.time.LocalTime.now()
        val due = clock.plusHours(1).let { if (it < clock) java.time.LocalTime.of(23, 59) else it }
        val rem = h.reminder(med, ReminderConfig.FixedTime(hour = due.hour, minute = due.minute, dose = 1.0))
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        runBlocking {
            for (d in 1 until 10) {
                val at = now - d * day
                h.container.doseEventRepository.answerProjected(rem, med, at, 1.0, DoseStatus.TAKEN, at)
            }
        }
        h.medicine("Paracetamolo 1000 mg", stock = 4.0, unit = "compresse")
        return med
    }

    /** The top window's main scroll container, if any. */
    private fun scroller() = FormProbe.topWindow().roots.flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .filter { SemanticsProperties.VerticalScrollAxisRange in it.config && SemanticsActions.ScrollBy in it.config }
        .maxByOrNull { it.size.width.toLong() * it.size.height }

    private fun scrollBy(dy: Float) {
        val n = scroller() ?: return
        compose.runOnIdle { n.config[SemanticsActions.ScrollBy].action?.invoke(0f, dy) }
        settle()
    }

    /**
     * Every text leaf the screen shows, whole, from the top of its scroll to the end, above the navigation bar. Unlike
     * [FormRules.sweep] it keeps no frames: at 360 dp xxhdpi a frame is about 20 MB and a long screen ran 512 MB out.
     * When measuring, [tag] names the stop's files.
     */
    private fun sweepTexts(tag: String): List<ProbeNode> {
        scroller()?.let { val v = it.config[SemanticsProperties.VerticalScrollAxisRange].value(); if (v > 0f) scrollBy(-v - 10f) }
        val seen = HashSet<Int>()
        val out = mutableListOf<ProbeNode>()
        val dir = measureDir
        val record = mutableListOf<String>()
        var navTopPx: Float? = null
        fun done(): List<ProbeNode> {
            if (dir != null) writeMeasure(dir, tag, navTopPx, record)
            return out
        }
        for (frame in 0 until 24) {
            val f = FormProbe.frame()
            val navTop = f.merged.filter { it.role == Role.Tab }.minOfOrNull { it.unclipped.top }
            val sc = scroller()
            val offset = sc?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.value() ?: 0f
            if (dir != null) {
                if (frame == 0) { navTopPx = navTop; savePng(dir, tag, f.px) }
                for (n in f.merged) if (n.fullyVisible()) record += nodeJson("merged", n, offset, sc?.id, frame)
                for (n in f.leaves) if (n.isText && n.fullyVisible()) record += nodeJson("leaf", n, offset, sc?.id, frame)
            }
            for (l in f.leaves) {
                if (!l.isText || !l.fullyVisible() || l.id in seen || (navTop != null && l.unclipped.top >= navTop - 0.5f)) continue
                seen += l.id; out += l
            }
            val n = sc ?: return done()
            val r = n.config[SemanticsProperties.VerticalScrollAxisRange]
            if (r.value() >= r.maxValue() - 0.5f) return done()
            scrollBy(n.size.height * 0.6f)
            val after = scroller()?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.value() ?: return done()
            if (abs(after - r.value()) < 0.5f) return done()
        }
        return done()
    }

    private fun esc(t: String?) = t?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" } ?: "null"

    /** One node in window pixels; [offset] is its frame's scroll position (a lazy list's is an estimate), [scroller] the container's id. */
    private fun nodeJson(kind: String, n: ProbeNode, offset: Float, scroller: Int?, frame: Int): String {
        val inScroll = scroller != null && scroller in n.ancestors
        val u = n.unclipped
        return "{\"kind\":\"$kind\",\"id\":${n.id},\"frame\":$frame,\"scroll\":$offset,\"in_scroll\":$inScroll," +
            "\"text\":${esc(n.text)},\"desc\":${esc(n.description)},\"role\":${esc(n.role?.toString())},\"clickable\":${n.clickable}," +
            "\"editable\":${n.editable},\"lines\":${n.lines},\"cut\":${n.cut},\"l\":${u.left},\"t\":${u.top},\"r\":${u.right},\"b\":${u.bottom}}"
    }

    private fun stem(tag: String) = "${tag}_w${res.configuration.screenWidthDp}_fs${(fontScale.floatValue * 10).toInt()}"

    private fun savePng(dir: File, tag: String, px: Pixels) {
        dir.mkdirs()
        val a = IntArray(px.width * px.height) { i -> px[i % px.width, i / px.width] }
        val bmp = Bitmap.createBitmap(a, px.width, px.height, Bitmap.Config.ARGB_8888)
        File(dir, stem(tag) + ".png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun writeMeasure(dir: File, tag: String, navTop: Float?, nodes: List<String>) {
        dir.mkdirs()
        val c = res.configuration
        File(dir, stem(tag) + ".json").writeText(
            "{\"screen\":\"$tag\",\"width_dp\":${c.screenWidthDp},\"height_dp\":${c.screenHeightDp},\"density\":$dp," +
                "\"font\":${fontScale.floatValue},\"nav_top\":$navTop,\"nodes\":[\n" + nodes.joinToString(",\n") + "\n]}\n",
        )
    }

    /** A cut text, or a text with no space in it drawn on more than one line: a word broken in two. [expect] must be on the screen somewhere. */
    private fun defects(tag: String, expect: String? = null): List<String> {
        // A layout switch lands through DataStore on its own thread: sweep again until the expected text is drawn.
        var leaves = sweepTexts(tag)
        var tries = 0
        while (expect != null && leaves.none { expect in it.label } && tries++ < 20) { Thread.sleep(100); settle(); leaves = sweepTexts(tag) }
        val missing = if (expect != null && leaves.none { expect in it.label }) listOf("'$expect' never shown") else emptyList()
        return missing + leaves.filter { it.cut || (it.lines > 1 && ' ' !in it.label.trim()) }.map { "'${it.label}' lines=${it.lines} cut=${it.cut}" }
    }

    private fun goTab(label: Int, wait: String, cond: () -> Boolean) {
        tap(hasText(s(label)) and hasClickAction())
        waitFor(wait, cond = cond)
        settle()
    }

    private fun openSettingsRow(row: Int, tag: String): List<String> {
        // Settings comes back scrolled where it was left: from the top, step down until the row is composed, then
        // bring it whole into view. performScrollToNode never returned for a lower row at 384 dp and font 2.0.
        val target = hasText(s(row)) and hasClickAction()
        scroller()?.let { val v = it.config[SemanticsProperties.VerticalScrollAxisRange].value(); if (v > 0f) scrollBy(-v - 10f) }
        var steps = 0
        while (!inTop(target) && steps++ < 20) scroller()?.let { scrollBy(it.size.height * 0.5f) } ?: break
        tap(target)
        settle()
        val found = defects(tag)
        pressBack()
        return found
    }

    // One test per area, so each one's state is let go before the next.

    private fun start(page: StartPage) {
        Assume.assumeTrue("measuring only", !measureOnly || measureDir != null)
        fontScale.floatValue = res.configuration.fontScale
        seed()
        host { EmivitaNavHost(startPage = page) }
    }

    /**
     * Stops whose defect at a large font waits on the owner's choice of fix, each with its ledger row. Anything else
     * fails; and a listed stop that comes back whole fails too, so the entry is taken out with the fix.
     */
    private val ownerOpenAtLargeFont = emptyMap<String, String>() // R131's history_table closed by R132

    private fun gate(found: Map<String, List<String>>) {
        println("SWEEP fs${fontScale.floatValue} w${res.configuration.screenWidthDp}: " + found.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val open = if (fontScale.floatValue > 1f) ownerOpenAtLargeFont.keys else emptySet()
        val failing = found.filter { it.value.isNotEmpty() && it.key !in open }
        assertTrue("drawn short or broken mid-word: $failing", failing.isEmpty())
        val fixed = open.filter { it in found && found[it]!!.isEmpty() }
        assertTrue("now whole, take it out of ownerOpenAtLargeFont: $fixed", fixed.isEmpty())
    }

    @Test fun today_in_every_layout() {
        val found = linkedMapOf<String, List<String>>()
        for (layout in TodayLayout.entries) {
            runBlocking { h.container.settingsRepository.setTodayLayout(layout) }
            if (found.isEmpty()) start(StartPage.TODAY)
            // At font 2.0 the no-backup card can hold the doses below the fold: the sweep scrolls to them.
            waitFor("Today, $layout") { inTop(hasText(s(R.string.nav_today)) and hasClickAction()) }
            settle()
            found["today_$layout"] = defects("today_$layout", expect = "Metformina")
        }
        gate(found)
    }

    @Test fun medicines_and_a_medicine() {
        start(StartPage.MEDICINES)
        waitFor("the Medicines list") { inTop(hasText("Paracetamolo 1000 mg")) }
        val found = linkedMapOf("medicines" to defects("medicines"))
        tap(hasText("Metformina") and hasClickAction())
        found["medicine_detail"] = defects("medicine_detail")
        gate(found)
    }

    @Test fun history_in_every_view() {
        start(StartPage.HISTORY)
        waitFor("History") { inTop(hasText(s(R.string.history_view_calendar))) }
        val found = linkedMapOf("history_list" to defects("history_list", expect = "Metformina"))
        for ((view, tag) in listOf(R.string.history_view_calendar to "history_calendar", R.string.history_view_table to "history_table")) {
            tap(hasText(s(view)) and hasClickAction())
            found[tag] = defects(tag)
        }
        gate(found)
    }

    @Test fun settings_and_its_pages() {
        start(StartPage.MEDICINES)
        waitFor("the Medicines list") { inTop(hasText("Paracetamolo 1000 mg")) }
        goTab(R.string.nav_settings, "Settings") { inTop(hasText(s(R.string.settings_row_notifications)) and hasClickAction()) }
        val found = linkedMapOf("settings" to defects("settings"))
        for ((row, tag) in listOf(
            R.string.settings_row_notifications to "notifications_alerts", R.string.settings_row_appearance to "appearance",
            R.string.settings_row_data_backup to "data_backup", R.string.settings_row_medical_id to "medical_id",
        )) found[tag] = openSettingsRow(row, tag)
        gate(found)
    }
}

@Config(fontScale = 2f)
class ScreenSweepFs2Test : ScreenSweepTest()

/** The 6T as set (1080 px at 450 dpi), measured at the walked phone's width; skipped in the gate. */
@Config(qualifiers = "it-rIT-w384dp-h832dp-450dpi")
class ScreenSweep6TMeasure : ScreenSweepTest() {
    override val measureOnly = true
}

/** The 6T at font 2.0, measuring only. */
@Config(qualifiers = "it-rIT-w384dp-h832dp-450dpi", fontScale = 2f)
class ScreenSweep6TFs2Measure : ScreenSweepTest() {
    override val measureOnly = true
}
