package com.hazia.emivita.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The set-aside section under Data & Backup (spec §9 #32): absent with no
 * files, a count and a delete that asks first when there are.
 */
@RunWith(RobolectricTestRunner::class)
class SetAsideFilesSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val settings = mockk<SettingsRepository>().also { every { it.biometricLockEnabled } returns flowOf(false) }

    @Test
    fun `nothing set aside, nothing shown`() {
        compose.setContent { SetAsideFilesSection(settingsRepository = settings, incidents = emptyList()) }
        compose.onNodeWithText("Set-aside database files").assertDoesNotExist()
    }

    @Test
    fun `two incidents are counted, and Delete asks before it deletes`() {
        val files = listOf(folder.newFile("emivita.db.unopenable-1"), folder.newFile("emivita.db.unopenable-2"))
        files.forEach { it.writeBytes(ByteArray(2048)) }
        var deleted = 0
        compose.setContent { SetAsideFilesSection(settingsRepository = settings, incidents = files, onDeleted = { deleted++ }) }

        compose.onNodeWithText("Set-aside database files").assertExists()
        compose.onNodeWithText("2 files", substring = true).assertExists()
        compose.onNodeWithText("Delete set-aside files").performClick()
        assertEquals("asked, not done", 0, deleted)
        compose.onNodeWithText("Delete the set-aside files?").assertExists()

        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Delete set-aside files").performClick()
        // The dialog's confirm carries the same label as the section's button; it is the second node.
        compose.onAllNodesWithText("Delete set-aside files")[1].performClick()
        compose.waitForIdle()
        assertEquals(1, deleted)
        compose.onNodeWithText("Set-aside files deleted.").assertExists()
    }
}
