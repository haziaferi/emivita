package com.hazia.emivita.core.data.backup

import com.hazia.emivita.core.data.settings.SwipeAction
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Settings in the backup (§7 R133, medTimer E-13; the owner: "Before replacing current settings"): the travelling
 * settings round-trip, the phone's own never leave it, a restore replaces them only when asked, and every key is
 * placed in one list or the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SettingsBackupTest {

    private val h = ReceiverHarness()
    private val settings get() = h.container.settingsRepository
    private val backup get() = h.container.backupRepository

    @After fun close() = h.close()

    @Test fun every_key_is_placed() {
        assertEquals("a new setting must travel or stay with the phone", emptyList<String>(), settings.unclassifiedKeys())
    }

    @Test fun the_travelling_settings_round_trip_and_the_lock_stays() = runBlocking {
        settings.setWeekendModeEnabled(true)
        settings.setWeekendDays(setOf(6, 7))
        settings.setSnoozeDurationMinutes(15)
        settings.setSwipeAction(SwipeAction.SKIP)
        settings.setBiometricLockEnabled(true)
        val snapshot = backup.exportSnapshot(0L)
        val carried = checkNotNull(snapshot.settings)
        assertTrue("the lock is the phone's", "biometric_lock_enabled" !in carried)

        settings.setWeekendModeEnabled(false)
        settings.setWeekendDays(setOf(1))
        settings.setSnoozeDurationMinutes(5)
        settings.setSwipeAction(SwipeAction.NOTHING)
        settings.setBiometricLockEnabled(false)
        backup.restoreSnapshot(snapshot, withSettings = true)

        assertEquals(true, settings.weekendModeEnabled.first())
        assertEquals(setOf(6, 7), settings.weekendDays.first())
        assertEquals(15, settings.snoozeDurationMinutes.first())
        assertEquals(SwipeAction.SKIP, settings.swipeAction.first())
        assertEquals("the lock is not touched", false, settings.biometricLockEnabled.first())
    }

    @Test fun without_the_box_the_current_settings_stay() = runBlocking {
        settings.setSnoozeDurationMinutes(15)
        val snapshot = backup.exportSnapshot(0L)
        settings.setSnoozeDurationMinutes(5)
        backup.restoreSnapshot(snapshot)
        assertEquals(5, settings.snoozeDurationMinutes.first())
    }

    @Test fun a_file_without_settings_or_with_a_wrong_value_restores_safely() = runBlocking {
        settings.setSnoozeDurationMinutes(15)
        val snapshot = backup.exportSnapshot(0L)
        backup.restoreSnapshot(snapshot.copy(settings = null), withSettings = true)
        assertEquals("an older file leaves the settings alone", 15, settings.snoozeDurationMinutes.first())
        // A value of the wrong type is skipped, not thrown.
        settings.restoreFromBackup(mapOf("snooze_duration_minutes" to JsonPrimitive("dieci"), "weekend_mode_enabled" to JsonPrimitive(true)))
        assertEquals(true, settings.weekendModeEnabled.first())
    }

    @Test fun the_settings_survive_the_json() {
        val data = runBlocking { backup.exportSnapshot(0L) }.copy(settings = mapOf("snooze_duration_minutes" to JsonPrimitive(12)))
        val json = BackupJson.encodeToString(BackupData.serializer(), data)
        val back = (decodeBackup(json) as BackupDecodeResult.Ok).data
        assertEquals(data.settings, back.settings)
    }
}
