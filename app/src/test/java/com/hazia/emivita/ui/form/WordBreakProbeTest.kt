package com.hazia.emivita.ui.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sweep's broken-word check can fire (§7 R136): R131's rule saw a break only in a label with no space, so
 * "Paracetamolo 1000 mg" split inside its first word passed, the case the 2026-10-31 renders showed on Today.
 */
class WordBreakProbeTest : FormScreenTestBase() {

    @Test fun a_word_split_across_lines_is_broken_and_a_wrap_at_a_space_is_not() {
        host {
            Column {
                Text("Levotiroxina", Modifier.width(56.dp))
                Text("Paracetamolo 1000 mg", Modifier.width(56.dp))
                Text("Ogni giorno alle otto", Modifier.width(96.dp))
                // A name its row's button left no room at all, as "Aggiungi promemoria" did at font 2.0.
                Text("Ramipril", Modifier.width(0.dp))
            }
        }
        settle()
        val leaves = FormProbe.frame().leaves.filter { it.isText }.associateBy { it.label }
        assertEquals("laid out at no width, drawn as nothing", true, leaves.getValue("Ramipril").squeezed)
        assertEquals("a drawn text is not squeezed", false, leaves.getValue("Levotiroxina").squeezed)
        fun broken(label: String) = leaves.getValue(label).let { it.lines to it.brokenWord }
        assertEquals("one word split", true, broken("Levotiroxina").second)
        assertEquals("split inside the first word, though the label has a space", true, broken("Paracetamolo 1000 mg").second)
        val wrap = broken("Ogni giorno alle otto")
        assertEquals("wraps (${wrap.first} lines) only at spaces", true to false, (wrap.first > 1) to wrap.second)
    }
}
