package com.hazia.emivita.feature.settings

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHasClickAction
import com.hazia.emivita.core.data.settings.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Language row and the Time format group (spec §9 #28): one node per
 *  option, languages named in themselves. */
@RunWith(RobolectricTestRunner::class)
class AppearanceLanguageSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTimeFormatIsThreeRadioNodesAndTheLanguageRowOpensOneNodePerLanguage() {
        var chosenFormat: TimeFormat? = null
        compose.setContent {
            LanguageAndTimeSection(languageTag = "it", languages = listOf("en", "it"), onLanguage = {}, timeFormat = TimeFormat.SYSTEM, onTimeFormat = { chosenFormat = it })
        }
        assertEquals(3, compose.onAllNodes(isSelectable()).fetchSemanticsNodes().size)
        compose.onNodeWithText("Follow the phone").assertIsSelected()
        compose.onNodeWithText("24-hour").performClick()
        assertEquals(TimeFormat.H24, chosenFormat)

        compose.onNodeWithText("Italiano").assertHasClickAction()
    }

    @Test
    fun theLanguageListIsOneRadioNodePerLanguageWithSystemDefaultFirst() {
        var chosenTag: String? = "unset"
        compose.setContent { LanguageList(languageTag = "it", languages = listOf("en", "it"), onLanguage = { chosenTag = it }) }
        assertEquals(3, compose.onAllNodes(isSelectable()).fetchSemanticsNodes().size)
        compose.onNodeWithText("Italiano").assertIsSelected()
        compose.onNodeWithText("System default").performClick()
        assertEquals(null, chosenTag)
        compose.onNodeWithText("English").performClick()
        assertEquals("en", chosenTag)
    }

    @Test
    fun aLanguageIsNamedInItself() {
        assertEquals("Italiano", languageDisplayName("it", "System"))
        assertEquals("English", languageDisplayName("en", "System"))
        assertEquals("System", languageDisplayName(null, "System"))
        assertEquals(true, languageDisplayName("en-XA", "System").endsWith("(en-XA)"))
    }
}
