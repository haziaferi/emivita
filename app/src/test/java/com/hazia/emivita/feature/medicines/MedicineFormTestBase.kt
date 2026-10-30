package com.hazia.emivita.feature.medicines

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.ui.form.FormScreenTestBase
import kotlinx.coroutines.runBlocking

/** The medicine forms and the screens PR 2 moved onto the shared parts, reached as on the phone (§7 R125). */
abstract class MedicineFormTestBase : FormScreenTestBase() {

    protected var metformina = 0L

    protected fun seed() {
        h.medicine("Levotiroxina", stock = 30.0, unit = "compresse")
        metformina = h.medicine("Metformina", stock = 30.0, unit = "compresse")
        runBlocking {
            h.container.medicineRepository.addTag(metformina, "Mattina")
            h.container.medicineRepository.addTag(metformina, "Sera")
        }
    }

    /** The full add form, as the barcode path opens it (it has no route from the list without a scan). */
    protected fun addForm() {
        seed()
        host { AddMedicineScreen(profileId = h.profileId, medicineRepository = h.container.medicineRepository, onSaved = {}, onCancel = {}) }
        waitFor("the add form") { inTop(button(s(R.string.common_save))) }
    }

    protected fun openMedicine() {
        waitFor("Metformina in the list") { inTop(button("Metformina")) }
        tap(button("Metformina"))
        waitFor("the medicine's page") { inTop(described(s(R.string.medicine_detail_edit_medicine))) }
    }

    protected fun editForm() {
        seed(); hostApp(); openMedicine()
        tap(described(s(R.string.medicine_detail_edit_medicine)))
        waitFor("the edit form") { inTop(button(s(R.string.common_save))) }
    }

    protected fun quickAdd() {
        seed(); hostApp()
        tap(described(s(R.string.medicines_add_medicine)))
        waitFor("the quick-add sheet") { windows() > 1 && inTop(button(s(R.string.common_save))) }
    }

    protected fun stock() {
        seed(); hostApp(); openMedicine()
        tap(described(s(R.string.medicine_detail_more)))
        waitFor("the menu") { inTop(hasText(s(R.string.common_stock_expiration)) and hasClickAction()) }
        tapNode(top(hasText(s(R.string.common_stock_expiration)) and hasClickAction()))
        waitFor("the stock screen") { inTop(button(s(R.string.common_save))) && inTop(hasText(s(R.string.stock_section_current))) }
    }

    private fun settingsRow(row: Int) {
        seed(); hostApp()
        tap(hasText(s(R.string.nav_settings)) and hasClickAction())
        // The list is lazy: a row below the fold is not composed until revealed (360 dp at font 2.0, §7 R129).
        waitFor("Settings") { inTop(hasText(s(R.string.settings_row_alarms)) and hasClickAction()) }
        tap(hasText(s(row)) and hasClickAction())
    }

    protected fun medicalId() {
        settingsRow(R.string.settings_row_medical_id)
        waitFor("Medical ID") { inTop(button(s(R.string.common_save))) && inTop(hasText(s(R.string.medical_id_blood_type))) }
    }

    protected fun alarmsSnooze() {
        settingsRow(R.string.settings_row_alarms)
        waitFor("Alarms & snooze") { windows() > 1 && inTop(button(s(R.string.common_save))) && inTop(hasText(s(R.string.alarms_group_snooze))) }
    }

    /** Alarms & snooze's Rinvio group, its own sheet over the main one (§7 R130), where the length is typed. */
    protected fun snoozeGroup() {
        tap(hasText(s(R.string.alarms_group_snooze)) and hasClickAction())
        waitFor("the Rinvio sheet") { windows() > 2 && inTop(hasText(s(R.string.settings_detail_snooze_duration_minutes))) }
    }

    protected fun weekendMode() {
        settingsRow(R.string.settings_row_weekend)
        waitFor("Weekend mode") { windows() > 1 && inTop(button(s(R.string.common_save))) && inTop(hasText(s(R.string.settings_detail_days))) }
    }

    protected fun group(id: Int) = hasText(s(id), substring = true) and hasClickAction()

    protected fun openGroup(id: Int) {
        tap(group(id))
        waitFor("the group's sheet") { windows() > 1 && inTop(button(s(R.string.common_save))) }
    }
}
