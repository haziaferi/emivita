package com.hazia.emivita.feature.history

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.settings.StartPage
import com.hazia.emivita.navigation.EmivitaNavHost
import com.hazia.emivita.ui.form.FormProbe
import com.hazia.emivita.ui.form.FormScreenTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * History's table on the narrowest phone (§7 R132). At font 2.0 every cell ended in "…", and at font 1.0 a long name
 * did ("Paracetamolo …"); the owner chose two-line rows ("C: two-line rows") whenever the four columns cannot show
 * whole, and the plain table whenever they can.
 */
open class HistoryTableLayoutTest : FormScreenTestBase() {

    private fun seed(names: List<String>) {
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        for ((i, name) in names.withIndex()) {
            val med = h.medicine(name, stock = 60.0, unit = "compresse")
            val rem = h.reminder(med, ReminderConfig.FixedTime(hour = 8 + i * 4, minute = 0, dose = 1.0 + i % 2))
            runBlocking {
                for (d in 1..3) {
                    val at = now - d * day - i * 3_600_000L
                    if (i == 2) h.container.doseEventRepository.materializeIfAbsent(rem, med, at, 1.0)
                    else h.container.doseEventRepository.answerProjected(rem, med, at, 1.0 + i % 2, if (i == 1) DoseStatus.SKIPPED else DoseStatus.TAKEN, at)
                }
            }
        }
    }

    private fun openTable(names: List<String>) {
        fontScale.floatValue = res.configuration.fontScale
        seed(names)
        host { EmivitaNavHost(startPage = StartPage.HISTORY) }
        waitFor("History") { inTop(hasText(s(R.string.history_view_table)) and hasClickAction()) }
        tap(hasText(s(R.string.history_view_table)) and hasClickAction())
        waitFor("the table") { inTop(hasText(names.last(), substring = true)) }
        settle()
    }

    /** Every text below the view bar: none cut, none a word broken across lines. */
    private fun tableDefects(): List<String> {
        val f = FormProbe.frame()
        val bar = f.leaves.filter { it.text == s(R.string.history_view_table) }.maxOf { it.bounds.bottom }
        val navTop = f.merged.filter { it.role == androidx.compose.ui.semantics.Role.Tab }.minOfOrNull { it.unclipped.top } ?: Float.MAX_VALUE
        return f.leaves.filter { it.isText && it.fullyVisible() && it.bounds.top > bar && it.bounds.bottom < navTop }
            .filter { it.cut || (it.lines > 1 && ' ' !in it.label.trim()) }
            .map { "'${it.label}' lines=${it.lines} cut=${it.cut}" }
    }

    @Test fun a_long_name_shows_whole_on_two_line_rows() {
        openTable(listOf("Metformina", "Paracetamolo 1000 mg", "Levotiroxina"))
        val found = tableDefects()
        println("TABLE long fs${fontScale.floatValue}: $found")
        assertTrue("cut or broken: $found", found.isEmpty())
        val f = FormProbe.frame()
        val name = f.leaves.first { it.text == "Paracetamolo 1000 mg" }
        val date = f.leaves.filter { it.text?.matches(Regex("\\d{1,2} \\w+, \\d{1,2}:\\d{2}")) == true }.minBy { abs(it.bounds.top - name.bounds.bottom) }
        assertTrue("two-line rows: the date sits under the name", date.bounds.top >= name.bounds.bottom - 1f)
    }

    @Test fun short_names_keep_the_four_columns_at_font_one() {
        openTable(listOf("Metformina", "Aspirina", "Levotiroxina"))
        assertTrue(tableDefects().isEmpty())
        val f = FormProbe.frame()
        val heads = listOf(R.string.history_table_date, R.string.history_table_dose).map { id -> f.leaves.first { it.text == s(id) } }
        val oneLine = abs(heads[0].bounds.center.y - heads[1].bounds.center.y) < 4f
        println("TABLE short fs${fontScale.floatValue}: header on one line=$oneLine")
        if (fontScale.floatValue <= 1f) assertTrue("at font 1.0 short names keep the four columns", oneLine)
    }
}

@Config(fontScale = 2f)
class HistoryTableLayoutFs2Test : HistoryTableLayoutTest()

/** The 6T as set (384 dp), where the walks run. */
@Config(qualifiers = "it-rIT-w384dp-h832dp-450dpi", fontScale = 2f)
class HistoryTableLayout6TFs2Test : HistoryTableLayoutTest()
