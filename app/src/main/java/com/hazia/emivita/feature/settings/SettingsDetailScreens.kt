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
import com.hazia.emivita.ui.components.TimeOfDayField
import com.hazia.emivita.ui.theme.MedPalette
import com.hazia.emivita.ui.theme.ThemeMode
import com.hazia.emivita.ui.theme.displayName
import com.hazia.emivita.ui.theme.swatchColorFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Shared by every Settings detail screen in this package, including
 *  BackupSyncScreen's -- `internal`, not `private`, because a file-private
 *  top-level function can't cross files even inside one package, which is
 *  how this ended up written twice. */
@Composable
internal fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        DetailHeader("Alarms & Snooze", onBack)

        val current = minutesText
        if (current == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        val minutesValue = current.toIntOrNull()
        OutlinedTextField(
            value = current,
            onValueChange = { minutesText = it },
            label = { Text("Snooze duration (minutes)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "How long a snoozed reminder waits before firing again.",
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
        ) { Text("Save") }
    }
}

private val weekendDayLabels = listOf("Mon" to 1, "Tue" to 2, "Wed" to 3, "Thu" to 4, "Fri" to 5, "Sat" to 6, "Sun" to 7)

/** Weekend Mode (spec §3/§9 item 12) -- wires the already-existing Settings
 *  destination to the global preferences SchedulingEngine now reads
 *  (applyWeekendMode). A day-of-week set + one push-to time window, applied
 *  uniformly to every reminder type -- matches medTimer's own
 *  WeekendModeSchedulingDecorator (spec §5), no per-reminder opt-out. */
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
        DetailHeader("Weekend Mode", onBack)

        if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().clickable { enabled = !enabled },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Delay reminders on chosen days", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Pushes reminders that fall inside the window below to its end time.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Spacer(Modifier.height(20.dp))
        Text("Days", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            weekendDayLabels.forEach { (label, dayNum) ->
                FilterChip(
                    selected = dayNum in days,
                    onClick = { days = if (dayNum in days) days - dayNum else days + dayNum },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeOfDayField("Window start", startHour, startMinute) { h, m -> startHour = h; startMinute = m }
            TimeOfDayField("Window end", endHour, endMinute) { h, m -> endHour = h; endMinute = m }
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
        }) { Text("Save") }
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

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader("Notifications & Alerts", onBack)

        Text(
            "Every reminder posts to one of three channels, by the medicine's notification priority.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        NotificationTierRow("Standard", "Regular reminders for medicines and supplements.")
        NotificationTierRow("High", "Higher-priority reminders that shouldn't be easy to miss.")
        NotificationTierRow("Alarm", "Full-screen alarm reminders for medicines that must not be skipped.")

        FullScreenAlarmAccessCard()
        BatteryOptimizationCard()

        Spacer(Modifier.height(20.dp))
        Text(
            "Sound and vibration for each channel are controlled by Android itself, " +
                "not this app -- open the system notification settings to adjust them.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        }) { Text("Open system notification settings") }
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
                "Alarm reminders can't take over the screen",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Right now an Alarm-tier reminder arrives as an ordinary notification you have to " +
                    "notice and tap, instead of waking the screen. Allow full-screen notifications to " +
                    "restore it.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { context.startActivity(fullScreenIntentSettings(context)) }) {
                Text("Allow full-screen alarms")
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
                "Reminders can arrive late",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Battery optimisation lets the system hold this app's alarms back, so a dose can be " +
                    "announced minutes after it was due — measured at about five on one phone, and the " +
                    "system reserves the right to defer a daily reminder by up to an hour. Excluding " +
                    "Emivita from optimisation is the only remaining way to stop that.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (resolvable) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = { context.startActivity(settingsIntent) }) {
                    Text("Open battery settings")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Find Emivita in the list and set it to not optimised.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
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
        DetailHeader("Appearance", onBack)

        if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Text("Theme mode", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { themeMode = mode },
                    label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Palette", style = MaterialTheme.typography.labelLarge)
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
        }) { Text("Save") }
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
            .clickable(onClick = onClick)
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
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}
