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
        val max = field(s(R.string.add_medicine_prn_max))
        // Measured from the field to the next row, not in the window: typing scrolls the field
        // into view, by an amount that depends on timing (CI moved the page 50 dp), and a
        // scroll moves both alike while an error that pushed content down would not.
        fun gap() = bounds(next).top - bounds(max).top
        reveal(next)
        reveal(max)
        val before = gap()
        typeInto(max, "3")
        val broken = said(both) && !said(help)
        reveal(next)
        val after = gap()
        typeInto(field(s(R.string.add_medicine_prn_hours)), "24")
        val fixed = said(help) && !said(both)
        assertTrue("at rest the help, no rule", atRest)
        assertTrue("half a limit: the error in the help's place", broken)
        assertTrue("both filled: the help again", fixed)
        assertEquals("the error moves nothing below", before, after, 0.5f)
    }

    private fun bounds(m: androidx.compose.ui.test.SemanticsMatcher) = top(m).fetchSemanticsNode().boundsInWindow

    /**
     * "Grow as you type" keeps the anchored foot (§7 R126): on Medical ID the allergies grow from
     * one line to three below their own top, Salva and the field's top unmoved; the field is taller.
     */
    @Test fun a_growing_field_moves_neither_the_foot_nor_itself_on_a_page() {
        medicalId()
        val allergies = field(s(R.string.medical_id_allergies))
        val save = button(s(R.string.common_save))
        reveal(allergies)
        val field0 = bounds(allergies); val foot0 = bounds(save).bottom
        typeInto(allergies, "Penicillina\nLattosio\nNichel")
        val field1 = bounds(allergies); val foot1 = bounds(save).bottom
        assertTrue("the field grew: ${field0.height} -> ${field1.height} px", field1.height >= field0.height + 30 * dp)
        assertEquals("the field's top stays", field0.top, field1.top, 0.5f)
        assertEquals("the foot stays", foot0, foot1, 0.5f)
    }

    /**
     * In a sheet, which grows upward only (§7 R124 C5), the notes grow up from their last line: the
     * foot and the line being typed (the field's bottom) stay where they were.
     */
    @Test fun a_growing_field_moves_neither_the_foot_nor_its_typed_line_in_a_sheet() {
        addForm()
        openGroup(R.string.medicine_group_details)
        val notes = field(s(R.string.add_medicine_notes_optional))
        val save = button(s(R.string.common_save))
        val field0 = bounds(notes); val foot0 = bounds(save).bottom
        typeInto(notes, "Dopo i pasti\nNon con il latte\nTenere al fresco")
        val field1 = bounds(notes); val foot1 = bounds(save).bottom
        assertTrue("the field grew: ${field0.height} -> ${field1.height} px", field1.height >= field0.height + 30 * dp)
        assertEquals("the line being typed stays", field0.bottom, field1.bottom, 0.5f)
        assertEquals("the foot stays", foot0, foot1, 0.5f)
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
