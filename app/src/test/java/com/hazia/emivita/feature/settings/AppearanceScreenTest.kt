package com.hazia.emivita.feature.settings

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.data.settings.TodayLayout
import com.hazia.emivita.ui.theme.EmivitaTheme
import com.hazia.emivita.ui.theme.MedPalette
import com.hazia.emivita.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Appearance applies on the tap (§7 R76): a palette card, a mode chip and a
 * layout card each write their setting the moment they are chosen, and
 * there is no Save button to reach below the fold -- the reason R75's pass
 * chose a palette and saw nothing happen. The Tema section starts closed
 * with the current mode and palette in its header.
 */
@RunWith(RobolectricTestRunner::class)
class AppearanceScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    private fun show() {
        compose.setContent { EmivitaTheme { AppearanceScreen(settings, onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Today layout")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the theme section starts collapsed, naming the mode and palette, and opens on tap`() {
        runBlocking(Dispatchers.IO) { settings.setThemeMode(ThemeMode.SYSTEM); settings.setPalette(MedPalette.PORCELLANA) }
        show()
        compose.onNodeWithText("System · Porcellana").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Sabbia")).assertCountEquals(0)
        compose.onNodeWithText("Theme").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Sabbia").assertIsDisplayed()
    }

    @Test
    fun `a palette card, a mode chip and a layout card each apply on the tap, and nothing waits for a Save`() {
        runBlocking(Dispatchers.IO) { settings.setThemeMode(ThemeMode.SYSTEM); settings.setPalette(MedPalette.PORCELLANA); settings.setTodayLayout(TodayLayout.TIMELINE) }
        show()
        compose.onAllNodes(hasText("Save")).assertCountEquals(0)

        compose.onNodeWithText("Theme").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Sabbia").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking(Dispatchers.IO) { settings.palette.first() } == MedPalette.SABBIA }

        compose.onNodeWithText("Dark").performClick()
        compose.waitUntil(5_000) { runBlocking(Dispatchers.IO) { settings.themeMode.first() } == ThemeMode.DARK }

        compose.onNodeWithContentDescription("Compact").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking(Dispatchers.IO) { settings.todayLayout.first() } == TodayLayout.COMPACT }

        assertEquals(MedPalette.SABBIA, runBlocking(Dispatchers.IO) { settings.palette.first() })
        assertEquals(ThemeMode.DARK, runBlocking(Dispatchers.IO) { settings.themeMode.first() })
        assertEquals(TodayLayout.COMPACT, runBlocking(Dispatchers.IO) { settings.todayLayout.first() })
        // The header follows the choice once the section is closed again.
        compose.onNodeWithText("Theme").performScrollTo().performClick()
        compose.onNodeWithText("Dark · Sabbia").performScrollTo().assertIsDisplayed()
    }
}
