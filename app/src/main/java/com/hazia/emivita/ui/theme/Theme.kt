package com.hazia.emivita.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The 8 curated palettes from spec §6 / Appendix A. SettingsRepository
 *  persists the choice as `.name`, so this enum is the source of truth for
 *  valid values -- that Flow falls back to GOLDEN_HOUR for any name it can't
 *  parse. */
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

/**
 * The 13 colours one palette actually defines for one theme mode -- the set
 * spec §6 / Appendix A audits. [colorSchemeFor] expands these into Material3's
 * 22 ColorScheme slots; the expansion is the same for every palette, so it is
 * written once here rather than eight times by hand.
 */
private data class PaletteTokens(
    val background: Color,
    val surface: Color,
    val outline: Color,
    val onBackground: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val error: Color,
    val onError: Color,
)

private fun tokensFor(palette: MedPalette, dark: Boolean): PaletteTokens = when (palette) {
    MedPalette.GOLDEN_HOUR -> with(GoldenHourPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary, error = DarkError,
            onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary, error = LightError,
            onError = LightOnError,
        )
    }
    MedPalette.SUNLIT_FOCUS -> with(SunlitFocusPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary, error = DarkError,
            onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary, error = LightError,
            onError = LightOnError,
        )
    }
    MedPalette.FOREST_FLOOR -> with(ForestFloorPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary, error = DarkError,
            onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary, error = LightError,
            onError = LightOnError,
        )
    }
    MedPalette.SAGE_CLINICAL -> with(SageClinicalPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary, error = DarkError,
            onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary, error = LightError,
            onError = LightOnError,
        )
    }
    MedPalette.INK_LEDGER -> with(InkLedgerPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary, error = DarkError,
            onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary, error = LightError,
            onError = LightOnError,
        )
    }
    MedPalette.COBALT_PORCELAIN -> with(CobaltPorcelainPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary, error = DarkError,
            onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary, error = LightError,
            onError = LightOnError,
        )
    }
    MedPalette.ROSE_PETAL -> with(RosePetalPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary, error = DarkError,
            onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary, error = LightError,
            onError = LightOnError,
        )
    }
    MedPalette.VELVET_RIBBON -> with(VelvetRibbonPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary, error = DarkError,
            onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary, error = LightError,
            onError = LightOnError,
        )
    }
}

/**
 * Expands one palette's tokens into a Material3 ColorScheme.
 *
 * Two mappings here are deliberate and were uniform across all eight
 * hand-written palettes this replaced, so they are not a simplification:
 *  - `onSurface` is fed from the palette's OnBackground. Appendix A audits one
 *    text colour, not a separate on-surface tone, so there is no OnSurface
 *    token to read.
 *  - Every `*Container` slot reuses its base role rather than a lighter tone.
 *    Appendix A audits a single tone per role, framed as large-element use --
 *    see Color.kt's own note, which flags a second container tier as
 *    unspecified rather than missing.
 */
/**
 * Expands one palette's tokens into a Material3 ColorScheme.
 *
 * Two mappings here are deliberate, and were uniform across all eight
 * hand-written palettes this replaced -- they are preserved, not simplified:
 *  - `onSurface` is fed from the palette's OnBackground. Appendix A audits one
 *    text colour, not a separate on-surface tone, so there is no OnSurface
 *    token to read.
 *  - Every `*Container` slot reuses its base role rather than a lighter tone.
 *    Appendix A audits a single tone per role, framed as large-element use --
 *    see Color.kt's note, which flags a second container tier as unspecified
 *    rather than missing.
 *
 * The two branches are spelled out rather than sharing one argument list via a
 * function reference: darkColorScheme and lightColorScheme differ in their
 * defaults for the slots this app doesn't override (surfaceVariant, scrim,
 * the inverse* family), so which builder runs is itself load-bearing.
 */
private fun colorSchemeFor(palette: MedPalette, dark: Boolean): ColorScheme {
    val t = tokensFor(palette, dark)
    return if (dark) {
        darkColorScheme(
            background = t.background,
            error = t.error,
            errorContainer = t.error,
            onBackground = t.onBackground,
            onError = t.onError,
            onErrorContainer = t.onError,
            onPrimary = t.onPrimary,
            onPrimaryContainer = t.onPrimary,
            onSecondary = t.onSecondary,
            onSecondaryContainer = t.onSecondary,
            onSurface = t.onBackground,
            onSurfaceVariant = t.onSurfaceVariant,
            onTertiary = t.onTertiary,
            onTertiaryContainer = t.onTertiary,
            outline = t.outline,
            primary = t.primary,
            primaryContainer = t.primary,
            secondary = t.secondary,
            secondaryContainer = t.secondary,
            surface = t.surface,
            tertiary = t.tertiary,
            tertiaryContainer = t.tertiary,
        )
    } else {
        lightColorScheme(
            background = t.background,
            error = t.error,
            errorContainer = t.error,
            onBackground = t.onBackground,
            onError = t.onError,
            onErrorContainer = t.onError,
            onPrimary = t.onPrimary,
            onPrimaryContainer = t.onPrimary,
            onSecondary = t.onSecondary,
            onSecondaryContainer = t.onSecondary,
            onSurface = t.onBackground,
            onSurfaceVariant = t.onSurfaceVariant,
            onTertiary = t.onTertiary,
            onTertiaryContainer = t.onTertiary,
            outline = t.outline,
            primary = t.primary,
            primaryContainer = t.primary,
            secondary = t.secondary,
            secondaryContainer = t.secondary,
            surface = t.surface,
            tertiary = t.tertiary,
            tertiaryContainer = t.tertiary,
        )
    }
}

/** Public swatch color for a palette picker UI (onboarding, Appearance settings) --
 *  reuses colorSchemeFor rather than duplicating a second lookup table. Light-mode
 *  primary is used regardless of the device's actual theme mode since this is only
 *  ever shown as a small representative dot, not the real rendered surface. */
fun swatchColorFor(palette: MedPalette): Color = colorSchemeFor(palette, dark = false).primary

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
