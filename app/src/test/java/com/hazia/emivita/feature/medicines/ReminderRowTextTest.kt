package com.hazia.emivita.feature.medicines

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.format.LocaleFormats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A reminder row on the medicine page (§7 R120, the owner's pick of 2026-10-23):
 * when · how often · how much, "08:00 · Ogni giorno · 1 gocce", with the kind
 * beneath. Every row read "Fixed Time" with no time, so six reminders looked
 * the same on the phone. Times are 24-hour here so the text is fixed.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderRowTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resources get() = context.resources

    @Before
    fun hour24() { LocaleFormats.hour24Override = true }

    @After
    fun reset() { LocaleFormats.hour24Override = null }

    private fun reminder(config: ReminderConfig, type: ReminderType, id: Long = 2L, parentId: Long? = null) = Reminder(
        id = id, medicineId = 1L, parentReminderId = parentId, type = type, configJson = encodeReminderConfig(config), createdAtEpochMillis = 0,
    )

    private fun fixed(hour: Int, minute: Int, dose: Double = 1.0, pattern: RepeatPattern = RepeatPattern.Daily) =
        reminder(ReminderConfig.FixedTime(hour, minute, dose, pattern), ReminderType.FIXED_TIME)

    @Test
    fun `a fixed time reads its time, its repeat and its dose`() {
        assertEquals("08:00 · Daily · 1 drops", reminderRowText(fixed(8, 0), "drops", null, resources))
        assertEquals("20:30 · Daily · 1 dose", reminderRowText(fixed(20, 30), null, null, resources))
        assertEquals("08:00 · Daily · 0.5 ml", reminderRowText(fixed(8, 0, dose = 0.5), "ml", null, resources))
    }

    @Test
    fun `each repeat pattern reads in the form's own words`() {
        assertEquals("08:00 · Mon, Wed, Fri · 1 dose", reminderRowText(fixed(8, 0, pattern = RepeatPattern.SpecificDaysOfWeek(setOf(5, 1, 3))), null, null, resources))
        assertEquals("08:00 · Every 3 days · 1 dose", reminderRowText(fixed(8, 0, pattern = RepeatPattern.EveryNDays(3, 0)), null, null, resources))
    }

    @Test
    fun `an active and pause cycle reads its day counts`() {
        // §7 R121, the owner's "Add counts": it read "Active/pause cycle".
        assertEquals("08:00 · 21 days on, 7 off · 1 dose", reminderRowText(fixed(8, 0, pattern = RepeatPattern.CyclicActivePause(0, 21, 7)), null, null, resources))
        assertEquals("08:00 · 1 day on, 1 off · 1 dose", reminderRowText(fixed(8, 0, pattern = RepeatPattern.CyclicActivePause(0, 1, 1)), null, null, resources))
    }

    @Test
    @Config(qualifiers = "it")
    fun `in Italian a cycle reads 21 giorni si, 7 no`() {
        assertEquals("08:00 · 21 giorni sì, 7 no · 1 compressa", reminderRowText(fixed(8, 0, pattern = RepeatPattern.CyclicActivePause(0, 21, 7)), "compressa", null, resources))
        assertEquals("08:00 · 1 giorno sì, 6 no · 1 compressa", reminderRowText(fixed(8, 0, pattern = RepeatPattern.CyclicActivePause(0, 1, 6)), "compressa", null, resources))
    }

    @Test
    fun `two fixed-time reminders of one medicine no longer read the same`() {
        // A fixed-time reminder holds one time; the phone's six were six reminders.
        val rows = listOf(fixed(8, 0), fixed(20, 0)).map { reminderRowText(it, "gocce", null, resources) }
        assertEquals(listOf("08:00 · Daily · 1 gocce", "20:00 · Daily · 1 gocce"), rows)
    }

    @Test
    fun `an interval reads every so many hours and its dose`() {
        val r = reminder(ReminderConfig.RepeatingInterval(480, 1.0, IntervalStartBehavior.ON_REMINDED, 0), ReminderType.REPEATING_INTERVAL)
        assertEquals("Every 8 h · 1 drops", reminderRowText(r, "drops", null, resources))
        val half = reminder(ReminderConfig.RepeatingInterval(90, 1.0, IntervalStartBehavior.ON_REMINDED, 0), ReminderType.REPEATING_INTERVAL)
        assertEquals("Every 1.5 h · 1 drops", reminderRowText(half, "drops", null, resources))
    }

    @Test
    fun `a daily window reads its window, its interval and its dose`() {
        val r = reminder(ReminderConfig.DailyWindow(240, 2.0, 8, 0, 20, 0, IntervalStartBehavior.ON_REMINDED), ReminderType.DAILY_WINDOW)
        assertEquals("08:00 – 20:00 · Every 4 h · 2 drops", reminderRowText(r, "drops", null, resources))
    }

    @Test
    fun `a following dose reads how long after which reminder`() {
        val parent = reminder(ReminderConfig.FixedTime(8, 0, 1.0), ReminderType.FIXED_TIME, id = 1L)
        val child = reminder(ReminderConfig.LinkedFollowing(30, 1.0, IntervalStartBehavior.ON_REMINDED), ReminderType.LINKED_FOLLOWING, parentId = 1L)
        assertEquals("30 min after 08:00 · 1 drops", reminderRowText(child, "drops", parent, resources))
        val intervalParent = reminder(ReminderConfig.RepeatingInterval(480, 1.0, IntervalStartBehavior.ON_REMINDED, 0), ReminderType.REPEATING_INTERVAL, id = 1L)
        assertEquals("30 min after Repeating Interval · 1 drops", reminderRowText(child, "drops", intervalParent, resources))
        assertEquals("its parent gone", "30 min · 1 drops", reminderRowText(child.copy(parentReminderId = null), "drops", null, resources))
    }

    @Test
    fun `an alert keeps its kind as its text`() {
        val r = reminder(ReminderConfig.ExpirationAlert(7, ExpirationTriggerFrequency.ONCE), ReminderType.EXPIRATION_ALERT)
        assertNull(reminderRowText(r, "drops", null, resources))
    }

    @Test
    @Config(qualifiers = "it")
    fun `in Italian it is the owner's 08 00 Ogni giorno 1 gocce`() {
        assertEquals("08:00 · Ogni giorno · 1 gocce", reminderRowText(fixed(8, 0), "gocce", null, resources))
        val r = reminder(ReminderConfig.RepeatingInterval(480, 1.0, IntervalStartBehavior.ON_REMINDED, 0), ReminderType.REPEATING_INTERVAL)
        assertEquals("Ogni 8 h · 1 gocce", reminderRowText(r, "gocce", null, resources))
    }

    @Test
    fun `the row shows the summary as its text and the kind beneath it`() {
        compose.setContent { ReminderRow(fixed(8, 0), onEdit = {}, onEnabledChange = {}, doseUnit = "drops") }
        compose.onNodeWithText("08:00 · Daily · 1 drops").assertExists()
        compose.onNodeWithText("Fixed Time").assertExists()
    }
}
