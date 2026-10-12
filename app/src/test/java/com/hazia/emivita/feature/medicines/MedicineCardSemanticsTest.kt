package com.hazia.emivita.feature.medicines

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.StockInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/** The Medicines card and its drag handle (spec §9 #24): one button node
 *  whose state is the card's state line; a handle a screen reader can use
 *  through "Move up" / "Move down" since it cannot drag. */
@RunWith(RobolectricTestRunner::class)
class MedicineCardSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun entry(state: MedicineCardState) = MedicineListEntry(
        medicine = Medicine(id = 1, profileId = 1, name = "Sertraline", iconKey = "pill", stockInfo = StockInfo(), sortOrder = 0.0, createdAtEpochMillis = 0),
        tagNames = emptyList(),
        state = state,
    )

    private val due = MedicineCardState(TodayStatus.DUE, expired = false, empty = false, runsOutInDays = 2, course = CourseState.None, nextDoseEpochMillis = null)

    @Test
    fun theCardIsOneButtonWhoseStateIsItsStateLine() {
        compose.setContent { MedicineCard(entry(due), onClick = {}, dragHandle = null) }
        compose.onAllNodes(hasRole(Role.Button)).assertCountEquals(1)
        compose.onAllNodes(hasRole(Role.Button))[0].assert(hasStateDescription("Due now · Runs out in 2 days"))
    }

    @Test
    fun theDragHandleOffersMoveUpAndMoveDownAsAccessibilityActions() {
        var moved = 0
        compose.setContent {
            val drag = DragReorderState(rememberLazyListState())
            DragHandle(itemKey = 1L, index = 1, count = 3, drag = drag, onMoveBy = { moved += it }, onReorder = { _, _ -> }, onDrop = {})
        }
        val handle = compose.onNodeWithContentDescription("Drag to reorder")
        handle.assert(hasStateDescription("Position 2 of 3"))
        val actions = handle.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move up", "Move down"), actions.map { it.label })
        actions.first { it.label == "Move down" }.action()
        assertEquals(1, moved)
    }

    // ---- §9 #27: the filter bar ----

    @Test
    fun theSearchFieldIsFoundByItsLabelAndClearsItself() {
        var q = "ser"
        compose.setContent {
            MedicinesFilterBar(searching = true, query = q, onQuery = { q = it }, tags = emptyList(), selectedTags = emptySet(), onToggleTag = {}, hideEmptyOffered = false, hideEmpty = false, onHideEmpty = {})
        }
        compose.onNodeWithContentDescription("Search medicines").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", q)
    }

    @Test
    fun eachTagIsItsOwnChipAndTheBadgeCountsTheSelectedOnes() {
        var toggled: String? = null
        compose.setContent {
            MedicinesFilterBar(searching = false, query = "", onQuery = {}, tags = listOf("morning", "travel"), selectedTags = setOf("morning"), onToggleTag = { toggled = it }, hideEmptyOffered = true, hideEmpty = false, onHideEmpty = {})
        }
        compose.onNodeWithText("morning").assertIsSelected()
        compose.onNodeWithText("travel").assertIsNotSelected().performClick()
        assertEquals("travel", toggled)
        compose.onNodeWithText("Hide empty").assertExists()
        compose.onNodeWithContentDescription("1 tag selected").assertExists()
        assertEquals("three chips, each selectable", 3, compose.onAllNodes(isSelectable()).fetchSemanticsNodes().size)
    }

    @Test
    fun theFirstItemCannotMoveUpAndTheLastCannotMoveDown() {
        compose.setContent {
            val drag = DragReorderState(rememberLazyListState())
            DragHandle(itemKey = 1L, index = 0, count = 2, drag = drag, onMoveBy = {}, onReorder = { _, _ -> }, onDrop = {})
        }
        val actions = compose.onNodeWithContentDescription("Drag to reorder").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move down"), actions.map { it.label })
    }

    /** §7 R83: the gesture was keyed on the index it moves, so the first
     *  reorder tore it down and the drop never fired. A drag across two
     *  neighbours must reach onDrop with the final place. */
    @Test
    fun aDragAcrossTwoNeighboursReachesTheDropWithItsFinalPlace() {
        val drops = mutableListOf<Pair<Long, Int>>()
        compose.setContent {
            var order by remember { mutableStateOf(listOf(1L, 2L, 3L)) }
            val listState = rememberLazyListState()
            val drag = remember(listState) { DragReorderState(listState) }
            LazyColumn(state = listState) {
                itemsIndexed(order, key = { _, id -> id }) { index, id ->
                    Box(Modifier.height(60.dp)) {
                        DragHandle(
                            itemKey = id, index = index, count = order.size, drag = drag,
                            onMoveBy = {},
                            onReorder = { from, to -> order = order.toMutableList().also { it.add(to, it.removeAt(from)) } },
                            onDrop = { drops += id to it },
                        )
                    }
                }
            }
        }
        // One frame per step: the teardown is a recomposition, so a gesture
        // injected in a single batch would never meet it.
        val handle = compose.onAllNodesWithContentDescription("Drag to reorder")[0]
        handle.performTouchInput { down(center) }
        repeat(30) {
            handle.performTouchInput { moveBy(Offset(0f, 8f)) }
            compose.waitForIdle()
        }
        handle.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(1L to 2), drops)
    }
}
