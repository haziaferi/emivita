package com.hazia.emivita.feature.widget

import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.ui.theme.MedPalette
import com.hazia.emivita.ui.theme.colorSchemeFor
import kotlinx.coroutines.flow.first

/**
 * The widgets in the owner's palette (§7 R76). A widget is drawn by the
 * launcher, so it follows the *system* light/dark switch through Glance's
 * own day/night `ColorProviders`, not the app's ThemeMode -- the palette is
 * the app's, the mode is the launcher's. Until R76 both widgets used bare
 * `GlanceTheme`, which is Material You's dynamic colour on Android 12+ and
 * Glance's baseline lavender below: never the app's own colours.
 */
suspend fun SettingsRepository.widgetPalette(): MedPalette = palette.first()

@Composable
fun WidgetTheme(palette: MedPalette, content: @Composable () -> Unit) {
    GlanceTheme(
        colors = ColorProviders(light = colorSchemeFor(palette, dark = false), dark = colorSchemeFor(palette, dark = true)),
        content = content,
    )
}
