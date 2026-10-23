package com.hazia.emivita.ui.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C3 and "Grow as you type" (§7 R126): a field declared free text in [FormDecl.freeText] may hold
 * several typed lines; everything else in the field pattern stays one line. Each surface is two
 * fields and the foot, drawn with the app's parts; C2 and C3 are read off the full audit.
 */
class FreeTextRuleTest : FormScreenTestBase() {

    private val three = "Penicillina\nLattosio\nNichel"
    private val freeText = FormDecl(freeText = setOf(ALLERGIES))

    private fun surface(field: @Composable () -> Unit) {
        host {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    FormTextField("Rossi", {}, "Nome")
                    Spacer(Modifier.height(8.dp))
                    field()
                }
                FormFootBar("Salva", saveEnabled = true, onSave = {}, onCancel = {})
            }
        }
        waitFor("the surface") { inTop(button("Salva")) }
        settle()
    }

    private fun check(audit: FormRules.Audit, name: String) = audit.checks.first { it.name.startsWith(name) }

    private fun report(what: String, audit: FormRules.Audit) = println("FREE-TEXT $what\n$audit")

    @Test fun a_declared_free_text_field_of_three_lines_passes() {
        surface { FormTextField(three, {}, ALLERGIES, freeText = true) }
        val audit = audit(sheet = false, decl = freeText)
        report("declared, 3 lines", audit)
        val grown = audit.controls.single { it.kind == "field" && it.label.startsWith(ALLERGIES) }
        val other = audit.controls.single { it.kind == "field" && it.label.startsWith("Nome") }
        assertTrue("the value grew: ${grown.hDp} dp against ${other.hDp} dp", grown.hDp >= other.hDp + 30)
        assertTrue(check(audit, "C2").toString(), check(audit, "C2").pass)
        assertTrue(check(audit, "C3").toString(), check(audit, "C3").pass)
    }

    /** The control: the same field undeclared, and C3 says what it said before R126. */
    @Test fun undeclared_the_same_field_fails() {
        surface { FormTextField(three, {}, ALLERGIES, freeText = true) }
        val audit = audit(sheet = false)
        report("undeclared, 3 lines", audit)
        assertFalse(check(audit, "C3").toString(), check(audit, "C3").pass)
        assertTrue(check(audit, "C3").toString(), check(audit, "C3").violations.any { "wraps to 3 lines" in it })
    }

    @Test fun empty_a_free_text_field_is_one_line_the_height_of_the_others() {
        surface { FormTextField("", {}, ALLERGIES, freeText = true) }
        val audit = audit(sheet = false, decl = freeText)
        report("declared, empty", audit)
        val heights = audit.controls.filter { it.kind == "field" }.map { it.hDp }
        assertTrue("one height: $heights", heights.distinct().size == 1)
        assertTrue(check(audit, "C2").toString(), check(audit, "C2").pass)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun a_two_line_placeholder_still_fails_when_declared() {
        surface {
            OutlinedTextField(
                state = rememberTextFieldState(""),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge,
                labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
                label = { Text(ALLERGIES, maxLines = 1) },
                placeholder = { Text("Una per riga\nper esempio Penicillina") },
                lineLimits = TextFieldLineLimits.MultiLine(1, FreeTextMaxLines),
            )
        }
        val audit = audit(sheet = false, decl = freeText)
        report("declared, two-line placeholder", audit)
        assertFalse(check(audit, "C3").toString(), check(audit, "C3").pass)
        assertTrue(check(audit, "C3").toString(), check(audit, "C3").violations.any { "placeholder" in it || "wraps to 2 lines" in it })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun a_two_line_title_or_help_still_fails_when_declared() {
        surface {
            OutlinedTextField(
                state = rememberTextFieldState(three),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge,
                labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
                label = { Text("$ALLERGIES\ne intolleranze") },
                supportingText = { Text("Stampate sulla scheda\ncosì come scritte") },
                lineLimits = TextFieldLineLimits.MultiLine(1, FreeTextMaxLines),
            )
        }
        val audit = audit(sheet = false, decl = freeText)
        report("declared, two-line title and help", audit)
        val c3 = check(audit, "C3")
        assertTrue(c3.toString(), c3.violations.any { "wraps to 2 lines inside the box" in it })
        assertTrue(c3.toString(), c3.violations.any { it.startsWith("help ") && "runs 2 lines" in it })
    }

    private companion object {
        const val ALLERGIES = "Allergie"
    }
}
