package com.hazia.emivita.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hazia.emivita.feature.settings.DetailHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The medicine detail shares the settings screens' `DetailHeader` (spec §9
 * #34): the pass found it under its own nested Scaffold and TopAppBar, the
 * tall header §7 R59 had already taken off the tabs. With an action in the
 * row or without, and with a name long enough to fill the row, the header
 * measures the same and the action stays on it -- the name ellipsises.
 */
@RunWith(RobolectricTestRunner::class)
class DetailHeaderLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val longName = "androidTest Levothyroxine sodium 100 mcg film-coated tablets, morning, with water"

    @Test
    fun `a header with an action and a long name is exactly as tall as a plain one, at both phone widths`() {
        compose.setContentAtPhoneWidths { width ->
            Column {
                Box(Modifier.semantics { testTag = tagAt("plain", width) }) { DetailHeader("Alarms", onBack = {}) }
                Box(Modifier.semantics { testTag = tagAt("with_action", width) }) {
                    DetailHeader(longName, onBack = {}) {
                        IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = "More@$width") }
                    }
                }
            }
        }
        for (width in PHONE_WIDTHS_DP) {
            val plain = compose.onNodeWithTag(tagAt("plain", width)).px()
            val withAction = compose.onNodeWithTag(tagAt("with_action", width)).px()
            assertEquals("at $width dp", plain.height, withAction.height, 0.5f)
            val action = compose.onNode(hasContentDescription("More@$width")).assertIsDisplayed().px()
            assertWithin("the action at $width dp", action, withAction)
            assertTrue("the action sits at the row's end at $width dp", action.right >= withAction.right - compose.dp(8f))
        }
    }
}
