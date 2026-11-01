package com.hazia.emivita.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Today's date turns at midnight (§7 R140, the W4 walk). `delay` counts on the
 * monotonic clock, which stops while the phone is in deep sleep, so a wait
 * computed at 23:50 can still be running at 00:10 by the wall clock. The test's
 * main clock is that monotonic clock; [wall] is the one the date is read from.
 */
@RunWith(RobolectricTestRunner::class)
class CurrentDateTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private var wall = 0L

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Text(rememberCurrentDate(zone) { wall }.toString()) }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `awake across midnight, the day turns at midnight`() {
        wall = at("2026-10-31T23:59:30")
        show()
        compose.onNodeWithText("2026-10-31").assertExists()
        wall = at("2026-11-01T00:00:02")
        compose.mainClock.advanceTimeBy(32_000)
        compose.onNodeWithText("2026-11-01").assertExists()
    }

    @Test
    fun `asleep across midnight, the day turns within a minute of waking`() {
        wall = at("2026-10-31T23:50")
        show()
        compose.onNodeWithText("2026-10-31").assertExists()
        // Twenty minutes of deep sleep: the wall clock moved, the monotonic one did not.
        wall = at("2026-11-01T00:10")
        compose.mainClock.advanceTimeBy(61_000)
        compose.onNodeWithText("2026-11-01").assertExists()
    }
}
