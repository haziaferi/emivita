package com.hazia.emivita.feature.widget

import android.app.Application
import android.content.Intent
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.feature.alarm.TakeAmountActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Next reminders widget's Take (§7 R113): on a reminder that asks the
 * amount (§7 R109, B-45) it materialises the slot and opens the amount
 * screen on it, recording nothing; on any other it records the planned dose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WidgetTakeAsksAmountTest {
    private val h = ReceiverHarness()

    @After
    fun tearDown() = h.close()

    private val slot = LocalDate.now().atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun nextStarted(): Intent? = shadowOf(h.context as Application).nextStartedActivity

    @Test
    fun `Take on a reminder that asks the amount opens the amount screen and leaves the dose due`() {
        val m = h.medicine("Metformin", stock = 10.0)
        val r = h.reminder(m, ReminderConfig.FixedTime(20, 0, 1.0), variableAmount = true)
        runBlocking { takeFromWidget(h.context, h.container, r, m, slot, 1.0) }
        val event = h.doseEvents(r).single { it.scheduledEpochMillis == slot }
        assertEquals(DoseStatus.PENDING, event.status)
        assertEquals(10.0, h.medicineRow(m).stockInfo.quantity, 0.0)
        val started = nextStarted()
        assertNotNull("Take opened no screen", started)
        assertEquals(TakeAmountActivity::class.java.name, started!!.component?.className)
        assertEquals(event.id, started.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L))
        assert(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `Take on any other reminder records the planned dose`() {
        val m = h.medicine("Ramipril", stock = 10.0)
        val r = h.reminder(m, ReminderConfig.FixedTime(20, 0, 1.0))
        runBlocking { takeFromWidget(h.context, h.container, r, m, slot, 1.0) }
        assertEquals(DoseStatus.TAKEN, h.doseEvents(r).single { it.scheduledEpochMillis == slot }.status)
        assertEquals(9.0, h.medicineRow(m).stockInfo.quantity, 0.0)
        assertNull(nextStarted())
    }
}
