package com.hazia.emivita.core.data.backup

import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.Reminder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The two reminder flags of §7 R109 travel in the backup, and a file written
 * before them still restores, with both off: the additive case that keeps
 * [BACKUP_SCHEMA_VERSION] where it is.
 */
class BackupReminderFlagsTest {
    private val reminder = Reminder(
        id = 1, medicineId = 1, type = ReminderType.FIXED_TIME, configJson = "{}",
        stickyUntilAnswered = true, variableAmount = true, createdAtEpochMillis = 0,
    )

    @Test
    fun `both flags round-trip`() {
        val json = BackupJson.encodeToString(Reminder.serializer(), reminder)
        assertEquals(reminder, BackupJson.decodeFromString(Reminder.serializer(), json))
    }

    @Test
    fun `a reminder written without them decodes with both off`() {
        val full = BackupJson.parseToJsonElement(BackupJson.encodeToString(Reminder.serializer(), reminder)).jsonObject
        val older = JsonObject(full - "stickyUntilAnswered" - "variableAmount").toString()
        val decoded = BackupJson.decodeFromString(Reminder.serializer(), older)
        assertFalse(decoded.stickyUntilAnswered)
        assertFalse(decoded.variableAmount)
        assertEquals(reminder.copy(stickyUntilAnswered = false, variableAmount = false), decoded)
    }
}
