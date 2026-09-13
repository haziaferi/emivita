package com.hazia.emivita.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Delete everything asks with a named checkbox (spec §9 #29, MT-22). */
@RunWith(RobolectricTestRunner::class)
class PurgeConfirmDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Delete is disabled until the checkbox names what goes`() {
        var confirmed = 0
        compose.setContent { PurgeConfirmDialog(onDismiss = {}, onConfirm = { confirmed++ }) }
        compose.onNodeWithText("Delete everything").assertIsNotEnabled()
        compose.onNodeWithText("Every medicine, reminder, dose and note on this phone").performClick()
        compose.onNodeWithText("Delete everything").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }
}
