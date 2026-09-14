package com.hazia.emivita.feature.today

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.ui.layout.PHONE_WIDTHS_DP
import com.hazia.emivita.ui.layout.assertNoOverlap
import com.hazia.emivita.ui.layout.assertWithin
import com.hazia.emivita.ui.layout.nodesInside
import com.hazia.emivita.ui.layout.px
import com.hazia.emivita.ui.layout.setContentAtPhoneWidths
import com.hazia.emivita.ui.layout.tagAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * The four Today layouts (§9 #37): the bands' thresholds, the hero's pick,
 * and the compact row's rule that no single tap changes a dose -- a due row
 * opens its actions on the first tap and closes them on the second.
 */
@RunWith(RobolectricTestRunner::class)
class TodayLayoutsTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val day: LocalDate = LocalDate.of(2026, 9, 15)
    private fun at(hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun medicine(id: Long = 1L, name: String = "Levothyroxine sodium 100 mcg film-coated") = Medicine(
        id = id, profileId = 1L, name = name, iconKey = "tablet", sortOrder = 0.0, createdAtEpochMillis = 0L,
    )
    private fun entry(status: TimelineStatus, id: Long = 1L, reminderId: Long? = 3L) = TimelineEntry(
        medicine = medicine(id), doseAmount = 1.0, status = status, doseEventId = if (status == TimelineStatus.UPCOMING) null else 7L + id,
        resolvedAtEpochMillis = null, reminderId = reminderId,
    )
    private fun node(epochMillis: Long, vararg entries: TimelineEntry) = TimelineNode(epochMillis, entries.toList())

    @Test
    fun `the bands break at 11 and 17`() {
        assertEquals(DayBand.MORNING, bandOf(at(10, 59), zone))
        assertEquals(DayBand.AFTERNOON, bandOf(at(11, 0), zone))
        assertEquals(DayBand.AFTERNOON, bandOf(at(16, 59), zone))
        assertEquals(DayBand.EVENING, bandOf(at(17, 0), zone))
        assertEquals(DayBand.MORNING, bandOf(at(0, 0), zone))
    }

    @Test
    fun `grouping keeps the timeline's order inside a band and omits empty bands`() {
        val nodes = listOf(
            node(at(7), entry(TimelineStatus.TAKEN, id = 1)),
            node(at(8), entry(TimelineStatus.TAKEN, id = 2), entry(TimelineStatus.SKIPPED, id = 3)),
            node(at(20), entry(TimelineStatus.UPCOMING, id = 4)),
        )
        val grouped = groupByBand(nodes, zone)
        assertEquals(listOf(DayBand.MORNING, DayBand.EVENING), grouped.keys.toList())
        assertEquals(listOf(1L, 2L, 3L), grouped.getValue(DayBand.MORNING).map { it.second.medicine.id })
        assertEquals(listOf(4L), grouped.getValue(DayBand.EVENING).map { it.second.medicine.id })
    }

    @Test
    fun `the hero is the first due dose, else the first still to come, else none`() {
        val due = flatten(listOf(node(at(7), entry(TimelineStatus.TAKEN, id = 1)), node(at(9), entry(TimelineStatus.PENDING, id = 2)), node(at(10), entry(TimelineStatus.PENDING, id = 3))))
        assertEquals(2L, nextDose(due)!!.second.medicine.id)
        val upcoming = flatten(listOf(node(at(7), entry(TimelineStatus.SKIPPED, id = 1)), node(at(20), entry(TimelineStatus.UPCOMING, id = 4))))
        assertEquals(4L, nextDose(upcoming)!!.second.medicine.id)
        assertNull(nextDose(flatten(listOf(node(at(7), entry(TimelineStatus.TAKEN, id = 1))))))
    }

    @Test
    fun `a due compact row shows its actions on the first tap and hides them on the second`() {
        compose.setContent { CompactRow(node(at(9)), entry(TimelineStatus.PENDING), canSkip = true, onResolve = {}, onClick = {}) }
        compose.onAllNodes(hasText("Take")).assertCountEquals(0)
        compose.onNodeWithText("Levothyroxine sodium 100 mcg film-coated").performClick()
        compose.onNodeWithText("Take").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
        compose.onNodeWithText("Levothyroxine sodium 100 mcg film-coated").performClick()
        compose.waitUntil { compose.onAllNodes(hasText("Take")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a taken compact row opens the edit dialog on tap and never shows actions`() {
        var clicks = 0
        compose.setContent { CompactRow(node(at(9)), entry(TimelineStatus.TAKEN), canSkip = true, onResolve = {}, onClick = { clicks++ }) }
        compose.onNodeWithText("Levothyroxine sodium 100 mcg film-coated").performClick()
        assertEquals(1, clicks)
        compose.onAllNodes(hasText("Take")).assertCountEquals(0)
    }

    @Test
    fun `the hero card names the dose and offers Take, or says the day is done`() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                HeroCard(node(at(9)), entry(TimelineStatus.PENDING), canSkip = true, onResolve = {})
                HeroCard(null, null, canSkip = false, onResolve = {})
            }
        }
        compose.onNodeWithText("Levothyroxine sodium 100 mcg film-coated").assertIsDisplayed()
        compose.onNodeWithText("Take").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
        compose.onNodeWithText("Nothing left to take today").assertIsDisplayed()
    }

    @Test
    fun `at both phone widths the compact row's name, time and tile stay inside it without overlapping`() {
        compose.setContentAtPhoneWidths { width ->
            Box(Modifier.semantics { testTag = tagAt("row", width) }) {
                CompactRow(node(at(9)), entry(TimelineStatus.PENDING), canSkip = true, onResolve = {}, onClick = {})
            }
        }
        for (width in PHONE_WIDTHS_DP) {
            val row = compose.onNodeWithTag(tagAt("row", width)).px()
            val texts = compose.nodesInside(row, hasText("Levothyroxine", substring = true))
            assertTrue("the name renders at $width dp", texts.isNotEmpty())
            val name = texts.first().boundsInRoot
            assertWithin("the name at $width dp", name, row)
            val time = compose.nodesInside(row, hasText("09:00", substring = true)).firstOrNull()?.boundsInRoot
            if (time != null) assertNoOverlap("time and name at $width dp", time, name)
        }
    }
}
