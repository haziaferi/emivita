package com.hazia.emivita.feature.medicines

import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.notification.ReminderNotificationChannels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the medicine form does with its sheets, its limit line and its delete (§7 R125). */
class MedicineFormBehaviourTest : MedicineFormTestBase() {

    private fun said(text: String, substring: Boolean = false) = inTop(hasText(text, substring = substring))
    private fun stored() = runBlocking { h.container.medicineRepository.getById(metformina)!! }

    private fun discardSheet() {
        tapNode(top(button(s(R.string.common_cancel))))
        waitFor("the question") { said(s(R.string.unsaved_title)) }
        tapNode(top(button(s(R.string.unsaved_discard))))
        waitFor("the sheet closed") { windows() == 1 }
    }

    private fun saveForm() {
        tap(button(s(R.string.common_save_changes)))
        waitFor("the medicine's page") { inTop(described(s(R.string.medicine_detail_edit_medicine))) }
    }

    /**
     * Notifica edits a draft: a tier picked and dropped with Annulla changes nothing, not even the
     * row; picked and applied with the sheet's Salva, the row says it and the form's Salva stores it.
     */
    @Test fun the_notification_sheet_applies_only_on_its_salva() {
        editForm()
        val high = s(ReminderNotificationChannels.tierNameFor(NotificationPriority.HIGH))
        openGroup(R.string.reminder_group_notification)
        tap(button(high))
        discardSheet()
        val rowAfterDrop = said(high, substring = true)
        openGroup(R.string.reminder_group_notification)
        tap(button(high))
        tapNode(top(button(s(R.string.common_save))))
        waitFor("the sheet closed") { windows() == 1 }
        val rowAfterSave = said(high, substring = true)
        saveForm()
        assertFalse("dropped with Annulla, the row still says the old tier", rowAfterDrop)
        assertTrue("applied with Salva, the row says the new tier", rowAfterSave)
        assertEquals(NotificationPriority.HIGH, stored().notificationPriority)
    }

    @Test fun the_details_sheet_applies_only_on_its_salva() {
        editForm()
        val purpose = s(R.string.add_medicine_what_it_s_for_optional)
        openGroup(R.string.medicine_group_details)
        typeInto(field(purpose), "Glicemia")
        discardSheet()
        val nothing = said(s(R.string.medicine_details_none))
        openGroup(R.string.medicine_group_details)
        typeInto(field(purpose), "Glicemia")
        tapNode(top(button(s(R.string.common_save))))
        waitFor("the sheet closed") { windows() == 1 }
        val shown = said("Glicemia", substring = true)
        saveForm()
        assertTrue("dropped with Annulla, the row still says nothing was added", nothing)
        assertTrue("applied with Salva, the row says it", shown)
        assertEquals("Glicemia", stored().indication)
    }

    /** The limit pair's one line: the help at rest, the error in its place only while broken, nothing below moving. */
    @Test fun the_limit_line_says_the_error_only_when_broken() {
        addForm()
        val help = s(R.string.add_medicine_prn_ceiling_desc)
        val both = s(R.string.add_medicine_prn_both)
        val atRest = said(help) && !said(both)
        val next = group(R.string.medicine_group_details)
        reveal(next)
        val before = top(next).fetchSemanticsNode().boundsInWindow.top
        typeInto(field(s(R.string.add_medicine_prn_max)), "3")
        val broken = said(both) && !said(help)
        val after = top(next).fetchSemanticsNode().boundsInWindow.top
        typeInto(field(s(R.string.add_medicine_prn_hours)), "24")
        val fixed = said(help) && !said(both)
        assertTrue("at rest the help, no rule", atRest)
        assertTrue("half a limit: the error in the help's place", broken)
        assertTrue("both filled: the help again", fixed)
        assertEquals("the error moves nothing below", before, after, 0.5f)
    }

    /** Delete on the edit form asks first, then archives (§9 #26) and leaves for the list. */
    @Test fun delete_on_the_edit_form_asks_then_archives() {
        editForm()
        tap(button(s(R.string.medicine_detail_delete)))
        waitFor("the question") { said(s(R.string.medicine_detail_delete_title, "Metformina")) }
        tapNode(top(button(s(R.string.common_cancel))))
        waitFor("the question closed") { windows() == 1 }
        val stillThere = runBlocking { h.container.medicineRepository.observeForProfile(h.profileId).first() }.any { it.medicine.id == metformina }
        tap(button(s(R.string.medicine_detail_delete)))
        waitFor("the question") { said(s(R.string.medicine_detail_delete_title, "Metformina")) }
        tapNode(top(button(s(R.string.medicine_detail_delete)) and hasText(s(R.string.medicine_detail_delete))))
        waitFor("the list") { inTop(described(s(R.string.medicines_add_medicine))) }
        val listed = runBlocking { h.container.medicineRepository.observeForProfile(h.profileId).first() }.any { it.medicine.id == metformina }
        assertTrue("Annulla keeps it", stillThere)
        assertFalse("Elimina archives it: gone from the list", listed)
    }
}
