package com.hazia.emivita.feature.alarm

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The amount screen (§7 R109) after §7 R113: the field's suffix is the dose
 * unit, the one documented as the suffix beside every dose-amount field, not
 * the stock's unit; and a second Take while it is open loads that dose, where
 * the single-task activity used to keep showing the first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TakeAmountActivityTest {
    private val h = ReceiverHarness()
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)

    /** A medicine counted in [stockUnit] and dosed in [doseUnit], with one dose due. */
    private fun due(name: String, doseUnit: String, stockUnit: String): Long = runBlocking {
        val c = h.container
        val m = c.medicineRepository.create(
            Medicine(
                profileId = h.profileId, name = name, iconKey = "pill", sortOrder = 0.0,
                doseUnit = doseUnit, stockInfo = StockInfo(quantity = 10.0, unit = stockUnit), createdAtEpochMillis = 0L,
            ),
        )
        val r = h.reminder(m, ReminderConfig.FixedTime(9, 0, 1.0), variableAmount = true)
        checkNotNull(c.doseEventRepository.materializeIfAbsent(r, m, 1_000L, 1.0))
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the amount field's suffix is the dose unit`() {
        val id = due("Metformin", doseUnit = "mg", stockUnit = "tablet")
        val controller = Robolectric.buildActivity(TakeAmountActivity::class.java, TakeAmountActivity.intent(h.context, id)).setup()
        try {
            waitFor("Metformin")
            compose.onNode(hasText("mg")).assertExists()
            compose.onNode(hasText("tablet")).assertDoesNotExist()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `a second Take while the screen is open shows the second dose`() {
        val first = due("Metformin", doseUnit = "mg", stockUnit = "tablet")
        val second = due("Ramipril", doseUnit = "mg", stockUnit = "tablet")
        val controller = Robolectric.buildActivity(TakeAmountActivity::class.java, TakeAmountActivity.intent(h.context, first)).setup()
        try {
            waitFor("Metformin")
            controller.newIntent(TakeAmountActivity.intent(h.context, second))
            waitFor("Ramipril")
            compose.onNode(hasText("Metformin")).assertDoesNotExist()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
