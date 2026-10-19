package com.hazia.emivita.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §7 R99: with three-button navigation Android lays a contrast scrim over the
 * navigation bar, 90 % white in light mode, so the bar read #FDFDFE over the
 * app's #F5F6F8. Every activity themed by EmivitaTheme turns it off.
 */
@RunWith(RobolectricTestRunner::class)
class SystemBarScrimTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the theme turns off the navigation bar's contrast scrim in every mode`() {
        var mode by mutableStateOf(ThemeMode.entries.first())
        rule.activity.window.isNavigationBarContrastEnforced = true
        rule.setContent { EmivitaTheme(themeMode = mode) {} }
        rule.waitForIdle()
        assertFalse("scrim still enforced in $mode", rule.activity.window.isNavigationBarContrastEnforced)
        // Each later mode recomposes the theme with the scrim switched back on first.
        for (next in ThemeMode.entries.drop(1)) {
            rule.runOnIdle {
                rule.activity.window.isNavigationBarContrastEnforced = true
                mode = next
            }
            rule.waitForIdle()
            assertFalse("scrim still enforced in $mode", rule.activity.window.isNavigationBarContrastEnforced)
        }
    }
}
