package com.hazia.emivita.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails the build on a `Switch`, `Checkbox` or `RadioButton` that is its own tap
 * target beside a label (§9 #19 Stage B's "one node" pattern; §7 R81 found seven,
 * §7 R82 widened the gate). TalkBack reads what a node says about itself, so a
 * live control next to a `Text` announces "interruttore, attivo" with no name.
 *
 * Accepted shapes: the row is the single `toggleable`/`selectable` node and the
 * control is display-only (`onCheckedChange = null`, `onClick = null`), or the
 * control names itself with a non-null `contentDescription`.
 *
 * Source-level like [HardcodedUiTextGateTest], because a semantics test only
 * covers the composable it was written for and the seventh live checkbox sat in
 * a `FlowRow` no test rendered. Comments are blanked so a quoted call cannot
 * trip it.
 */
class LiveControlBesideLabelGateTest {

    private val src = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/com/hazia/emivita") }.first { it.isDirectory }

    private val call = Regex("""\b(Switch|Checkbox|RadioButton)\s*\(""")

    /**
     * Display-only is only half the pattern: something above must carry the tap
     * target. `DropdownMenuItem` counts -- its own `onClick` is the single node,
     * which is why a `RadioButton(onClick = null)` may sit in its `leadingIcon`.
     */
    private val rowTarget = Regex("""\b(toggleable|selectable|DropdownMenuItem)\s*\(""")

    /** How far above a control the row's modifier may sit, in lines. */
    private val rowSearchLines = 40

    /** Every control call that is neither display-only inside a tappable row nor self-naming. */
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
            val before = blanked.substring(0, m.range.first)
            val line = before.count { it == '\n' } + 1
            val displayOnly = Regex("""\bon(CheckedChange|Click)\s*=\s*null\b""").containsMatchIn(args)
            // The lookahead sits straight after `=`: with `\s*` before it the
            // regex simply matches zero spaces and passes on `= null`.
            val namesItself = Regex("""\bcontentDescription\s*=(?!\s*null\b)""").containsMatchIn(args)
            val inTappableRow = rowTarget.containsMatchIn(before.takeLast(rowSearchLines * 90))
            when {
                namesItself -> null
                displayOnly && inTappableRow -> null
                displayOnly -> "$name:$line ${m.groupValues[1]} display-only but no toggleable/selectable row"
                else -> "$name:$line ${m.groupValues[1]}"
            }
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
    fun `no live control sits beside its label as a separate node`() {
        var scanned = 0
        val found = listOf("feature", "ui").flatMap { dir ->
            File(src, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .flatMap {
                    scanned++
                    offenders(it.readText(), it.relativeTo(src).path.replace('\\', '/')).asSequence()
                }.toList()
        }
        // Without this the gate goes green over a tree it never opened (§7 R82).
        assertTrue("expected to find Kotlin sources under $src, scanned $scanned", scanned > 40)
        assertEquals(
            "make the row toggleable/selectable and the control display-only (onCheckedChange/onClick = null)",
            emptyList<String>(), found,
        )
    }

    @Test
    fun theGateItselfCanFail() {
        val live = "Row { Switch(checked = on, onCheckedChange = { on = it }) ; Text(label) }"
        assertEquals(listOf("x.kt:1 Switch"), offenders(live, "x.kt"))

        val display = "Row(Modifier.toggleable(on) { on = it }) { Switch(checked = on, onCheckedChange = null) }"
        assertTrue(offenders(display, "x.kt").isEmpty())

        val radio = "Row(Modifier.selectable(sel) { pick() }) { RadioButton(selected = sel, onClick = null) }"
        assertTrue(offenders(radio, "x.kt").isEmpty())

        val named = "Switch(checked = on, onCheckedChange = f, modifier = Modifier.semantics { contentDescription = l })"
        assertTrue(offenders(named, "x.kt").isEmpty())

        assertTrue(offenders("// Switch(checked = on, onCheckedChange = { on = it })", "x.kt").isEmpty())

        // §7 R82: the three holes the test-suite audit found in the R81 gate.
        val orphan = "Row { RadioButton(selected = sel, onClick = null) ; Text(label) }"
        assertEquals(1, offenders(orphan, "x.kt").size)

        val liveRadio = "Row { RadioButton(selected = sel, onClick = { pick() }) ; Text(label) }"
        assertEquals(listOf("x.kt:1 RadioButton"), offenders(liveRadio, "x.kt"))

        val nulled = "Switch(checked = on, onCheckedChange = f, modifier = Modifier.semantics { contentDescription = null })"
        assertEquals(listOf("x.kt:1 Switch"), offenders(nulled, "x.kt"))

        val menuItem = "DropdownMenuItem(text = { Text(l) }, leadingIcon = { RadioButton(selected = s, onClick = null) }, onClick = pick)"
        assertTrue(offenders(menuItem, "x.kt").isEmpty())
    }
}
