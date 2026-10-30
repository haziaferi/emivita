package com.hazia.emivita.feature.settings

import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.feature.medicines.MedicineFormTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * The owner's answer of 2026-10-24, "Ask when edited" (§7 R126): Annulla on Alarms & snooze and
 * Weekend mode asks when the sheet's form was edited, as every other form does, and leaves at once
 * when it was not; Back does the same. Reached from the Settings list, as on the phone.
 */
class SettingsSheetAskTest : MedicineFormTestBase() {

    private fun said(text: String) = inTop(hasText(text))
    private fun snooze() = runBlocking { h.container.settingsRepository.snoozeDurationMinutes.first() }
    private fun weekendDays() = runBlocking { h.container.settingsRepository.weekendDays.first() }
    private val snoozeField get() = field(s(R.string.settings_detail_snooze_duration_minutes))

    private fun askedThenKept(leave: () -> Unit, still: () -> Boolean) {
        leave()
        waitFor("the question") { said(s(R.string.unsaved_title)) }
        tapNode(top(button(s(R.string.unsaved_keep))))
        waitFor("the question closed, the sheet back") { !said(s(R.string.unsaved_title)) && windows() > 1 && inTop(button(s(R.string.common_save))) }
        assertTrue("kept: the edit is still in the sheet", still())
    }

    private fun askedThenDiscarded(leave: () -> Unit) {
        leave()
        waitFor("the question") { said(s(R.string.unsaved_title)) }
        tapNode(top(button(s(R.string.unsaved_discard))))
        waitFor("the sheet closed") { windows() == 1 }
    }

    private val annulla: () -> Unit = { tapNode(top(button(s(R.string.common_cancel)))) }
    private val back: () -> Unit = { pressBack() }

    @Test fun alarms_annulla_and_back_ask_when_the_length_was_edited() {
        alarmsSnooze()
        val stored = snooze()
        // The length is typed in the Rinvio group and its Salva keeps it in the sheet's draft (§7 R130, "One save model").
        snoozeGroup()
        typeInto(snoozeField, "${stored + 7}")
        tapNode(top(button(s(R.string.common_save))))
        waitFor("back on the main sheet") { windows() == 2 }
        val typed = { inTop(hasText("${stored + 7} min", substring = true)) }
        askedThenKept(annulla, typed)
        askedThenKept(back, typed)
        askedThenDiscarded(annulla)
        assertEquals("discarded: nothing stored", stored, snooze())
    }

    @Test fun alarms_untouched_annulla_and_back_leave_at_once() {
        alarmsSnooze()
        annulla()
        waitFor("the sheet closed") { windows() == 1 }
        val askedOnAnnulla = said(s(R.string.unsaved_title))
        alarmsSnoozeAgain()
        back()
        waitFor("the sheet closed") { windows() == 1 }
        assertFalse("untouched, Annulla does not ask", askedOnAnnulla)
        assertFalse("untouched, Back does not ask", said(s(R.string.unsaved_title)))
    }

    @Test fun weekend_annulla_and_back_ask_when_edited() {
        weekendMode()
        val before = weekendDays()
        val day = DayOfWeek.WEDNESDAY
        val name = day.getDisplayName(TextStyle.SHORT, res.configuration.locales[0])
        tap(toggle and hasText(name))
        val on = { top(toggle and hasText(name)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.On }
        askedThenKept(back, on)
        askedThenKept(annulla, on)
        askedThenDiscarded(back)
        assertEquals("discarded: the days are as stored", before, weekendDays())
    }

    @Test fun weekend_untouched_annulla_leaves_at_once() {
        weekendMode()
        annulla()
        waitFor("the sheet closed") { windows() == 1 }
        assertFalse("untouched, Annulla does not ask", said(s(R.string.unsaved_title)))
    }

    /** The Settings list is still on screen: the same row again. */
    private fun alarmsSnoozeAgain() {
        tap(hasText(s(R.string.settings_row_alarms)) and androidx.compose.ui.test.hasClickAction())
        waitFor("Alarms & snooze") { windows() > 1 && inTop(button(s(R.string.common_save))) && inTop(hasText(s(R.string.alarms_group_snooze))) }
    }
}
