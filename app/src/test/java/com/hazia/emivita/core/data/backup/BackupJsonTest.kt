package com.hazia.emivita.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file's forward and backward compatibility (spec §7 R60 E F1).
 * Pure: no Room, no Android. What it pins is the *contract* of the file on
 * disk, which is the thing a user restores three app versions later.
 */
class BackupJsonTest {

    private val empty = BackupData(
        exportedAtEpochMillis = 1_000L,
        profiles = emptyList(),
        medicines = emptyList(),
        tags = emptyList(),
        medicineTagCrossRefs = emptyList(),
        doseGroups = emptyList(),
        reminders = emptyList(),
        doseEvents = emptyList(),
    )

    @Test
    fun `the schema version is written to disk, not left to the default`() {
        // With the default Json a defaulted field is omitted, so no file ever
        // carried the version that existed to protect it.
        val json = BackupJson.encodeToString(BackupData.serializer(), empty)
        assertTrue(json, json.contains("\"schemaVersion\":$BACKUP_SCHEMA_VERSION"))
    }

    @Test
    fun `a file from a newer build with a field this build does not know still decodes its known half`() {
        val json = BackupJson.encodeToString(BackupData.serializer(), empty)
            .replaceFirst("{", "{\"someFutureField\":42,")
        val result = decodeBackup(json)
        assertTrue(result is BackupDecodeResult.Ok)
        assertEquals(1_000L, (result as BackupDecodeResult.Ok).data.exportedAtEpochMillis)
    }

    @Test
    fun `a file whose schema version is higher than this build's is refused by name`() {
        val json = BackupJson.encodeToString(BackupData.serializer(), empty)
            .replace("\"schemaVersion\":$BACKUP_SCHEMA_VERSION", "\"schemaVersion\":${BACKUP_SCHEMA_VERSION + 1}")
        val result = decodeBackup(json)
        assertEquals(BackupDecodeResult.NewerThanApp(BACKUP_SCHEMA_VERSION + 1), result)
    }

    @Test
    fun `a pre-R60 file with no version field reads as version 1`() {
        val json = BackupJson.encodeToString(BackupData.serializer(), empty)
            .replace("\"schemaVersion\":$BACKUP_SCHEMA_VERSION,", "")
        assertTrue(decodeBackup(json) is BackupDecodeResult.Ok)
    }
}
