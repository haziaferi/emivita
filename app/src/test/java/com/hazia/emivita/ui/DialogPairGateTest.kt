package com.hazia.emivita.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every AlertDialog's buttons are the one dialog pair (§7 R125, the owner: "Yes, same order and
 * look"): a `confirmButton` slot holds [com.hazia.emivita.ui.form.DialogActionPair] and no dialog
 * has a `dismissButton` of its own, whose place Material puts left of the confirm in its own look.
 * [DialogPairRulesTest][com.hazia.emivita.ui.form.DialogPairRulesTest] measures the pair drawn;
 * this finds the dialog nobody measured.
 */
class DialogPairGateTest {

    /** A dialog whose one button only closes it: there is no pair to arrange. */
    private val loneDismiss = setOf("feature/settings/AppearanceSettingsScreens.kt")

    private fun repoDir(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }

    @Test
    fun everyDialogUsesTheDialogPair() {
        val root = repoDir("app/src/main/java")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected to find Kotlin sources under $root", sources.size > 50)
        val offences = mutableListOf<String>()
        var pairs = 0
        for (file in sources) {
            val text = file.readText()
            val rel = file.relativeTo(root).path.replace('\\', '/').substringAfter("com/hazia/emivita/")
            text.lines().forEachIndexed { i, line ->
                if (Regex("""\bdismissButton\s*=""").containsMatchIn(line)) offences += "$rel:${i + 1} has a dismissButton"
            }
            for (m in Regex("""\bconfirmButton\s*=\s*\{""").findAll(text)) {
                val after = text.substring(m.range.last + 1).trimStart()
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                when {
                    after.startsWith("DialogActionPair(") -> pairs++
                    // A value computed first, then the pair (the snooze picker's minutes).
                    after.take(200).contains("DialogActionPair(") -> pairs++
                    rel in loneDismiss -> Unit
                    else -> offences += "$rel:$line's confirmButton is not a DialogActionPair"
                }
            }
        }
        assertEquals("dialogs off the pair rule", emptyList<String>(), offences)
        assertTrue("expected the dialogs to be found ($pairs)", pairs >= 25)
    }
}
