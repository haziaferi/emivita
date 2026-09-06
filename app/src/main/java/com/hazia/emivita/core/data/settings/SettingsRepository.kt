package com.hazia.emivita.core.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.scheduling.DEFAULT_SNOOZE_MINUTES
import com.hazia.emivita.ui.theme.DEFAULT_PALETTE
import com.hazia.emivita.ui.theme.MedPalette
import com.hazia.emivita.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Context.settingsDataStore by preferencesDataStore(name = "emivita_settings")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val DEFAULT_WEEKEND_START = LocalTime.of(9, 0)
private val DEFAULT_WEEKEND_END = LocalTime.of(18, 0)

/**
 * Global, non-sensitive, device-wide preferences (spec §3's "Global user
 * preferences (DataStore, not the SQLCipher medical DB)" table, resolved as
 * spec §9 item 12) -- pure AndroidX, no GMS, fits §1.
 *
 * Note that lockRemindersUntilTakenByDefault currently has no Settings UI to
 * set it: [isLockedUntilTaken] resolves against it and Add/Edit Medicine can
 * override it per medicine, but the global default itself is unreachable.
 */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    private object Keys {
        val PALETTE = stringPreferencesKey("palette")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SNOOZE_DURATION_MINUTES = intPreferencesKey("snooze_duration_minutes")
        val WEEKEND_MODE_ENABLED = booleanPreferencesKey("weekend_mode_enabled")
        val WEEKEND_DAYS = stringSetPreferencesKey("weekend_days") // "1".."7", Mon..Sun -- matches SpecificDaysOfWeek's convention
        val WEEKEND_START_TIME = stringPreferencesKey("weekend_start_time") // "HH:mm"
        val WEEKEND_END_TIME = stringPreferencesKey("weekend_end_time")
        val LOCK_REMINDERS_UNTIL_TAKEN_BY_DEFAULT = booleanPreferencesKey("lock_reminders_until_taken_by_default")
        // SAF tree Uri as a plain string (persisted permission survives reboots
        // via takePersistableUriPermission -- see BackupSyncScreen). Null =
        // auto-sync not set up. The passphrase itself is NOT here -- it's
        // sensitive, so it lives in AutoSyncPassphraseStore's Keystore-wrapped
        // storage instead of plaintext DataStore (spec §9 #9(b)).
        val AUTO_SYNC_FOLDER_URI = stringPreferencesKey("auto_sync_folder_uri")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        // POST_NOTIFICATIONS is a runtime permission on API 33+; this tracks
        // whether the app has already shown the system request dialog once,
        // so EmivitaApp's launcher (see NotificationPermissionEffect) asks
        // exactly once rather than re-prompting on every app open after a
        // denial (PERM-023/PERM-024 -- respect the user's decision).
        val NOTIFICATION_PERMISSION_REQUESTED = booleanPreferencesKey("notification_permission_requested")
    }

    val palette: Flow<MedPalette> = dataStore.data.map { prefs ->
        prefs[Keys.PALETTE]?.let { runCatching { MedPalette.valueOf(it) }.getOrNull() }
            ?: DEFAULT_PALETTE
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    /** Replaces the hardcoded DEFAULT_SNOOZE_MINUTES constant everywhere except
     *  as this Flow's own fallback value -- the constant stays put as "what a
     *  fresh install snoozes for," not duplicated as a second magic number. */
    val snoozeDurationMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.SNOOZE_DURATION_MINUTES] ?: DEFAULT_SNOOZE_MINUTES.toInt()
    }

    /** The master switch and, with the three keys below, the definition of
     *  *what* the weekend is -- which days, and the start/end of the quiet
     *  window. It no longer decides *which* reminders are shifted by it: that
     *  is per reminder, on `Reminder.weekendModeOptIn`, and both have to be on
     *  (spec §7 R36). A window is a property of the user's week; being willing
     *  to wait is a property of one medicine. */
    val weekendModeEnabled: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.WEEKEND_MODE_ENABLED] ?: false }

    val weekendDays: Flow<Set<Int>> = dataStore.data.map { prefs ->
        prefs[Keys.WEEKEND_DAYS]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    }

    val weekendStartTime: Flow<LocalTime> = dataStore.data.map { prefs ->
        prefs[Keys.WEEKEND_START_TIME]?.let { runCatching { LocalTime.parse(it, TIME_FORMAT) }.getOrNull() } ?: DEFAULT_WEEKEND_START
    }

    val weekendEndTime: Flow<LocalTime> = dataStore.data.map { prefs ->
        prefs[Keys.WEEKEND_END_TIME]?.let { runCatching { LocalTime.parse(it, TIME_FORMAT) }.getOrNull() } ?: DEFAULT_WEEKEND_END
    }

    val lockRemindersUntilTakenByDefault: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.LOCK_REMINDERS_UNTIL_TAKEN_BY_DEFAULT] ?: false
    }

    val autoSyncFolderUri: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.AUTO_SYNC_FOLDER_URI] }

    /** Biometric app-lock (spec §9 #11) -- gates the whole app shell (below
     *  onboarding, which has nothing to protect yet) behind a BiometricPrompt
     *  challenge every time the app resumes from background. No PIN/pattern
     *  fallback and no "hide names"/"block screenshots" toggles -- those are
     *  the other two rows the "App Lock & Privacy" Settings entry describes,
     *  but #11 only scopes the biometric lock itself, same "build what's
     *  actually named" discipline AlarmsSnoozeScreen already applied. */
    val biometricLockEnabled: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.BIOMETRIC_LOCK_ENABLED] ?: false }

    val notificationPermissionRequested: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[Keys.NOTIFICATION_PERMISSION_REQUESTED] ?: false }

    suspend fun setPalette(palette: MedPalette) {
        dataStore.edit { it[Keys.PALETTE] = palette.name }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = themeMode.name }
    }

    suspend fun setSnoozeDurationMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SNOOZE_DURATION_MINUTES] = minutes }
    }

    suspend fun setWeekendModeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WEEKEND_MODE_ENABLED] = enabled }
    }

    suspend fun setWeekendDays(days: Set<Int>) {
        dataStore.edit { it[Keys.WEEKEND_DAYS] = days.map(Int::toString).toSet() }
    }

    suspend fun setWeekendStartTime(time: LocalTime) {
        dataStore.edit { it[Keys.WEEKEND_START_TIME] = time.format(TIME_FORMAT) }
    }

    suspend fun setWeekendEndTime(time: LocalTime) {
        dataStore.edit { it[Keys.WEEKEND_END_TIME] = time.format(TIME_FORMAT) }
    }

    suspend fun setLockRemindersUntilTakenByDefault(locked: Boolean) {
        dataStore.edit { it[Keys.LOCK_REMINDERS_UNTIL_TAKEN_BY_DEFAULT] = locked }
    }

    /** Null clears the setting (the "Stop syncing" action) -- callers also
     *  release the SAF persisted-uri-permission and clear the cached
     *  passphrase (AutoSyncPassphraseStore) when doing so; this repository
     *  only owns the folder-uri half of that state. */
    suspend fun setAutoSyncFolderUri(uri: String?) {
        dataStore.edit {
            if (uri != null) it[Keys.AUTO_SYNC_FOLDER_URI] = uri else it.remove(Keys.AUTO_SYNC_FOLDER_URI)
        }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIOMETRIC_LOCK_ENABLED] = enabled }
    }

    suspend fun setNotificationPermissionRequested() {
        dataStore.edit { it[Keys.NOTIFICATION_PERMISSION_REQUESTED] = true }
    }

    /** Resolves [Medicine.remindersLockedUntilTaken]'s "no override, inherit
     *  the global default" contract (spec §3's own bug writeup: it was typed
     *  as a plain `Boolean` and nothing read the resolved value at all --
     *  both fixed now, this is the one place that resolution should happen). */
    suspend fun isLockedUntilTaken(medicine: Medicine): Boolean =
        medicine.remindersLockedUntilTaken ?: lockRemindersUntilTakenByDefault.first()
}
