package com.hazia.emivita.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Four palettes, light and dark, each derived by `tools/palette_derive.py`
 * from three agreed colours per register (ground, text, primary) and printed
 * by it into this file (`--kotlin`) and SPEC Appendix A (`--tables`), so a
 * hand edit on any side fails `PaletteSpecConsistencyTest` or `--check`
 * (spec §6, §7 R76).
 *
 * Cards are tonal (`surfaceContainer`), the palette's hue lives in controls
 * (mostly the pale `primaryContainer`, solid `primary` only on the toggle and
 * the timeline rail), a medicine's own colour is its icon tile, and a warning
 * is a glyph, never a fill: `attention` (burnt orange, never amber or red) for
 * a missed dose, low stock or an approaching expiry, `error` for genuine
 * failures only, with `errorContainer` tinting the three permission cards.
 *
 * Every on-card colour is fitted against the darkest card tier,
 * `surfaceContainerHigh`, and `onPrimaryContainer` against its own container
 * at 7:1, which `PaletteContrastTest` asserts in light, dark and AMOLED.
 */

/** The 17 colours one palette defines for one register. [colorSchemeFor]
 *  places the 16 Material ones into Material3's slots and derives the tiers
 *  the tables do not list; `attention` has no slot and travels on
 *  [LocalEmivitaColors]. */
data class PaletteTokens(
    val background: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val attention: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

// ---------- Porcellana ----------
// Ming blue-and-white (visual-craft lexicon, world traditions).
object PorcellanaPalette {
    val Light = PaletteTokens(
        background = Color(0xFFF5F6F8),
        surface = Color(0xFFF5F6F8),
        surfaceContainer = Color(0xFFEBEDF1),
        surfaceContainerHigh = Color(0xFFE1E5EB),
        onSurface = Color(0xFF143667),
        onSurfaceVariant = Color(0xFF4F6A78),
        outline = Color(0xFF7691A6),
        outlineVariant = Color(0xFFCDD3DE),
        primary = Color(0xFF17488C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBBC9DC),
        onPrimaryContainer = Color(0xFF143767),
        attention = Color(0xFFA05027),
        error = Color(0xFF9E2A25),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFE2C9CA),
        onErrorContainer = Color(0xFF572F45),
    )
    val Dark = PaletteTokens(
        background = Color(0xFF0F141C),
        surface = Color(0xFF0F141C),
        surfaceContainer = Color(0xFF1C2129),
        surfaceContainerHigh = Color(0xFF262B33),
        onSurface = Color(0xFFE4E8EF),
        onSurfaceVariant = Color(0xFF8E9296),
        outline = Color(0xFF60656C),
        outlineVariant = Color(0xFF353A42),
        primary = Color(0xFF8FB0E0),
        onPrimary = Color(0xFF1A2028),
        primaryContainer = Color(0xFF45566E),
        onPrimaryContainer = Color(0xFFF9F9FD),
        attention = Color(0xFFE0925F),
        error = Color(0xFFF09590),
        onError = Color(0xFF3A0B08),
        errorContainer = Color(0xFF654548),
        onErrorContainer = Color(0xFFF8E8E9),
    )
}

// ---------- Ardesia ----------
// Slate Scholastic (lexicon, contexts).
object ArdesiaPalette {
    val Light = PaletteTokens(
        background = Color(0xFFEEF4F6),
        surface = Color(0xFFEEF4F6),
        surfaceContainer = Color(0xFFE4EBED),
        surfaceContainerHigh = Color(0xFFDBE2E4),
        onSurface = Color(0xFF19282E),
        onSurfaceVariant = Color(0xFF5D656A),
        outline = Color(0xFF818E96),
        outlineVariant = Color(0xFFC8CFD2),
        primary = Color(0xFF2D6276),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBCCED5),
        onPrimaryContainer = Color(0xFF1C3E49),
        attention = Color(0xFF9C4E26),
        error = Color(0xFF9E2A25),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFDCC8C8),
        onErrorContainer = Color(0xFF5C292A),
    )
    val Dark = PaletteTokens(
        background = Color(0xFF152328),
        surface = Color(0xFF152328),
        surfaceContainer = Color(0xFF223034),
        surfaceContainerHigh = Color(0xFF2D3A3F),
        onSurface = Color(0xFFF3F5F6),
        onSurfaceVariant = Color(0xFF9BA2A3),
        outline = Color(0xFF697376),
        outlineVariant = Color(0xFF3D494D),
        primary = Color(0xFF7FB2C6),
        onPrimary = Color(0xFF172024),
        primaryContainer = Color(0xFF425F6A),
        onPrimaryContainer = Color(0xFFFFFFFF),
        attention = Color(0xFFE0925F),
        error = Color(0xFFF09590),
        onError = Color(0xFF3A0B08),
        errorContainer = Color(0xFF684E50),
        onErrorContainer = Color(0xFFFDF6F6),
    )
}

// ---------- Sangue di bue ----------
// Tense · Held Breath (lexicon, moods).
object SangueDiBuePalette {
    val Light = PaletteTokens(
        background = Color(0xFFE9E7E4),
        surface = Color(0xFFE9E7E4),
        surfaceContainer = Color(0xFFE0DEDB),
        surfaceContainerHigh = Color(0xFFD6D4D2),
        onSurface = Color(0xFF17181A),
        onSurfaceVariant = Color(0xFF5C5C5C),
        outline = Color(0xFF858483),
        outlineVariant = Color(0xFFC3C2C0),
        primary = Color(0xFF6E241F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC9B4B1),
        onPrimaryContainer = Color(0xFF471F1D),
        attention = Color(0xFF904823),
        error = Color(0xFF9E2A25),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFD8BDBA),
        onErrorContainer = Color(0xFF5A2120),
    )
    val Dark = PaletteTokens(
        background = Color(0xFF0E0F11),
        surface = Color(0xFF0E0F11),
        surfaceContainer = Color(0xFF1A1B1D),
        surfaceContainerHigh = Color(0xFF242526),
        onSurface = Color(0xFFD8D6D2),
        onSurfaceVariant = Color(0xFF8C8C8C),
        outline = Color(0xFF60605F),
        outlineVariant = Color(0xFF323334),
        primary = Color(0xFFD96B5E),
        onPrimary = Color(0xFF271311),
        primaryContainer = Color(0xFF633631),
        onPrimaryContainer = Color(0xFFF1D1CF),
        attention = Color(0xFFE0925F),
        error = Color(0xFFF09590),
        onError = Color(0xFF3A0B08),
        errorContainer = Color(0xFF644241),
        onErrorContainer = Color(0xFFF6E2E2),
    )
}

// ---------- Sabbia ----------
// Serene · Chalk (lexicon, moods).
object SabbiaPalette {
    val Light = PaletteTokens(
        background = Color(0xFFF5F3EF),
        surface = Color(0xFFF5F3EF),
        surfaceContainer = Color(0xFFECEAE6),
        surfaceContainerHigh = Color(0xFFE4E2DE),
        onSurface = Color(0xFF35322D),
        onSurfaceVariant = Color(0xFF696361),
        outline = Color(0xFF8F8A84),
        outlineVariant = Color(0xFFD2D0CC),
        primary = Color(0xFF6F634B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD2CEC4),
        onPrimaryContainer = Color(0xFF413B31),
        attention = Color(0xFF9C4E26),
        error = Color(0xFF9E2A25),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFE2C7C3),
        onErrorContainer = Color(0xFF612925),
    )
    val Dark = PaletteTokens(
        background = Color(0xFF1B1917),
        surface = Color(0xFF1B1917),
        surfaceContainer = Color(0xFF272522),
        surfaceContainerHigh = Color(0xFF312F2C),
        onSurface = Color(0xFFE4DFD6),
        onSurfaceVariant = Color(0xFF999791),
        outline = Color(0xFF686561),
        outlineVariant = Color(0xFF3F3D39),
        primary = Color(0xFFC0AE8E),
        onPrimary = Color(0xFF231F1A),
        primaryContainer = Color(0xFF605849),
        onPrimaryContainer = Color(0xFFFFFFFF),
        attention = Color(0xFFE0925F),
        error = Color(0xFFF09590),
        onError = Color(0xFF3A0B08),
        errorContainer = Color(0xFF6C4845),
        onErrorContainer = Color(0xFFFBF0EF),
    )
}

/**
 * Fallback accent for a medicine or profile with no colour of its own -- the
 * flat neutral spec §3/§6 calls for. Palette-independent on purpose: it is
 * what a medicine wearing *no* colour looks like, so it must not shift when
 * the user switches palette. Survived the six-palette rebuild and the four-palette one
 * unchanged for exactly that reason: it belongs to no palette, so no palette
 * can retire it.
 */
val NeutralMedicineAccent = Color(0xFFB8ABA9)
