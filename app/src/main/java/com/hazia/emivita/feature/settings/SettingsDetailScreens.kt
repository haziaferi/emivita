package com.hazia.emivita.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hazia.emivita.core.di.LocalAppContainer
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.notification.NotificationDelivery
import com.hazia.emivita.core.notification.ReminderNotificationChannels
import com.hazia.emivita.core.notification.cardBody
import com.hazia.emivita.core.notification.cardTitle
import com.hazia.emivita.core.notification.notificationDelivery
import com.hazia.emivita.ui.components.TimeOfDayField
import com.hazia.emivita.ui.theme.MedPalette
import com.hazia.emivita.ui.theme.ThemeMode
import com.hazia.emivita.ui.theme.displayName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.hazia.emivita.ui.theme.labelRes
import androidx.compose.runtime.collectAsState
import com.hazia.emivita.core.data.settings.SwipeAction
import androidx.compose.material3.RadioButton
import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import com.hazia.emivita.core.notification.postTestReminder
import androidx.core.net.toUri
import android.app.Activity
import com.hazia.emivita.core.data.settings.TimeFormat
import com.hazia.emivita.core.locale.AppLocale
import com.hazia.emivita.core.format.LocaleFormats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.hazia.emivita.core.platform.AutoStartSettings
import com.hazia.emivita.core.scheduling.AlarmWatchdog
import android.app.AlarmManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.hazia.emivita.core.data.settings.StartPage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import com.hazia.emivita.core.data.settings.TodayLayout
import com.hazia.emivita.ui.components.CollapsibleSection
import com.hazia.emivita.ui.theme.EmivitaTheme
import androidx.annotation.StringRes

/** Shared by every Settings detail screen in this package, including
 *  BackupSyncScreen's -- `internal`, not `private`, because a file-private
 *  top-level function can't cross files even inside one package, which is
 *  how this ended up written twice. */
@Composable
internal fun DetailHeader(title: String, onBack: () -> Unit, actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Spacer(Modifier.width(4.dp))
        // One line, ellipsised, so a long medicine name never pushes the
        // actions off the row (§9 #34; the medicine detail uses this too).
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (actions != null) actions()
    }
    Spacer(Modifier.height(20.dp))
}

/** Alarms & Snooze (spec §9 item 12) -- moves DEFAULT_SNOOZE_MINUTES from a
 *  hardcoded constant to a persisted setting. Alarm sound/vibration aren't
 *  built yet (no audio-picker work has landed anywhere in this app) -- this
 *  screen is scoped to what's actually wired end-to-end: snooze length. */
@Composable
fun AlarmsSnoozeScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    var minutesText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        minutesText = settingsRepository.snoozeDurationMinutes.first().toString()
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader(stringResource(R.string.settings_detail_alarms_snooze), onBack)

        val current = minutesText
        if (current == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        val minutesValue = current.toIntOrNull()
        OutlinedTextField(
            value = current,
            onValueChange = { minutesText = it },
            label = { Text(stringResource(R.string.settings_detail_snooze_duration_minutes)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_detail_how_long_a_snoozed_reminder_waits_before),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = minutesValue != null && minutesValue > 0,
            onClick = {
                val value = minutesValue ?: return@Button
                scope.launch {
                    settingsRepository.setSnoozeDurationMinutes(value)
                    onBack()
                }
            },
        ) { Text(stringResource(R.string.common_save)) }

        Spacer(Modifier.height(8.dp))
        val askEachTime by settingsRepository.snoozeAskEachTime.collectAsState(initial = false)
        SettingSwitchRow(
            title = stringResource(R.string.settings_snooze_ask_each_time),
            description = stringResource(R.string.settings_snooze_ask_each_time_desc),
            checked = askEachTime,
        ) { scope.launch { settingsRepository.setSnoozeAskEachTime(it) } }

        // §9 #25 -- what a swipe records. Saved on tap; one selectable node
        // per option (Stage B).
        Spacer(Modifier.height(24.dp))
        val swipe by settingsRepository.swipeAction.collectAsState(initial = null)
        Text(stringResource(R.string.settings_swipe_title), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.settings_swipe_desc),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        for (option in SwipeAction.entries) {
            Row(
                Modifier.fillMaxWidth().selectable(selected = swipe == option, role = Role.RadioButton) {
                    scope.launch { settingsRepository.setSwipeAction(option) }
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = swipe == option, onClick = null)
                Text(stringResource(option.labelRes()))
            }
        }

        // §9 #11 / #25 -- hidden names.
        Spacer(Modifier.height(16.dp))
        val hideNames by settingsRepository.hideMedicineNames.collectAsState(initial = false)
        Row(
            Modifier.fillMaxWidth().toggleable(value = hideNames, role = Role.Switch) { checked ->
                scope.launch { settingsRepository.setHideMedicineNames(checked) }
            },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_hide_names), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_hide_names_desc),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = hideNames, onCheckedChange = null)
        }

        AlarmSettingsSection(settingsRepository)
    }
}

@Composable
private fun AlarmSettingsSection(settingsRepository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toneUri by settingsRepository.alarmRingtoneUri.collectAsState(initial = null)
    val noSound by settingsRepository.alarmNoSoundWhenSilent.collectAsState(initial = false)
    val noVibration by settingsRepository.alarmNoVibrationWhenSilent.collectAsState(initial = false)
    val alarmClock by settingsRepository.useAlarmClock.collectAsState(initial = false)

    // The system ringtone picker, filtered to alarm tones (medTimer uses the
    // preferencex picker for the same). The chosen Uri is a content grant we
    // hold as a string; AlarmActivity falls back to the default if it dies.
    val pickTone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            scope.launch { settingsRepository.setAlarmRingtoneUri(picked?.toString()) }
        }
    }
    val toneTitle = remember(toneUri) {
        val uri = toneUri?.let { it.toUri() } ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
    }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.settings_alarm_tier_title), style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(R.string.settings_alarm_tier_desc),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_alarm_tone_pick)) {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, toneUri?.let { it.toUri() } ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
            }
            runCatching { pickTone.launch(intent) }
        }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_alarm_tone), style = MaterialTheme.typography.bodyLarge)
            Text(
                toneTitle ?: stringResource(R.string.settings_alarm_tone_default),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SettingSwitchRow(
        title = stringResource(R.string.settings_alarm_no_sound_when_silent),
        description = stringResource(R.string.settings_alarm_no_sound_when_silent_desc),
        checked = noSound,
    ) { scope.launch { settingsRepository.setAlarmNoSoundWhenSilent(it) } }
    SettingSwitchRow(
        title = stringResource(R.string.settings_alarm_no_vibration_when_silent),
        description = stringResource(R.string.settings_alarm_no_vibration_when_silent_desc),
        checked = noVibration,
    ) { scope.launch { settingsRepository.setAlarmNoVibrationWhenSilent(it) } }
    SettingSwitchRow(
        title = stringResource(R.string.settings_use_alarm_clock),
        description = stringResource(R.string.settings_use_alarm_clock_desc),
        checked = alarmClock,
    ) { scope.launch { settingsRepository.setUseAlarmClock(it) } }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = { postTestReminder(context) }) { Text(stringResource(R.string.settings_test_reminder)) }
    Text(
        stringResource(R.string.settings_test_reminder_desc),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One toggleable node per switch row (Stage B pattern). */
@Composable
private fun SettingSwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun SwipeAction.labelRes(): Int = when (this) {
    SwipeAction.NOTHING -> R.string.settings_swipe_nothing
    SwipeAction.SNOOZE -> R.string.settings_swipe_snooze
    SwipeAction.SKIP -> R.string.settings_swipe_skip
}


/** Weekend Mode (spec §3/§9 item 12) -- wires the already-existing Settings
 *  destination to the global preferences SchedulingEngine reads
 *  (applyWeekendMode): a day-of-week set + one push-to time window.
 *
 *  This used to say "applied uniformly to every reminder type, no
 *  per-reminder opt-out", which stopped being true at §7 R36: the global
 *  setting here is necessary but not sufficient, and each reminder opts in
 *  via Reminder.weekendModeOptIn. Third copy of that stale sentence found
 *  and corrected (§7 R58 fixed the first two). */
@Composable
fun WeekendModeScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val schedulingCoordinator = LocalAppContainer.current.schedulingCoordinator
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(setOf<Int>()) }
    var startHour by remember { mutableIntStateOf(9) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(18) }
    var endMinute by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        enabled = settingsRepository.weekendModeEnabled.first()
        days = settingsRepository.weekendDays.first()
        val start = settingsRepository.weekendStartTime.first()
        startHour = start.hour
        startMinute = start.minute
        val end = settingsRepository.weekendEndTime.first()
        endHour = end.hour
        endMinute = end.minute
        loaded = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader(stringResource(R.string.settings_detail_weekend_mode), onBack)

        if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Row(
            // One switch node, not a clickable row plus a live Switch (§9 #19 Stage B).
            Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch) { enabled = it },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_detail_delay_reminders_on_chosen_days), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_detail_pushes_reminders_that_fall_inside_the_window),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_detail_days), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Day names from java.time, which already localises them (§9 #19).
            (1..7).forEach { dayNum ->
                val label = DayOfWeek.of(dayNum).getDisplayName(TextStyle.SHORT, Locale.current.platformLocale)
                FilterChip(
                    selected = dayNum in days,
                    onClick = { days = if (dayNum in days) days - dayNum else days + dayNum },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeOfDayField(stringResource(R.string.common_window_start), startHour, startMinute) { h, m -> startHour = h; startMinute = m }
            TimeOfDayField(stringResource(R.string.common_window_end), endHour, endMinute) { h, m -> endHour = h; endMinute = m }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            scope.launch {
                settingsRepository.setWeekendModeEnabled(enabled)
                settingsRepository.setWeekendDays(days)
                settingsRepository.setWeekendStartTime(LocalTime.of(startHour, startMinute))
                settingsRepository.setWeekendEndTime(LocalTime.of(endHour, endMinute))
                // Weekend mode is read by SchedulingEngine at compute time, but
                // nothing observes these DataStore keys: without this, already
                // armed alarms keep their old times and the projection cache
                // (which watches the medicines/reminders tables, not DataStore)
                // never recomputes, so a saved change did nothing until the next
                // reboot. Before onBack(), not after -- dismissing the sheet
                // leaves the composition and cancels this scope mid-flight.
                // See docs/SPEC.md §7 R23.
                schedulingCoordinator.rescheduleAll()
                onBack()
            }
        }) { Text(stringResource(R.string.common_save)) }
    }
}

/** Notifications & Alerts -- the 3 [com.hazia.emivita.core.notification.ReminderNotificationChannels]
 *  tiers (Standard/High/Alarm) are real, working channels already; what's
 *  actually missing in-app for "sound, priority, how reminders behave" is
 *  per-channel sound/vibration, and Android deliberately makes that
 *  system-settings-only once a channel exists (an app can't override a
 *  user's own per-channel choice) -- so this screen explains the three
 *  tiers and deep-links into the OS's own per-app notification settings,
 *  the correct way to reach that control, rather than faking an in-app
 *  sound picker Android would silently ignore. */
@Composable
fun NotificationsAlertsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsRepository = LocalAppContainer.current.settingsRepository
    val scope = rememberCoroutineScope()
    // The global default behind each medicine's "use global default" lock
    // option (§7 R60 D F2). The key, its flow and its setter existed; no
    // screen called the setter, so "inherit" always inherited false and the
    // medicine form's first radio button was a promise nothing kept.
    val lockByDefault by settingsRepository.lockRemindersUntilTakenByDefault.collectAsState(initial = false)

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader(stringResource(R.string.settings_detail_notifications_alerts), onBack)

        Text(
            stringResource(R.string.settings_detail_every_reminder_posts_to_one_of_three),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        // Names and descriptions come from the same resources the channels
        // themselves are created with, so this screen cannot describe a tier
        // differently from how Android's own settings do (§9 #19).
        NotificationTierRow(stringResource(R.string.tier_standard), stringResource(R.string.channel_standard_desc))
        NotificationTierRow(stringResource(R.string.tier_high), stringResource(R.string.channel_high_desc))
        NotificationTierRow(stringResource(R.string.tier_alarm), stringResource(R.string.channel_alarm_desc))

        // First of the three on purpose: this is the only one of them that
        // means nothing arrives at all. The other two describe a reminder
        // that is degraded or late, which is a smaller problem and stays
        // true underneath this one.
        // One place for the four things Android can take away (§9 #29, HM-37):
        // a row each with its state, the card with the reasons only under a
        // blocked one. Re-read on every resume, since all four change in
        // system settings behind the app's back.
        PermissionsSection(missedAlarmAt = AlarmWatchdog.notice.collectAsState().value)
        NotificationsBlockedCard()
        FullScreenAlarmAccessCard()
        BatteryOptimizationCard()

        Spacer(Modifier.height(20.dp))
        Row(
            // One switch node, not a clickable row plus a live Switch (§9 #19 Stage B).
            Modifier.fillMaxWidth().toggleable(value = lockByDefault, role = Role.Switch) { checked ->
                scope.launch { settingsRepository.setLockRemindersUntilTakenByDefault(checked) }
            },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_lock_until_taken_default), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_lock_until_taken_default_desc),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = lockByDefault, onCheckedChange = null)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.settings_detail_sound_and_vibration_for_each_channel_are),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { context.startActivity(appNotificationSettings(context)) }) {
            Text(stringResource(R.string.settings_detail_open_system_notification_settings))
        }
    }
}

/**
 * Shown only while the Alarm tier can't actually do what the row above
 * promises it does.
 *
 * `ReminderNotificationBuilder.configureAlarmPresentation` already checks
 * [NotificationManagerCompat.canUseFullScreenIntent] and degrades to a
 * tap-to-open action when it's false -- correct, but silent: it writes one
 * `Log.i` line and carries on. From Android 14 the permission is not granted
 * at install to an app outside the calling/alarm categories, and the user can
 * revoke it at any time, so the Alarm tier can arrive already degraded on a
 * fresh install with nothing in the app saying so and no way back.
 *
 * Re-read on ON_RESUME rather than through an activity result: this is a
 * special-access settings screen, and those don't deliver a result to the
 * caller, so `rememberLauncherForActivityResult` would never fire.
 */
@Composable
private fun FullScreenAlarmAccessCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(NotificationManagerCompat.from(context).canUseFullScreenIntent()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationManagerCompat.from(context).canUseFullScreenIntent()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    Spacer(Modifier.height(16.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.settings_detail_alarm_reminders_can_t_take_over_the_screen),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_detail_right_now_an_alarm_tier_reminder_arrives_as),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { context.startActivity(fullScreenIntentSettings(context)) }) {
                Text(stringResource(R.string.settings_detail_allow_full_screen_alarms))
            }
        }
    }
}


/**
 * Warns when the OS is allowed to defer this app's alarms, and offers the way
 * out (spec §7 R39).
 *
 * Measured, not assumed. On a OnePlus 9 Pro (OxygenOS, Android 14) with
 * battery saver on, a reminder scheduled for 19:50 via
 * `setExactAndAllowWhileIdle` — with `USE_EXACT_ALARM` held and
 * `canScheduleExact()` returning true, so the strongest scheduling call the
 * platform offers — arrived at about 19:55. `dumpsys alarm` showed the OS
 * carrying a four-minute tolerance window on that firing and a **one hour**
 * window on the next day's, with a `battery_saver` policy delta applied and
 * OxygenOS's own app freezer having suspended the process. Everything the app
 * can do at the AlarmManager layer was already being done; the remaining lever
 * is the battery-optimization exemption, which only the user can grant.
 *
 * An app whose entire purpose is timely reminders should say so when it is
 * being throttled rather than quietly arriving late, which is what it did
 * before this card existed.
 *
 * **No new permission.** `isIgnoringBatteryOptimizations` is a plain query and
 * `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` opens the system list, neither
 * of which requires holding `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — that one
 * is only needed to pop the direct allow-this-app dialog. AndroidManifest.xml
 * removed it on least-privilege grounds and it stays removed: the user takes
 * the last step in Settings, which is one extra tap and keeps §1's permission
 * posture intact.
 *
 * Re-read on ON_RESUME for the same reason [FullScreenAlarmAccessCard] does:
 * this is a special-access settings screen and does not deliver a result.
 */
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isExempt(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    var exempt by remember { mutableStateOf(isExempt()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = isExempt()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (exempt) return

    val settingsIntent = remember { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
    // Every device that reaches this app has the screen (it is standard from
    // API 23 and minSdk is 30), but an OEM can remove it, and starting an
    // unresolvable intent throws.
    val resolvable = remember {
        settingsIntent.resolveActivity(context.packageManager) != null ||
            context.packageManager.queryIntentActivities(settingsIntent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }

    Spacer(Modifier.height(16.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.settings_detail_reminders_can_arrive_late),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_detail_battery_optimisation_lets_the_system_hold),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (resolvable) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = { context.startActivity(settingsIntent) }) {
                    Text(stringResource(R.string.settings_detail_open_battery_settings))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_detail_find_emivita_in_the_list_and_set_it_to_not),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            // The OEM's own kill list (§9 #29, HM-36), when this phone has one.
            val autoStart = remember { AutoStartSettings.intentFor(context) }
            if (autoStart != null) {
                Spacer(Modifier.height(6.dp))
                Button(onClick = { context.startActivity(autoStart) }) { Text(stringResource(R.string.settings_open_auto_start)) }
                Text(
                    stringResource(R.string.settings_open_auto_start_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * Shown while notifications cannot actually reach the user (spec §7 R58).
 *
 * The audit that opened R58 found the app already had a card for the Alarm
 * tier arriving degraded, and a card for reminders arriving late, and nothing
 * at all for reminders not arriving. Nothing in the tree called
 * `areNotificationsEnabled()`. `EmivitaApp` asks for POST_NOTIFICATIONS once
 * and never again -- correct, PERM-023/024 -- and `postIfPermitted` then
 * drops each notification with a `Log.w` nobody reads, so the app looks
 * entirely healthy while doing nothing.
 *
 * Passive, like both its siblings: it states what is happening and offers the
 * way out. It does not re-prompt for the permission, which is the decision
 * `EmivitaApp` already respected and this card is not here to reopen.
 *
 * Re-read on ON_RESUME for the same reason the other two are: the user leaves
 * for a system settings screen that delivers no result to the caller, so
 * `rememberLauncherForActivityResult` would never fire.
 */
@Composable
private fun NotificationsBlockedCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun read(): NotificationDelivery = notificationDelivery(
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        blockedTiers = ReminderNotificationChannels.blockedTiers(context),
    )

    var delivery by remember { mutableStateOf(read()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                delivery = read()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val titleText = delivery.cardTitle() ?: return
    val bodyText = delivery.cardBody() ?: return
    val title = stringResource(titleText.res, *titleText.args.toTypedArray())
    val body = stringResource(bodyText.res, *bodyText.args.toTypedArray())

    Spacer(Modifier.height(16.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { context.startActivity(appNotificationSettings(context)) }) {
                Text(stringResource(R.string.settings_detail_open_notification_settings))
            }
        }
    }
}

/** Android's own per-app notification screen -- the only place either an
 *  app-level block or a per-channel one can be undone, since an app cannot
 *  override a user's channel choice once the channel exists. */
private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

/** The dedicated special-access screen exists from Android 14; below that the
 *  permission is install-granted and this card shouldn't be showing at all, so
 *  the per-app notification settings are the honest fallback rather than an
 *  intent that would fail to resolve. */
private fun fullScreenIntentSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.fromParts("package", context.packageName, null),
        )
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }

@Composable
private fun NotificationTierRow(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Appearance -- palette + theme mode. The first profile is created silently
 *  with whatever `SettingsRepository.palette`'s own default is (see
 *  `EmivitaApp.kt`, spec §9 item 1 follow-up -- no onboarding screen exists
 *  to have asked); this is the "change your mind" path the Settings row
 *  always promised. Own copy of the palette-grid layout (small enough not
 *  to be worth extracting into a shared composable). */
@Composable
fun AppearanceScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // null until DataStore answers, so the cards never show the default as
    // chosen for a frame and then jump (the loaded gate R56 had, kept).
    val palette by produceState<MedPalette?>(initialValue = null) { settingsRepository.palette.collect { value = it } }
    val themeMode by produceState<ThemeMode?>(initialValue = null) { settingsRepository.themeMode.collect { value = it } }
    val todayLayout by produceState<TodayLayout?>(initialValue = null) { settingsRepository.todayLayout.collect { value = it } }
    val timeFormat by settingsRepository.timeFormat.collectAsState(initial = TimeFormat.SYSTEM)
    var languageTag by remember { mutableStateOf(AppLocale.current(context)) }
    var themeOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader(stringResource(R.string.common_appearance), onBack)

        val currentPalette = palette
        val currentMode = themeMode
        val currentLayout = todayLayout
        if (currentPalette == null || currentMode == null || currentLayout == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        // Everything on this screen applies on the tap (§7 R76): the palette
        // and mode re-theme the app under the finger through EmivitaApp's
        // collected flows. The Save button that used to sit below the fold
        // -- the reason R75's pass saw nothing change -- is gone.
        CollapsibleSection(
            title = stringResource(R.string.appearance_theme_section),
            summary = stringResource(R.string.appearance_theme_summary, stringResource(currentMode.labelRes()), currentPalette.displayName()),
            expanded = themeOpen,
            onToggle = { themeOpen = !themeOpen },
        ) {
            Text(stringResource(R.string.settings_detail_theme_mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            // FlowRow, not Row (§9 #34): four chips do not fit a phone width in
            // Italian, and a Row answered by breaking "AMOLED" across three lines.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentMode == mode,
                        onClick = { scope.launch { settingsRepository.setThemeMode(mode) } },
                        label = { Text(stringResource(mode.labelRes()), maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.settings_detail_palette), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            OptionGrid(MedPalette.entries) { option ->
                PaletteCard(
                    palette = option,
                    themeMode = currentMode,
                    selected = option == currentPalette,
                    onClick = { scope.launch { settingsRepository.setPalette(option) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.appearance_layout_section), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OptionGrid(TodayLayout.entries) { option ->
            LayoutCard(
                layout = option,
                selected = option == currentLayout,
                onClick = { scope.launch { settingsRepository.setTodayLayout(option) } },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(20.dp))
        // Open on (§9 #29, HM-33): applies at the next launch, saved on tap.
        val startPage by settingsRepository.startPage.collectAsState(initial = StartPage.TODAY)
        Text(stringResource(R.string.settings_start_page), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StartPage.entries.forEach { page ->
                FilterChip(
                    selected = startPage == page,
                    onClick = { scope.launch { settingsRepository.setStartPage(page) } },
                    label = { Text(stringResource(when (page) { StartPage.TODAY -> R.string.nav_today; StartPage.MEDICINES -> R.string.nav_medicines; StartPage.HISTORY -> R.string.nav_history })) },
                )
            }
        }
        Text(
            stringResource(R.string.settings_start_page_desc),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        // Language and time format (§9 #28) apply on the spot like the rest.
        LanguageAndTimeSection(
            languageTag = languageTag,
            languages = AppLocale.supported(context),
            onLanguage = { tag ->
                languageTag = tag
                AppLocale.set(context, tag)
                if (AppLocale.needsRecreate) (context as? Activity)?.recreate()
            },
            timeFormat = timeFormat,
            onTimeFormat = { scope.launch { settingsRepository.setTimeFormat(it) } },
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Two options per row, the last row padded so a lone card keeps its width. */
@Composable
private fun <T> OptionGrid(options: List<T>, card: @Composable RowScope.(T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { card(it) }
                if (pair.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A language is named in itself ("Italiano", not "Italian"), the platform
 *  convention, so a person who cannot read the current language still finds
 *  theirs; the pseudolocales show their tags. */
internal fun languageDisplayName(tag: String?, systemLabel: String): String {
    if (tag == null) return systemLabel
    val locale = java.util.Locale.forLanguageTag(tag)
    val name = locale.getDisplayName(locale)
    return if (tag.contains("-X")) "$name ($tag)" else name.replaceFirstChar { it.titlecase(locale) }
}

@Composable
internal fun LanguageAndTimeSection(
    languageTag: String?,
    languages: List<String>,
    onLanguage: (String?) -> Unit,
    timeFormat: TimeFormat,
    onTimeFormat: (TimeFormat) -> Unit,
) {
    var pickingLanguage by remember { mutableStateOf(false) }
    val systemLabel = stringResource(R.string.settings_language_system)

    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_language_change)) { pickingLanguage = true }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(languageDisplayName(languageTag, systemLabel), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_language_change), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_time_format), style = MaterialTheme.typography.labelLarge)
    // A live example beside each option, so the choice is seen, not inferred.
    val example = remember { java.time.LocalDate.now().atTime(20, 5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
    TimeFormat.entries.forEach { option ->
        val label = when (option) {
            TimeFormat.SYSTEM -> stringResource(R.string.settings_time_format_system)
            TimeFormat.H12 -> stringResource(R.string.settings_time_format_12)
            TimeFormat.H24 -> stringResource(R.string.settings_time_format_24)
        }
        Row(
            Modifier.fillMaxWidth().selectable(selected = timeFormat == option, role = Role.RadioButton) { onTimeFormat(option) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = timeFormat == option, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text(label, modifier = Modifier.weight(1f))
            Text(
                exampleTime(option, example),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pickingLanguage) {
        AlertDialog(
            onDismissRequest = { pickingLanguage = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = { LanguageList(languageTag, languages) { pickingLanguage = false; onLanguage(it) } },
            confirmButton = { TextButton(onClick = { pickingLanguage = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** One radio node per language, "System default" first (§9 #28). */
@Composable
internal fun LanguageList(languageTag: String?, languages: List<String>, onLanguage: (String?) -> Unit) {
    val systemLabel = stringResource(R.string.settings_language_system)
    Column {
        (listOf<String?>(null) + languages).forEach { tag ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = languageTag == tag, role = Role.RadioButton) { onLanguage(tag) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = languageTag == tag, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(languageDisplayName(tag, systemLabel))
            }
        }
    }
}

/** The example rendered the way [option] would render it, without touching
 *  the process-wide hooks. */
internal fun exampleTime(option: TimeFormat, epochMillis: Long): String {
    val saved = LocaleFormats.hour24Override
    return try {
        LocaleFormats.hour24Override = when (option) { TimeFormat.SYSTEM -> null; TimeFormat.H12 -> false; TimeFormat.H24 -> true }
        LocaleFormats.time(epochMillis)
    } finally {
        LocaleFormats.hour24Override = saved
    }
}

/**
 * One palette, drawn in itself (§7 R76): the card sits inside its own
 * [EmivitaTheme], so its three swatches -- primary, attention, the ground --
 * and its name are that palette's, in the mode the screen is in. Until R76
 * every card showed one dot of its light primary and the selected card was
 * tinted in the *current* palette's green whatever it named.
 */
@Composable
internal fun PaletteCard(palette: MedPalette, themeMode: ThemeMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    EmivitaTheme(palette = palette, themeMode = themeMode) {
        OptionCard(
            selected = selected,
            onClick = onClick,
            label = palette.displayName(),
            modifier = modifier.semantics { contentDescription = palette.displayName() },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Swatch(MaterialTheme.colorScheme.primary)
                Swatch(EmivitaTheme.colors.attention)
                Swatch(MaterialTheme.colorScheme.surface, outlined = true)
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, outlined: Boolean = false) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color)
            .let { if (outlined) it.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else it },
    )
}

/** One Today layout (§9 #37): a 56 dp line-art of its shape in the palette's
 *  own colours, and its name. */
@Composable
internal fun LayoutCard(layout: TodayLayout, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(layout.labelRes())
    OptionCard(selected = selected, onClick = onClick, label = label, modifier = modifier.semantics { contentDescription = label }) {
        LayoutPreview(layout)
    }
}

@StringRes
internal fun TodayLayout.labelRes(): Int = when (this) {
    TodayLayout.TIMELINE -> R.string.today_layout_timeline
    TodayLayout.GROUPED -> R.string.today_layout_grouped
    TodayLayout.COMPACT -> R.string.today_layout_compact
    TodayLayout.HERO -> R.string.today_layout_hero
}

@Composable
private fun LayoutPreview(layout: TodayLayout) {
    val disc = MaterialTheme.colorScheme.primaryContainer
    val rail = MaterialTheme.colorScheme.primary
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width; val h = size.height
        val r = 5.dp.toPx()
        when (layout) {
            TodayLayout.TIMELINE -> {
                drawLine(rail, Offset(r + 2.dp.toPx(), 0f), Offset(r + 2.dp.toPx(), h), strokeWidth = 2.dp.toPx())
                for (i in 0 until 3) {
                    val y = h * (0.2f + 0.3f * i)
                    drawCircle(disc, r, Offset(r + 2.dp.toPx(), y))
                    drawRoundRect(card, Offset(r * 3.5f, y - r * 1.1f), Size(w - r * 3.5f, r * 2.2f), CornerRadius(r * 0.8f))
                }
            }
            TodayLayout.GROUPED -> {
                for (i in 0 until 3) {
                    val y = h * (0.12f + 0.3f * i)
                    drawCircle(rail, r * 0.35f, Offset(r * 0.5f, y))
                    drawRoundRect(line, Offset(r * 1.5f, y - r * 0.2f), Size(w * 0.3f, r * 0.4f), CornerRadius(r * 0.2f))
                    drawRoundRect(card, Offset(0f, y + r * 0.7f), Size(w, r * 1.6f), CornerRadius(r * 0.6f))
                    drawCircle(disc, r * 0.65f, Offset(r * 1.2f, y + r * 1.5f))
                }
            }
            TodayLayout.COMPACT -> {
                for (i in 0 until 4) {
                    val y = h * (0.12f + 0.25f * i)
                    drawCircle(disc, r * 0.7f, Offset(r, y))
                    drawRoundRect(line, Offset(r * 2.8f, y - r * 0.25f), Size(w * 0.6f, r * 0.5f), CornerRadius(r * 0.2f))
                    drawLine(line, Offset(0f, y + r * 1.4f), Offset(w, y + r * 1.4f), strokeWidth = 1.dp.toPx())
                }
            }
            TodayLayout.HERO -> {
                drawRoundRect(disc, Offset(0f, 0f), Size(w, h * 0.48f), CornerRadius(r))
                drawRoundRect(rail, Offset(r, h * 0.3f), Size(w * 0.3f, h * 0.12f), CornerRadius(r * 0.5f))
                for (i in 0 until 2) {
                    val y = h * (0.66f + 0.2f * i)
                    drawCircle(disc, r * 0.6f, Offset(r, y))
                    drawRoundRect(line, Offset(r * 2.6f, y - r * 0.25f), Size(w * 0.5f, r * 0.5f), CornerRadius(r * 0.2f))
                }
            }
        }
    }
}

/** The shared frame of a palette or layout card: tonal, a 2 dp border in
 *  primary when selected, the check at the top end, the name beneath. */
@Composable
private fun OptionCard(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier, preview: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        // Padding inside the Surface, not on its modifier: outside, the fill
        // was inset from the border and the label's first letter sat on the
        // clipped corner (seen on the phone, §7 R76).
        Box(Modifier.padding(12.dp)) {
            Column {
                preview()
                Spacer(Modifier.height(8.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.settings_detail_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/** What the four rows show (§9 #29, HM-37); pure so the section is testable. */
internal data class PermissionState(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
    val batteryExempt: Boolean,
)

internal fun readPermissionState(context: Context): PermissionState {
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return PermissionState(
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        exactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarms?.canScheduleExactAlarms() ?: true else true,
        fullScreen = NotificationManagerCompat.from(context).canUseFullScreenIntent(),
        batteryExempt = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
    )
}

@Composable
private fun PermissionsSection(missedAlarmAt: Long?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(readPermissionState(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state = readPermissionState(context) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_permissions), style = MaterialTheme.typography.labelLarge)
    PermissionRows(
        state = state,
        onNotifications = { context.startActivity(appNotificationSettings(context)) },
        onExactAlarms = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)))
            }
        },
        onFullScreen = { context.startActivity(fullScreenIntentSettings(context)) },
        onBattery = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
    )
    // The watchdog's finding, beside the row that explains it (§9 #29, DP-04):
    // "may" becomes "did, at …" while a missed alarm is on record.
    missedAlarmAt?.let {
        Text(
            stringResource(R.string.missed_alarm_body, LocaleFormats.monthDayTime(it)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Four merged nodes, each carrying Granted/Blocked as its state and its own
 *  action button when blocked (one node per control, Stage B). */
@Composable
internal fun PermissionRows(
    state: PermissionState,
    onNotifications: () -> Unit,
    onExactAlarms: () -> Unit,
    onFullScreen: () -> Unit,
    onBattery: () -> Unit,
) {
    PermissionRow(stringResource(R.string.permission_notifications), state.notifications, onNotifications)
    PermissionRow(stringResource(R.string.permission_exact_alarms), state.exactAlarms, onExactAlarms)
    PermissionRow(stringResource(R.string.permission_full_screen), state.fullScreen, onFullScreen)
    PermissionRow(stringResource(R.string.permission_battery), state.batteryExempt, onBattery)
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onFix: () -> Unit) {
    val stateLabel = stringResource(if (granted) R.string.permission_granted else R.string.permission_blocked)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { stateDescription = stateLabel },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(stateLabel, style = MaterialTheme.typography.labelMedium, color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        if (!granted) TextButton(onClick = onFix) { Text(stringResource(R.string.permission_fix)) }
    }
}
