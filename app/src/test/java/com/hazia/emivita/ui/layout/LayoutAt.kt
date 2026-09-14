package com.hazia.emivita.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import com.hazia.emivita.ui.theme.EmivitaTheme
import org.junit.Assert.assertTrue

/**
 * Geometry, not semantics (spec §9 #33, F-14). Robolectric runs Compose's
 * layout phase for real even though it renders no pixels, so a node's
 * `boundsInRoot` is what the phone would measure. Content is composed
 * inside a Box of a phone's width; the two widths the tests use are the
 * common phone (360 dp) and the small one (320 dp) -- §7 R59's squeeze only
 * happened at phone width, which is why width is the parameter. All numbers
 * here are px at the test density.
 *
 * One limit, measured: Robolectric has no font metrics, and a text node
 * measures about one px per character ("Sertraline" is 10 px wide). So a
 * width ratio against text is meaningless here; what is real is everything
 * layout decides from dp -- padding, minimum targets, weights, stacking,
 * containment -- and a text node's line count. These tests assert structure,
 * not glyph widths; the glyphs are what the device pass is for.
 */
val PHONE_WIDTHS_DP = listOf(360, 320)

fun ComposeContentTestRule.setContentAt(widthDp: Int, content: @Composable () -> Unit) {
    setContent {
        EmivitaTheme {
            Box(Modifier.width(widthDp.dp)) { content() }
        }
    }
}

/** One composition, both phone widths stacked (a rule's `setContent` runs
 *  once per test): [content] receives the width and tags its nodes with
 *  [tagAt] so each width's nodes can be found. */
fun ComposeContentTestRule.setContentAtPhoneWidths(content: @Composable (widthDp: Int) -> Unit) {
    setContent {
        EmivitaTheme {
            Column {
                PHONE_WIDTHS_DP.forEach { width -> Box(Modifier.width(width.dp)) { content(width) } }
            }
        }
    }
}

fun tagAt(tag: String, widthDp: Int) = "$tag@$widthDp"

fun SemanticsNodeInteraction.px(): Rect = fetchSemanticsNode().boundsInRoot

/** Every unmerged node matching [matcher] whose bounds lie inside [container]. */
fun ComposeContentTestRule.nodesInside(container: Rect, matcher: SemanticsMatcher): List<SemanticsNode> =
    onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().filter { it.boundsInRoot.top >= container.top - 1f && it.boundsInRoot.bottom <= container.bottom + 1f }

fun ComposeContentTestRule.dp(value: Float): Float = value * density.density

/** [below] starts at or under [above]'s bottom edge: stacked, never beside. */
fun assertStacked(label: String, above: Rect, below: Rect) =
    assertTrue("$label: expected the second to start below the first, got $above then $below", below.top >= above.bottom - 0.5f)

fun assertWithin(label: String, inner: Rect, outer: Rect) =
    assertTrue("$label: $inner is not inside $outer", inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f && inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f)

fun assertNoOverlap(label: String, a: Rect, b: Rect) =
    assertTrue("$label: $a overlaps $b", a.right <= b.left + 0.5f || b.right <= a.left + 0.5f || a.bottom <= b.top + 0.5f || b.bottom <= a.top + 0.5f)
