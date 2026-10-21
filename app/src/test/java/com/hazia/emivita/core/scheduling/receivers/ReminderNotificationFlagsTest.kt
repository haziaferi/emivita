package com.hazia.emivita.core.scheduling.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.settings.SwipeAction
import com.hazia.emivita.core.notification.notificationAccentArgb
import com.hazia.emivita.core.notification.reminderCanBeSwiped
import com.hazia.emivita.feature.alarm.TakeAmountActivity
import com.hazia.emivita.ui.theme.medicineAccent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * §7 R109: the reminder notification's buttons (Appendix E B-10), the
 * keep-on-screen switch (B-42), the medicine's colour and icon (B-43) and
 * the variable amount on Take (B-45), on the notification the receiver posts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderNotificationFlagsTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val h = ReceiverHarness()
    private val take = h.context.getString(R.string.common_take)
    private val snooze = h.context.getString(R.string.common_snooze)
    private val skip = h.context.getString(R.string.common_skip)

    /** DataStore outlives the Robolectric application between tests. */
    @After
    fun tearDown() {
        runBlocking {
            h.container.settingsRepository.setSwipeAction(SwipeAction.SNOOZE)
            h.container.settingsRepository.setHideMedicineNames(false)
        }
        h.close()
    }

    private fun daily() = ReminderConfig.FixedTime(hour = 9, minute = 0, dose = 1.0)

    /** A medicine, a daily reminder, its first fire: the dose and its posted notification. */
    private fun posted(sticky: Boolean = false, variableAmount: Boolean = false, color: String? = null, stock: Double = 10.0): Triple<Long, Long, Notification> {
        val medicineId = h.medicine(stock = stock, color = color)
        val reminderId = h.reminder(medicineId, daily(), sticky = sticky, variableAmount = variableAmount)
        h.fire(h.regularAlarms(reminderId).single())
        val doseId = h.doseEvents(reminderId).single().id
        val nm = h.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = shadowOf(nm).activeNotifications.single { it.id == doseId.toInt() }.notification
        return Triple(medicineId, doseId, n)
    }

    private fun labels(n: Notification) = n.actions.orEmpty().map { it.title.toString() }

    // ---- B-10 ---------------------------------------------------------------

    @Test
    fun `with swipe set to Snooze there is no Snooze button, on the card or the lock-screen copy`() {
        val (_, _, n) = posted()
        assertEquals(listOf(take, skip), labels(n))
        assertEquals(listOf(take), labels(n.publicVersion))
    }

    @Test
    fun `a reminder kept on screen cannot be swiped, so it keeps Snooze`() {
        val (_, _, n) = posted(sticky = true)
        assertEquals(listOf(take, snooze, skip), labels(n))
        assertEquals(listOf(take, snooze), labels(n.publicVersion))
    }

    @Test
    fun `with swipe set to Skip the Skip button goes, and with Nothing every button stays`() {
        runBlocking { h.container.settingsRepository.setSwipeAction(SwipeAction.SKIP) }
        assertEquals(listOf(take, snooze), labels(posted().third))
        runBlocking { h.container.settingsRepository.setSwipeAction(SwipeAction.NOTHING) }
        assertEquals(listOf(take, snooze, skip), labels(posted().third))
    }

    @Test
    fun `the Alarm tier ringing in its service cannot be swiped below Android 13`() {
        assertFalse(reminderCanBeSwiped(sticky = false, ringsInService = true, sdk = 30))
        assertTrue(reminderCanBeSwiped(sticky = false, ringsInService = true, sdk = 33))
        assertTrue(reminderCanBeSwiped(sticky = false, ringsInService = false, sdk = 30))
        assertFalse(reminderCanBeSwiped(sticky = true, ringsInService = false, sdk = 35))
    }

    // ---- B-42 ---------------------------------------------------------------

    @Test
    fun `a sticky reminder is posted ongoing and an ordinary one is not`() {
        assertTrue((posted(sticky = true).third.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(0, posted().third.flags and Notification.FLAG_ONGOING_EVENT)
    }

    // ---- B-43 ---------------------------------------------------------------

    @Test
    fun `the notification takes the medicine's colour and icon`() {
        val n = posted(color = "#2E7D32").third
        assertEquals(medicineAccent("#2E7D32").toArgb(), n.color)
        assertNotNull("large icon", n.getLargeIcon())
    }

    @Test
    fun `with names hidden it takes the palette's accent and no icon`() {
        runBlocking { h.container.settingsRepository.setHideMedicineNames(true) }
        val n = posted(color = "#2E7D32").third
        assertEquals(runBlocking { notificationAccentArgb(h.container.settingsRepository) }, n.color)
        assertNull("large icon", n.getLargeIcon())
    }

    // ---- B-45 ---------------------------------------------------------------

    private fun takeIntent(n: Notification) = shadowOf(n.actions.single { it.title.toString() == take }.actionIntent)

    @Test
    fun `a variable-amount reminder's Take opens the amount screen, and 2 records Taken 2 with stock down 2`() {
        val (medicineId, doseId, n) = posted(variableAmount = true)
        val pi = takeIntent(n)
        assertTrue("Take opens an activity", pi.isActivityIntent)
        assertEquals(TakeAmountActivity::class.java.name, pi.savedIntent.component?.className)

        ActivityScenario.launch<TakeAmountActivity>(Intent(pi.savedIntent)).use {
            compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextReplacement("2")
            compose.onNode(hasText(take) and hasClickAction()).performClick()
            // Room writes run on Dispatchers.IO, outside the test's clock (as TodayTakeAllTest waits).
            val deadline = System.nanoTime() + 10_000_000_000L
            while (h.doseEvent(doseId).status != DoseStatus.TAKEN && System.nanoTime() < deadline) {
                Thread.sleep(50)
                compose.waitForIdle()
            }
        }

        val event = h.doseEvent(doseId)
        assertEquals(DoseStatus.TAKEN, event.status)
        assertEquals(2.0, event.doseAmount, 0.0)
        assertEquals(8.0, h.medicineRow(medicineId).stockInfo.quantity, 0.0)
    }

    @Test
    fun `an ordinary reminder's Take records the planned amount`() {
        val (medicineId, doseId, n) = posted()
        val pi = takeIntent(n)
        assertTrue("Take is a broadcast", pi.isBroadcastIntent)

        h.deliver(Intent(pi.savedIntent))

        val event = h.doseEvent(doseId)
        assertEquals(DoseStatus.TAKEN, event.status)
        assertEquals(1.0, event.doseAmount, 0.0)
        assertEquals(9.0, h.medicineRow(medicineId).stockInfo.quantity, 0.0)
    }
}
