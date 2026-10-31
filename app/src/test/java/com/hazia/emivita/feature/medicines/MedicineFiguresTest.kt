package com.hazia.emivita.feature.medicines

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.format.LocaleFormats
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The medicine page's figures (§7 R135, the owner's pick, "One-row strip"): History's counts for this medicine
 * over 30 days, its last Taken, and the day strip short enough that the reminders stay on the first screen.
 */
class MedicineFiguresTest : MedicineFormTestBase() {

    private val zone = ZoneId.systemDefault()

    private fun at(daysAgo: Long) = LocalDate.now(zone).minusDays(daysAgo).atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()

    @Test fun the_figures_count_and_the_reminders_stay_in_view() {
        seed()
        val reminder = h.reminder(metformina, ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0))
        val takenAt = at(2) + 3 * 60_000L
        runBlocking {
            h.container.doseEventRepository.answerProjected(reminder, metformina, at(2), 1.0, DoseStatus.TAKEN, takenAt)
            h.container.doseEventRepository.answerProjected(reminder, metformina, at(3), 1.0, DoseStatus.SKIPPED, at(3))
        }
        hostApp(); openMedicine()
        waitFor("the figures") { inTop(hasTestTag(MEDICINE_FIGURES_TAG)) }
        assertTrue("skipped counts as missed", inTop(hasText(res.getQuantityString(R.plurals.doses_taken_of, 2, 1, 2))))
        val last = s(R.string.medicine_figures_last_date, LocaleFormats.monthDay(LocalDate.now(zone).minusDays(2)), LocaleFormats.time(takenAt))
        assertTrue("no '$last'", inTop(hasText(last)))
        // One row about 20 dp tall, not the mocked five-week grid's 200 dp: the reminders keep the first screen.
        val strip = compose.onNode(hasTestTag(MEDICINE_STRIP_TAG), useUnmergedTree = true).getBoundsInRoot() // inside the merged card
        assertTrue("the strip is ${strip.height} tall", strip.height in 16.dp..24.dp)
        val row = top(hasText("08:00", substring = true)).getBoundsInRoot()
        val screen = compose.onRoot().getBoundsInRoot()
        assertTrue("the first reminder ends at ${row.bottom}, below the screen's ${screen.bottom}", row.bottom <= screen.bottom)
    }

    @Test fun nothing_to_count_draws_nothing() {
        seed(); hostApp(); openMedicine()
        assertFalse(inTop(hasTestTag(MEDICINE_FIGURES_TAG)))
    }
}
