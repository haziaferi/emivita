package com.hazia.emivita.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.annotation.StringRes
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

/** Destinations reachable from the Settings home list. How each one responds
 *  to a tap is [RowKind]'s concern, not this enum's -- only the NAVIGATE rows
 *  correspond to an actual EmivitaRoute. */
enum class SettingsDestination {
    NotificationsAlerts, AlarmsSnooze, WeekendMode, Appearance, PrivacySecurity, DataBackup, MedicalId,
}

/** How a row responds to a tap -- optimizes navigation depth to match each
 *  destination's actual complexity (per the click/page-count pass over
 *  Settings): a one-field or one-toggle screen doesn't deserve a full page
 *  push with its own back-stack entry and transition; only DataBackup's
 *  genuinely multi-flow content (export/restore/auto-sync, each with its own
 *  SAF picker and dialogs) still warrants a real destination. */
private enum class RowKind { NAVIGATE, SHEET, TOGGLE }

// title/description/label are @StringRes, not String: settingsGroups below is
// a top-level val, so there is no composable scope to call stringResource()
// in. The ids travel and the row composables resolve them (spec §9 #19).
private data class SettingsRow(
    val destination: SettingsDestination,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val kind: RowKind,
)

private data class SettingsGroup(
    @StringRes val label: Int,
    val rows: List<SettingsRow>,
)

private val settingsGroups = listOf(
    SettingsGroup(
        label = R.string.settings_group_reminders,
        rows = listOf(
            SettingsRow(
                SettingsDestination.NotificationsAlerts,
                Icons.Filled.Notifications,
                R.string.settings_row_notifications,
                R.string.settings_row_notifications_desc,
                RowKind.NAVIGATE,
            ),
            SettingsRow(
                SettingsDestination.AlarmsSnooze,
                Icons.Filled.Alarm,
                R.string.settings_row_alarms,
                R.string.settings_row_alarms_desc,
                RowKind.SHEET,
            ),
            SettingsRow(
                SettingsDestination.WeekendMode,
                Icons.Filled.CalendarViewWeek,
                R.string.settings_row_weekend,
                R.string.settings_row_weekend_desc,
                RowKind.SHEET,
            ),
        ),
    ),
    SettingsGroup(
        label = R.string.settings_group_appearance,
        rows = listOf(
            SettingsRow(
                SettingsDestination.Appearance,
                Icons.Filled.Palette,
                R.string.settings_row_appearance,
                R.string.settings_row_appearance_desc,
                RowKind.NAVIGATE,
            ),
        ),
    ),
    SettingsGroup(
        label = R.string.settings_group_privacy,
        rows = listOf(
            SettingsRow(
                SettingsDestination.PrivacySecurity,
                Icons.Filled.Lock,
                R.string.settings_row_biometric,
                R.string.settings_row_biometric_desc,
                RowKind.TOGGLE,
            ),
        ),
    ),
    SettingsGroup(
        label = R.string.settings_group_data,
        rows = listOf(
            SettingsRow(
                SettingsDestination.DataBackup,
                Icons.Filled.CloudUpload,
                R.string.settings_row_data_backup,
                R.string.settings_row_data_backup_desc,
                RowKind.NAVIGATE,
            ),
            SettingsRow(
                SettingsDestination.MedicalId,
                Icons.Filled.MedicalInformation,
                R.string.settings_row_medical_id,
                R.string.settings_row_medical_id_desc,
                RowKind.NAVIGATE,
            ),
        ),
    ),
)

/**
 * Settings home list -- three response modes per row (see [RowKind]) instead
 * of one uniform "push a new screen" for everything:
 *  - NAVIGATE: Notifications, Appearance and DataBackup route through
 *    [onOpenDetail], since DataBackup's several flows do not fit a sheet
 *  - SHEET: Alarms & Snooze and Weekend Mode open in a [ModalBottomSheet],
 *    one dismiss gesture back, for content that is a handful of fields
 *  - TOGGLE: Biometric lock is a Switch on the row, read from
 *    [SettingsRepository.biometricLockEnabled] plus device availability,
 *    disabled and unchecked when the device cannot challenge (the other end
 *    of [com.hazia.emivita.feature.lock.AppLockGate]'s fail-open design).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsRepository: SettingsRepository, onOpenDetail: (SettingsDestination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availability = remember { BiometricAuthenticator.availability(context) }
    var biometricEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        biometricEnabled = settingsRepository.biometricLockEnabled.first()
    }

    // Saveable (§7 R83 (a), found on the phone): a plain remember closed the
    // sheet on a rotation or a process death, and its saved fields with it.
    var activeSheet by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_settings), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
        }

        items(settingsGroups) { group ->
            Text(
                stringResource(group.label).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                // Tonal, no border: the same card every list in the app uses (§7 R76).
                color = MaterialTheme.colorScheme.surfaceContainer,
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
                                enabled = availability == BiometricAvailability.AVAILABLE || availability == BiometricAvailability.CREDENTIAL_ONLY,
                                description = when (availability) {
                                    BiometricAvailability.AVAILABLE -> stringResource(row.description)
                                    BiometricAvailability.CREDENTIAL_ONLY -> stringResource(R.string.settings_biometric_credential_only)
                                    BiometricAvailability.NOT_ENROLLED -> stringResource(R.string.settings_biometric_not_enrolled)
                                    BiometricAvailability.NO_HARDWARE -> stringResource(R.string.settings_biometric_no_hardware)
                                },
                                onToggle = { checked ->
                                    biometricEnabled = checked
                                    scope.launch { settingsRepository.setBiometricLockEnabled(checked) }
                                },
                            )
                        }
                        if (index != group.rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(row.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(row.description),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    // toggleable() on the row with the Switch made display-only (§9 #19
    // Stage B). Before: a clickable Row and a live Switch -- two nodes, both
    // toggling, the row with no role and no state. Now one node that
    // announces as a switch with its checked state, which is what Material's
    // own guidance says a labelled switch row should be.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(row.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
