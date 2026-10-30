package com.hazia.emivita.feature.settings

import com.hazia.emivita.ui.components.AttentionGlyph
import com.hazia.emivita.ui.components.AttentionCard
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.hazia.emivita.ui.theme.labelRes
import androidx.compose.runtime.collectAsState
import com.hazia.emivita.core.data.settings.SwipeAction
import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilledTonalButton
import com.hazia.emivita.core.notification.postTestReminder
import androidx.core.net.toUri
import android.app.Activity
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.feature.permissions.PermissionRows
import com.hazia.emivita.feature.permissions.appNotificationSettings
import com.hazia.emivita.feature.permissions.batteryExemptionRequest
import com.hazia.emivita.feature.permissions.rememberPermissionFixer
import com.hazia.emivita.feature.permissions.rememberPermissionState
import com.hazia.emivita.core.platform.AutoStartSettings
import com.hazia.emivita.core.scheduling.AlarmWatchdog
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.semantics.contentDescription
import com.hazia.emivita.ui.form.FormScaffold
import com.hazia.emivita.ui.form.FormRadioRow
import com.hazia.emivita.ui.form.FormSheetScaffold
import com.hazia.emivita.ui.form.FormSwitchRow
import com.hazia.emivita.ui.form.FormGroupRow
import androidx.compose.runtime.SideEffect
import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.ui.form.SecondaryControlHeight

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

/** What Alarms & Snooze edits, read once and saved together by its Salva (§7 R130, the owner: "One save model"). */
internal data class AlarmsDraft(
    val minutes: String,
    val askEachTime: Boolean,
    val swipe: SwipeAction,
    val toneUri: String?,
    val noSound: Boolean,
    val noVibration: Boolean,
    val alarmClock: Boolean,
) {
    val minutesValue: Int? get() = minutes.toIntOrNull()?.takeIf { it > 0 }
}

private suspend fun SettingsRepository.alarmsDraft() = AlarmsDraft(
    minutes = snoozeDurationMinutes.first().toString(),
    askEachTime = snoozeAskEachTime.first(),
    swipe = swipeAction.first(),
    toneUri = alarmRingtoneUri.first(),
    noSound = alarmNoSoundWhenSilent.first(),
    noVibration = alarmNoVibrationWhenSilent.first(),
    alarmClock = useAlarmClock.first(),
)

private suspend fun SettingsRepository.save(d: AlarmsDraft) {
    d.minutesValue?.let { setSnoozeDurationMinutes(it) }
    setSnoozeAskEachTime(d.askEachTime)
    setSwipeAction(d.swipe)
    setAlarmRingtoneUri(d.toneUri)
    setAlarmNoSoundWhenSilent(d.noSound)
    setAlarmNoVibrationWhenSilent(d.noVibration)
    setUseAlarmClock(d.alarmClock)
}

private enum class AlarmsGroup { SNOOZE, SWIPE, ALARM }

/**
 * Alarms & Snooze (spec §9 item 12), grouped as the medicine form's Avanzate (§7 R124, R130): one
 * row per group says its values in one line and opens the group's sheet, which edits a copy that
 * only its own Salva applies. Nothing is stored until this sheet's Salva; Annulla, the back arrow,
 * Back and the scrim ask when anything was changed (§7 R126).
 */
@Composable
fun AlarmsSnoozeScreen(settingsRepository: SettingsRepository, onBack: () -> Unit, onSaved: () -> Unit = onBack, onDirty: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<AlarmsDraft?>(null) }
    var draft by remember { mutableStateOf<AlarmsDraft?>(null) }
    var open by remember { mutableStateOf<AlarmsGroup?>(null) }
    LaunchedEffect(Unit) {
        val d = settingsRepository.alarmsDraft()
        loaded = d
        draft = d
    }
    val dirty = draft != null && draft != loaded
    SideEffect { onDirty(dirty) }

    FormScaffold(
        title = stringResource(R.string.settings_detail_alarms_snooze),
        saveLabel = stringResource(R.string.common_save),
        saveEnabled = draft?.minutesValue != null,
        onSave = {
            draft?.let { d -> scope.launch { settingsRepository.save(d); onSaved() } }
        },
        onCancel = onBack,
        onBack = onBack,
        inSheet = true,
    ) {
        val d = draft
        if (d == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@FormScaffold
        }
        val toneTitle = rememberToneTitle(d.toneUri)
        FormGroupRow(
            stringResource(R.string.alarms_group_snooze),
            stringResource(if (d.askEachTime) R.string.alarms_snooze_summary_ask else R.string.alarms_snooze_summary_fixed, d.minutesValue ?: 0),
        ) { open = AlarmsGroup.SNOOZE }
        FormGroupRow(stringResource(R.string.settings_swipe_title), stringResource(d.swipe.labelRes())) { open = AlarmsGroup.SWIPE }
        FormGroupRow(stringResource(R.string.settings_alarm_tier_title), toneTitle) { open = AlarmsGroup.ALARM }

        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { postTestReminder(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = SecondaryControlHeight)) { Text(stringResource(R.string.settings_test_reminder)) }
        Text(
            stringResource(R.string.settings_test_reminder_desc),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val close = { open = null }
        when (open) {
            AlarmsGroup.SNOOZE -> SnoozeSheet(d, close) { draft = it; close() }
            AlarmsGroup.SWIPE -> SwipeSheet(d, close) { draft = it; close() }
            AlarmsGroup.ALARM -> AlarmTierSheet(d, close) { draft = it; close() }
            null -> Unit
        }
    }
}

/** The tone's own title, or the system default's. The picker's Uri is a content grant held as a string. */
@Composable
private fun rememberToneTitle(toneUri: String?): String {
    val context = LocalContext.current
    val fallback = stringResource(R.string.settings_alarm_tone_default)
    return remember(toneUri) {
        val uri = toneUri?.toUri() ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
    } ?: fallback
}

@Composable
private fun SnoozeSheet(entry: AlarmsDraft, close: () -> Unit, apply: (AlarmsDraft) -> Unit) {
    var d by remember { mutableStateOf(entry) }
    FormSheetScaffold(stringResource(R.string.alarms_group_snooze), dirty = d != entry, saveEnabled = d.minutesValue != null, onSave = { apply(d) }, close = close) {
        FormTextField(
            d.minutes, { d = d.copy(minutes = it) }, stringResource(R.string.settings_detail_snooze_duration_minutes),
            keyboardType = KeyboardType.Number,
            help = stringResource(R.string.settings_detail_how_long_a_snoozed_reminder_waits_before),
            error = if (d.minutesValue == null) stringResource(R.string.form_min_one) else null,
        )
        FormSwitchRow(stringResource(R.string.settings_snooze_ask_each_time), stringResource(R.string.settings_snooze_ask_each_time_desc), d.askEachTime) {
            d = d.copy(askEachTime = it)
        }
    }
}

@Composable
private fun SwipeSheet(entry: AlarmsDraft, close: () -> Unit, apply: (AlarmsDraft) -> Unit) {
    var d by remember { mutableStateOf(entry) }
    FormSheetScaffold(stringResource(R.string.settings_swipe_title), dirty = d != entry, saveEnabled = true, onSave = { apply(d) }, close = close) {
        Text(stringResource(R.string.settings_swipe_desc), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        for (option in SwipeAction.entries) FormRadioRow(stringResource(option.labelRes()), d.swipe == option) { d = d.copy(swipe = option) }
    }
}

@Composable
private fun AlarmTierSheet(entry: AlarmsDraft, close: () -> Unit, apply: (AlarmsDraft) -> Unit) {
    var d by remember { mutableStateOf(entry) }
    // The system ringtone picker, filtered to alarm tones (medTimer uses the preferencex picker for the
    // same); AlarmActivity falls back to the default if the chosen tone's grant dies.
    val pickTone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            d = d.copy(toneUri = picked?.toString())
        }
    }
    FormSheetScaffold(stringResource(R.string.settings_alarm_tier_title), dirty = d != entry, saveEnabled = true, onSave = { apply(d) }, close = close) {
        Text(stringResource(R.string.settings_alarm_tier_desc), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FormGroupRow(stringResource(R.string.settings_alarm_tone), rememberToneTitle(d.toneUri), stringResource(R.string.settings_alarm_tone_pick)) {
            val current = d.toneUri?.toUri() ?: Settings.System.DEFAULT_ALARM_ALERT_URI
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            }
            runCatching { pickTone.launch(intent) }
        }
        FormSwitchRow(stringResource(R.string.settings_alarm_no_sound_when_silent), stringResource(R.string.settings_alarm_no_sound_when_silent_desc), d.noSound) {
            d = d.copy(noSound = it)
        }
        FormSwitchRow(stringResource(R.string.settings_alarm_no_vibration_when_silent), stringResource(R.string.settings_alarm_no_vibration_when_silent_desc), d.noVibration) {
            d = d.copy(noVibration = it)
        }
        FormSwitchRow(stringResource(R.string.settings_use_alarm_clock), stringResource(R.string.settings_use_alarm_clock_desc), d.alarmClock) {
            d = d.copy(alarmClock = it)
        }
    }
}

private fun SwipeAction.labelRes(): Int = when (this) {
    SwipeAction.NOTHING -> R.string.settings_swipe_nothing
    SwipeAction.SNOOZE -> R.string.settings_swipe_snooze
    SwipeAction.SKIP -> R.string.settings_swipe_skip
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

        // One place for the four things Android can take away (§9 #29, HM-37),
        // re-read on every resume since all four change behind the app's back.
        // The blocked-notifications card leads the three cards on purpose: it is
        // the only one that means nothing arrives at all (§7 R58).
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
 * Warns when the OS is allowed to defer this app's alarms, and offers the way
 * out (spec §7 R39): measured on a OnePlus 9 Pro with battery saver on, an
 * exact alarm with `USE_EXACT_ALARM` held still arrived about five minutes
 * late, and the exemption is the one remaining lever, which only the user can
 * grant.
 *
 * The owner chose the system's direct allow-this-app dialog over the plain
 * list, so the manifest declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and
 * the list stays as the fallback (§7 R83). Re-read on ON_RESUME like
 * [FullScreenAlarmAccessCard], since a special-access screen delivers no result.
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

    // The one-tap system dialog since §7 R83; the plain list where an OEM removed it.
    val oneTap = remember { batteryExemptionRequest(context).takeIf { it.resolveActivity(context.packageManager) != null } }
    val settingsIntent = remember { oneTap ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
    // Every device that reaches this app has the screen (it is standard from
    // API 23 and minSdk is 30), but an OEM can remove it, and starting an
    // unresolvable intent throws.
    val resolvable = remember {
        settingsIntent.resolveActivity(context.packageManager) != null ||
            context.packageManager.queryIntentActivities(settingsIntent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }

    Spacer(Modifier.height(16.dp))
    AttentionCard(stringResource(R.string.settings_detail_reminders_can_arrive_late)) {
        Text(
            stringResource(R.string.settings_detail_battery_optimisation_lets_the_system_hold),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (resolvable) {
            Spacer(Modifier.height(10.dp))
            // The owner's wording (§7 R88): "Allow", as every grant reads on the
            // permission sheet, when it opens the one-tap dialog; the list keeps
            // "Open battery settings", which is what it opens.
            Button(onClick = { context.startActivity(settingsIntent) }) {
                Text(stringResource(if (oneTap != null) R.string.permission_allow else R.string.settings_detail_open_battery_settings))
            }
            // The search hint belongs to the list only; the one-tap dialog
            // needs none (found on the phone, §7 R83).
            if (oneTap == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_detail_find_emivita_in_the_list_and_set_it_to_not),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The OEM's own kill list (§9 #29, HM-36), when this phone has one.
        val autoStart = remember { AutoStartSettings.intentFor(context) }
        // A page that resolves can still refuse to open (an OEM that exports it
        // but guards it); say so instead of a button that does nothing (§7.1 rule 17).
        var autoStartFailed by remember { mutableStateOf(false) }
        if (autoStart != null) {
            Spacer(Modifier.height(6.dp))
            if (autoStartFailed) {
                Text(
                    stringResource(R.string.settings_open_auto_start_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(onClick = {
                    try {
                        context.startActivity(autoStart)
                    } catch (e: RuntimeException) {
                        // ActivityNotFoundException and SecurityException are both RuntimeExceptions.
                        autoStartFailed = true
                    }
                }) { Text(stringResource(R.string.settings_open_auto_start)) }
            }
            Text(
                stringResource(R.string.settings_open_auto_start_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown while notifications cannot actually reach the user (spec §7 R58),
 * since `postIfPermitted` otherwise drops each one with a `Log.w` nobody reads.
 *
 * Passive like its siblings: it never re-prompts for POST_NOTIFICATIONS,
 * which `EmivitaApp` asks for once by design (PERM-023/024). Re-read on
 * ON_RESUME, because the system settings screen delivers no result, so
 * `rememberLauncherForActivityResult` would never fire.
 */
@Composable
private fun NotificationsBlockedCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun read(): NotificationDelivery = notificationDelivery(
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        blockedTiers = ReminderNotificationChannels.blockedTiers(context),
        loweredTiers = ReminderNotificationChannels.loweredTiers(context),
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
    AttentionCard(title) {
        Text(
            body,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { context.startActivity(appNotificationSettings(context)) }) {
            Text(stringResource(R.string.settings_detail_open_notification_settings))
        }
    }
}

@Composable
private fun NotificationTierRow(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionsSection(missedAlarmAt: Long?) {
    val state = rememberPermissionState()
    val onFix = rememberPermissionFixer(LocalAppContainer.current.settingsRepository)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_permissions), style = MaterialTheme.typography.labelLarge)
    PermissionRows(state = state, onFix = onFix)
    // The watchdog's finding, beside the row that explains it (§9 #29, DP-04):
    // "may" becomes "did, at …" while a missed alarm is on record.
    missedAlarmAt?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttentionGlyph()
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.missed_alarm_body, LocaleFormats.monthDayTime(it)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
