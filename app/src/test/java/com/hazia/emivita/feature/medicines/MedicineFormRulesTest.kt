package com.hazia.emivita.feature.medicines

import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.ui.form.FormDecl
import com.hazia.emivita.ui.form.FormRules
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The owner's rules C1-C10 (§7 R124) as a gate on every surface forms PR 2 moved onto the shared
 * parts (§7 R125), at font 1.0 and 2.0: the medicine form (add, edit, its Notifica and Dettagli
 * sheets), the quick-add sheet, Stock & expiry, Medical ID, Alarms & snooze and Weekend mode; C4
 * across the medicine form and its sheets.
 */
class MedicineFormRulesTest : MedicineFormTestBase() {

    private fun at(fs: Float) { fontScale.floatValue = fs }

    private fun gate(surface: String, fs: Float, audit: FormRules.Audit) {
        val report = "$surface fs$fs\n$audit"
        println("FORM-RULES $report")
        assertTrue(report, audit.failed.isEmpty())
    }

    /**
     * What the code says (C10, C8). The dose limit applies whatever "Al bisogno" says
     * (getTakenSince counts every TAKEN dose; Log dose's headroom ignores asNeeded; §7 R116), and
     * the tags are a chip flow, ragged right by the owner's rule.
     */
    private val medicineDecl get() = FormDecl(
        independent = listOf(R.string.add_medicine_prn_max, R.string.add_medicine_prn_hours).associate { s(it) to "the limit applies without the switch (R116)" },
        flows = setOf("Mattina", "Sera"),
    )

    /** The pack's dates and the opened-on days are stored whatever the expiry alert's switch says (StockExpirationScreen's save). */
    private val stockDecl get() = FormDecl(
        independent = listOf(R.string.stock_production_date, R.string.stock_expiration_expiration_date, R.string.stock_opened_on, R.string.stock_use_within_days)
            .associate { s(it) to "stockInfo is saved whatever the alert switch says" },
    )

    /** The fields the code makes `freeText = true` (§7 R126, "Grow as you type"): Medical ID's allergies and the doctor's address. */
    private val medicalDecl get() = FormDecl(freeText = setOf(s(R.string.medical_id_allergies), s(R.string.medical_id_doctor_address)))

    /** The Dettagli sheet's notes, free text (§7 R126). */
    private val detailsDecl get() = FormDecl(freeText = setOf(s(R.string.add_medicine_notes_optional)))

    private val threeLines = "Penicillina\nLattosio\nNichel"

    /** A name typed, as in the approved mockups: with none, Salva is off and C5 reads its disabled grey. */
    private fun named() = typeInto(field(s(R.string.common_name)), "Prova")

    private fun add(fs: Float) { at(fs); addForm(); named(); gate("medicine add", fs, audit(sheet = false, decl = medicineDecl)) }
    private fun edit(fs: Float) { at(fs); editForm(); gate("medicine edit", fs, audit(sheet = false, decl = medicineDecl)) }
    private fun sheet(fs: Float, group: Int) {
        at(fs); addForm(); named(); openGroup(group)
        gate("medicine sheet ${s(group)}", fs, audit(sheet = true, decl = if (group == R.string.medicine_group_details) detailsDecl else FormDecl()))
    }
    private fun quick(fs: Float) { at(fs); quickAdd(); named(); gate("quick-add", fs, audit(sheet = true)) }
    private fun stockScreen(fs: Float) { at(fs); stock(); gate("stock", fs, audit(sheet = false, decl = stockDecl)) }
    private fun medical(fs: Float) { at(fs); medicalId(); gate("medical id", fs, audit(sheet = false, decl = medicalDecl)) }
    private fun alarms(fs: Float) { at(fs); alarmsSnooze(); gate("alarms & snooze", fs, audit(sheet = true)) }
    private fun weekend(fs: Float) { at(fs); weekendMode(); gate("weekend mode", fs, audit(sheet = true)) }

    /** §7 R127 (C-31): the tags sheet over the edit form, one field and its bin per tag. */
    private fun tagsSheet(fs: Float) {
        at(fs); editForm()
        tap(button(s(R.string.add_medicine_manage_tags)))
        waitFor("the tags sheet") { windows() > 1 && inTop(button(s(R.string.common_save))) }
        gate("tags sheet", fs, audit(sheet = true))
    }

    /** §7 R127 (C-20): Stock & expiry with a run-out date, so "Aggiungi al calendario" shows. */
    private fun stockWithCalendar(fs: Float) {
        at(fs)
        h.medicine("Levotiroxina", stock = 30.0, unit = "compresse")
        metformina = h.medicine("Metformina", stock = 5.0, unit = "compresse")
        h.reminder(metformina, com.hazia.emivita.core.data.config.ReminderConfig.FixedTime(8, 0, 1.0))
        hostApp(); openMedicine()
        tap(described(s(R.string.medicine_detail_more)))
        waitFor("the menu") { inTop(hasText(s(R.string.common_stock_expiration)) and androidx.compose.ui.test.hasClickAction()) }
        tapNode(top(hasText(s(R.string.common_stock_expiration)) and androidx.compose.ui.test.hasClickAction()))
        waitFor("the calendar button") { inTop(button(s(R.string.stock_add_to_calendar))) }
        gate("stock with the calendar button", fs, audit(sheet = false, decl = stockDecl))
    }

    /**
     * §7 R127 (C-29): the Medicines list while every reminder is paused. A list, not a form: it has no
     * Salva/Annulla foot, so C5 does not apply; and the tab header's icons and the add button float
     * over the edges by the header's own rule (§9 #33), so C1 skips the rows they start. Every other
     * check, and C1 on every other row, the paused line's included, must pass.
     */
    private fun listPaused(fs: Float) {
        at(fs)
        val rl = h.reminder(h.medicine("Levotiroxina"), com.hazia.emivita.core.data.config.ReminderConfig.FixedTime(8, 0, 1.0))
        val rm = h.reminder(h.medicine("Metformina"), com.hazia.emivita.core.data.config.ReminderConfig.FixedTime(20, 0, 1.0))
        kotlinx.coroutines.runBlocking { for (id in listOf(rl, rm)) h.container.reminderRepository.run { setEnabled(getById(id)!!, false) } }
        hostApp()
        waitFor("the paused line") { inTop(button(s(R.string.medicines_resume))) }
        val a = audit(sheet = false)
        val report = "medicines list paused fs$fs\n$a"
        println("FORM-RULES $report")
        val header = listOf(s(R.string.medicines_medicines), s(R.string.medicines_add_medicine)).map { "'$it'" }
        val left = a.checks.filter { !it.name.startsWith("C5") }.flatMap { c ->
            c.violations.filter { v -> !(c.name.startsWith("C1") && header.any { v.contains(it) }) }.map { "${c.name}: $it" }
        }
        assertTrue(report, left.isEmpty())
        assertTrue("the paused line is measured", a.controls.any { it.label == s(R.string.medicines_resume) })
    }

    @Test fun tags_sheet_fs10() = tagsSheet(1f)
    @Test @Config(fontScale = 2f) fun tags_sheet_fs20() = tagsSheet(2f)
    @Test fun stock_calendar_fs10() = stockWithCalendar(1f)
    @Test @Config(fontScale = 2f) fun stock_calendar_fs20() = stockWithCalendar(2f)
    @Test fun list_paused_fs10() = listPaused(1f)
    @Test @Config(fontScale = 2f) fun list_paused_fs20() = listPaused(2f)
    @Test fun add_fs10() = add(1f)
    @Test @Config(fontScale = 2f) fun add_fs20() = add(2f)
    @Test fun edit_fs10() = edit(1f)
    @Test @Config(fontScale = 2f) fun edit_fs20() = edit(2f)
    @Test fun sheet_notification_fs10() = sheet(1f, R.string.reminder_group_notification)
    @Test @Config(fontScale = 2f) fun sheet_notification_fs20() = sheet(2f, R.string.reminder_group_notification)
    @Test fun sheet_details_fs10() = sheet(1f, R.string.medicine_group_details)
    @Test @Config(fontScale = 2f) fun sheet_details_fs20() = sheet(2f, R.string.medicine_group_details)
    @Test fun quick_add_fs10() = quick(1f)
    @Test @Config(fontScale = 2f) fun quick_add_fs20() = quick(2f)
    @Test fun stock_fs10() = stockScreen(1f)
    @Test @Config(fontScale = 2f) fun stock_fs20() = stockScreen(2f)
    @Test fun medical_id_fs10() = medical(1f)
    @Test @Config(fontScale = 2f) fun medical_id_fs20() = medical(2f)
    @Test fun alarms_snooze_fs10() = alarms(1f)
    @Test @Config(fontScale = 2f) fun alarms_snooze_fs20() = alarms(2f)
    @Test fun weekend_mode_fs10() = weekend(1f)
    @Test @Config(fontScale = 2f) fun weekend_mode_fs20() = weekend(2f)

    /** "Grow as you type" (§7 R126): the allergies and the address at three lines each, every check still passing. */
    @Test fun medical_id_grown_fs10() {
        medicalId()
        typeInto(field(s(R.string.medical_id_allergies)), threeLines)
        typeInto(field(s(R.string.medical_id_doctor_address)), "Via Roma 1\n00100 Roma\nScala B")
        gate("medical id, two fields at 3 lines", 1f, audit(sheet = false, decl = medicalDecl))
    }

    /** The Dettagli sheet's notes at three lines (§7 R126). */
    @Test fun sheet_details_grown_fs10() {
        addForm(); named(); openGroup(R.string.medicine_group_details)
        typeInto(field(s(R.string.add_medicine_notes_optional)), threeLines)
        gate("medicine sheet Dettagli, notes at 3 lines", 1f, audit(sheet = true, decl = detailsDecl))
    }

    /** C4: the medicine form at rest and its two sheets, one look per kind of control. */
    @Test fun consistency_fs10() {
        addForm(); named()
        val surfaces = linkedMapOf("add" to audit(sheet = false, decl = medicineDecl))
        for (g in listOf(R.string.reminder_group_notification, R.string.medicine_group_details)) {
            openGroup(g)
            surfaces["sheet ${s(g)}"] = audit(sheet = true)
            tapNode(top(button(s(R.string.common_cancel))))
            waitFor("the sheet closed") { windows() == 1 }
        }
        val c4 = rules.consistency(surfaces)
        println("FORM-RULES $c4")
        assertTrue(c4.toString(), c4.pass)
    }

    /** The weekdays on the shared grid (C6), turned on so a cell is drawn in both states. */
    @Test fun weekend_days_on_fs10() {
        weekendMode()
        tap(toggle and hasText(java.time.DayOfWeek.SATURDAY.getDisplayName(java.time.format.TextStyle.SHORT, res.configuration.locales[0])))
        gate("weekend mode, Saturday on", 1f, audit(sheet = true))
    }
}
