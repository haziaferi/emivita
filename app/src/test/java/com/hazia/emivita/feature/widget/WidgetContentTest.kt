package com.hazia.emivita.feature.widget

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.format.maskedMedicineName
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.feature.today.settled
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * Both widgets as the launcher draws them (§7 R108): composed by Glance's own
 * [GlanceRemoteViews] at a launcher cell size, the RemoteViews applied into a
 * frame of exactly that size on Android 11 (SDK 30, the 6T's), and the frame's
 * real text views read back. Sizes: a 4-wide widget is 379 dp, a row of cells
 * 120 dp less 16 dp of launcher padding (R106's assumed 4 x 6 grid).
 *
 * Twelve medicines with a daily reminder every two hours, so the projection
 * always holds more upcoming doses than any size shows, and Metformina taken
 * two hours ago, so the last-dose line has one to name.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(application = TestEmivitaApplication::class, sdk = [30], qualifiers = "it-rIT-w411dp-h891dp-xxhdpi")
class WidgetContentTest {
    private val h = ReceiverHarness()
    private val c = h.container
    private val context: Context = h.context
    private val names = listOf(
        "Levotiroxina", "Metformina", "Ramipril", "Atorvastatina", "Colecalciferolo", "Omeprazolo",
        "Amlodipina", "Bisoprololo", "Furosemide", "Pantoprazolo", "Simvastatina", "Warfarin",
    )
    private val now = System.currentTimeMillis()
    private val takenAt = now - 2 * HOUR

    @Before
    fun seed() = runBlocking {
        names.forEachIndexed { i, name ->
            val id = h.medicine(name, stock = 30.0 + i, unit = "compresse")
            c.medicineRepository.update(h.medicineRow(id).let { it.copy(stockInfo = it.stockInfo.copy(refillQuantity = 30.0)) })
            val reminder = h.reminder(id, ReminderConfig.FixedTime(i * 2, 0, 1.0))
            if (name == "Metformina") c.doseEventRepository.answerProjected(reminder, id, takenAt - 5 * MINUTE, 1.0, DoseStatus.TAKEN, takenAt)
        }
        c.occurrenceProjectionCache.requestWindow(CONSUMER, 2)
        val deadline = System.nanoTime() + 10_000_000_000L
        // A whole pass, too: the count line (§7 R135) waits for one.
        while (c.occurrenceProjectionCache.state.value.let { st -> !st.settled || st.occurrences.filter { it.epochMillis >= now }.distinctBy { it.reminderId }.size < names.size }) {
            check(System.nanoTime() < deadline) { "the projection never held the twelve reminders" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
    }

    @After
    fun tearDown() {
        c.occurrenceProjectionCache.releaseWindow(CONSUMER)
        h.close()
    }

    // ---- E-38: the rows fill the size the owner picks ------------------------

    @Test fun `Next doses fills 4 x 2 and 4 x 4 with the rows that fit`() = fills(next = true)
    @Test @Config(fontScale = 2f) fun `Next doses at font 2 fills both sizes and clips nothing`() = fills(next = true)
    @Test fun `Stock levels fills 4 x 2 and 4 x 4 with the rows that fit`() = fills(next = false)
    @Test @Config(fontScale = 2f) fun `Stock levels at font 2 fills both sizes and clips nothing`() = fills(next = false)

    private fun fills(next: Boolean) {
        val nextData = runBlocking { loadNextDoses(c, now) }
        val stockData = runBlocking { loadStockRows(c) }
        val available = if (next) nextData.rows.size else stockData.size
        assertEquals("every reminder is a candidate row", names.size, available)
        val shown = mutableMapOf<Int, Int>()
        for (cells in listOf(2, 4)) {
            val frame = render(WIDTH_4, cells * ROW - PAD) { if (next) NextRemindersContent(nextData) else StockLevelsContent(stockData) }
            val texts = texts(frame)
            assertEquals("text cut by the frame at 4 x $cells: ${texts.filter { !it.whole }.map { it.text }}", emptyList<String>(), texts.filter { !it.whole }.map { it.text })
            val rows = texts.filter { t -> names.any { t.text == it } }
            shown[cells] = rows.size
            assertTrue("at least one row at 4 x $cells", rows.isNotEmpty())
            if (rows.size < available) {
                // The next row would not have fitted: the space left under the last
                // row is less than a plain row (name, detail and 8 dp of padding).
                val last = rows.last()
                val detail = texts.first { it.top >= last.bottom }
                // A row's own 4 dp of bottom padding sits under its last text; the
                // count is allowed one dp of the model's rounding (a line is taken as
                // 1.36 x its size, a little over what the render draws).
                val bottom = texts.maxOf { it.bottom } + px(4f)
                val left = frame.height - px(12f) - bottom
                val plainRow = last.bottom - last.top + (detail.bottom - detail.top) + px(8f)
                assertTrue("4 x $cells left $left px under ${rows.size} rows, room for another of $plainRow px", left < plainRow + px(1f))
            }
        }
        assertTrue("4 x 4 shows more rows than 4 x 2 ($shown)", shown.getValue(4) > shown.getValue(2))
        if (context.resources.configuration.fontScale == 1f) assertTrue("4 x 4 shows more than today's four rows ($shown)", shown.getValue(4) > 4)
    }

    // ---- E-40: the last answered dose above the rows -------------------------

    @Test
    fun `the last-dose line names the dose taken earlier, above the upcoming rows`() {
        val data = runBlocking { loadNextDoses(c, now) }
        val texts = texts(render(WIDTH_4, 2 * ROW - PAD) { NextRemindersContent(data) })
        val line = context.getString(R.string.widget_last_taken, "Metformina", LocaleFormats.time(takenAt))
        val at = texts.indexOfFirst { it.text == line }
        assertTrue("no line '$line' in ${texts.map { it.text }}", at >= 0)
        assertTrue("the line sits above the rows", texts.drop(at + 1).any { t -> names.any { t.text == it } })
    }

    @Test
    fun `the count line says today's planned doses, above the rows`() {
        val data = runBlocking { loadNextDoses(c, now) }
        val p = checkNotNull(data.progress) { "no count for today" }
        assertTrue("twelve daily reminders plan something today: $p", p.planned >= 1)
        val line = context.resources.getQuantityString(R.plurals.widget_progress_today, p.planned, p.taken, p.planned)
        val texts = texts(render(WIDTH_4, 2 * ROW - PAD) { NextRemindersContent(data) })
        val at = texts.indexOfFirst { it.text == line }
        assertTrue("no line '$line' in ${texts.map { it.text }}", at >= 0)
        assertTrue("the line sits above the rows", texts.drop(at + 1).any { t -> names.any { t.text == it } })
    }

    @Test
    fun `with hide-names on the last-dose line shows only the initial`() {
        runBlocking { c.settingsRepository.setHideMedicineNames(true) }
        val data = runBlocking { loadNextDoses(c, now) }
        val texts = texts(render(WIDTH_4, 2 * ROW - PAD) { NextRemindersContent(data) }).map { it.text }
        val line = context.getString(R.string.widget_last_taken, maskedMedicineName("Metformina", true), LocaleFormats.time(takenAt))
        assertTrue("no line '$line' in $texts", line in texts)
    }

    // ---- E-36: both widgets follow hide-names --------------------------------

    @Test
    fun `with hide-names on neither widget shows a full medicine name`() {
        runBlocking { c.settingsRepository.setHideMedicineNames(true) }
        val next = runBlocking { loadNextDoses(c, now) }
        val stock = runBlocking { loadStockRows(c) }
        for ((label, content) in listOf<Pair<String, @Composable () -> Unit>>("Next doses" to { NextRemindersContent(next) }, "Stock levels" to { StockLevelsContent(stock) })) {
            val texts = texts(render(WIDTH_4, 4 * ROW - PAD, content)).map { it.text }
            val named = texts.filter { t -> names.any { t.contains(it) } }
            assertEquals("$label shows full names", emptyList<String>(), named)
            assertTrue("$label shows the initials: $texts", texts.any { Regex("^[A-Z]\\*+$").matches(it) })
        }
    }

    // ---- Take and Refill: a 48 dp tap area -----------------------------------

    @Test
    fun `the Take and Refill pills are tapped on at least 48 dp`() {
        val next = runBlocking { loadNextDoses(c, now) }
        val stock = runBlocking { loadStockRows(c) }
        for ((label, content) in listOf<Pair<Int, @Composable () -> Unit>>(R.string.common_take to { NextRemindersContent(next) }, R.string.action_refill to { StockLevelsContent(stock) })) {
            val frame = render(WIDTH_4, 2 * ROW - PAD, content)
            val target = clickableAround(frame, context.getString(label))
            val dp = context.resources.displayMetrics.density
            assertTrue("${context.getString(label)}: ${target.width / dp} x ${target.height / dp} dp", target.height / dp >= 48f - 0.5f && target.width / dp >= 48f - 0.5f)
        }
    }

    // ---- rendering ----------------------------------------------------------

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun render(wDp: Float, hDp: Float, content: @Composable () -> Unit): FrameLayout {
        val palette = runBlocking { c.settingsRepository.widgetPalette() }
        val rv = runBlocking { GlanceRemoteViews().compose(context, DpSize(wDp.dp, hDp.dp)) { WidgetTheme(palette) { content() } } }.remoteViews
        val frame = FrameLayout(context)
        frame.addView(rv.apply(context, frame))
        val w = px(wDp).toInt()
        val hPx = px(hDp).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, hPx)
        return frame
    }

    /** A text view in frame pixels; [whole] when it is drawn in full: inside the frame, its lines inside its box, nothing ellipsized. */
    private class Shown(val text: String, val top: Float, val bottom: Float, val whole: Boolean)

    private fun texts(frame: View): List<Shown> {
        val out = mutableListOf<Shown>()
        fun walk(v: View, x: Float, y: Float) {
            if (v.visibility != View.VISIBLE) return
            val left = x + v.left
            val top = y + v.top
            val tv = v as? TextView
            val text = tv?.text?.toString()
            if (tv != null && !text.isNullOrBlank()) {
                val layout = tv.layout
                val linesFit = layout != null && layout.height <= tv.height - tv.compoundPaddingTop - tv.compoundPaddingBottom + 1 &&
                    (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 }
                val inside = tv.height > 0 && top >= -0.5f && top + tv.height <= frame.height + 0.5f && left >= -0.5f && left + tv.width <= frame.width + 0.5f
                out += Shown(text, top, top + tv.height, linesFit && inside)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), left, top)
        }
        walk(frame, 0f, 0f)
        return out
    }

    /** The smallest clickable view holding the text [label]. */
    private fun clickableAround(frame: View, label: String): View {
        fun holds(v: View): Boolean = (v as? TextView)?.text?.toString() == label || (v is ViewGroup && (0 until v.childCount).any { holds(v.getChildAt(it)) })
        val found = mutableListOf<View>()
        fun walk(v: View) {
            if (v.isClickable && holds(v)) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(frame)
        return found.minByOrNull { it.width * it.height } ?: error("nothing clickable holds '$label'")
    }

    private fun px(dp: Float) = dp * context.resources.displayMetrics.density

    private companion object {
        const val CONSUMER = "widget-content-test"
        const val WIDTH_4 = 379f
        const val ROW = 120f
        const val PAD = 16f
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
