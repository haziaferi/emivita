package com.hazia.emivita.navigation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import com.hazia.emivita.R
import com.hazia.emivita.core.data.settings.StartPage
import com.hazia.emivita.ui.form.FormScreenTestBase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A page pushed from a tab keeps that tab lit (§7 R144, W3 of the R139 walk): the graph is flat, so
 * the medicine page and Appearance matched no tab and the bar showed none selected.
 */
class NavTabSelectionTest : FormScreenTestBase() {

    private val tabs by lazy { listOf(R.string.nav_today, R.string.nav_medicines, R.string.nav_history, R.string.nav_settings).map { s(it) } }

    // Role.Tab: Appearance's start-page choice also reads "Oggi" and is selected.
    private fun lit(): List<String> = tabs.filter { inTop(hasText(it) and isSelected() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)) }

    @Test fun the_medicine_page_keeps_medicines_lit() {
        h.medicine("Lansoprazolo")
        hostApp()
        assertEquals(listOf(s(R.string.nav_medicines)), lit())
        tap(hasText("Lansoprazolo") and hasClickAction())
        waitFor("the medicine page") { !inTop(described(s(R.string.medicines_add_medicine))) && inTop(hasText(s(R.string.medicine_detail_reminders))) }
        assertEquals("on the medicine page", listOf(s(R.string.nav_medicines)), lit())

        // Another tab, then back to Medicines: the bar restores the medicine page and Medicines is lit.
        tap(hasText(s(R.string.nav_today)) and hasClickAction())
        waitFor("Today lit") { lit() == listOf(s(R.string.nav_today)) }
        tap(hasText(s(R.string.nav_medicines)) and hasClickAction())
        waitFor("the medicine page again") { inTop(hasText(s(R.string.medicine_detail_reminders))) }
        assertEquals("on the restored medicine page", listOf(s(R.string.nav_medicines)), lit())
    }

    @Test fun appearance_keeps_settings_lit() {
        hostApp(StartPage.TODAY)
        waitFor("Today lit") { lit() == listOf(s(R.string.nav_today)) }
        tap(hasText(s(R.string.nav_settings)) and hasClickAction())
        waitFor("the Settings list") { inTop(hasText(s(R.string.settings_row_appearance_desc))) }
        tap(hasText(s(R.string.settings_row_appearance)) and hasClickAction())
        waitFor("Appearance") { !inTop(hasText(s(R.string.settings_row_appearance_desc))) }
        assertEquals("on Appearance", listOf(s(R.string.nav_settings)), lit())
        pressBack()
        assertEquals("back on the Settings list", listOf(s(R.string.nav_settings)), lit())
    }
}
