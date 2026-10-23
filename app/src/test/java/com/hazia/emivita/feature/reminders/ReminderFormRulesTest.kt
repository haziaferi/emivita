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
        tap(toggle and hasText(DayOfWeek.MONDAY.getDisplayName(TextStyle.SHORT, res.configuration.locales[0])))
        gate("weekdays", fs, audit(sheet = false))
    }

    private fun sheet(fs: Float, group: Int) {
        at(fs); reminderForm(); openGroup(group)
        gate("sheet ${s(group)}", fs, audit(sheet = true, decl = decl(group)))
    }

    private fun type(fs: Float, type: ReminderType) { at(fs); reminderForm(type); gate(type.name, fs, audit(sheet = false)) }

    private fun edit(fs: Float) {
        at(fs); seed(); h.reminder(metformina, ReminderConfig.FixedTime(8, 0, 1.0)); hostApp(); openMedicine()
        tap(hasText("08:00", substring = true) and androidx.compose.ui.test.hasClickAction())
        waitFor("the edit form") { inTop(button(s(R.string.common_save_changes))) }
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
