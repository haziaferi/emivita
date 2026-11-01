package com.hazia.emivita.core.scheduling.receivers

import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
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

    /** This minute's slot, a few seconds back: waits out the end of a minute so the test stays inside it. */
    private fun slotInTheLastMinute(): LocalDateTime {
        val second = LocalDateTime.now(zone).second
        if (second > 40) Thread.sleep((62 - second) * 1_000L) else if (second == 0) Thread.sleep(1_500L)
        return LocalDateTime.now(zone).withSecond(0).withNano(0)
    }

    private fun launch() = runBlocking {
        h.container.schedulingCoordinator.rescheduleAll()
        h.container.missedDoseBackfiller.backfill()
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
