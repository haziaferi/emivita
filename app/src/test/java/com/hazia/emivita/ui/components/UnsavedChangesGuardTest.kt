package com.hazia.emivita.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Back from a touched form asks; from an untouched one it just goes (spec §9 #29, HM-40). */
@RunWith(RobolectricTestRunner::class)
class UnsavedChangesGuardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `untouched -- cancel passes straight through, no dialog`() {
        var discarded = 0
        compose.setContent {
            val cancel = unsavedChangesGuard(dirty = false, onDiscard = { discarded++ })
            Button(onClick = cancel) { Text("Cancel") }
        }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, discarded)
        compose.onAllNodes(hasText("Discard changes?")).assertCountEquals(0)
    }

    @Test
    fun `touched -- cancel asks, Keep editing stays, Discard goes`() {
        var discarded = 0
        compose.setContent {
            val cancel = unsavedChangesGuard(dirty = true, onDiscard = { discarded++ })
            Button(onClick = cancel) { Text("Cancel") }
        }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, discarded)
        compose.onNodeWithText("Keep editing").performClick()
        assertEquals(0, discarded)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard").performClick()
        assertEquals(1, discarded)
    }

    @Test
    fun `dirty is any field differing from its first value, and resets with the key`() {
        var value by mutableStateOf("a"); var key by mutableStateOf(0)
        var dirty = false
        compose.setContent { dirty = rememberDirty(key, value, 7) }
        assertFalse(dirty)
        value = "b"; compose.waitForIdle(); assertTrue(dirty)
        value = "a"; compose.waitForIdle(); assertFalse(dirty)
        value = "c"; compose.waitForIdle(); assertTrue(dirty)
        key = 1; compose.waitForIdle(); assertFalse("a new baseline after the key changes", dirty)
    }
}
