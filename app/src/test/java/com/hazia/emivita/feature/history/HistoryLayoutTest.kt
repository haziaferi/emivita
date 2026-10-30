package com.hazia.emivita.feature.history

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.settings.StartPage
import com.hazia.emivita.navigation.EmivitaNavHost
import com.hazia.emivita.ui.form.FormProbe
import com.hazia.emivita.ui.form.FormScreenTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * History's list at the phone's font size on the narrowest phone (§7 R130, found on the 6T at font 2.0):
 * the summary and chart stood above the list and left it a 40 px strip, none on the 9 Pro, and the
 * view bar broke "Tabella" into syllables. Now everything scrolls as one list, the chart is left out when
 * it could not show whole above the first dose (the owner: "Both"), and the bar's labels are capped
 * like the navigation's.
 */
open class HistoryLayoutTest : FormScreenTestBase() {

    private fun seedHistory() {
        val med = h.medicine("Metformina", stock = 60.0, unit = "compresse")
        val rem = h.reminder(med, ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0))
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        runBlocking {
            for (d in 1 until 20) {
                val at = now - d * day
                h.container.doseEventRepository.answerProjected(rem, med, at, 1.0, DoseStatus.TAKEN, at)
            }
        }
    }

    private fun openHistory() {
        fontScale.floatValue = res.configuration.fontScale
        seedHistory()
        host { EmivitaNavHost(startPage = StartPage.HISTORY) }
        waitFor("the dose list") { inTop(hasText("Metformina")) }
        settle()
    }

    @Test fun a_dose_shows_above_the_navigation_bar() {
        openHistory()
        val fr = FormProbe.frame()
        // "Storico" is the screen's title too: the navigation label is the lower one.
        val navTop = fr.leaves.filter { it.text == s(R.string.nav_history) }.maxOf { it.bounds.top }
        val dose = fr.leaves.firstOrNull { it.text == "Metformina" && it.fullyVisible() && it.bounds.bottom <= navTop }
        println("HISTORY fs${fontScale.floatValue}: first dose at ${dose?.bounds?.top?.div(dp)} dp, nav bar at ${navTop / dp} dp")
        assertNotNull("no dose row whole above the navigation bar", dose)
    }

    @Test fun the_view_bar_labels_are_whole_on_one_line() {
        openHistory()
        val names = listOf(R.string.history_view_list, R.string.history_view_calendar, R.string.history_view_table).map { s(it) }
        val leaves = FormProbe.frame().leaves.filter { it.text in names }
        println("HISTORY-TABS fs${fontScale.floatValue}: " + leaves.map { "${it.text} lines=${it.lines} cut=${it.cut}" })
        assertEquals(names.toSet(), leaves.map { it.text }.toSet())
        leaves.forEach { assertTrue("'${it.text}' runs ${it.lines} lines or is cut", it.lines == 1 && !it.cut) }
    }

    @Test fun the_chart_shows_at_font_one_and_scrolls_with_the_list() {
        openHistory()
        val title = s(R.string.adherence_adherence_trend)
        val drawn = inTop(hasText(title))
        println("HISTORY-CHART fs${fontScale.floatValue}: drawn=$drawn")
        if (fontScale.floatValue <= 1f) assertTrue("the chart is drawn at font 1.0", drawn)
        // One list: a swipe carries the summary off the top rather than scrolling the doses beneath it.
        val summaryTop = { FormProbe.frame().leaves.firstOrNull { it.text?.contains("%") == true }?.bounds?.top }
        val before = summaryTop()
        compose.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
        settle()
        val after = summaryTop()
        assertTrue("the summary did not scroll with the list: $before -> $after", after == null || (before != null && after < before))
    }
}

@Config(fontScale = 2f)
class HistoryLayoutFs2Test : HistoryLayoutTest()

/** The 6T as set (1080 px at 450 dpi), where the walk found it. */
@Config(qualifiers = "it-rIT-w384dp-h832dp-450dpi", fontScale = 2f)
class HistoryLayout6TFs2Test : HistoryLayoutTest()

/** The rule alone, at the sizes the walk measured. */
class HistoryChartFitsTest {
    @Test fun font_one_keeps_the_chart_on_both_phones() {
        assertTrue(historyChartFits(560.dp, 1f))
        assertTrue(historyChartFits(600.dp, 1f))
    }

    @Test fun font_two_drops_it_where_the_first_dose_would_not_show() {
        assertFalse(historyChartFits(600.dp, 2f))
        assertTrue(historyChartFits(1000.dp, 2f))
    }
}
