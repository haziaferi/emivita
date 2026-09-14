package com.hazia.emivita.feature.history

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hazia.emivita.ui.layout.PHONE_WIDTHS_DP
import com.hazia.emivita.ui.layout.dp
import com.hazia.emivita.ui.layout.nodesInside
import com.hazia.emivita.ui.layout.px
import com.hazia.emivita.ui.layout.setContentAtPhoneWidths
import com.hazia.emivita.ui.layout.tagAt
import com.patrykandpatrick.vico.core.cartesian.Zoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The chart fits its whole window to the width and does not scroll (spec §9
 * #34). Vico's default opened a wider-than-screen layer at its *start*, so
 * the recent days -- the only ones with bars -- sat off-screen on every phone
 * from §7 R55 until the device pass. The fit is pinned as values because the
 * zoom and scroll states are Vico internals a Compose test cannot read; the
 * second test measures what it can, the host's bounds against a thirty-day
 * window at both phone widths.
 */
@RunWith(RobolectricTestRunner::class)
class AdherenceChartFitTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the chart is fitted to its content and neither zooms nor scrolls`() {
        assertEquals(Zoom.Content, AdherenceChartFit.INITIAL_ZOOM)
        assertFalse(AdherenceChartFit.ZOOM_ENABLED)
        assertFalse(AdherenceChartFit.SCROLL_ENABLED)
    }

    @Test
    fun `thirty days stay inside the card at both phone widths`() {
        val start = LocalDate.of(2026, 8, 1)
        val counts = (0 until 30).map { DayAdherenceCounts(start.plusDays(it.toLong()), taken = 1 + it % 3, skipped = it % 2, missed = 0) }
        compose.setContentAtPhoneWidths { width ->
            Box(Modifier.semantics { testTag = tagAt("chart", width) }) { AdherenceChart(counts) }
        }
        for (width in PHONE_WIDTHS_DP) {
            val frame = compose.onNodeWithTag(tagAt("chart", width)).px()
            val host = compose.nodesInside(frame, hasContentDescription("Adherence chart.", substring = true)).single().boundsInRoot
            assertTrue("at $width dp the host's right edge ${host.right} px is past the frame's ${frame.right} px", host.right <= frame.right + 0.5f)
            assertTrue("at $width dp the host has no width", host.width > 0f)
            assertEquals("at $width dp the frame is the phone width", compose.dp(width.toFloat()), frame.width, 0.5f)
        }
    }
}
