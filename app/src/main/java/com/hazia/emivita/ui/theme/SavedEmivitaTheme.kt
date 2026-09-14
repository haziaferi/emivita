package com.hazia.emivita.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hazia.emivita.core.data.settings.SettingsRepository

/**
 * [EmivitaTheme] with the palette and mode the owner saved (§7 R76). Every
 * Activity that shows Compose goes through this -- `MainActivity` via
 * `EmivitaApp`, `AlarmActivity`, `SnoozePickerActivity` -- so a chosen palette
 * is the palette everywhere; until R76 the two alarm activities called
 * `EmivitaTheme()` bare and rang in the default whatever was chosen. The
 * first frame is the default until DataStore answers, the same as
 * `EmivitaApp` has always done.
 */
@Composable
fun SavedEmivitaTheme(settingsRepository: SettingsRepository, content: @Composable () -> Unit) {
    val palette by settingsRepository.palette.collectAsState(initial = DEFAULT_PALETTE)
    val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    EmivitaTheme(palette = palette, themeMode = themeMode, content = content)
}
