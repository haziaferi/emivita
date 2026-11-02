package com.hazia.emivita.core.scheduling.receivers

import android.content.Intent
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.scheduling.BootRescheduleReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Opening the app while the OS holds a dose's alarm (§7 R141, after R140's W4):
 * the launch re-arm moved a slot of the last minute to tomorrow, the back-fill
 * leaves that minute to the alarm path, and the dose got no row at all. The
 * launch path's two steps run here in its order, on the real container.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LaunchHeldSlotTest {

    private val h = ReceiverHarness()
    private val zone: ZoneId = ZoneId.systemDefault()

    @After
    fun tearDown() = h.close()

    /** This minute's slot, a few seconds back: a second's wait at the turn of a minute, no longer the twenty it took
     *  to stay inside R141's one-minute window, since R143 holds a held slot at any age. */
    private fun slotInTheLastMinute(): LocalDateTime {
        if (LocalDateTime.now(zone).second == 0) Thread.sleep(1_000L)
        return LocalDateTime.now(zone).withSecond(0).withNano(0)
    }

    private fun slotMinutesAgo(minutes: Long): LocalDateTime = LocalDateTime.now(zone).minusMinutes(minutes).withSecond(0).withNano(0)

    private fun epochOf(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    private fun launch() = runBlocking {
        h.container.schedulingCoordinator.rescheduleAll()
        h.container.missedDoseBackfiller.backfill()
    }

    /** A FixedTime reminder at [slot] whose alarm was armed for it while it was ahead and has not been delivered. */
    private fun heldReminder(slot: LocalDateTime, name: String = "Ibuprofen"): Long {
        val reminderId = h.reminder(h.medicine(name), ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0))
        h.container.alarmScheduler.schedule(reminderId, epochOf(slot))
        return reminderId
    }

    @Test
    fun `a slot held past its minute still reaches its dose when the app is opened again`() {
        // §7 R143 (I3 of the PR #62 review): an opening in the slot's minute moved "last seen" past the slot, so the
        // back-fill starts after it; a second opening after that minute moved the held slot to tomorrow, and the dose
        // got no row.
        val slot = slotMinutesAgo(3)
        val at = epochOf(slot)
        val reminderId = heldReminder(slot)
        runBlocking { h.container.settingsRepository.setAppLastSeenEpochMillis(at + 30_000L) }
        launch()
        val held = h.regularAlarms(reminderId).filter { h.scheduledAtOf(it) == at }
        assertEquals("the held slot is still armed after the second opening", 1, held.size)
        h.fire(held.single())
        assertEquals(listOf(at), h.doseEvents(reminderId).map { it.scheduledEpochMillis })
    }

    @Test
    fun `another reminder's alarm during the hold does not hide the held slot`() {
        // M4 of the review: R141 told a held slot from a fired one by the last alarm of any kind, so another
        // reminder ringing in that minute read as this one having rung, and the launch moved it to tomorrow.
        val slot = slotInTheLastMinute()
        val at = epochOf(slot)
        val held = heldReminder(slot, "Metformin")
        val other = heldReminder(slot, "Ramipril")
        h.fire(h.regularAlarms(other).single { h.scheduledAtOf(it) == at })
        launch()
        assertEquals(1, h.regularAlarms(held).count { h.scheduledAtOf(it) == at })
    }

    @Test
    fun `a "when taken" slot that rang is not armed again while its dose waits`() {
        // Control for R143's rule: such a reminder's alarm record stays on the slot that rang until the dose is
        // answered, so the record alone reads as held; the dose row says it rang.
        val slot = slotMinutesAgo(3)
        val at = epochOf(slot)
        val reminderId = h.reminder(h.medicine(), ReminderConfig.RepeatingInterval(240, 1.0, IntervalStartBehavior.ON_RESOLVED, at))
        h.container.alarmScheduler.schedule(reminderId, at)
        h.fire(h.regularAlarms(reminderId).single { h.scheduledAtOf(it) == at })
        launch()
        assertTrue("no second ring for a dose already posted", h.regularAlarms(reminderId).none { h.scheduledAtOf(it) == at })
        assertEquals(1, h.doseEvents(reminderId).size)
    }

    @Test
    fun `a slot that passed before the phone restarted is left to the back-fill`() {
        // Control for R143's rule: a restart drops every alarm, so a slot from before it was missed, not held; the
        // back-fill and the re-post answer for it as they did, rather than a ring for it at boot as well.
        val slot = slotMinutesAgo(3)
        val reminderId = heldReminder(slot)
        h.deliver(Intent(h.context, BootRescheduleReceiver::class.java).setAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(h.regularAlarms(reminderId).none { h.scheduledAtOf(it) == epochOf(slot) })
    }

    @Test
    fun `a slot the OS is holding, the app opened within its minute, still reaches its dose`() {
        val slot = slotInTheLastMinute()
        val at = slot.atZone(zone).toInstant().toEpochMilli()
        val reminderId = h.reminder(h.medicine(), ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0))
        // Armed for the slot while it was still ahead; the OS has not delivered it.
        h.container.alarmScheduler.schedule(reminderId, at)
        launch()
        val held = h.regularAlarms(reminderId).filter { h.scheduledAtOf(it) == at }
        assertEquals("the held slot is still armed after the launch", 1, held.size)
        h.fire(held.single())
        val doses = h.doseEvents(reminderId)
        assertEquals(listOf(at), doses.map { it.scheduledEpochMillis })
        assertEquals(DoseStatus.PENDING, doses.single().status)
    }

    @Test
    fun `a slot that already fired is not armed again by the launch`() {
        val slot = slotInTheLastMinute()
        val at = slot.atZone(zone).toInstant().toEpochMilli()
        val reminderId = h.reminder(h.medicine(), ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0))
        h.container.alarmScheduler.schedule(reminderId, at)
        h.fire(h.regularAlarms(reminderId).single { h.scheduledAtOf(it) == at })
        launch()
        assertTrue("no second ring for a dose already posted", h.regularAlarms(reminderId).none { h.scheduledAtOf(it) == at })
        assertEquals(1, h.doseEvents(reminderId).size)
    }
}
