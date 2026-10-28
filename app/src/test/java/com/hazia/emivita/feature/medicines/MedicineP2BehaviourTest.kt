package com.hazia.emivita.feature.medicines

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.ZoneOffset

/**
 * The owner's P2 picks on the Medicines side (§7 R127): the tags sheet (C-31 V1), pause all with
 * Riattiva (C-29 V2), swipe to delete (D-03 V0) and "Aggiungi al calendario" (C-20), each as on the
 * phone. The C-29 cases are the scoring rig's `P2ShipFixesTest` ones.
 */
class MedicineP2BehaviourTest : MedicineFormTestBase() {

    private val repo get() = h.container.medicineRepository
    private fun allTagNames() = runBlocking { repo.observeAllTags().first().map { it.name } }

    // ---- C-31 V1 ----------------------------------------------------------------------------------

    /**
     * Metformina's edit form with its tags sheet open: Metformina has Mattina and Sera, Levotiroxina
     * has Sera too, and Vecchio is on no medicine.
     */
    private fun tagsSheet() {
        val levo = h.medicine("Levotiroxina", stock = 30.0, unit = "compresse")
        metformina = h.medicine("Metformina", stock = 30.0, unit = "compresse")
        runBlocking {
            repo.addTag(metformina, "Mattina"); repo.addTag(metformina, "Sera")
            repo.addTag(levo, "Sera")
            repo.addTag(levo, "Vecchio"); repo.removeTag(levo, repo.observeAllTags().first().single { it.name == "Vecchio" }.id)
        }
        hostApp(); openMedicine()
        tap(described(s(R.string.medicine_detail_edit_medicine)))
        waitFor("the edit form") { inTop(button(s(R.string.common_save))) }
        waitFor("the tags") { inTop(button(s(R.string.add_medicine_manage_tags))) }
        tap(button(s(R.string.add_medicine_manage_tags)))
        waitFor("the tags sheet") { windows() > 1 && inTop(button(s(R.string.common_save))) }
    }

    private fun bin(tag: String) = described(s(R.string.tag_delete_a11y, tag))
    private fun asked(tag: String) = inTop(hasText(s(R.string.tag_delete_title, tag)))

    @Test fun control_a_tag_no_other_medicine_has_goes_at_once() {
        tagsSheet()
        tap(bin("Vecchio"))
        assertFalse("no question", asked("Vecchio"))
        assertFalse("gone from the sheet", inTop(bin("Vecchio")))
        tap(bin("Mattina")) // on this medicine only
        assertFalse("its own tag does not ask either", asked("Mattina"))
    }

    @Test fun a_tag_another_medicine_has_asks_first() {
        tagsSheet()
        tap(bin("Sera"))
        assertTrue("Levotiroxina has Sera: the question", asked("Sera"))
        val check = rules.dialogPair(s(R.string.common_confirm), s(R.string.common_cancel))
        println("DIALOG-PAIR tag delete: $check")
        assertTrue(check.toString(), check.pass)
    }

    @Test fun deletions_and_renames_apply_only_on_the_sheet_salva() {
        tagsSheet()
        tap(bin("Vecchio"))
        typeInto(hasText("Mattina") and androidx.compose.ui.test.hasSetTextAction(), "Mattino")
        assertTrue("nothing stored before Salva", "Vecchio" in allTagNames() && "Mattina" in allTagNames())
        tapNode(top(button(s(R.string.common_save))))
        waitFor("the sheet closed") { windows() == 1 }
        waitFor("stored") { "Vecchio" !in allTagNames() && "Mattino" in allTagNames() }
        assertFalse("Mattina is renamed, not added", "Mattina" in allTagNames())
        assertTrue("the form's chip has the new name", inTop(toggle and hasText("Mattino")))
    }

    @Test fun annulla_drops_the_sheet_edits() {
        tagsSheet()
        tap(bin("Vecchio"))
        tapNode(top(button(s(R.string.common_cancel))))
        if (inTop(hasText(s(R.string.unsaved_title)))) tapNode(top(button(s(R.string.unsaved_discard))))
        waitFor("the sheet closed") { windows() == 1 }
        assertTrue("Vecchio" in allTagNames())
    }

    // ---- C-29 V2 ----------------------------------------------------------------------------------

    private class Paused(val levo: Long, val metf: Long, val vitd: Long)

    private fun enabled(id: Long) = runBlocking { checkNotNull(h.container.reminderRepository.getById(id)).enabled }

    /** Levotiroxina 08:00, Metformina 20:00, Vitamina D 09:00 paused on purpose; then pause-all from the menu. */
    private fun pauseAll(): Paused {
        val rl = h.reminder(h.medicine("Levotiroxina"), ReminderConfig.FixedTime(8, 0, 1.0))
        val rm = h.reminder(h.medicine("Metformina"), ReminderConfig.FixedTime(20, 0, 1.0))
        val rv = h.reminder(h.medicine("Vitamina D"), ReminderConfig.FixedTime(9, 0, 1.0))
        runBlocking { h.container.reminderRepository.setEnabled(checkNotNull(h.container.reminderRepository.getById(rv)), false) }
        hostApp()
        waitFor("the list") { inTop(button("Metformina")) }
        tap(described(s(R.string.medicine_detail_more)))
        tapNode(top(button(s(R.string.medicine_detail_pause_all))))
        waitFor("every reminder paused") { !enabled(rl) && !enabled(rm) }
        waitFor("the Riattiva line") { inTop(button(s(R.string.medicines_resume))) }
        return Paused(rl, rm, rv)
    }

    private fun riattiva(p: Paused) {
        tap(button(s(R.string.medicines_resume)))
        waitFor("Levotiroxina and Metformina back on") { enabled(p.levo) && enabled(p.metf) }
        repeat(4) { settle() }
    }

    @Test fun c29_control_riattiva_resumes_what_pause_all_paused() {
        val p = pauseAll()
        assertTrue(inTop(hasText(s(R.string.medicines_all_paused))))
        riattiva(p)
        assertTrue(enabled(p.levo)); assertTrue(enabled(p.metf))
        assertEquals("Levotiroxina's alarm armed again", 1, h.regularAlarms(p.levo).size)
        assertFalse("the line goes", inTop(button(s(R.string.medicines_resume))))
    }

    @Test fun c29_riattiva_keeps_a_reminder_paused_on_purpose() {
        val p = pauseAll()
        riattiva(p)
        assertFalse("Vitamina D was paused on purpose before pause-all, and Riattiva switched it back on", enabled(p.vitd))
        assertEquals("Vitamina D's alarm stays unarmed", 0, h.regularAlarms(p.vitd).size)
    }

    @Test fun c29_the_menu_resume_all_clears_the_record() {
        val p = pauseAll()
        tap(described(s(R.string.medicine_detail_more)))
        tapNode(top(button(s(R.string.medicine_detail_resume_all))))
        waitFor("every reminder back on") { enabled(p.levo) && enabled(p.metf) && enabled(p.vitd) }
        waitFor("the record cleared") { runBlocking { h.container.settingsRepository.pausedByPauseAll.first() }.isEmpty() }
    }

    // ---- D-03 V0 ----------------------------------------------------------------------------------

    private fun swipe(name: String) {
        top(button(name)).performTouchInput { swipeLeft() }
        settle()
    }

    @Test fun a_swipe_asks_and_cancel_snaps_back() {
        seed(); hostApp()
        waitFor("the list") { inTop(button("Metformina")) }
        swipe("Metformina")
        waitFor("the question") { inTop(hasText(s(R.string.medicine_detail_delete_title, "Metformina"))) }
        val check = rules.dialogPair(s(R.string.common_confirm), s(R.string.common_cancel))
        println("DIALOG-PAIR swipe delete: $check")
        assertTrue(check.toString(), check.pass)
        tapNode(top(button(s(R.string.common_cancel))))
        waitFor("the dialog closed") { windows() == 1 }
        assertFalse("not archived", h.medicineRow(metformina).archived)
        val card = top(button("Metformina")).fetchSemanticsNode().boundsInWindow
        assertTrue("the card is back in place: ${card.left}", card.left in 0f..(17f * dp))
        // Snapped back, the card swipes again and asks again (left dismissed, a second swipe does nothing).
        swipe("Metformina")
        waitFor("the question again") { inTop(hasText(s(R.string.medicine_detail_delete_title, "Metformina"))) }
    }

    @Test fun a_swipe_then_elimina_archives() {
        seed(); hostApp()
        waitFor("the list") { inTop(button("Metformina")) }
        swipe("Metformina")
        waitFor("the question") { inTop(hasText(s(R.string.medicine_detail_delete_title, "Metformina"))) }
        tapNode(top(button(s(R.string.common_confirm))))
        waitFor("archived") { h.medicineRow(metformina).archived }
        waitFor("gone from the list") { !inTop(button("Metformina")) }
        assertTrue("Levotiroxina stays", inTop(button("Levotiroxina")))
    }

    // ---- C-20 -------------------------------------------------------------------------------------

    @Test fun add_to_calendar_starts_an_all_day_insert_with_the_full_name() {
        runBlocking { h.container.settingsRepository.setHideMedicineNames(true) }
        // Five tablets at one a day: a run-out date inside the projection's horizon.
        h.medicine("Levotiroxina", stock = 30.0, unit = "compresse")
        metformina = h.medicine("Metformina", stock = 5.0, unit = "compresse")
        h.reminder(metformina, ReminderConfig.FixedTime(8, 0, 1.0))
        hostApp(); openMedicine()
        tap(described(s(R.string.medicine_detail_more)))
        waitFor("the menu") { inTop(hasText(s(R.string.common_stock_expiration)) and hasClickAction()) }
        tapNode(top(hasText(s(R.string.common_stock_expiration)) and hasClickAction()))
        waitFor("the calendar button") { inTop(button(s(R.string.stock_add_to_calendar))) }
        val runOut = h.container.occurrenceProjectionCache.state.value.stockRunOutDates.getValue(metformina)
        tap(button(s(R.string.stock_add_to_calendar)))
        val intent = checkNotNull(shadowOf(compose.activity).nextStartedActivity) { "no activity started" }
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("the full name, hide-names on", s(R.string.stock_calendar_title, "Metformina"), intent.getStringExtra(CalendarContract.Events.TITLE))
        assertTrue("all day", intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
        val day = Instant.ofEpochMilli(intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1)).atZone(ZoneOffset.UTC).toLocalDate()
        assertEquals("on the run-out date", runOut, day)
    }
}
