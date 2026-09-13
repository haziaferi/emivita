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
import androidx.datastore.preferences.core.longPreferencesKey

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
        // Which of History's three views was open last (§7 R60 D F5); the
        // enum name, tolerant of a value from a removed case.
        val HISTORY_VIEW_MODE = stringPreferencesKey("history_view_mode")
        // The Medicines tab's sort key (enum name) and hide-empty filter (§9 #24).
        val MEDICINES_SORT = stringPreferencesKey("medicines_sort")
        val MEDICINES_HIDE_EMPTY = booleanPreferencesKey("medicines_hide_empty")
        // §9 #27: the tag filter on Medicines, by tag name (tags are keyed by
        // name on the form; a renamed or deleted tag simply stops matching).
        val MEDICINES_TAG_FILTER = stringSetPreferencesKey("medicines_tag_filter")
        // §9 #25: what a swiped-away reminder records (enum name; default SNOOZE,
        // the owner's choice) and whether notifications mask the medicine name.
        val SWIPE_ACTION = stringPreferencesKey("notification_swipe_action")
        // §9 #28 (MT-20): System follows the phone's own 24-hour switch, which
        // java.time's localized time never did; the other two override it.
        val TIME_FORMAT = stringPreferencesKey("time_format")
        // §9 #29 (MT-21): the medical disclaimer, accepted once on first launch.
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val HIDE_MEDICINE_NAMES = booleanPreferencesKey("hide_medicine_names")
        // §9 #25 alarm settings (medTimer's AlarmSettingsFragment, HomeMedkit HM-21).
        val ALARM_RINGTONE_URI = stringPreferencesKey("alarm_ringtone_uri")           // null = system default alarm
        val ALARM_NO_SOUND_WHEN_SILENT = booleanPreferencesKey("alarm_no_sound_when_silent")
        val ALARM_NO_VIBRATION_WHEN_SILENT = booleanPreferencesKey("alarm_no_vibration_when_silent")
        val USE_ALARM_CLOCK = booleanPreferencesKey("use_alarm_clock")
        // §9 #25: the notification's Snooze action opens a duration picker
        // instead of snoozing for the saved duration (medTimer's "Custom…").
        val SNOOZE_ASK_EACH_TIME = booleanPreferencesKey("snooze_ask_each_time")
        // §9 #26: the last instant the app is known to have been running --
        // launch, boot, an alarm firing. The Missed back-fill covers what lies
        // between this and now, so a pause-and-resume while alive is not a gap.
        val APP_LAST_SEEN_EPOCH_MILLIS = longPreferencesKey("app_last_seen_epoch_millis")
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

    /** The last History view, by enum name; null when never set or stale. */
    val historyViewModeName: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.HISTORY_VIEW_MODE] }

    suspend fun setHistoryViewModeName(name: String) {
        dataStore.edit { it[Keys.HISTORY_VIEW_MODE] = name }
    }

    val medicinesSortName: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.MEDICINES_SORT] }
    suspend fun setMedicinesSortName(name: String) {
        dataStore.edit { it[Keys.MEDICINES_SORT] = name }
    }

    val medicinesHideEmpty: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.MEDICINES_HIDE_EMPTY] ?: false }

    val swipeAction: Flow<SwipeAction> = dataStore.data.map { prefs ->
        prefs[Keys.SWIPE_ACTION]?.let { n -> SwipeAction.entries.firstOrNull { it.name == n } } ?: SwipeAction.SNOOZE
    }
    suspend fun setSwipeAction(action: SwipeAction) {
        dataStore.edit { it[Keys.SWIPE_ACTION] = action.name }
    }

    val disclaimerAccepted: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.DISCLAIMER_ACCEPTED] ?: false }
    suspend fun setDisclaimerAccepted() {
        dataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = true }
    }

    val timeFormat: Flow<TimeFormat> = dataStore.data.map { prefs ->
        prefs[Keys.TIME_FORMAT]?.let { n -> TimeFormat.entries.firstOrNull { it.name == n } } ?: TimeFormat.SYSTEM
    }
    suspend fun setTimeFormat(format: TimeFormat) {
        dataStore.edit { it[Keys.TIME_FORMAT] = format.name }
    }

    /** Mask the medicine's name in every notification (§9 #11, #25): first
     *  letter and asterisks, so a shade glanced at across a table says a dose
     *  is due without saying of what. The idea is medTimer's; re-implemented. */
    val hideMedicineNames: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.HIDE_MEDICINE_NAMES] ?: false }
    suspend fun setHideMedicineNames(hide: Boolean) {
        dataStore.edit { it[Keys.HIDE_MEDICINE_NAMES] = hide }
    }

    /** The Alarm tier's ringtone as a Uri string; null = the system's default
     *  alarm sound. Only AlarmActivity plays it -- a channel's sound is fixed
     *  at creation (§7 R59), so the heads-up keeps the default. */
    val alarmRingtoneUri: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.ALARM_RINGTONE_URI] }
    suspend fun setAlarmRingtoneUri(uri: String?) {
        dataStore.edit { if (uri == null) it.remove(Keys.ALARM_RINGTONE_URI) else it[Keys.ALARM_RINGTONE_URI] = uri }
    }

    val alarmNoSoundWhenSilent: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.ALARM_NO_SOUND_WHEN_SILENT] ?: false }
    suspend fun setAlarmNoSoundWhenSilent(value: Boolean) {
        dataStore.edit { it[Keys.ALARM_NO_SOUND_WHEN_SILENT] = value }
    }

    val alarmNoVibrationWhenSilent: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.ALARM_NO_VIBRATION_WHEN_SILENT] ?: false }
    suspend fun setAlarmNoVibrationWhenSilent(value: Boolean) {
        dataStore.edit { it[Keys.ALARM_NO_VIBRATION_WHEN_SILENT] = value }
    }

    /** Arm reminders with `setAlarmClock` (HM-21): the status bar shows the
     *  alarm icon and the lock screen the next reminder's time, and the OS
     *  treats it as a user-visible alarm for battery purposes. Off by default:
     *  a person with six medicines may not want six lock-screen times. */
    val useAlarmClock: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.USE_ALARM_CLOCK] ?: false }
    suspend fun setUseAlarmClock(value: Boolean) {
        dataStore.edit { it[Keys.USE_ALARM_CLOCK] = value }
    }

    val appLastSeenEpochMillis: Flow<Long?> = dataStore.data.map { prefs -> prefs[Keys.APP_LAST_SEEN_EPOCH_MILLIS] }
    suspend fun setAppLastSeenEpochMillis(epochMillis: Long) {
        dataStore.edit { it[Keys.APP_LAST_SEEN_EPOCH_MILLIS] = epochMillis }
    }

    val snoozeAskEachTime: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.SNOOZE_ASK_EACH_TIME] ?: false }
    suspend fun setSnoozeAskEachTime(value: Boolean) {
        dataStore.edit { it[Keys.SNOOZE_ASK_EACH_TIME] = value }
    }
    suspend fun setMedicinesHideEmpty(hide: Boolean) {
        dataStore.edit { it[Keys.MEDICINES_HIDE_EMPTY] = hide }
    }

    val medicinesTagFilter: Flow<Set<String>> = dataStore.data.map { prefs -> prefs[Keys.MEDICINES_TAG_FILTER] ?: emptySet() }
    suspend fun setMedicinesTagFilter(tags: Set<String>) {
        dataStore.edit { it[Keys.MEDICINES_TAG_FILTER] = tags }
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

/** What swiping a reminder notification away records (§9 #25). medTimer
 *  offers the same three and defaults to Skip; the owner chose Snooze -- a
 *  swipe is closer to "not now" than to "no". NOTHING leaves the dose Due. */
enum class SwipeAction { NOTHING, SNOOZE, SKIP }

/** §9 #28: how times render. SYSTEM = the phone's 24-hour switch. */
enum class TimeFormat { SYSTEM, H12, H24 }
