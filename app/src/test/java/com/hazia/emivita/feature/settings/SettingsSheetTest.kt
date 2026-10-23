package com.hazia.emivita.feature.settings

import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import org.junit.rules.TestRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.di.LocalAppContainer
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * §7 R88, found on the phone (R84): the Weekend sheet opened half expanded,
 * so its window buttons sat under the navigation bar and Save was off screen
 * until the sheet was dragged up. Opened from the Settings list, as a person
 * opens it, Save must sit above the navigation bar at once.
 */
@RunWith(RobolectricTestRunner::class)
// The OnePlus 6T the defect was found on: 1080x2340 at 420 dpi.
@Config(application = TestEmivitaApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SettingsSheetTest {

    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)


    @Test
    fun `the Weekend sheet opens with Save on screen`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides h.container) {
                SettingsScreen(h.container.settingsRepository, onOpenDetail = {})
            }
        }
        compose.onAllNodesWithText("Weekend Mode").onFirst().performClick()
        compose.waitForIdle()
        // §7 R125: Save is in the form's foot, which ends the fully expanded sheet, so it
        // ends within 24 dp of the window's bottom. The sheet's own window insets are to lift it
        // above the phone's bar: unverified here, Robolectric reports none (a phone check).
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInWindow
        val window = compose.onAllNodes(isRoot()).onFirst().fetchSemanticsNode().boundsInRoot
        val foot = with(compose.density) { 24.dp.toPx() }
        assertTrue("Save ends at ${save.bottom} in a window to ${window.bottom}", save.bottom <= window.bottom && save.bottom >= window.bottom - foot)
    }
}
