package com.hazia.emivita.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails the build on a `Switch` or `Checkbox` that is its own tap target beside a
 * label (§9 #19 Stage B's "one node" pattern; §7 R81 found seven left).
 *
 * TalkBack reads what a node says about itself. A live `Switch(onCheckedChange = …)`
 * next to a `Text` is two nodes: the text, which does nothing, and a switch that
 * announces "interruttore, attivo" with no name. The pattern this codebase settled
 * on (`SettingSwitchRow`, `PermissionRowsSemanticsTest`) is the row as the single
 * `toggleable` node with the control display-only (`onCheckedChange = null`). The
 * other acceptable shape is a control that names itself (`Modifier.semantics {
 * contentDescription = … }`, `ReminderRow`'s enabled switch).
 *
 * Source-level like [HardcodedUiTextGateTest], and for the same reason: a semantics
 * test covers the composable it was written for, and the seventh live checkbox sat in
 * a `FlowRow` no test rendered. Comments are blanked so a quoted call cannot trip it.
 */
class LiveControlBesideLabelGateTest {

    private val src = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/com/hazia/emivita") }.first { it.isDirectory }

    private val call = Regex("""\b(Switch|Checkbox)\s*\(""")

    /** Every control call whose own arguments neither disable the control nor name it. */
    fun offenders(text: String, name: String): List<String> {
        val blanked = blankComments(text)
        return call.findAll(blanked).mapNotNull { m ->
            val open = m.range.last
            var depth = 0
            var end = open
            while (end < blanked.length) {
                when (blanked[end]) { '(' -> depth++; ')' -> depth-- }
                if (depth == 0) break
                end++
            }
            val args = blanked.substring(open + 1, end)
            val displayOnly = Regex("""\bonCheckedChange\s*=\s*null\b""").containsMatchIn(args)
            val namesItself = args.contains("contentDescription")
            if (displayOnly || namesItself) null
            else "$name:${blanked.substring(0, m.range.first).count { it == '\n' } + 1} ${m.groupValues[1]}"
        }.toList()
    }

    private fun blankComments(text: String): String {
        val sb = StringBuilder(text)
        for (m in Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            for (i in m.range) if (sb[i] != '\n') sb.setCharAt(i, ' ')
        }
        return sb.toString()
    }

    @Test
    fun `no live Switch or Checkbox sits beside its label as a separate node`() {
        val found = listOf("feature", "ui").flatMap { dir ->
            File(src, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .flatMap { offenders(it.readText(), it.relativeTo(src).path.replace('\\', '/')).asSequence() }.toList()
        }
        assertEquals("make the row toggleable and the control display-only (onCheckedChange = null)", emptyList<String>(), found)
    }

    @Test
    fun theGateItselfCanFail() {
        val live = "Row { Switch(checked = on, onCheckedChange = { on = it }) ; Text(label) }"
        assertEquals(listOf("x.kt:1 Switch"), offenders(live, "x.kt"))
        val display = "Row(Modifier.toggleable(on) { on = it }) { Switch(checked = on, onCheckedChange = null) }"
        assertTrue(offenders(display, "x.kt").isEmpty())
        val named = "Switch(checked = on, onCheckedChange = f, modifier = Modifier.semantics { contentDescription = l })"
        assertTrue(offenders(named, "x.kt").isEmpty())
        assertTrue(offenders("// Switch(checked = on, onCheckedChange = { on = it })", "x.kt").isEmpty())
    }
}
