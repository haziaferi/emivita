package com.hazia.emivita.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hazia.emivita.ui.components.TabHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The four tabs' headers measure the same (spec §9 #33, F-14; the §7 R59
 * shape). An action inside the padded row stretches it by the 48 dp target's
 * excess over the text; floated over the row, it adds nothing. The second
 * test keeps the inline shape History carried until this batch, so the
 * number it produced is on record and not just in a ledger sentence.
 */
@RunWith(RobolectricTestRunner::class)
class TabHeaderLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a header with an action is exactly as tall as one without, at both phone widths`() {
        compose.setContentAtPhoneWidths { width ->
            androidx.compose.foundation.layout.Column {
                TabHeader("Today", Modifier.semantics { testTag = tagAt("plain", width) })
                TabHeader("History", Modifier.semantics { testTag = tagAt("with_action", width) }) {
                    IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = "Export") }
                }
            }
        }
        for (width in PHONE_WIDTHS_DP) {
            val plain = compose.onNodeWithTag(tagAt("plain", width)).px()
            val withAction = compose.onNodeWithTag(tagAt("with_action", width)).px()
            assertEquals("at $width dp", plain.height, withAction.height, 0.5f)
        }
    }

    @Test
    fun `the inline shape History used -- an IconButton inside the padded row -- is taller than the tab header`() {
        compose.setContentAt(360) {
            androidx.compose.foundation.layout.Column {
                TabHeader("Today", Modifier.semantics { testTag = "header" })
                Row(
                    // The tag goes outside the padding so the node is the whole row.
                    Modifier.semantics { testTag = "inline" }.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("History", style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = "Export") }
                }
            }
        }
        val header = compose.onNodeWithTag("header").px()
        val inline = compose.onNodeWithTag("inline").px()
        assertTrue("the old shape (${inline.height} px) sat taller than the shared header (${header.height} px)", inline.height > header.height)
        // The row is 16 dp padding + max(text, 48 dp target) + 16 dp: the excess is the target over the text line.
        assertEquals("by the 48 dp target's excess over the text line", compose.dp(48f) - (header.height - compose.dp(32f)), inline.height - header.height, 0.5f)
        println("TabHeaderLayoutTest: shared header ${header.height / compose.density.density} dp, History's old inline row ${inline.height / compose.density.density} dp")
    }
}
