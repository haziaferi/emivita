package com.hazia.emivita.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Makes the spec's palette tables and the splash resource checkable by the build
 * rather than by a person remembering to look (§7 R56/R57, §7.1 rules 5 and 8).
 *
 * Three sources have to agree about 136 hex values, and only one of them is
 * Kotlin. `docs/SPEC.md`'s Appendix A is what a reader trusts; `Color.kt` is what
 * the app renders; the two `colors.xml` `splash_background`s are what shows for
 * the frames before Compose starts. Nothing connected them until R56, which
 * is exactly the shape §7 R47 was opened for: an audit proved a *token* was safe
 * while nothing proved the app used it. Since R76 both Appendix A and Color.kt
 * are printed by `tools/palette_derive.py`, whose `--check` pins the appendix
 * to the tool; this test pins the code to the appendix, closing the loop.
 *
 * This asserts against [colorSchemeFor] and [emivitaColorsFor] -- the resolved
 * scheme the app actually builds -- rather than against the palette objects, for
 * the same reason [PaletteContrastTest] does: a table can be right while the
 * expansion into it is wrong.
 *
 * Pure JVM, no Robolectric. If Appendix A is ever edited by hand and the code is
 * not (or vice versa), this fails and names the role.
 */
class PaletteSpecConsistencyTest {

    /** Gradle runs unit tests with the module directory as the working dir, but
     *  don't rely on it: walk up for the repo root instead. */
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }

    private fun Color.hex(): String = "#%02X%02X%02X".format(
        (red * 255f + 0.5f).toInt(), (green * 255f + 0.5f).toInt(), (blue * 255f + 0.5f).toInt(),
    )

    /** Appendix A's role names, mapped onto the resolved scheme. */
    private fun resolved(palette: MedPalette, dark: Boolean): Map<String, String> {
        val s = colorSchemeFor(palette, dark)
        val e = emivitaColorsFor(palette, dark)
        return mapOf(
            "background" to s.background.hex(),
            "surface" to s.surface.hex(),
            "surfaceContainer" to s.surfaceContainer.hex(),
            "surfaceContainerHigh" to s.surfaceContainerHigh.hex(),
            "onSurface" to s.onSurface.hex(),
            "onSurfaceVariant" to s.onSurfaceVariant.hex(),
            "outline" to s.outline.hex(),
            "outlineVariant" to s.outlineVariant.hex(),
            "primary" to s.primary.hex(),
            "onPrimary" to s.onPrimary.hex(),
            "primaryContainer" to s.primaryContainer.hex(),
            "onPrimaryContainer" to s.onPrimaryContainer.hex(),
            "attention" to e.attention.hex(),
            "error" to s.error.hex(),
            "onError" to s.onError.hex(),
            "errorContainer" to s.errorContainer.hex(),
            "onErrorContainer" to s.onErrorContainer.hex(),
        )
    }

    /** Parses Appendix A into palette-name -> role -> (light, dark). */
    private fun appendixA(): Map<String, Map<String, Pair<String, String>>> {
        val spec = repoFile("docs/SPEC.md").readText()
        val from = spec.indexOf("## Appendix A —")
        val to = spec.indexOf("## Appendix B —")
        assertTrue("Appendix A/B headings not found in docs/SPEC.md", from in 0 until to)
        val body = spec.substring(from, to)

        val out = LinkedHashMap<String, MutableMap<String, Pair<String, String>>>()
        var current: MutableMap<String, Pair<String, String>>? = null
        val heading = Regex("""^### \d+ · ([\w ]+?) —""")
        val row = Regex("""^\| (\w+) \| `(#[0-9A-Fa-f]{6})` \| `(#[0-9A-Fa-f]{6})` \|$""")
        for (line in body.lineSequence()) {
            heading.find(line)?.let { current = out.getOrPut(it.groupValues[1]) { LinkedHashMap() } }
            row.find(line)?.let { m ->
                current?.put(m.groupValues[1], m.groupValues[2].uppercase() to m.groupValues[3].uppercase())
            }
        }
        return out
    }

    @Test
    fun appendixAMatchesTheColorSchemeTheAppBuilds() {
        val spec = appendixA()
        assertEquals(
            "Appendix A should document exactly the palettes MedPalette declares",
            MedPalette.entries.map { it.displayName() }.toSet(),
            spec.keys,
        )
        var compared = 0
        for (palette in MedPalette.entries) {
            val table = spec.getValue(palette.displayName())
            for (dark in listOf(false, true)) {
                val actual = resolved(palette, dark)
                assertEquals(
                    "${palette.displayName()}: Appendix A lists different roles than the scheme resolves",
                    actual.keys, table.keys,
                )
                for ((role, pair) in table) {
                    val want = if (dark) pair.second else pair.first
                    assertEquals(
                        "${palette.displayName()} ${if (dark) "dark" else "light"} `$role`: " +
                            "docs/SPEC.md Appendix A says $want, the app resolves ${actual.getValue(role)} " +
                            "-- one of the two was edited without the other",
                        want, actual.getValue(role),
                    )
                    compared++
                }
            }
        }
        assertEquals("expected 4 palettes x 2 modes x 17 roles", 136, compared)
    }

    /**
     * `splash_background` is a native resource painted before Compose's first
     * frame, so it cannot read a token and has to be kept in step by hand. This
     * is the only thing that notices when it is not.
     */
    @Test
    fun splashBackgroundMatchesTheDefaultPalette() {
        // Both qualifiers: the night one had sat at a warm brown matching no
        // palette from R56 to R75, because only `values/` was pinned.
        for ((folder, dark) in listOf("values" to false, "values-night" to true)) {
            val xml = repoFile("app/src/main/res/$folder/colors.xml").readText()
            val declared = Regex("""<color name="splash_background">(#[0-9A-Fa-f]{6})</color>""")
                .find(xml)?.groupValues?.get(1)?.uppercase()
            assertTrue("splash_background not found in res/$folder/colors.xml", declared != null)
            val expected = colorSchemeFor(DEFAULT_PALETTE, dark).background.hex()
            assertEquals(
                "res/$folder splash_background is $declared but the default palette " +
                    "(${DEFAULT_PALETTE.displayName()}) has a ${if (dark) "dark" else "light"} background of $expected -- " +
                    "they must match, or the splash flashes a different colour before Compose starts",
                expected, declared,
            )
        }
    }

    /** A name stored by the six-palette build lands on its nearest of the
     *  four, and an unknown name on the default (§7 R76). */
    @Test
    fun theSixStoredNamesMapToTheirNearestOfTheFour() {
        assertEquals(MedPalette.PORCELLANA, legacyPaletteNames.getValue("DAYLIGHT"))
        assertEquals(MedPalette.PORCELLANA, legacyPaletteNames.getValue("SAGE"))
        assertEquals(MedPalette.SABBIA, legacyPaletteNames.getValue("HONEYCOMB"))
        assertEquals(MedPalette.SABBIA, legacyPaletteNames.getValue("LINEN"))
        assertEquals(MedPalette.ARDESIA, legacyPaletteNames.getValue("MALLOW"))
        assertEquals(MedPalette.ARDESIA, legacyPaletteNames.getValue("PERIWINKLE"))
        assertEquals(6, legacyPaletteNames.size)
        assertTrue("no legacy name may collide with a current one", legacyPaletteNames.keys.none { name -> MedPalette.entries.any { it.name == name } })
    }
}
