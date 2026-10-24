package com.hazia.emivita.core.data.backup

import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.data.entity.Reminder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A reminder's own notification tier (§7 R127, B-38) travels in the backup, and a file written
 * before it restores with none, "as the medicine": the additive case that keeps
 * [BACKUP_SCHEMA_VERSION] where it is.
 */
class BackupReminderImportanceTest {
    private val reminder = Reminder(
        id = 1, medicineId = 1, type = ReminderType.FIXED_TIME, configJson = "{}",
        importance = NotificationPriority.ALARM, createdAtEpochMillis = 0,
    )

    @Test
    fun `the tier round-trips`() {
        val json = BackupJson.encodeToString(Reminder.serializer(), reminder)
        assertEquals(NotificationPriority.ALARM, BackupJson.decodeFromString(Reminder.serializer(), json).importance)
    }

    @Test
    fun `a reminder written without it decodes as the medicine`() {
        val full = BackupJson.parseToJsonElement(BackupJson.encodeToString(Reminder.serializer(), reminder)).jsonObject
        val older = JsonObject(full - "importance").toString()
        val decoded = BackupJson.decodeFromString(Reminder.serializer(), older)
        assertNull(decoded.importance)
        assertEquals(reminder.copy(importance = null), decoded)
    }
}
