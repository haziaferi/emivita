package com.hazia.emivita.feature.reminders

import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.ui.form.FormDecl
import com.hazia.emivita.ui.form.FormRules
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * The owner's absolute rules C1-C10 (§7 R124) as a gate on every surface of the reminder form,
 * at font 1.0 and 2.0: the form at rest, the weekdays, each Avanzate sheet, the interval, the
 * window, the follow-up and the edit form; C4 across the form and its sheets.
 */
class ReminderFormRulesTest : ReminderFormTestBase() {

    private fun at(fs: Float) { fontScale.floatValue = fs }

    private fun gate(surface: String, fs: Float, audit: FormRules.Audit) {
        val report = "$surface fs$fs\n$audit"
        println("FORM-RULES $report")
        assertTrue(report, audit.failed.isEmpty())
    }

    /**
     * What the code says about each sheet (C10). The instructions are stored whatever "ask the amount"
     * says (AdvancedReminderFieldsState.snapshot); the pre-alarm minutes and the course's doses are
     * ignored while their switch is off, and drawn disabled.
     */
    private fun decl(group: Int): FormDecl = when (group) {
        R.string.reminder_group_taking -> FormDecl(independent = mapOf(s(R.string.add_reminder_instructions) to "snapshot stores the instructions whatever variableAmount says"))
        R.string.reminder_group_unanswered -> FormDecl(ignored = setOf(s(R.string.add_reminder_pre_alarm_minutes)))
        R.string.reminder_group_duration -> FormDecl(ignored = setOf(s(R.string.add_reminder_doses_remaining)))
        else -> FormDecl()
    }

    private fun rest(fs: Float) { at(fs); reminderForm(); gate("rest", fs, audit(sheet = false)) }

    private fun weekdays(fs: Float) {
        at(fs); reminderForm()
        tap(button(s(R.string.add_reminder_specific_days)))
        tap(toggle and dayNamed(DayOfWeek.MONDAY.getDisplayName(TextStyle.SHORT, res.configuration.locales[0])))
        gate("weekdays", fs, audit(sheet = false))
    }

    private fun sheet(fs: Float, group: Int) {
        at(fs); reminderForm(); openGroup(group)
        gate("sheet ${s(group)}", fs, audit(sheet = true, decl = decl(group)))
    }

    /** §7 R127 (A-13): the days of the month on the 42 dp grid, two on, and the next dates under it. */
    private fun daysOfMonth(fs: Float) {
        at(fs); reminderForm()
        tap(button(s(R.string.add_reminder_days_of_month)))
        tap(toggle and hasText("1")); tap(toggle and hasText("15"))
        gate("days of month", fs, audit(sheet = false))
    }

    /** §7 R127 (A-10): the weekdays and the cycle together, the rule line and the next dates under the cycle. */
    private fun combination(fs: Float, weekdayOffset: Long = 0) {
        at(fs); reminderForm()
        tap(button(s(R.string.add_reminder_specific_days)))
        tap(toggle and dayNamed(java.time.LocalDate.now().plusDays(weekdayOffset).dayOfWeek.getDisplayName(TextStyle.SHORT, res.configuration.locales[0])))
        tap(button(s(R.string.add_reminder_active_pause_cycle)))
        typeInto(field(s(R.string.add_reminder_active_days)), "1")
        typeInto(field(s(R.string.add_reminder_pause_days)), "6")
        if (weekdayOffset == 0L) { gate("combination", fs, audit(sheet = false)); return }
        // Salva is off by design here, so it is drawn disabled and C5's two-colour clause cannot hold; every
        // other clause and check must, and Salva must be the disabled one.
        val a = audit(sheet = false)
        val report = "combination that never meets fs$fs\n$a"
        println("FORM-RULES $report")
        val left = a.checks.flatMap { c -> c.violations.filter { !(c.name.startsWith("C5") && it == "Annulla is not more desaturated than Salva") }.map { "${c.name}: $it" } }
        assertTrue(report, left.isEmpty())
        assertTrue("Salva is off", disabled(button(s(R.string.common_save))))
    }

    /** §7 R127 (D-12): the interval in minutes, under an hour, the question under the field. */
    private fun intervalMinutes(fs: Float) {
        at(fs); reminderForm(ReminderType.REPEATING_INTERVAL)
        typeInto(field(s(R.string.add_reminder_every)), "8")
        tap(toggle and hasText(res.getQuantityString(R.plurals.interval_unit_minutes, 8, 8)))
        gate("interval in minutes", fs, audit(sheet = false))
    }

    private fun type(fs: Float, type: ReminderType) { at(fs); reminderForm(type); gate(type.name, fs, audit(sheet = false)) }

    private fun edit(fs: Float) {
        at(fs); seed(); h.reminder(metformina, ReminderConfig.FixedTime(8, 0, 1.0)); hostApp(); openMedicine()
        // The medicine page loads its reminders from Room: wait for the row before revealing it (slow CI).
        waitFor("the reminder row") { inTop(hasText("08:00", substring = true) and androidx.compose.ui.test.hasClickAction()) }
        tap(hasText("08:00", substring = true) and androidx.compose.ui.test.hasClickAction())
        waitFor("the edit form") { inTop(button(s(R.string.common_save))) }
        gate("edit", fs, audit(sheet = false))
    }

    @Test fun rest_fs10() = rest(1f)
    @Test @Config(fontScale = 2f) fun rest_fs20() = rest(2f)
    @Test fun weekdays_fs10() = weekdays(1f)
    @Test @Config(fontScale = 2f) fun weekdays_fs20() = weekdays(2f)
    @Test fun sheet_taking_fs10() = sheet(1f, R.string.reminder_group_taking)
    @Test @Config(fontScale = 2f) fun sheet_taking_fs20() = sheet(2f, R.string.reminder_group_taking)
    @Test fun sheet_notification_fs10() = sheet(1f, R.string.reminder_group_notification)
    @Test @Config(fontScale = 2f) fun sheet_notification_fs20() = sheet(2f, R.string.reminder_group_notification)
    @Test fun sheet_unanswered_fs10() = sheet(1f, R.string.reminder_group_unanswered)
    @Test @Config(fontScale = 2f) fun sheet_unanswered_fs20() = sheet(2f, R.string.reminder_group_unanswered)
    @Test fun sheet_duration_fs10() = sheet(1f, R.string.reminder_group_duration)
    @Test @Config(fontScale = 2f) fun sheet_duration_fs20() = sheet(2f, R.string.reminder_group_duration)
    @Test fun interval_fs10() = type(1f, ReminderType.REPEATING_INTERVAL)
    @Test @Config(fontScale = 2f) fun interval_fs20() = type(2f, ReminderType.REPEATING_INTERVAL)
    @Test fun window_fs10() = type(1f, ReminderType.DAILY_WINDOW)
    @Test @Config(fontScale = 2f) fun window_fs20() = type(2f, ReminderType.DAILY_WINDOW)
    @Test fun linked_fs10() = type(1f, ReminderType.LINKED_FOLLOWING)
    @Test @Config(fontScale = 2f) fun linked_fs20() = type(2f, ReminderType.LINKED_FOLLOWING)
    @Test fun days_of_month_fs10() = daysOfMonth(1f)
    @Test @Config(fontScale = 2f) fun days_of_month_fs20() = daysOfMonth(2f)
    @Test fun combination_fs10() = combination(1f)
    @Test @Config(fontScale = 2f) fun combination_fs20() = combination(2f)
    @Test fun combination_never_fs10() = combination(1f, weekdayOffset = 1)
    @Test @Config(fontScale = 2f) fun combination_never_fs20() = combination(2f, weekdayOffset = 1)
    @Test fun interval_minutes_fs10() = intervalMinutes(1f)
    @Test @Config(fontScale = 2f) fun interval_minutes_fs20() = intervalMinutes(2f)
    @Test fun edit_fs10() = edit(1f)
    @Test @Config(fontScale = 2f) fun edit_fs20() = edit(2f)

    /** C4: the form at rest and its four sheets, one look per kind of control. */
    @Test fun consistency_fs10() {
        reminderForm()
        val surfaces = linkedMapOf("rest" to audit(sheet = false))
        for (g in listOf(R.string.reminder_group_taking, R.string.reminder_group_notification, R.string.reminder_group_unanswered, R.string.reminder_group_duration)) {
            openGroup(g)
            surfaces["sheet ${s(g)}"] = audit(sheet = true, decl = decl(g))
            tapNode(top(button(s(R.string.common_cancel))))
            waitFor("the sheet closed") { windows() == 1 }
        }
        val c4 = rules.consistency(surfaces)
        println("FORM-RULES $c4")
        assertTrue(c4.toString(), c4.pass)
    }
}
