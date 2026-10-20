package com.hazia.emivita.feature.reminders

import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.core.data.config.ReminderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §7 R104 (census B-16): repeat-until-answered is on for a new reminder, as in medTimer. */
class RepeatUntilAnsweredDefaultTest {

    private fun reminder(repeat: RepeatConfig?) =
        Reminder(medicineId = 1, type = ReminderType.FIXED_TIME, configJson = "{}", repeatOnUnanswered = repeat, createdAtEpochMillis = 0)

    @Test
    fun `a new reminder repeats until answered`() {
        assertTrue(AdvancedReminderFieldsState(null).nagEnabled)
    }

    @Test
    fun `an existing reminder keeps its own choice`() {
        assertFalse(AdvancedReminderFieldsState(reminder(null)).nagEnabled)
        assertTrue(AdvancedReminderFieldsState(reminder(RepeatConfig(delayMinutes = 30, maxRepeats = 3))).nagEnabled)
    }
}
