package com.hazia.emivita.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import com.hazia.emivita.R

/** The 6 curated palettes (see Color.kt). SettingsRepository persists the
 *  choice as `.name`, so this enum is the source of truth for valid values --
 *  that Flow falls back to DAYLIGHT for any name it can't parse, which now
 *  also covers a name saved by a build that still had the previous eight. */
enum class MedPalette {
    DAYLIGHT,
    SAGE,
    HONEYCOMB,
    LINEN,
    MALLOW,
    PERIWINKLE,
}

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * The palette a fresh install gets, named once so the three places that need it
 * cannot drift apart -- [EmivitaTheme]'s default parameter, `SettingsRepository`'s
 * fallback for an unparseable stored name, and the initial value `EmivitaApp`
 * collects with before DataStore has loaded.
 *
 * Chosen rather than first-listed (§6, §7 R56): Daylight is the
 * Health/Consumer-Wellbeing register's own documented default pair and the most
 * nearly neutral of the six, so it presumes least about a user who has not
 * chosen.
 *
 * **`res/values/colors.xml`'s `splash_background` must equal this palette's light
 * background.** It is a native resource, so it cannot read a Compose token; a
 * mismatch flashes between the splash window and Compose's first frame.
 * `PaletteSpecConsistencyTest` asserts the two agree, which is the only thing
 * stopping this from being a comment nobody acts on.
 */
val DEFAULT_PALETTE: MedPalette = MedPalette.DAYLIGHT

/**
 * English on purpose, and not a string resource (spec §9 #19). These are the
 * palettes' names in the sense a typeface has a name: `PaletteSpecConsistencyTest`
 * matches them against SPEC Appendix A's headings, so resourcing them would
 * break the one gate that keeps the spec's tables honest, and a translated
 * "Daylight" would be a different palette to a reader of the spec. The rest
 * of the Appearance screen is translated; the six names stay as they are.
 */
/** Found by en-XA on the device, not by any scanner (spec §9 #19): the
 *  Appearance screen rendered `mode.name.lowercase().replaceFirstChar(...)`,
 *  text derived from an identifier with no literal anywhere to detect. */
@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
    ThemeMode.SYSTEM -> R.string.theme_mode_system
}

fun MedPalette.displayName(): String = when (this) {
    MedPalette.DAYLIGHT -> "Daylight"
    MedPalette.SAGE -> "Sage"
    MedPalette.HONEYCOMB -> "Honeycomb"
    MedPalette.LINEN -> "Linen"
    MedPalette.MALLOW -> "Mallow"
    MedPalette.PERIWINKLE -> "Periwinkle"
}


/**
 * The 17 colours one palette defines for one theme mode. [colorSchemeFor]
 * expands the 13 Material ones into Material3's 22 ColorScheme slots; the
 * expansion is the same for every palette, so it is written once here rather
 * than six times by hand. `attention` and `neutralFill` have no ColorScheme
 * slot to go in -- Material3's are all spoken for -- so they travel separately
 * on [LocalEmivitaColors]. See Color.kt for what they are for.
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
    val attention: Color,
    val onAttention: Color,
    val neutralFill: Color,
    val onNeutralFill: Color,
    val error: Color,
    val onError: Color,
)

private fun tokensFor(palette: MedPalette, dark: Boolean): PaletteTokens = when (palette) {
    MedPalette.DAYLIGHT -> with(DaylightPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
            attention = DarkAttention, onAttention = DarkOnAttention,
            neutralFill = DarkNeutralFill, onNeutralFill = DarkOnNeutralFill,
            error = DarkError, onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary,
            attention = LightAttention, onAttention = LightOnAttention,
            neutralFill = LightNeutralFill, onNeutralFill = LightOnNeutralFill,
            error = LightError, onError = LightOnError,
        )
    }
    MedPalette.SAGE -> with(SagePalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
            attention = DarkAttention, onAttention = DarkOnAttention,
            neutralFill = DarkNeutralFill, onNeutralFill = DarkOnNeutralFill,
            error = DarkError, onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary,
            attention = LightAttention, onAttention = LightOnAttention,
            neutralFill = LightNeutralFill, onNeutralFill = LightOnNeutralFill,
            error = LightError, onError = LightOnError,
        )
    }
    MedPalette.HONEYCOMB -> with(HoneycombPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
            attention = DarkAttention, onAttention = DarkOnAttention,
            neutralFill = DarkNeutralFill, onNeutralFill = DarkOnNeutralFill,
            error = DarkError, onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary,
            attention = LightAttention, onAttention = LightOnAttention,
            neutralFill = LightNeutralFill, onNeutralFill = LightOnNeutralFill,
            error = LightError, onError = LightOnError,
        )
    }
    MedPalette.LINEN -> with(LinenPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
            attention = DarkAttention, onAttention = DarkOnAttention,
            neutralFill = DarkNeutralFill, onNeutralFill = DarkOnNeutralFill,
            error = DarkError, onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary,
            attention = LightAttention, onAttention = LightOnAttention,
            neutralFill = LightNeutralFill, onNeutralFill = LightOnNeutralFill,
            error = LightError, onError = LightOnError,
        )
    }
    MedPalette.MALLOW -> with(MallowPalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
            attention = DarkAttention, onAttention = DarkOnAttention,
            neutralFill = DarkNeutralFill, onNeutralFill = DarkOnNeutralFill,
            error = DarkError, onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary,
            attention = LightAttention, onAttention = LightOnAttention,
            neutralFill = LightNeutralFill, onNeutralFill = LightOnNeutralFill,
            error = LightError, onError = LightOnError,
        )
    }
    MedPalette.PERIWINKLE -> with(PeriwinklePalette) {
        if (dark) PaletteTokens(
            background = DarkBackground, surface = DarkSurface, outline = DarkOutline,
            onBackground = DarkOnBackground, onSurfaceVariant = DarkOnSurfaceVariant, primary = DarkPrimary,
            onPrimary = DarkOnPrimary, secondary = DarkSecondary, onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
            attention = DarkAttention, onAttention = DarkOnAttention,
            neutralFill = DarkNeutralFill, onNeutralFill = DarkOnNeutralFill,
            error = DarkError, onError = DarkOnError,
        )
        else PaletteTokens(
            background = LightBackground, surface = LightSurface, outline = LightOutline,
            onBackground = LightOnBackground, onSurfaceVariant = LightOnSurfaceVariant, primary = LightPrimary,
            onPrimary = LightOnPrimary, secondary = LightSecondary, onSecondary = LightOnSecondary,
            tertiary = LightTertiary, onTertiary = LightOnTertiary,
            attention = LightAttention, onAttention = LightOnAttention,
            neutralFill = LightNeutralFill, onNeutralFill = LightOnNeutralFill,
            error = LightError, onError = LightOnError,
        )
    }
}

/**
 * Expands one palette's tokens into a Material3 ColorScheme.
 *
 * Two mappings here are deliberate, and were uniform across all six
 * hand-written palettes -- they are preserved, not simplified:
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

/**
 * The two roles Material3's ColorScheme has no slot left for.
 *
 * Every one of its 22 slots is already spoken for by [colorSchemeFor], and the
 * alternative -- borrowing a slot whose name means something else -- is exactly
 * the overload this pair exists to undo. So they travel beside the scheme
 * instead, read as `EmivitaTheme.colors.attention`.
 */
@Immutable
data class EmivitaColors(
    val attention: Color,
    val onAttention: Color,
    val neutralFill: Color,
    val onNeutralFill: Color,
)

internal fun emivitaColorsFor(palette: MedPalette, dark: Boolean): EmivitaColors =
    with(tokensFor(palette, dark)) {
        EmivitaColors(attention, onAttention, neutralFill, onNeutralFill)
    }

/** Defaulted rather than left to `error()` so a preview or a test that renders a
 *  composable outside [EmivitaTheme] still gets a coherent pair instead of a crash. */
val LocalEmivitaColors = staticCompositionLocalOf {
    emivitaColorsFor(MedPalette.DAYLIGHT, dark = false)
}

/** Accessor object beside the composable of the same name, the shape Material3
 *  itself uses for `MaterialTheme.colorScheme`. */
object EmivitaTheme {
    val colors: EmivitaColors
        @Composable @ReadOnlyComposable get() = LocalEmivitaColors.current
}

@Composable
fun EmivitaTheme(
    palette: MedPalette = DEFAULT_PALETTE,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = colorSchemeFor(palette, useDark)
    val extended = emivitaColorsFor(palette, useDark)

    CompositionLocalProvider(LocalEmivitaColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EmivitaTypography,
            content = content,
        )
    }
}
