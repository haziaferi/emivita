package com.hazia.emivita.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The 8 curated palettes from spec section 6 / Appendix A (replaced the
 *  original 4 -- Clinical/Cozy/Neutral/Playful -- entirely in section 9 item
 *  0b; this is not an additive change). Persisted as a string in DataStore
 *  (see core/data SettingsRepository once that's built) -- this enum is the
 *  source of truth for valid values, so store `.name`. No persisted palette
 *  identifier exists anywhere in the codebase yet (nothing has shipped that
 *  writes one), so there is nothing to migrate off the old 4 names. */
enum class MedPalette {
    GOLDEN_HOUR,
    SUNLIT_FOCUS,
    FOREST_FLOOR,
    SAGE_CLINICAL,
    INK_LEDGER,
    COBALT_PORCELAIN,
    ROSE_PETAL,
    VELVET_RIBBON,
}

enum class ThemeMode { LIGHT, DARK, SYSTEM }

fun MedPalette.displayName(): String = when (this) {
    MedPalette.GOLDEN_HOUR -> "Golden Hour"
    MedPalette.SUNLIT_FOCUS -> "Sunlit Focus"
    MedPalette.FOREST_FLOOR -> "Forest Floor"
    MedPalette.SAGE_CLINICAL -> "Sage Clinical"
    MedPalette.INK_LEDGER -> "Ink & Ledger"
    MedPalette.COBALT_PORCELAIN -> "Cobalt Porcelain"
    MedPalette.ROSE_PETAL -> "Rose Petal"
    MedPalette.VELVET_RIBBON -> "Velvet Ribbon"
}

private fun colorSchemeFor(palette: MedPalette, dark: Boolean) = when (palette) {
    MedPalette.GOLDEN_HOUR -> if (dark) darkColorScheme(
        background = GoldenHourPalette.DarkBackground,
        onBackground = GoldenHourPalette.DarkOnBackground,
        surface = GoldenHourPalette.DarkSurface,
        onSurface = GoldenHourPalette.DarkOnBackground,
        onSurfaceVariant = GoldenHourPalette.DarkOnSurfaceVariant,
        primary = GoldenHourPalette.DarkPrimary,
        onPrimary = GoldenHourPalette.DarkOnPrimary,
        primaryContainer = GoldenHourPalette.DarkPrimary,
        onPrimaryContainer = GoldenHourPalette.DarkOnPrimary,
        secondary = GoldenHourPalette.DarkSecondary,
        onSecondary = GoldenHourPalette.DarkOnSecondary,
        secondaryContainer = GoldenHourPalette.DarkSecondary,
        onSecondaryContainer = GoldenHourPalette.DarkOnSecondary,
        tertiary = GoldenHourPalette.DarkTertiary,
        onTertiary = GoldenHourPalette.DarkOnTertiary,
        tertiaryContainer = GoldenHourPalette.DarkTertiary,
        onTertiaryContainer = GoldenHourPalette.DarkOnTertiary,
        error = GoldenHourPalette.DarkError,
        onError = GoldenHourPalette.DarkOnError,
        errorContainer = GoldenHourPalette.DarkError,
        onErrorContainer = GoldenHourPalette.DarkOnError,
        outline = GoldenHourPalette.DarkOutline,
    ) else lightColorScheme(
        background = GoldenHourPalette.LightBackground,
        onBackground = GoldenHourPalette.LightOnBackground,
        surface = GoldenHourPalette.LightSurface,
        onSurface = GoldenHourPalette.LightOnBackground,
        onSurfaceVariant = GoldenHourPalette.LightOnSurfaceVariant,
        primary = GoldenHourPalette.LightPrimary,
        onPrimary = GoldenHourPalette.LightOnPrimary,
        primaryContainer = GoldenHourPalette.LightPrimary,
        onPrimaryContainer = GoldenHourPalette.LightOnPrimary,
        secondary = GoldenHourPalette.LightSecondary,
        onSecondary = GoldenHourPalette.LightOnSecondary,
        secondaryContainer = GoldenHourPalette.LightSecondary,
        onSecondaryContainer = GoldenHourPalette.LightOnSecondary,
        tertiary = GoldenHourPalette.LightTertiary,
        onTertiary = GoldenHourPalette.LightOnTertiary,
        tertiaryContainer = GoldenHourPalette.LightTertiary,
        onTertiaryContainer = GoldenHourPalette.LightOnTertiary,
        error = GoldenHourPalette.LightError,
        onError = GoldenHourPalette.LightOnError,
        errorContainer = GoldenHourPalette.LightError,
        onErrorContainer = GoldenHourPalette.LightOnError,
        outline = GoldenHourPalette.LightOutline,
    )

    MedPalette.SUNLIT_FOCUS -> if (dark) darkColorScheme(
        background = SunlitFocusPalette.DarkBackground,
        onBackground = SunlitFocusPalette.DarkOnBackground,
        surface = SunlitFocusPalette.DarkSurface,
        onSurface = SunlitFocusPalette.DarkOnBackground,
        onSurfaceVariant = SunlitFocusPalette.DarkOnSurfaceVariant,
        primary = SunlitFocusPalette.DarkPrimary,
        onPrimary = SunlitFocusPalette.DarkOnPrimary,
        primaryContainer = SunlitFocusPalette.DarkPrimary,
        onPrimaryContainer = SunlitFocusPalette.DarkOnPrimary,
        secondary = SunlitFocusPalette.DarkSecondary,
        onSecondary = SunlitFocusPalette.DarkOnSecondary,
        secondaryContainer = SunlitFocusPalette.DarkSecondary,
        onSecondaryContainer = SunlitFocusPalette.DarkOnSecondary,
        tertiary = SunlitFocusPalette.DarkTertiary,
        onTertiary = SunlitFocusPalette.DarkOnTertiary,
        tertiaryContainer = SunlitFocusPalette.DarkTertiary,
        onTertiaryContainer = SunlitFocusPalette.DarkOnTertiary,
        error = SunlitFocusPalette.DarkError,
        onError = SunlitFocusPalette.DarkOnError,
        errorContainer = SunlitFocusPalette.DarkError,
        onErrorContainer = SunlitFocusPalette.DarkOnError,
        outline = SunlitFocusPalette.DarkOutline,
    ) else lightColorScheme(
        background = SunlitFocusPalette.LightBackground,
        onBackground = SunlitFocusPalette.LightOnBackground,
        surface = SunlitFocusPalette.LightSurface,
        onSurface = SunlitFocusPalette.LightOnBackground,
        onSurfaceVariant = SunlitFocusPalette.LightOnSurfaceVariant,
        primary = SunlitFocusPalette.LightPrimary,
        onPrimary = SunlitFocusPalette.LightOnPrimary,
        primaryContainer = SunlitFocusPalette.LightPrimary,
        onPrimaryContainer = SunlitFocusPalette.LightOnPrimary,
        secondary = SunlitFocusPalette.LightSecondary,
        onSecondary = SunlitFocusPalette.LightOnSecondary,
        secondaryContainer = SunlitFocusPalette.LightSecondary,
        onSecondaryContainer = SunlitFocusPalette.LightOnSecondary,
        tertiary = SunlitFocusPalette.LightTertiary,
        onTertiary = SunlitFocusPalette.LightOnTertiary,
        tertiaryContainer = SunlitFocusPalette.LightTertiary,
        onTertiaryContainer = SunlitFocusPalette.LightOnTertiary,
        error = SunlitFocusPalette.LightError,
        onError = SunlitFocusPalette.LightOnError,
        errorContainer = SunlitFocusPalette.LightError,
        onErrorContainer = SunlitFocusPalette.LightOnError,
        outline = SunlitFocusPalette.LightOutline,
    )

    MedPalette.FOREST_FLOOR -> if (dark) darkColorScheme(
        background = ForestFloorPalette.DarkBackground,
        onBackground = ForestFloorPalette.DarkOnBackground,
        surface = ForestFloorPalette.DarkSurface,
        onSurface = ForestFloorPalette.DarkOnBackground,
        onSurfaceVariant = ForestFloorPalette.DarkOnSurfaceVariant,
        primary = ForestFloorPalette.DarkPrimary,
        onPrimary = ForestFloorPalette.DarkOnPrimary,
        primaryContainer = ForestFloorPalette.DarkPrimary,
        onPrimaryContainer = ForestFloorPalette.DarkOnPrimary,
        secondary = ForestFloorPalette.DarkSecondary,
        onSecondary = ForestFloorPalette.DarkOnSecondary,
        secondaryContainer = ForestFloorPalette.DarkSecondary,
        onSecondaryContainer = ForestFloorPalette.DarkOnSecondary,
        tertiary = ForestFloorPalette.DarkTertiary,
        onTertiary = ForestFloorPalette.DarkOnTertiary,
        tertiaryContainer = ForestFloorPalette.DarkTertiary,
        onTertiaryContainer = ForestFloorPalette.DarkOnTertiary,
        error = ForestFloorPalette.DarkError,
        onError = ForestFloorPalette.DarkOnError,
        errorContainer = ForestFloorPalette.DarkError,
        onErrorContainer = ForestFloorPalette.DarkOnError,
        outline = ForestFloorPalette.DarkOutline,
    ) else lightColorScheme(
        background = ForestFloorPalette.LightBackground,
        onBackground = ForestFloorPalette.LightOnBackground,
        surface = ForestFloorPalette.LightSurface,
        onSurface = ForestFloorPalette.LightOnBackground,
        onSurfaceVariant = ForestFloorPalette.LightOnSurfaceVariant,
        primary = ForestFloorPalette.LightPrimary,
        onPrimary = ForestFloorPalette.LightOnPrimary,
        primaryContainer = ForestFloorPalette.LightPrimary,
        onPrimaryContainer = ForestFloorPalette.LightOnPrimary,
        secondary = ForestFloorPalette.LightSecondary,
        onSecondary = ForestFloorPalette.LightOnSecondary,
        secondaryContainer = ForestFloorPalette.LightSecondary,
        onSecondaryContainer = ForestFloorPalette.LightOnSecondary,
        tertiary = ForestFloorPalette.LightTertiary,
        onTertiary = ForestFloorPalette.LightOnTertiary,
        tertiaryContainer = ForestFloorPalette.LightTertiary,
        onTertiaryContainer = ForestFloorPalette.LightOnTertiary,
        error = ForestFloorPalette.LightError,
        onError = ForestFloorPalette.LightOnError,
        errorContainer = ForestFloorPalette.LightError,
        onErrorContainer = ForestFloorPalette.LightOnError,
        outline = ForestFloorPalette.LightOutline,
    )

    MedPalette.SAGE_CLINICAL -> if (dark) darkColorScheme(
        background = SageClinicalPalette.DarkBackground,
        onBackground = SageClinicalPalette.DarkOnBackground,
        surface = SageClinicalPalette.DarkSurface,
        onSurface = SageClinicalPalette.DarkOnBackground,
        onSurfaceVariant = SageClinicalPalette.DarkOnSurfaceVariant,
        primary = SageClinicalPalette.DarkPrimary,
        onPrimary = SageClinicalPalette.DarkOnPrimary,
        primaryContainer = SageClinicalPalette.DarkPrimary,
        onPrimaryContainer = SageClinicalPalette.DarkOnPrimary,
        secondary = SageClinicalPalette.DarkSecondary,
        onSecondary = SageClinicalPalette.DarkOnSecondary,
        secondaryContainer = SageClinicalPalette.DarkSecondary,
        onSecondaryContainer = SageClinicalPalette.DarkOnSecondary,
        tertiary = SageClinicalPalette.DarkTertiary,
        onTertiary = SageClinicalPalette.DarkOnTertiary,
        tertiaryContainer = SageClinicalPalette.DarkTertiary,
        onTertiaryContainer = SageClinicalPalette.DarkOnTertiary,
        error = SageClinicalPalette.DarkError,
        onError = SageClinicalPalette.DarkOnError,
        errorContainer = SageClinicalPalette.DarkError,
        onErrorContainer = SageClinicalPalette.DarkOnError,
        outline = SageClinicalPalette.DarkOutline,
    ) else lightColorScheme(
        background = SageClinicalPalette.LightBackground,
        onBackground = SageClinicalPalette.LightOnBackground,
        surface = SageClinicalPalette.LightSurface,
        onSurface = SageClinicalPalette.LightOnBackground,
        onSurfaceVariant = SageClinicalPalette.LightOnSurfaceVariant,
        primary = SageClinicalPalette.LightPrimary,
        onPrimary = SageClinicalPalette.LightOnPrimary,
        primaryContainer = SageClinicalPalette.LightPrimary,
        onPrimaryContainer = SageClinicalPalette.LightOnPrimary,
        secondary = SageClinicalPalette.LightSecondary,
        onSecondary = SageClinicalPalette.LightOnSecondary,
        secondaryContainer = SageClinicalPalette.LightSecondary,
        onSecondaryContainer = SageClinicalPalette.LightOnSecondary,
        tertiary = SageClinicalPalette.LightTertiary,
        onTertiary = SageClinicalPalette.LightOnTertiary,
        tertiaryContainer = SageClinicalPalette.LightTertiary,
        onTertiaryContainer = SageClinicalPalette.LightOnTertiary,
        error = SageClinicalPalette.LightError,
        onError = SageClinicalPalette.LightOnError,
        errorContainer = SageClinicalPalette.LightError,
        onErrorContainer = SageClinicalPalette.LightOnError,
        outline = SageClinicalPalette.LightOutline,
    )

    MedPalette.INK_LEDGER -> if (dark) darkColorScheme(
        background = InkLedgerPalette.DarkBackground,
        onBackground = InkLedgerPalette.DarkOnBackground,
        surface = InkLedgerPalette.DarkSurface,
        onSurface = InkLedgerPalette.DarkOnBackground,
        onSurfaceVariant = InkLedgerPalette.DarkOnSurfaceVariant,
        primary = InkLedgerPalette.DarkPrimary,
        onPrimary = InkLedgerPalette.DarkOnPrimary,
        primaryContainer = InkLedgerPalette.DarkPrimary,
        onPrimaryContainer = InkLedgerPalette.DarkOnPrimary,
        secondary = InkLedgerPalette.DarkSecondary,
        onSecondary = InkLedgerPalette.DarkOnSecondary,
        secondaryContainer = InkLedgerPalette.DarkSecondary,
        onSecondaryContainer = InkLedgerPalette.DarkOnSecondary,
        tertiary = InkLedgerPalette.DarkTertiary,
        onTertiary = InkLedgerPalette.DarkOnTertiary,
        tertiaryContainer = InkLedgerPalette.DarkTertiary,
        onTertiaryContainer = InkLedgerPalette.DarkOnTertiary,
        error = InkLedgerPalette.DarkError,
        onError = InkLedgerPalette.DarkOnError,
        errorContainer = InkLedgerPalette.DarkError,
        onErrorContainer = InkLedgerPalette.DarkOnError,
        outline = InkLedgerPalette.DarkOutline,
    ) else lightColorScheme(
        background = InkLedgerPalette.LightBackground,
        onBackground = InkLedgerPalette.LightOnBackground,
        surface = InkLedgerPalette.LightSurface,
        onSurface = InkLedgerPalette.LightOnBackground,
        onSurfaceVariant = InkLedgerPalette.LightOnSurfaceVariant,
        primary = InkLedgerPalette.LightPrimary,
        onPrimary = InkLedgerPalette.LightOnPrimary,
        primaryContainer = InkLedgerPalette.LightPrimary,
        onPrimaryContainer = InkLedgerPalette.LightOnPrimary,
        secondary = InkLedgerPalette.LightSecondary,
        onSecondary = InkLedgerPalette.LightOnSecondary,
        secondaryContainer = InkLedgerPalette.LightSecondary,
        onSecondaryContainer = InkLedgerPalette.LightOnSecondary,
        tertiary = InkLedgerPalette.LightTertiary,
        onTertiary = InkLedgerPalette.LightOnTertiary,
        tertiaryContainer = InkLedgerPalette.LightTertiary,
        onTertiaryContainer = InkLedgerPalette.LightOnTertiary,
        error = InkLedgerPalette.LightError,
        onError = InkLedgerPalette.LightOnError,
        errorContainer = InkLedgerPalette.LightError,
        onErrorContainer = InkLedgerPalette.LightOnError,
        outline = InkLedgerPalette.LightOutline,
    )

    MedPalette.COBALT_PORCELAIN -> if (dark) darkColorScheme(
        background = CobaltPorcelainPalette.DarkBackground,
        onBackground = CobaltPorcelainPalette.DarkOnBackground,
        surface = CobaltPorcelainPalette.DarkSurface,
        onSurface = CobaltPorcelainPalette.DarkOnBackground,
        onSurfaceVariant = CobaltPorcelainPalette.DarkOnSurfaceVariant,
        primary = CobaltPorcelainPalette.DarkPrimary,
        onPrimary = CobaltPorcelainPalette.DarkOnPrimary,
        primaryContainer = CobaltPorcelainPalette.DarkPrimary,
        onPrimaryContainer = CobaltPorcelainPalette.DarkOnPrimary,
        secondary = CobaltPorcelainPalette.DarkSecondary,
        onSecondary = CobaltPorcelainPalette.DarkOnSecondary,
        secondaryContainer = CobaltPorcelainPalette.DarkSecondary,
        onSecondaryContainer = CobaltPorcelainPalette.DarkOnSecondary,
        tertiary = CobaltPorcelainPalette.DarkTertiary,
        onTertiary = CobaltPorcelainPalette.DarkOnTertiary,
        tertiaryContainer = CobaltPorcelainPalette.DarkTertiary,
        onTertiaryContainer = CobaltPorcelainPalette.DarkOnTertiary,
        error = CobaltPorcelainPalette.DarkError,
        onError = CobaltPorcelainPalette.DarkOnError,
        errorContainer = CobaltPorcelainPalette.DarkError,
        onErrorContainer = CobaltPorcelainPalette.DarkOnError,
        outline = CobaltPorcelainPalette.DarkOutline,
    ) else lightColorScheme(
        background = CobaltPorcelainPalette.LightBackground,
        onBackground = CobaltPorcelainPalette.LightOnBackground,
        surface = CobaltPorcelainPalette.LightSurface,
        onSurface = CobaltPorcelainPalette.LightOnBackground,
        onSurfaceVariant = CobaltPorcelainPalette.LightOnSurfaceVariant,
        primary = CobaltPorcelainPalette.LightPrimary,
        onPrimary = CobaltPorcelainPalette.LightOnPrimary,
        primaryContainer = CobaltPorcelainPalette.LightPrimary,
        onPrimaryContainer = CobaltPorcelainPalette.LightOnPrimary,
        secondary = CobaltPorcelainPalette.LightSecondary,
        onSecondary = CobaltPorcelainPalette.LightOnSecondary,
        secondaryContainer = CobaltPorcelainPalette.LightSecondary,
        onSecondaryContainer = CobaltPorcelainPalette.LightOnSecondary,
        tertiary = CobaltPorcelainPalette.LightTertiary,
        onTertiary = CobaltPorcelainPalette.LightOnTertiary,
        tertiaryContainer = CobaltPorcelainPalette.LightTertiary,
        onTertiaryContainer = CobaltPorcelainPalette.LightOnTertiary,
        error = CobaltPorcelainPalette.LightError,
        onError = CobaltPorcelainPalette.LightOnError,
        errorContainer = CobaltPorcelainPalette.LightError,
        onErrorContainer = CobaltPorcelainPalette.LightOnError,
        outline = CobaltPorcelainPalette.LightOutline,
    )

    MedPalette.ROSE_PETAL -> if (dark) darkColorScheme(
        background = RosePetalPalette.DarkBackground,
        onBackground = RosePetalPalette.DarkOnBackground,
        surface = RosePetalPalette.DarkSurface,
        onSurface = RosePetalPalette.DarkOnBackground,
        onSurfaceVariant = RosePetalPalette.DarkOnSurfaceVariant,
        primary = RosePetalPalette.DarkPrimary,
        onPrimary = RosePetalPalette.DarkOnPrimary,
        primaryContainer = RosePetalPalette.DarkPrimary,
        onPrimaryContainer = RosePetalPalette.DarkOnPrimary,
        secondary = RosePetalPalette.DarkSecondary,
        onSecondary = RosePetalPalette.DarkOnSecondary,
        secondaryContainer = RosePetalPalette.DarkSecondary,
        onSecondaryContainer = RosePetalPalette.DarkOnSecondary,
        tertiary = RosePetalPalette.DarkTertiary,
        onTertiary = RosePetalPalette.DarkOnTertiary,
        tertiaryContainer = RosePetalPalette.DarkTertiary,
        onTertiaryContainer = RosePetalPalette.DarkOnTertiary,
        error = RosePetalPalette.DarkError,
        onError = RosePetalPalette.DarkOnError,
        errorContainer = RosePetalPalette.DarkError,
        onErrorContainer = RosePetalPalette.DarkOnError,
        outline = RosePetalPalette.DarkOutline,
    ) else lightColorScheme(
        background = RosePetalPalette.LightBackground,
        onBackground = RosePetalPalette.LightOnBackground,
        surface = RosePetalPalette.LightSurface,
        onSurface = RosePetalPalette.LightOnBackground,
        onSurfaceVariant = RosePetalPalette.LightOnSurfaceVariant,
        primary = RosePetalPalette.LightPrimary,
        onPrimary = RosePetalPalette.LightOnPrimary,
        primaryContainer = RosePetalPalette.LightPrimary,
        onPrimaryContainer = RosePetalPalette.LightOnPrimary,
        secondary = RosePetalPalette.LightSecondary,
        onSecondary = RosePetalPalette.LightOnSecondary,
        secondaryContainer = RosePetalPalette.LightSecondary,
        onSecondaryContainer = RosePetalPalette.LightOnSecondary,
        tertiary = RosePetalPalette.LightTertiary,
        onTertiary = RosePetalPalette.LightOnTertiary,
        tertiaryContainer = RosePetalPalette.LightTertiary,
        onTertiaryContainer = RosePetalPalette.LightOnTertiary,
        error = RosePetalPalette.LightError,
        onError = RosePetalPalette.LightOnError,
        errorContainer = RosePetalPalette.LightError,
        onErrorContainer = RosePetalPalette.LightOnError,
        outline = RosePetalPalette.LightOutline,
    )

    MedPalette.VELVET_RIBBON -> if (dark) darkColorScheme(
        background = VelvetRibbonPalette.DarkBackground,
        onBackground = VelvetRibbonPalette.DarkOnBackground,
        surface = VelvetRibbonPalette.DarkSurface,
        onSurface = VelvetRibbonPalette.DarkOnBackground,
        onSurfaceVariant = VelvetRibbonPalette.DarkOnSurfaceVariant,
        primary = VelvetRibbonPalette.DarkPrimary,
        onPrimary = VelvetRibbonPalette.DarkOnPrimary,
        primaryContainer = VelvetRibbonPalette.DarkPrimary,
        onPrimaryContainer = VelvetRibbonPalette.DarkOnPrimary,
        secondary = VelvetRibbonPalette.DarkSecondary,
        onSecondary = VelvetRibbonPalette.DarkOnSecondary,
        secondaryContainer = VelvetRibbonPalette.DarkSecondary,
        onSecondaryContainer = VelvetRibbonPalette.DarkOnSecondary,
        tertiary = VelvetRibbonPalette.DarkTertiary,
        onTertiary = VelvetRibbonPalette.DarkOnTertiary,
        tertiaryContainer = VelvetRibbonPalette.DarkTertiary,
        onTertiaryContainer = VelvetRibbonPalette.DarkOnTertiary,
        error = VelvetRibbonPalette.DarkError,
        onError = VelvetRibbonPalette.DarkOnError,
        errorContainer = VelvetRibbonPalette.DarkError,
        onErrorContainer = VelvetRibbonPalette.DarkOnError,
        outline = VelvetRibbonPalette.DarkOutline,
    ) else lightColorScheme(
        background = VelvetRibbonPalette.LightBackground,
        onBackground = VelvetRibbonPalette.LightOnBackground,
        surface = VelvetRibbonPalette.LightSurface,
        onSurface = VelvetRibbonPalette.LightOnBackground,
        onSurfaceVariant = VelvetRibbonPalette.LightOnSurfaceVariant,
        primary = VelvetRibbonPalette.LightPrimary,
        onPrimary = VelvetRibbonPalette.LightOnPrimary,
        primaryContainer = VelvetRibbonPalette.LightPrimary,
        onPrimaryContainer = VelvetRibbonPalette.LightOnPrimary,
        secondary = VelvetRibbonPalette.LightSecondary,
        onSecondary = VelvetRibbonPalette.LightOnSecondary,
        secondaryContainer = VelvetRibbonPalette.LightSecondary,
        onSecondaryContainer = VelvetRibbonPalette.LightOnSecondary,
        tertiary = VelvetRibbonPalette.LightTertiary,
        onTertiary = VelvetRibbonPalette.LightOnTertiary,
        tertiaryContainer = VelvetRibbonPalette.LightTertiary,
        onTertiaryContainer = VelvetRibbonPalette.LightOnTertiary,
        error = VelvetRibbonPalette.LightError,
        onError = VelvetRibbonPalette.LightOnError,
        errorContainer = VelvetRibbonPalette.LightError,
        onErrorContainer = VelvetRibbonPalette.LightOnError,
        outline = VelvetRibbonPalette.LightOutline,
    )
}

/** Public swatch color for a palette picker UI (onboarding, Appearance settings) --
 *  reuses colorSchemeFor rather than duplicating a second lookup table. Light-mode
 *  primary is used regardless of the device's actual theme mode since this is only
 *  ever shown as a small representative dot, not the real rendered surface. */
fun swatchColorFor(palette: MedPalette): Color = colorSchemeFor(palette, dark = false).primary

/** Per-medicine accent color (from the HSV picker) is stored as a raw ARGB int
 *  independent of the active palette -- this just clamps it to a legible
 *  on-surface tone at render time so an arbitrarily-chosen medicine color
 *  never accidentally becomes unreadable text. Used by the alarm screen and
 *  notification buttons (see feature/reminders).
 *
 *  WCAG contrast ratio (relative luminance, spec formula) against
 *  [background], not a hue/lightness heuristic -- the same standard this
 *  app's own curated-palette tables (docs/SPEC.md §6) already check
 *  themselves against. If [this] already clears 4.5:1 (WCAG AA, normal
 *  text) it's returned unchanged; otherwise it's blended toward whichever
 *  of black/white increases contrast more, by the minimum amount needed to
 *  clear the bar -- preserves the original hue as closely as possible
 *  rather than snapping straight to black/white. */
fun Color.asOnSurfaceSafe(background: Color): Color {
    val minContrast = 4.5
    if (contrastRatio(this, background) >= minContrast) return this

    val target = if (contrastRatio(Color.Black, background) > contrastRatio(Color.White, background)) Color.Black else Color.White

    var low = 0f
    var high = 1f
    repeat(12) {
        val mid = (low + high) / 2f
        if (contrastRatio(androidx.compose.ui.graphics.lerp(this, target, mid), background) >= minContrast) high = mid else low = mid
    }
    return androidx.compose.ui.graphics.lerp(this, target, high)
}

private fun contrastRatio(a: Color, b: Color): Double {
    val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
    val darker = minOf(relativeLuminance(a), relativeLuminance(b))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val cs = c.toDouble()
        return if (cs <= 0.03928) cs / 12.92 else Math.pow((cs + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

@Composable
fun EmivitaTheme(
    palette: MedPalette = MedPalette.GOLDEN_HOUR,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = colorSchemeFor(palette, useDark)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EmivitaTypography,
        content = content,
    )
}

/** True on API 31+ where per-app dynamic (Material You) color is available --
 *  not used by default (the 8 curated palettes are the point), but kept here
 *  since it's a one-line feature if a future toggle wants it. */
val supportsDynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
