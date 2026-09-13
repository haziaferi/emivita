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
import com.hazia.emivita.ui.theme.swatchColorFor
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

/** Shared by every Settings detail screen in this package, including
 *  BackupSyncScreen's -- `internal`, not `private`, because a file-private
 *  top-level function can't cross files even inside one package, which is
 *  how this ended up written twice. */
@Composable
internal fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
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
    var loaded by remember { mutableStateOf(false) }
    var selectedPalette by remember { mutableStateOf(MedPalette.DAYLIGHT) }
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        selectedPalette = settingsRepository.palette.first()
        themeMode = settingsRepository.themeMode.first()
        loaded = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader(stringResource(R.string.common_appearance), onBack)

        if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Text(stringResource(R.string.settings_detail_theme_mode), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { themeMode = mode },
                    label = { Text(stringResource(mode.labelRes())) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_detail_palette), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MedPalette.entries.chunked(2).forEach { rowPalettes ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowPalettes.forEach { palette ->
                        AppearancePaletteOption(
                            palette = palette,
                            selected = palette == selectedPalette,
                            onClick = { selectedPalette = palette },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowPalettes.size < 2) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            scope.launch {
                settingsRepository.setPalette(selectedPalette)
                settingsRepository.setThemeMode(themeMode)
                onBack()
            }
        }) { Text(stringResource(R.string.common_save)) }
    }
}

@Composable
private fun AppearancePaletteOption(
    palette: MedPalette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        Box {
            Column {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(swatchColorFor(palette)),
                )
                Spacer(Modifier.height(8.dp))
                Text(palette.displayName(), style = MaterialTheme.typography.labelMedium)
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
