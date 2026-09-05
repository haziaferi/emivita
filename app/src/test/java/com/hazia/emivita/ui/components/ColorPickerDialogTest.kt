package com.hazia.emivita.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the *wiring* of the differentiation hint, not its arithmetic --
 * ColorDistinguishabilityTest covers the metric. Both halves are tested
 * because §7 R46 is the case where correct logic reached a call site that
 * never invoked it, and R47 found it by reading rather than by a red test.
 */
@RunWith(RobolectricTestRunner::class)
class ColorPickerDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val sertralineAmber = Color(red = 0.725f, green = 0.447f, blue = 0.180f)

    private fun show(initial: Color?, inUse: List<Pair<String, Color>>) {
        compose.setContent {
            ColorPickerDialog(
                initialColor = initial,
                onDismiss = {},
                onConfirm = {},
                inUse = inUse,
            )
        }
    }

    @Test
    fun namesTheMedicineAnAlmostIdenticalColorWouldClashWith() {
        show(sertralineAmber, listOf("Sertraline" to sertralineAmber))
        compose.onNodeWithText("Close to Sertraline", substring = true).assertExists()
    }

    @Test
    fun staysQuietWhenTheColorIsClearlyDistinct() {
        show(Color(red = 0.18f, green = 0.44f, blue = 0.73f), listOf("Sertraline" to sertralineAmber))
        compose.onNodeWithText("Close to", substring = true).assertDoesNotExist()
    }

    /** The profile picker passes nothing; it must not warn about a void. */
    @Test
    fun staysQuietWithNothingToCollideWith() {
        show(sertralineAmber, emptyList())
        compose.onNodeWithText("Close to", substring = true).assertDoesNotExist()
    }

    /** A hint, never a veto -- spec §3 kept the choice with the user. */
    @Test
    fun neverBlocksConfirmation() {
        show(sertralineAmber, listOf("Sertraline" to sertralineAmber))
        compose.onNodeWithText("Use color").assertExists()
    }
}
