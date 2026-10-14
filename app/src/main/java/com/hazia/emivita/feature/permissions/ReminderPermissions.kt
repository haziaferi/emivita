package com.hazia.emivita.feature.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hazia.emivita.R
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.ui.components.AttentionGlyph
import kotlinx.coroutines.launch

/** The four grants a reminder depends on (§9 #29, HM-37; asked for since §7 R83). */
enum class ReminderPermission(val label: Int) {
    NOTIFICATIONS(R.string.permission_notifications),
    EXACT_ALARMS(R.string.permission_exact_alarms),
    FULL_SCREEN(R.string.permission_full_screen),
    BATTERY(R.string.permission_battery),
}

/** What the four rows show; pure so the rows are testable. */
internal data class PermissionState(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
    val batteryExempt: Boolean,
) {
    val missing: List<ReminderPermission>
        get() = listOfNotNull(
            ReminderPermission.NOTIFICATIONS.takeUnless { notifications },
            ReminderPermission.EXACT_ALARMS.takeUnless { exactAlarms },
            ReminderPermission.FULL_SCREEN.takeUnless { fullScreen },
            ReminderPermission.BATTERY.takeUnless { batteryExempt },
        )

    fun granted(p: ReminderPermission): Boolean = p !in missing
}

/** The card's dismissal is kept per set of missing grants, so a new one shows it again. */
internal fun missingKey(missing: List<ReminderPermission>): String = missing.map { it.name }.sorted().joinToString(",")

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

/** Re-read on every resume: each grant is changed on a system screen that returns no result. */
@Composable
internal fun rememberPermissionState(): PermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(readPermissionState(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state = readPermissionState(context) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Android's own per-app notification screen -- the only place either an
 *  app-level block or a per-channel one can be undone, since an app cannot
 *  override a user's channel choice once the channel exists. */
internal fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

/** The dedicated special-access screen exists from Android 14; below that the
 *  permission is install-granted and this card shouldn't be showing at all, so
 *  the per-app notification settings are the honest fallback rather than an
 *  intent that would fail to resolve. */
internal fun fullScreenIntentSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.fromParts("package", context.packageName, null),
        )
    } else {
        appNotificationSettings(context)
    }

/** The system's one-tap "let this app run in the background" dialog (§7 R83,
 *  the owner's choice over the plain list). The manifest declares the
 *  permission it needs; an OEM that removed the dialog gets the list. */
@SuppressLint("BatteryLife")
internal fun batteryExemptionRequest(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))

private fun Context.startFirst(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // the next one
        }
    }
}

/**
 * Opens the right place for each grant. Notifications are asked for in the
 * system's own dialog the first time; after a refusal Android stops showing
 * that dialog, so later taps go to the notification settings instead.
 */
@Composable
internal fun rememberPermissionFixer(settingsRepository: SettingsRepository): (ReminderPermission) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val asked by settingsRepository.notificationPermissionRequested.collectAsState(initial = true)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { settingsRepository.setNotificationPermissionRequested() }
    }
    return remember(context, asked, launcher) {
        { permission ->
            when (permission) {
                ReminderPermission.NOTIFICATIONS ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startFirst(appNotificationSettings(context))
                    }
                ReminderPermission.EXACT_ALARMS ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startFirst(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)))
                    }
                ReminderPermission.FULL_SCREEN -> context.startFirst(fullScreenIntentSettings(context))
                ReminderPermission.BATTERY -> context.startFirst(batteryExemptionRequest(context), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}

/** One merged node per grant, carrying Allowed/Blocked as its state and its
 *  own button when blocked (one node per control, Stage B). */
@Composable
internal fun PermissionRows(
    state: PermissionState,
    onFix: (ReminderPermission) -> Unit,
    only: List<ReminderPermission> = ReminderPermission.entries,
    fixLabel: Int = R.string.permission_fix,
) {
    only.forEach { p -> PermissionRow(stringResource(p.label), state.granted(p), stringResource(fixLabel)) { onFix(p) } }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, fixLabel: String, onFix: () -> Unit) {
    val stateLabel = stringResource(if (granted) R.string.permission_granted else R.string.permission_blocked)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { stateDescription = stateLabel },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            // A missing grant is a state to fix, not a failure: the attention
            // glyph beside a neutral label, never red (§6, §7 R88).
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!granted) {
                    AttentionGlyph()
                    Spacer(Modifier.width(4.dp))
                }
                Text(stateLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!granted) TextButton(onClick = onFix) { Text(fixLabel) }
    }
}

/**
 * The first-launch sheet (§7 R83): every grant reminders need, one button
 * each, said once. It lists only what is missing and closes by itself once
 * nothing is; "Later" leaves the rest to the Today card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionSetupSheet(state: PermissionState, onFix: (ReminderPermission) -> Unit, onClose: () -> Unit) {
    val missing = remember(state) { state.missing }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.permission_setup_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.permission_setup_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            PermissionRows(state, onFix, only = missing.ifEmpty { ReminderPermission.entries }, fixLabel = R.string.permission_allow)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.missing.isEmpty()) {
                    Button(onClick = onClose) { Text(stringResource(R.string.permission_setup_done)) }
                } else {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.permission_setup_later)) }
                }
            }
        }
    }
}

/** Today's card while a grant is missing (§7 R83): which ones, a button each,
 *  and "Not now", which holds until a different grant goes missing. */
@Composable
internal fun MissingPermissionsCard(state: PermissionState, onFix: (ReminderPermission) -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.permission_card_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.permission_card_body), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PermissionRows(state, onFix, only = state.missing, fixLabel = R.string.permission_allow)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.permission_card_not_now)) }
        }
    }
}
