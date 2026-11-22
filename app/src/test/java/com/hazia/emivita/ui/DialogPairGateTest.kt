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

    /** One file's offences, and how many dialogs in it use the pair. */
    fun check(text: String, rel: String): Pair<List<String>, Int> {
        val offences = mutableListOf<String>()
        var pairs = 0
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
        return offences to pairs
    }

    @Test
    fun everyDialogUsesTheDialogPair() {
        val root = repoDir("app/src/main/java")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected to find Kotlin sources under $root", sources.size > 50)
        val offences = mutableListOf<String>()
        var pairs = 0
        for (file in sources) {
            val rel = file.relativeTo(root).path.replace('\\', '/').substringAfter("com/hazia/emivita/")
            val (found, n) = check(file.readText(), rel)
            offences += found
            pairs += n
        }
        assertEquals("dialogs off the pair rule", emptyList<String>(), offences)
        assertTrue("expected the dialogs to be found ($pairs)", pairs >= 25)
    }

    /** SPEC:R180 a gate proves it can catch what it looks for. */
    @Test
    fun theGateItselfCanFail() {
        val own = "AlertDialog(onDismissRequest = d, confirmButton = { TextButton(onClick = ok) { Text(y) } })"
        assertEquals(listOf("x.kt:1's confirmButton is not a DialogActionPair"), check(own, "x.kt").first)

        val dismiss = "AlertDialog(onDismissRequest = d,\n dismissButton = { TextButton(onClick = d) { Text(n) } })"
        assertEquals(listOf("x.kt:2 has a dismissButton"), check(dismiss, "x.kt").first)

        val pair = "AlertDialog(onDismissRequest = d, confirmButton = { DialogActionPair(onConfirm = ok, onDismiss = d) })"
        assertEquals(emptyList<String>() to 1, check(pair, "x.kt"))

        val lone = "AlertDialog(onDismissRequest = d, confirmButton = { TextButton(onClick = d) { Text(ok) } })"
        assertEquals(emptyList<String>(), check(lone, "feature/settings/AppearanceSettingsScreens.kt").first)
    }
}
