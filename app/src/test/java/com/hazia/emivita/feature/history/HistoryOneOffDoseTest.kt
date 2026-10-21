package com.hazia.emivita.feature.history

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import com.hazia.emivita.ui.theme.EmivitaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * History with a one-off dose (§7 R111, C-15): the row shows by its name, and
 * adherence is unchanged by it, since it measures the list's medicines and a
 * one-off was never scheduled, so it is neither taken against the plan nor missed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class, qualifiers = "w412dp-h869dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HistoryOneOffDoseTest {
    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)

    private fun at(hour: Int): Long = LocalDate.now().atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `the one-off shows by its name and adherence counts only the scheduled doses`() {
        val doses = h.container.doseEventRepository
        val metformin = h.medicine("Metformin")
        val reminder = h.reminder(metformin, ReminderConfig.FixedTime(8, 0, 1.0))
        runBlocking {
            doses.resolve(checkNotNull(doses.materializeIfAbsent(reminder, metformin, at(8), 1.0)), DoseStatus.TAKEN, at(8))
            val ramipril = h.medicine("Ramipril")
            val r2 = h.reminder(ramipril, ReminderConfig.FixedTime(9, 0, 1.0))
            doses.resolve(checkNotNull(doses.materializeIfAbsent(r2, ramipril, at(9), 1.0)), DoseStatus.SKIPPED, at(9))
            doses.logOneOffDose(h.profileId, "Paracetamol", 1.0, at(10))
        }
        val c = h.container
        compose.setContent {
            EmivitaTheme {
                HistoryScreen(
                    profileId = h.profileId,
                    doseEventRepository = c.doseEventRepository,
                    settingsRepository = c.settingsRepository,
                    reminderRepository = c.reminderRepository,
                    notificationBuilder = c.notificationBuilder,
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Paracetamol")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("50% of answered doses taken").assertExists()
        compose.onNodeWithText("1 taken, 1 skipped, 0 missed, last 30 days").assertExists()
    }
}
