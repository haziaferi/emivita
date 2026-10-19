package com.hazia.emivita.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import com.hazia.emivita.R

/** The four palettes (see Color.kt; spec §6, §7 R76). SettingsRepository
 *  persists the choice as `.name`, so this enum is the source of truth for
 *  valid values -- its Flow maps the six names the previous build stored to
 *  their nearest of these ([legacyPaletteNames]) and falls back to
 *  [DEFAULT_PALETTE] for anything else. */
enum class MedPalette {
    PORCELLANA,
    ARDESIA,
    SANGUE_DI_BUE,
    SABBIA,
}

/** The names R56's six palettes were stored under, each mapped to the one of
 *  the four that keeps its temperature: the two greens to the cool-neutral
 *  default, the two warm ones to Sabbia, the two violets to Ardesia. */
val legacyPaletteNames: Map<String, MedPalette> = mapOf(
    "DAYLIGHT" to MedPalette.PORCELLANA, "SAGE" to MedPalette.PORCELLANA,
    "HONEYCOMB" to MedPalette.SABBIA, "LINEN" to MedPalette.SABBIA,
    "MALLOW" to MedPalette.ARDESIA, "PERIWINKLE" to MedPalette.ARDESIA,
)

/** [AMOLED] (§9 #29, HM-34): the dark scheme on pure black -- an OLED panel
 *  switches those pixels off. Contrast-checked with the rest. */
enum class ThemeMode { LIGHT, DARK, SYSTEM, AMOLED }

/**
 * The palette a fresh install gets, the owner's choice (§6, §7 R76): Porcellana,
 * cobalt on porcelain white, named once so [EmivitaTheme]'s default parameter,
 * `SettingsRepository`'s fallback for an unparseable stored name and the value
 * `EmivitaApp` collects with before DataStore loads cannot drift apart.
 *
 * **`res/values/colors.xml`'s and `values-night/colors.xml`'s
 * `splash_background` must equal this palette's light and dark background**,
 * since a native resource cannot read a Compose token and a mismatch flashes
 * between the splash window and Compose's first frame.
 * `PaletteSpecConsistencyTest` asserts both agree.
 */
val DEFAULT_PALETTE: MedPalette = MedPalette.PORCELLANA

/** Found by en-XA on the device, not by any scanner (spec §9 #19): the
 *  Appearance screen rendered `mode.name.lowercase().replaceFirstChar(...)`,
 *  text derived from an identifier with no literal anywhere to detect. */
@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.AMOLED -> R.string.theme_mode_amoled
}

/**
 * Not a string resource (spec §9 #19). These are the palettes' names in the
 * sense a typeface has a name: `PaletteSpecConsistencyTest` matches them
 * against SPEC Appendix A's headings, so resourcing them would break the one
 * gate that keeps the spec's tables honest, and a translated name would be a
 * different palette to a reader of the spec. Italian, as the owner named them.
 */
fun MedPalette.displayName(): String = when (this) {
    MedPalette.PORCELLANA -> "Porcellana"
    MedPalette.ARDESIA -> "Ardesia"
    MedPalette.SANGUE_DI_BUE -> "Sangue di bue"
    MedPalette.SABBIA -> "Sabbia"
}


/** One palette's register, as Color.kt declares it (printed there by
 *  `tools/palette_derive.py`). */
/** The palette's AMOLED ground: its dark seed at 5 % lightness, never #000000 (§7 R98). */
internal fun amoledGroundFor(palette: MedPalette): Color = when (palette) {
    MedPalette.PORCELLANA -> PorcellanaPalette.AmoledGround
    MedPalette.ARDESIA -> ArdesiaPalette.AmoledGround
    MedPalette.SANGUE_DI_BUE -> SangueDiBuePalette.AmoledGround
    MedPalette.SABBIA -> SabbiaPalette.AmoledGround
}

internal fun tokensFor(palette: MedPalette, dark: Boolean): PaletteTokens = when (palette) {
    MedPalette.PORCELLANA -> if (dark) PorcellanaPalette.Dark else PorcellanaPalette.Light
    MedPalette.ARDESIA -> if (dark) ArdesiaPalette.Dark else ArdesiaPalette.Light
    MedPalette.SANGUE_DI_BUE -> if (dark) SangueDiBuePalette.Dark else SangueDiBuePalette.Light
    MedPalette.SABBIA -> if (dark) SabbiaPalette.Dark else SabbiaPalette.Light
}

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
 * The M3 slots this app has no audited tone for, derived from the ones it does,
 * because Material's baseline defaults put a lavender navigation bar and menus
 * under every palette (§7 R44).
 *
 * The ladder blends `surface` toward `onSurface` in small steps, which is right
 * in both modes without a branch: containers darken in light mode and lighten
 * in dark mode, as M3's tonal elevation does. Every step stays on the line
 * between two audited tokens, so a container can never leave the palette's hue.
 */
private fun ColorScheme.withDerivedSurfaces(t: PaletteTokens, background: Color = t.background, surface: Color = t.surface) = copy(
    surfaceVariant = t.surfaceContainerHigh,
    surfaceContainerLowest = background,
    surfaceContainerLow = blend(surface, t.onSurface, 0.03f),
    surfaceContainer = t.surfaceContainer,
    surfaceContainerHigh = t.surfaceContainerHigh,
    surfaceContainerHighest = blend(surface, t.onSurface, 0.13f),
    surfaceBright = blend(surface, t.onSurface, 0.02f),
    surfaceDim = blend(surface, t.onSurface, 0.10f),
    outlineVariant = t.outlineVariant,
    // M3's inverse pair is the surface/text relationship turned over, which the
    // audited tokens already give directly.
    inverseSurface = t.onSurface,
    inverseOnSurface = background,
    scrim = NearBlack,
    surfaceTint = t.primary,
)

// `internal`, not `private`: PaletteContrastTest reads every palette's resolved
// scheme to assert Appendix A's audited ratios hold in the built ColorScheme
// rather than only in the spec's tables (§7 R47).
/**
 * Expands one palette's tokens into a Material3 ColorScheme (§7 R76), with
 * `primaryContainer` and `errorContainer` taken from the tables as real tints
 * with their own `on*` colours (§7 R75). The roles the four palettes do not
 * carry are aliased, not left to Material's baseline: `secondary*` to
 * `primary*` (the tab indicator draws from `secondaryContainer`), `tertiary`
 * to `primary` and `tertiaryContainer` to `surfaceContainerHigh` (nothing in
 * the app asks for a third hue), `surfaceVariant` to `surfaceContainerHigh`.
 * The rest of the surface ladder, `scrim` and the `inverse*` family come from
 * [withDerivedSurfaces] (spec §7 R44).
 */
internal fun colorSchemeFor(palette: MedPalette, dark: Boolean, amoled: Boolean = false): ColorScheme {
    val t = tokensFor(palette, dark)
    if (dark && amoled) {
        // The dark scheme with its ground at the palette's AMOLED near-black,
        // not #000000 (§7 R98): the cards keep their dark tone, so they still
        // step up from the page (§9 #29, HM-34).
        val ground = amoledGroundFor(palette)
        return colorSchemeFor(palette, dark = true).copy(background = ground, surface = ground)
            .withDerivedSurfaces(t, background = ground, surface = ground)
    }
    val slots = ColorScheme(
        primary = t.primary, onPrimary = t.onPrimary, primaryContainer = t.primaryContainer, onPrimaryContainer = t.onPrimaryContainer,
        inversePrimary = t.primaryContainer,
        secondary = t.primary, onSecondary = t.onPrimary, secondaryContainer = t.primaryContainer, onSecondaryContainer = t.onPrimaryContainer,
        tertiary = t.primary, onTertiary = t.onPrimary, tertiaryContainer = t.surfaceContainerHigh, onTertiaryContainer = t.onSurface,
        background = t.background, onBackground = t.onSurface, surface = t.surface, onSurface = t.onSurface,
        surfaceVariant = t.surfaceContainerHigh, onSurfaceVariant = t.onSurfaceVariant, surfaceTint = t.primary,
        inverseSurface = t.onSurface, inverseOnSurface = t.background,
        error = t.error, onError = t.onError, errorContainer = t.errorContainer, onErrorContainer = t.onErrorContainer,
        outline = t.outline, outlineVariant = t.outlineVariant, scrim = NearBlack,
        surfaceBright = t.surface, surfaceDim = t.surface, surfaceContainer = t.surfaceContainer,
        surfaceContainerHigh = t.surfaceContainerHigh, surfaceContainerHighest = t.surfaceContainerHigh,
        surfaceContainerLow = t.surface, surfaceContainerLowest = t.background,
    )
    return slots.withDerivedSurfaces(t)
}

/**
 * The one role Material3's ColorScheme has no slot left for, so it travels
 * beside the scheme as `EmivitaTheme.colors.attention` rather than borrowing a
 * slot whose name means something else. Under the reference app's grammar
 * (§7 R76) a skip is drawn with `outline` and nothing is ever filled with
 * `attention`, so the role has no fill or `on*` pair.
 */
@Immutable
data class EmivitaColors(
    val attention: Color,
)

internal fun emivitaColorsFor(palette: MedPalette, dark: Boolean): EmivitaColors =
    EmivitaColors(attention = tokensFor(palette, dark).attention)

/** Defaulted rather than left to `error()` so a preview or a test that renders a
 *  composable outside [EmivitaTheme] still gets a coherent value instead of a crash. */
val LocalEmivitaColors = staticCompositionLocalOf {
    emivitaColorsFor(DEFAULT_PALETTE, dark = false)
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
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = colorSchemeFor(palette, useDark, amoled = themeMode == ThemeMode.AMOLED)
    val extended = emivitaColorsFor(palette, useDark)

    // The system bars' icon colour follows the *app's* mode, not the phone's
    // (§7 R76): enableEdgeToEdge decides from the system setting, so a dark
    // or AMOLED theme on a phone in light mode drew dark icons on black and
    // the clock vanished. A theme is the one place that knows which it is.
    // Three-button navigation also gets a system contrast scrim, 90 % white in
    // light mode (§7 R99); the icons follow the app's mode, so it is not needed.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                val bars = WindowCompat.getInsetsController(window, view)
                bars.isAppearanceLightStatusBars = !useDark
                bars.isAppearanceLightNavigationBars = !useDark
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalEmivitaColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EmivitaTypography,
            content = content,
        )
    }
}
