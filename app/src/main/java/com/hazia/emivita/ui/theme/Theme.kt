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
 * defaults for the slots below, so which builder runs is still load-bearing --
 * but the slots it used to matter most for no longer take those defaults.
 * `surfaceVariant`, the `surfaceContainer*` ladder, `outlineVariant`, `scrim`
 * and the `inverse*` family are derived from the audited tokens by
 * [withDerivedSurfaces]; leaving them to Material's baseline is what put a
 * lavender navigation bar under all eight palettes (spec §7 R44).
 */
/**
 * Linear sRGB interpolation between two palette colours, used only to derive
 * the M3 slots Appendix A does not audit (spec §7 R44).
 *
 * Computed rather than hand-picked, the same move Color.kt already makes for
 * `onPrimary`/`onSecondary`/`onTertiary`/`onError` -- values it describes as
 * "computed here (not spec'd directly)". Every input is an audited Appendix A
 * token, so nothing here invents a colour; it only places new tones on the line
 * between two that were audited.
 */
private fun blend(base: Color, toward: Color, fraction: Float): Color = Color(
    red = base.red + (toward.red - base.red) * fraction,
    green = base.green + (toward.green - base.green) * fraction,
    blue = base.blue + (toward.blue - base.blue) * fraction,
    alpha = 1f,
)

/**
 * The M3 slots this app has no audited tone for, derived from the ones it does.
 *
 * These used to be left to `lightColorScheme`/`darkColorScheme`'s own defaults,
 * which are Material's baseline palette -- so on every screen, in all eight
 * palettes, the bottom navigation bar (which draws from `surfaceContainer`) and
 * every menu surface rendered baseline lavender next to an audited amber, green,
 * blue or pink. The file's own doc comment recorded the reliance on those
 * defaults as deliberate; it was, and the consequence was not.
 *
 * The ladder blends `surface` toward `onSurface` in small steps. That direction
 * is correct in both modes without a branch: in light mode `onSurface` is dark
 * so containers darken, in dark mode it is light so they lighten, which is what
 * M3's tonal elevation does in each. Every step stays on the line between two
 * audited tokens, so a container can never leave the palette's own hue.
 */
private fun ColorScheme.withDerivedSurfaces(background: Color, surface: Color, onSurface: Color, outline: Color, primary: Color) = copy(
    surfaceVariant = blend(surface, onSurface, 0.08f),
    surfaceContainerLowest = background,
    surfaceContainerLow = blend(surface, onSurface, 0.03f),
    surfaceContainer = blend(surface, onSurface, 0.06f),
    surfaceContainerHigh = blend(surface, onSurface, 0.09f),
    surfaceContainerHighest = blend(surface, onSurface, 0.12f),
    surfaceBright = blend(surface, onSurface, 0.02f),
    surfaceDim = blend(surface, onSurface, 0.10f),
    // A hairline, not a second outline: the audited `outline` is sized for
    // borders that should be seen, and M3 uses outlineVariant for dividers that
    // should not compete with content.
    outlineVariant = blend(outline, surface, 0.55f),
    // M3's inverse pair is the surface/text relationship turned over, which the
    // audited tokens already give directly.
    inverseSurface = onSurface,
    inverseOnSurface = background,
    scrim = Color.Black,
    surfaceTint = primary,
)

// `internal`, not `private`: PaletteContrastTest reads every palette's resolved
// scheme to assert Appendix A's audited ratios hold in the built ColorScheme
// rather than only in the spec's tables (§7 R47).
internal fun colorSchemeFor(palette: MedPalette, dark: Boolean): ColorScheme {
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
        ).withDerivedSurfaces(t.background, t.surface, t.onBackground, t.outline, t.primary)
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
        ).withDerivedSurfaces(t.background, t.surface, t.onBackground, t.outline, t.primary)
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
