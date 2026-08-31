package com.hazia.emivita.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.security.BiometricAuthenticator
import com.hazia.emivita.core.security.BiometricAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Destinations reachable from the Settings home list. String-keyed for now;
 *  becomes a proper EmivitaRoute.Settings* sealed hierarchy once each
 *  detail screen is built (Notifications, AlarmsSnooze, Weekend, Appearance,
 *  Privacy, Backup). */
enum class SettingsDestination {
    NotificationsAlerts, AlarmsSnooze, WeekendMode, Appearance, PrivacySecurity, DataBackup,
}

/** How a row responds to a tap -- optimizes navigation depth to match each
 *  destination's actual complexity (per the click/page-count pass over
 *  Settings): a one-field or one-toggle screen doesn't deserve a full page
 *  push with its own back-stack entry and transition; only DataBackup's
 *  genuinely multi-flow content (export/restore/auto-sync, each with its own
 *  SAF picker and dialogs) still warrants a real destination. */
private enum class RowKind { NAVIGATE, SHEET, TOGGLE }

private data class SettingsRow(
    val destination: SettingsDestination,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val kind: RowKind,
)

private data class SettingsGroup(
    val label: String,
    val rows: List<SettingsRow>,
)

private val settingsGroups = listOf(
    SettingsGroup(
        label = "Reminders & Alarms",
        rows = listOf(
            SettingsRow(
                SettingsDestination.NotificationsAlerts,
                Icons.Filled.Notifications,
                "Notifications & Alerts",
                "Sound, priority, how reminders behave",
                RowKind.NAVIGATE,
            ),
            SettingsRow(
                SettingsDestination.AlarmsSnooze,
                Icons.Filled.Alarm,
                "Alarms & Snooze",
                "Alarm sound, vibration, snooze length",
                RowKind.SHEET,
            ),
            SettingsRow(
                SettingsDestination.WeekendMode,
                Icons.Filled.CalendarViewWeek,
                "Weekend Mode",
                "Delay reminders on chosen days",
                RowKind.SHEET,
            ),
        ),
    ),
    SettingsGroup(
        label = "Appearance",
        rows = listOf(
            SettingsRow(
                SettingsDestination.Appearance,
                Icons.Filled.Palette,
                "Appearance",
                "Theme, palette, display preferences",
                RowKind.NAVIGATE,
            ),
        ),
    ),
    SettingsGroup(
        label = "Privacy & Security",
        rows = listOf(
            SettingsRow(
                SettingsDestination.PrivacySecurity,
                Icons.Filled.Lock,
                "Biometric lock",
                "Require fingerprint or face unlock to open Emivita",
                RowKind.TOGGLE,
            ),
        ),
    ),
    SettingsGroup(
        label = "Data & Backup",
        rows = listOf(
            SettingsRow(
                SettingsDestination.DataBackup,
                Icons.Filled.CloudUpload,
                "Data & Backup",
                "Auto-backup, restore, PDF/CSV export",
                RowKind.NAVIGATE,
            ),
        ),
    ),
)

/**
 * Settings home list -- three response modes per row (see [RowKind]) instead
 * of one uniform "push a new screen" for everything:
 *  - NAVIGATE: DataBackup and the not-yet-built Notifications/Appearance
 *    destinations still route through [onOpenDetail] (a real EmivitaRoute
 *    push), since DataBackup genuinely has multiple flows (export, restore,
 *    auto-sync setup) that don't fit a sheet cleanly.
 *  - SHEET: Alarms & Snooze and Weekend Mode open in a [ModalBottomSheet]
 *    right over this list -- one dismiss gesture back to Settings instead of
 *    a full screen transition + back-stack entry, for content that's really
 *    just a handful of fields.
 *  - TOGGLE: Biometric lock doesn't navigate anywhere at all -- the whole
 *    "screen" was previously one Switch, so it's inline on the row itself.
 *    Reads live [SettingsRepository.biometricLockEnabled] plus device
 *    availability (fails closed to disabled+unchecked, matching
 *    [com.hazia.emivita.feature.lock.AppLockGate]'s own fail-open design at
 *    the other end -- can't turn on a lock the device can't challenge).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsRepository: SettingsRepository, onOpenDetail: (SettingsDestination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availability = remember { BiometricAuthenticator.availability(context) }
    var biometricEnabled by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        biometricEnabled = settingsRepository.biometricLockEnabled.first()
    }

    var activeSheet by remember { mutableStateOf<SettingsDestination?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
        }

        items(settingsGroups) { group ->
            Text(
                group.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    group.rows.forEachIndexed { index, row ->
                        when (row.kind) {
                            RowKind.NAVIGATE -> SettingsRowItem(row, onClick = { onOpenDetail(row.destination) })
                            RowKind.SHEET -> SettingsRowItem(row, onClick = { activeSheet = row.destination })
                            RowKind.TOGGLE -> SettingsToggleRowItem(
                                row = row,
                                checked = biometricEnabled,
                                enabled = availability == BiometricAvailability.AVAILABLE,
                                description = when (availability) {
                                    BiometricAvailability.AVAILABLE -> row.description
                                    BiometricAvailability.NOT_ENROLLED -> "No fingerprint or face unlock set up on this device"
                                    BiometricAvailability.NO_HARDWARE -> "This device doesn't support biometric authentication"
                                },
                                onToggle = { checked ->
                                    biometricEnabled = checked
                                    scope.launch { settingsRepository.setBiometricLockEnabled(checked) }
                                },
                            )
                        }
                        if (index != group.rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    val sheetDestination = activeSheet
    if (sheetDestination != null) {
        ModalBottomSheet(onDismissRequest = { activeSheet = null }) {
            Box(Modifier.padding(bottom = 24.dp)) {
                when (sheetDestination) {
                    SettingsDestination.AlarmsSnooze -> AlarmsSnoozeScreen(
                        settingsRepository = settingsRepository,
                        onBack = { activeSheet = null },
                    )
                    SettingsDestination.WeekendMode -> WeekendModeScreen(
                        settingsRepository = settingsRepository,
                        onBack = { activeSheet = null },
                    )
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun SettingsRowItem(row: SettingsRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                row.description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SettingsToggleRowItem(
    row: SettingsRow,
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = if (enabled) onToggle else null, enabled = enabled)
    }
}
