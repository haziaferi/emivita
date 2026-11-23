package com.hazia.emivita.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Under the reference app's grammar (§7 R76) a surface is a token, never a
 * colour at an alpha: every card is `surfaceContainer`, every tint the pale
 * `primaryContainer`, every warning a glyph. The fourteen `.copy(alpha = …)`
 * washes R75 counted outside `ui/theme` were how the old grammar leaked --
 * a medicine's accent at 14 % under a whole row, `attention` at 16 % under a
 * card, `primary` at 12 % under a summary. Two survive, each for a reason
 * written beside it; this test names any third.
 */
class AlphaTintGateTest {
    private val allowed = mapOf(
        // A picker draws colour: the cursor ring is black at 40 % over whatever hue is under it.
        "ui/components/ColorPickerDialog.kt" to 1,
        // The out-of-month day number, documented at the call site as the one alpha-muted text (§7 R47).
        "feature/history/HistoryCalendarView.kt" to 1,
    )

    /** How many alpha washes one file's text holds. */
    fun washes(text: String): Int = Regex("""\.copy\(alpha\s*=""").findAll(text).count()

    @Test
    fun `no alpha wash outside the two the grammar allows`() {
        val root = repoDir("app/src/main/java/com/hazia/emivita")
        val sources = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("/ui/theme/") }
            .toList()
        // SPEC:R180 without this the gate goes green over a tree it never opened.
        assertTrue("expected to find Kotlin sources under $root, found ${sources.size}", sources.size > 50)
        val found = sources
            .associate { file -> file.relativeTo(root).path.replace('\\', '/') to washes(file.readText()) }
            .filterValues { it > 0 }
        assertEquals("alpha washes outside ui/theme, by file", allowed, found)
    }

    /** SPEC:R180 a gate proves it can catch what it looks for. */
    @Test
    fun theGateItselfCanFail() {
        assertEquals(1, washes("Modifier.background(accent.copy(alpha = 0.14f))"))
        assertEquals(2, washes("a.copy(alpha=0.1f); b.copy(alpha = 0.2f)"))
        assertEquals(0, washes("a.copy(red = 0.5f); Color(0x80FFFFFF)"))
    }

    private fun repoDir(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }
}
