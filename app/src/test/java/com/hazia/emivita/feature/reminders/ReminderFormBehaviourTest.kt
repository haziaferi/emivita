package com.hazia.emivita.feature.reminders

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.entity.RepeatConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the reminder form does with what is typed and tapped (§7 R124). */
class ReminderFormBehaviourTest : ReminderFormTestBase() {

    private val save get() = button(s(R.string.common_save))
    private val cancel get() = button(s(R.string.common_cancel))
    private fun said(text: String) = inTop(hasText(text))
    private fun bottom(m: SemanticsMatcher) = top(m).fetchSemanticsNode().boundsInWindow.bottom
    private fun topOf(m: SemanticsMatcher) = top(m).fetchSemanticsNode().boundsInWindow.top

    // ---- "repeat every 0 min" ------------------------------------------------------------------

    /**
     * Typing 0 in "Ogni (min)" used to save every 0 minutes without a word. Now the field says
     * "Almeno 1 minuto", the sheet's Salva is off, Back asks, and the stored value stays 30; the
     * error moves nothing, and neither does turning the switch off.
     */
    @Test fun zero_minutes_is_refused_and_never_stored() {
        reminderForm()
        openGroup(R.string.reminder_group_unanswered)
        val every = field(s(R.string.add_reminder_every_min))
        val repeat = toggle and hasText(s(R.string.add_reminder_repeat_until_answered))
        val foot0 = bottom(save); val switch0 = topOf(repeat)
        typeInto(every, "0")
        val errorSaid = said(s(R.string.form_min_one_minute))
        val saveOff = disabled(save)
        val foot1 = bottom(save)
        tap(repeat)
        val foot2 = bottom(save); val switch2 = topOf(repeat)
        tap(repeat)
        // The person presses the sheet's Salva anyway (a touch, which a disabled button ignores), then leaves with Back.
        top(save).performClick(); settle()
        var asked = false
        if (windows() > 1) {
            pressBack()
            asked = said(s(R.string.unsaved_title))
            if (asked) tapNode(top(button(s(R.string.unsaved_discard))))
            waitFor("the sheet closed") { windows() == 1 }
        }
        saveForm()
        assertEquals("nothing saved silently: the default stays", 30, stored().repeatOnUnanswered?.delayMinutes)
        assertTrue("the field says it", errorSaid)
        assertTrue("the sheet's Salva is off", saveOff)
        assertTrue("Back with edits asks", asked)
        assertEquals("the error moves nothing below", foot0, foot1, 0.5f)
        assertEquals("the foot stays where it was with the switch off", foot0, foot2, 0.5f)
        assertEquals("the tapped switch never moves", switch0, switch2, 0.5f)
    }

    // ---- typed times and dates ------------------------------------------------------------------

    @Test fun a_typed_time_is_read_and_a_bad_one_refused() {
        reminderForm()
        val time = field(s(R.string.add_reminder_time))
        typeInto(time, "25:00")
        assertTrue(said(s(R.string.form_time_invalid)))
        assertTrue("Salva stays off on a bad time", disabled(save))
        typeInto(time, "7.30")
        assertFalse(said(s(R.string.form_time_invalid)))
        assertFalse(disabled(save))
        saveForm()
        val config = h.container.reminderRepository.decodeConfig(stored()) as ReminderConfig.FixedTime
        assertEquals(7 to 30, config.hour to config.minute)
    }

    @Test fun typed_dates_are_read_and_bad_ones_refused() {
        reminderForm()
        openGroup(R.string.reminder_group_duration)
        val from = field(s(R.string.add_reminder_from))
        val until = field(s(R.string.add_reminder_until))
        typeInto(from, "31/02/2026")
        assertTrue("no 31 February", said(s(R.string.form_date_invalid)))
        assertTrue(disabled(save))
        typeInto(from, "01/03/2026")
        typeInto(until, "01/02/2026")
        assertTrue("an end before the start is said", said(s(R.string.form_until_before_from)))
        assertTrue(disabled(save))
        typeInto(until, "31/03/2026")
        assertFalse(disabled(save))
        tapNode(top(save))
        waitFor("the sheet closed") { windows() == 1 }
        saveForm()
        val r = stored()
        assertEquals(java.time.LocalDate.of(2026, 3, 1).toEpochDay(), r.activeFromEpochDay)
        assertEquals(java.time.LocalDate.of(2026, 3, 31).toEpochDay(), r.activeUntilEpochDay)
    }

    // ---- the sheets save only on their Salva ------------------------------------------------------

    @Test fun a_sheet_applies_only_on_its_salva() {
        reminderForm()
        val weekend = toggle and hasText(s(R.string.add_reminder_follow_weekend_mode))
        val summary = s(R.string.reminder_sum_weekend)
        openGroup(R.string.reminder_group_notification)
        tap(weekend)
        tapNode(top(cancel))
        assertTrue("Annulla with an edit asks", said(s(R.string.unsaved_title)))
        tapNode(top(button(s(R.string.unsaved_discard))))
        waitFor("the sheet closed") { windows() == 1 }
        assertFalse("Annulla dropped it", inTop(hasText(summary, substring = true)))
        openGroup(R.string.reminder_group_notification)
        tap(weekend)
        tapNode(top(save))
        waitFor("the sheet closed") { windows() == 1 }
        assertTrue("Salva applied it: the row says so", inTop(group(R.string.reminder_group_notification) and hasText(summary, substring = true)))
        saveForm()
        assertTrue(stored().weekendModeOptIn)
    }

    // ---- Back with edits asks ---------------------------------------------------------------------

    @Test fun back_from_an_edited_form_asks() {
        reminderForm()
        typeInto(field(s(R.string.common_dose_amount)), "2")
        pressBack()
        assertTrue(said(s(R.string.unsaved_title)))
        tapNode(top(button(s(R.string.unsaved_keep))))
        assertTrue("kept: still on the form", inTop(save))
    }

    // ---- the rows say the stored values ---------------------------------------------------------------

    @Test fun the_avanzate_rows_say_what_is_stored() {
        seed()
        val id = h.reminder(metformina, ReminderConfig.FixedTime(8, 0, 1.0), repeat = RepeatConfig(delayMinutes = 10, maxRepeats = 2),
            preAlarmMinutes = 15, sticky = true, variableAmount = true)
        runBlocking {
            val r = h.container.reminderRepository.getById(id)!!
            h.container.reminderRepository.updateAdvancedFields(r, autoResolve = true, activeFromEpochDay = null, activeUntilEpochDay = null,
                repeatOnUnanswered = r.repeatOnUnanswered, weekendModeOptIn = true, preAlarmMinutes = 15, instructions = "Con acqua", dosesRemaining = 6,
                stickyUntilAnswered = true, variableAmount = true)
        }
        hostApp(); openMedicine()
        tap(hasText("08:00", substring = true) and hasClickAction())
        waitFor("the edit form") { inTop(button(s(R.string.common_save))) }
        fun row(g: Int, vararg parts: String) = assertTrue("${s(g)}: ${parts.toList()}", inTop(parts.fold(group(g)) { m, p -> m and hasText(p, substring = true) }))
        row(R.string.reminder_group_taking, s(R.string.reminder_sum_ask_amount), "Con acqua")
        row(R.string.reminder_group_notification, s(R.string.reminder_sum_weekend), s(R.string.add_reminder_auto_resolve))
        row(R.string.reminder_group_unanswered, s(R.string.reminder_sum_repeat, 10, 2), s(R.string.reminder_sum_sticky), s(R.string.reminder_sum_prealarm, 15))
        row(R.string.reminder_group_duration, s(R.string.reminder_sum_doses, 6))
    }

    // ---- the cycle's pause days -----------------------------------------------------------------------

    /** The scheduler accepts a cycle with 0 pause days (isActiveOn requires pauseDays >= 0); the form agrees. */
    @Test fun zero_pause_days_is_a_cycle_the_form_accepts() {
        reminderForm()
        tap(button(s(R.string.add_reminder_active_pause_cycle)))
        val pause = field(s(R.string.add_reminder_pause_days))
        typeInto(pause, "-1")
        assertTrue(said(s(R.string.form_min_zero)))
        assertTrue(disabled(save))
        typeInto(pause, "0")
        assertFalse(said(s(R.string.form_min_zero)))
        assertFalse(said(s(R.string.form_min_one)))
        assertFalse(disabled(save))
        saveForm()
        val pattern = (h.container.reminderRepository.decodeConfig(stored()) as ReminderConfig.FixedTime).repeatPattern as RepeatPattern.CyclicActivePause
        assertEquals(0, pattern.pauseDays)
    }
}
