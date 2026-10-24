package com.hazia.emivita.feature.reminders

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.format.labelRes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The owner's P2 picks on the reminder form (§7 R127): the day rules that combine with the days of
 * the month (A-10 + A-13 V2), the reminder's own importance (B-38 V1), the instruction samples
 * (D-10 V1) and the interval's unit (D-12 V1), each as on the phone.
 */
class ReminderP2BehaviourTest : ReminderFormTestBase() {

    private val save get() = button(s(R.string.common_save))
    private fun said(text: String) = inTop(hasText(text))
    private fun config() = h.container.reminderRepository.decodeConfig(stored())
    private fun weekday(n: Int) = java.time.DayOfWeek.of(n).getDisplayName(TextStyle.SHORT, res.configuration.locales[0])
    private fun day(n: Int) = toggle and hasText(n.toString())

    // ---- A-10 + A-13 V2 ---------------------------------------------------------------------------

    @Test fun days_of_the_month_save_as_day_rules_with_the_next_three_dates() {
        reminderForm()
        tap(button(s(R.string.add_reminder_days_of_month)))
        tap(day(1)); tap(day(15))
        var d = LocalDate.now(); val expected = mutableListOf<LocalDate>()
        while (expected.size < 3) { if (d.dayOfMonth == 1 || d.dayOfMonth == 15) expected += d; d = d.plusDays(1) }
        val line = s(R.string.add_reminder_next_dates, expected.joinToString(", ") { LocaleFormats.monthDay(it) })
        assertTrue("the next three dates: $line", said(line))
        saveForm()
        assertEquals(RepeatPattern.DayRules(daysOfMonth = setOf(1, 15)), (config() as ReminderConfig.FixedTime).repeatPattern)
        assertTrue("the row describes it", inTop(hasText(s(R.string.reminder_row_days_of_month, "1, 15"), substring = true)))
    }

    /** A weekday the cycle never lands on: a 1-on 6-off cycle from today is on only on today's weekday. */
    private fun cycleOnTodayOnly(weekdayOffset: Long) {
        reminderForm()
        tap(button(s(R.string.add_reminder_specific_days)))
        tap(toggle and hasText(weekday(LocalDate.now().plusDays(weekdayOffset).dayOfWeek.value)))
        tap(button(s(R.string.add_reminder_active_pause_cycle)))
        typeInto(field(s(R.string.add_reminder_active_days)), "1")
        typeInto(field(s(R.string.add_reminder_pause_days)), "6")
    }

    @Test fun control_rules_that_meet_save() {
        cycleOnTodayOnly(weekdayOffset = 0)
        assertFalse(said(s(R.string.add_reminder_rules_never)))
        assertFalse("Salva on", disabled(save))
        saveForm()
        val rules = (config() as ReminderConfig.FixedTime).repeatPattern as RepeatPattern.DayRules
        assertEquals(setOf(LocalDate.now().dayOfWeek.value), rules.weekdays)
        assertEquals(1 to 6, rules.cycle!!.activeDays to rules.cycle!!.pauseDays)
        assertNull(rules.daysOfMonth)
    }

    @Test fun rules_that_never_meet_say_so_and_keep_salva_off() {
        cycleOnTodayOnly(weekdayOffset = 1)
        assertTrue("the field error", said(s(R.string.add_reminder_rules_never)))
        assertTrue("Salva off", disabled(save))
    }

    @Test fun one_rule_alone_keeps_its_own_stored_shape() {
        reminderForm()
        tap(button(s(R.string.add_reminder_specific_days)))
        tap(toggle and hasText(weekday(1)))
        saveForm()
        assertEquals(RepeatPattern.SpecificDaysOfWeek(setOf(1)), (config() as ReminderConfig.FixedTime).repeatPattern)
    }

    // ---- B-38 V1 ----------------------------------------------------------------------------------

    private fun priority(p: NotificationPriority) = runBlocking {
        val m = h.container.medicineRepository.getById(metformina)!!
        h.container.medicineRepository.update(m.copy(notificationPriority = p))
    }

    private fun tier(p: NotificationPriority) = s(com.hazia.emivita.core.notification.ReminderNotificationChannels.tierNameFor(p))
    private fun choice(text: String) = hasText(text) and hasClickAction()

    /** The Fixed time form of Metformina at medicine tier [p], its Notifica sheet open. */
    private fun notificaAt(p: NotificationPriority) {
        seed(); priority(p)
        hostApp(); openMedicine()
        tap(described(s(R.string.common_add_reminder)))
        val fixed = s(ReminderType.FIXED_TIME.labelRes())
        waitFor("the type picker") { inTop(button(fixed)) }
        tap(button(fixed))
        waitFor("the form") { inTop(save) }
        openGroup(R.string.reminder_group_notification)
    }

    @Test fun a_standard_medicine_offers_high_and_alarm_and_the_row_says_the_pick() {
        notificaAt(NotificationPriority.STANDARD)
        assertTrue(inTop(choice(s(R.string.add_reminder_importance_same, tier(NotificationPriority.STANDARD)))))
        assertTrue(inTop(choice(tier(NotificationPriority.HIGH))))
        assertTrue(inTop(choice(tier(NotificationPriority.ALARM))))
        tap(choice(tier(NotificationPriority.HIGH)))
        tapNode(top(save))
        waitFor("the sheet closed") { windows() == 1 }
        assertTrue("the Notifica row says Alta", inTop(group(R.string.reminder_group_notification) and hasText(tier(NotificationPriority.HIGH), substring = true)))
        saveForm()
        assertEquals(NotificationPriority.HIGH, stored().importance)
    }

    @Test fun a_high_medicine_offers_only_alarm() {
        notificaAt(NotificationPriority.HIGH)
        assertTrue(inTop(choice(tier(NotificationPriority.ALARM))))
        assertFalse("not the medicine's own tier again", inTop(choice(tier(NotificationPriority.HIGH))))
    }

    @Test fun an_alarm_medicine_offers_none() {
        notificaAt(NotificationPriority.ALARM)
        assertFalse(said(s(R.string.add_reminder_importance)))
    }

    // ---- D-10 V1 ----------------------------------------------------------------------------------

    private val instructions get() = field(s(R.string.add_reminder_instructions))
    private fun fieldText() = top(instructions).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun control_a_sample_fills_an_empty_field() {
        reminderForm(); openGroup(R.string.reminder_group_taking)
        tap(button(s(R.string.instructions_sample_with_food)))
        assertEquals(s(R.string.instructions_sample_with_food), fieldText())
    }

    @Test fun a_sample_appends_with_a_dot_and_never_twice() {
        reminderForm(); openGroup(R.string.reminder_group_taking)
        val food = s(R.string.instructions_sample_with_food)
        val bed = s(R.string.instructions_sample_before_bed)
        tap(button(food)); tap(button(bed)); tap(button(food))
        assertEquals("two samples, joined, the repeat not added", "$food · $bed", fieldText())
        typeInto(instructions, "Lontano dal ferro")
        tap(button(food))
        assertEquals("a sample after typed text", "Lontano dal ferro · $food", fieldText())
        tapNode(top(save))
        waitFor("the sheet closed") { windows() == 1 }
        saveForm()
        assertEquals("Lontano dal ferro · $food", stored().instructions)
    }

    // ---- D-12 V1 ----------------------------------------------------------------------------------

    private val every get() = field(s(R.string.add_reminder_every))
    private fun unit(id: Int, count: Int) = toggle and hasText(res.getQuantityString(id, count, count))
    private fun question(n: Int) = res.getQuantityString(R.plurals.add_reminder_interval_minutes_check, n, n)

    @Test fun control_two_hours_does_not_ask_and_stores_minutes() {
        reminderForm(ReminderType.REPEATING_INTERVAL)
        typeInto(every, "2")
        tap(unit(R.plurals.interval_unit_hours, 2))
        assertFalse(said(question(120)))
        saveForm()
        assertEquals(120, (config() as ReminderConfig.RepeatingInterval).intervalMinutes)
    }

    @Test fun under_an_hour_asks_and_still_saves() {
        reminderForm(ReminderType.REPEATING_INTERVAL)
        typeInto(every, "8")
        tap(unit(R.plurals.interval_unit_minutes, 8))
        assertTrue("the field asks \"${question(8)}\"", said(question(8)))
        assertFalse("Salva stays on", disabled(save))
        saveForm()
        assertEquals(8, (config() as ReminderConfig.RepeatingInterval).intervalMinutes)
    }

    @Test fun a_week_opens_as_one_week() {
        seed()
        h.reminder(metformina, ReminderConfig.RepeatingInterval(10080, 1.0, IntervalStartBehavior.ON_REMINDED, 0L))
        hostApp(); openMedicine()
        waitFor("the reminder row") { inTop(hasText(s(R.string.reminder_row_every_hours, "168"), substring = true) and hasClickAction()) }
        tap(hasText(s(R.string.reminder_row_every_hours, "168"), substring = true) and hasClickAction())
        waitFor("the edit form") { inTop(button(s(R.string.common_save_changes))) }
        assertEquals("1", top(every).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("settimana is on", true, top(unit(R.plurals.interval_unit_weeks, 1)).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.On)
    }

    @Test fun the_window_takes_a_unit_too() {
        reminderForm(ReminderType.DAILY_WINDOW)
        typeInto(every, "90")
        tap(unit(R.plurals.interval_unit_minutes, 90))
        saveForm()
        assertEquals(90, (config() as ReminderConfig.DailyWindow).intervalMinutes)
    }
}
