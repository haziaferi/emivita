package com.hazia.emivita.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.feature.medicines.CourseState
import com.hazia.emivita.feature.medicines.MedicineCard
import com.hazia.emivita.feature.medicines.MedicineCardState
import com.hazia.emivita.feature.medicines.MedicineListEntry
import com.hazia.emivita.feature.medicines.TodayStatus
import com.hazia.emivita.feature.today.TimelineEntry
import com.hazia.emivita.feature.today.TimelineEntryRow
import com.hazia.emivita.feature.today.TimelineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two rows §7 R59 found by eye, measured (spec §9 #33, F-14): a Due
 * row's name beside two labelled chips ("Ser / tral / ine"), and the
 * medicine card's stacked lines. Geometry at the two phone widths; the
 * semantics tests next door say nothing about where anything is.
 */
@RunWith(RobolectricTestRunner::class)
class RowLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun medicine(name: String, strength: String? = null) = Medicine(
        id = 1L, profileId = 1L, name = name, iconKey = "tablet", sortOrder = 0.0, createdAtEpochMillis = 0L,
        strength = strength, stockInfo = StockInfo(quantity = 3.0, unit = "tablet"),
    )

    @Test
    fun `a due row keeps its name on one line and puts the chips beneath, at both phone widths`() {
        compose.setContentAtPhoneWidths { width ->
            Box(Modifier.semantics { testTag = tagAt("row", width) }) {
                TimelineEntryRow(
                    entry = TimelineEntry(medicine = medicine("Sertraline"), doseAmount = 1.0, status = TimelineStatus.PENDING, doseEventId = 7L, resolvedAtEpochMillis = null),
                    canSkip = true, onResolve = {}, onClick = {},
                )
            }
        }
        for (width in PHONE_WIDTHS_DP) {
            val row = compose.onNodeWithTag(tagAt("row", width)).px()
            val name = compose.nodesInside(row, hasText("Sertraline")).single().boundsInRoot
            val take = compose.nodesInside(row, hasText("Take")).single().boundsInRoot
            val skip = compose.nodesInside(row, hasText("Skip")).single().boundsInRoot
            assertStacked("at $width dp, Take under the name", name, take)
            assertStacked("at $width dp, Skip under the name", name, skip)
            assertTrue("at $width dp the name is one line tall (${name.height} px)", name.height <= compose.dp(36f))
            assertWithin("at $width dp the name inside the row", name, row)
        }
    }

    @Test
    fun `a medicine card stacks name, strength and state, and nothing crosses its right edge`() {
        val state = MedicineCardState(today = TodayStatus.UPCOMING, expired = false, empty = false, runsOutInDays = 2L, course = CourseState.None, nextDoseEpochMillis = null)
        compose.setContentAtPhoneWidths { width ->
            Box(Modifier.semantics { testTag = tagAt("card", width) }) {
                MedicineCard(entry = MedicineListEntry(medicine = medicine("Levothyroxine sodium", strength = "100 mcg"), tagNames = listOf("Morning", "Thyroid"), state = state), onClick = {}, dragHandle = null)
            }
        }
        for (width in PHONE_WIDTHS_DP) {
            val card = compose.onNodeWithTag(tagAt("card", width)).px()
            val name = compose.nodesInside(card, hasText("Levothyroxine sodium")).single().boundsInRoot
            val strength = compose.nodesInside(card, hasText("100 mcg")).single().boundsInRoot
            assertStacked("at $width dp, strength under the name", name, strength)
            assertEquals("at $width dp name and strength share one column", name.left, strength.left, 0.5f)
            for (node in compose.nodesInside(card, hasText("", substring = true))) {
                assertWithin("at $width dp a text node inside the card", node.boundsInRoot, card)
            }
        }
    }
}
