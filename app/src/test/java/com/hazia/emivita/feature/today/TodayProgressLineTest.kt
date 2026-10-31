package com.hazia.emivita.feature.today

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.settings.StartPage
import com.hazia.emivita.navigation.EmivitaNavHost
import com.hazia.emivita.ui.form.FormScreenTestBase
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Test

/** Today's progress line (§7 R135, the owner's pick): "0 di 1 presa · prossima alle …", then the count moves on a Take. */
class TodayProgressLineTest : FormScreenTestBase() {

    private fun line(taken: Int, planned: Int, tail: String) =
        s(R.string.today_progress_line, res.getQuantityString(R.plurals.doses_taken_of, planned, taken, planned), tail)

    @Test fun the_line_counts_and_moves_on_a_take() {
        val clock = LocalTime.now()
        val due = clock.plusHours(1).let { if (it < clock) LocalTime.of(23, 59) else it }
        val med = h.medicine("Metformina", stock = 10.0, unit = "compresse")
        h.reminder(med, ReminderConfig.FixedTime(hour = due.hour, minute = due.minute, dose = 1.0))
        host { EmivitaNavHost(startPage = StartPage.TODAY) }
        val before = line(0, 1, s(R.string.today_progress_next, "")).trimEnd()
        waitFor("the line before the Take") { inTop(hasText(before, substring = true)) }
        tap(hasText(s(R.string.today_take_now)) and hasClickAction())
        waitFor("the line after the Take") { inTop(hasText(line(1, 1, s(R.string.today_progress_done)))) }
    }

    @Test fun nothing_planned_draws_no_line() {
        h.medicine("Levotiroxina", stock = 10.0, unit = "compresse")
        host { EmivitaNavHost(startPage = StartPage.TODAY) }
        waitFor("Today with the medicine") { inTop(hasText("Levotiroxina")) }
        assertFalse(inTop(hasTestTag(TODAY_PROGRESS_TAG)))
    }
}
