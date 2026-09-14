package com.hazia.emivita.ui

import org.junit.Assert.assertEquals
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

    @Test
    fun `no alpha wash outside the two the grammar allows`() {
        val root = repoDir("app/src/main/java/com/hazia/emivita")
        val found = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("/ui/theme/") }
            .associate { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                rel to Regex("""\.copy\(alpha\s*=""").findAll(file.readText()).count()
            }
            .filterValues { it > 0 }
        assertEquals("alpha washes outside ui/theme, by file", allowed, found)
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
