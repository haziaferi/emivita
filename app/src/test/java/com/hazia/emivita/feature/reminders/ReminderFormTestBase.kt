package com.hazia.emivita.feature.reminders

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.format.labelRes
import com.hazia.emivita.ui.form.FormScreenTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** The reminder form reached as on the phone: the Medicines list, the medicine's page, Add reminder, a type. */
abstract class ReminderFormTestBase : FormScreenTestBase() {

    protected var metformina = 0L

    protected fun seed(withSibling: Boolean = false) {
        h.medicine("Levotiroxina", stock = 30.0, unit = "compresse")
        metformina = h.medicine("Metformina", stock = 30.0, unit = "compresse")
        if (withSibling) h.reminder(metformina, ReminderConfig.FixedTime(20, 0, 1.0))
    }

    protected fun openMedicine() {
        // The list loads its rows from Room: wait for the row before revealing it (slow CI, as R127's edit form).
        waitFor("the medicine row") { inTop(button("Metformina")) }
        tap(button("Metformina"))
        waitFor("the medicine's page") { inTop(described(s(R.string.medicine_detail_edit_medicine))) }
    }

    protected fun reminderForm(type: ReminderType = ReminderType.FIXED_TIME, withSibling: Boolean = type == ReminderType.LINKED_FOLLOWING) {
        seed(withSibling)
        hostApp()
        openMedicine()
        tap(described(s(R.string.common_add_reminder)))
        val label = s(type.labelRes())
        waitFor("the type picker") { inTop(button(label)) }
        tap(button(label))
        waitFor("the form") { inTop(button(s(R.string.common_save))) }
        if (type == ReminderType.LINKED_FOLLOWING) waitFor("the parent field") { inTop(hasText(s(R.string.add_reminder_follows))) }
    }

    protected fun group(id: Int) = hasText(s(id), substring = true) and hasClickAction()

    protected fun openGroup(id: Int) {
        tap(group(id))
        waitFor("the group's sheet") { windows() > 1 && inTop(button(s(R.string.common_save))) }
    }

    /** Salva on the form (reveals it first), then back on the medicine's page. */
    protected fun saveForm() {
        tap(button(s(R.string.common_save)))
        waitFor("the medicine's page") { inTop(described(s(R.string.medicine_detail_edit_medicine))) }
    }

    protected fun stored(): Reminder = runBlocking { h.container.reminderRepository.observeForMedicine(metformina).first().single() }
}
