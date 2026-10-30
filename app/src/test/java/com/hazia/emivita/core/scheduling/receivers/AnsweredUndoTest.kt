package com.hazia.emivita.core.scheduling.receivers

import android.app.Notification
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.notification.ACTION_SKIP
import com.hazia.emivita.core.notification.ACTION_TAKE
import com.hazia.emivita.core.notification.ACTION_UNDO
import com.hazia.emivita.core.notification.UNDO_WINDOW_MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The answered card (§7 R134, the owner's pick): Take or Skip from the shade leaves "Metformina: presa alle …" with
 * Annulla for a few seconds, silent, its lock-screen copy naming no medicine; Annulla puts the dose back to due, gives
 * the stock back and shows the reminder again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class, qualifiers = "it-rIT")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnsweredUndoTest {

    private val h = ReceiverHarness()

    @After fun tearDown() {
        runBlocking { h.container.settingsRepository.setHideMedicineNames(false) }
        h.close()
    }

    private fun pending(): Triple<Long, Long, Long> {
        val medicineId = h.medicine(name = "Metformina", stock = 10.0)
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(hour = 9, minute = 0, dose = 1.0))
        h.fire(h.regularAlarms(reminderId).single())
        return Triple(medicineId, reminderId, h.doseEvents(reminderId).single().id)
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test fun take_leaves_the_card_with_annulla() {
        val (_, _, doseId) = pending()
        h.action(ACTION_TAKE, doseId)
        val card = h.answeredCards[doseId.toInt()] ?: error("no answered card: ${h.answeredCards}")
        assertTrue(title(card), title(card).startsWith("Metformina: presa alle "))
        assertEquals("one action, Annulla", listOf("Annulla"), card.actions.map { it.title.toString() })
        assertEquals(UNDO_WINDOW_MS, card.timeoutAfter)
        assertFalse("the lock-screen copy names no medicine", title(checkNotNull(card.publicVersion)).contains("Metformina"))
        assertEquals("the reminder itself is gone", emptyList<Int>(), h.notificationIds)
    }

    @Test fun skip_leaves_the_card_too() {
        val (_, _, doseId) = pending()
        h.action(ACTION_SKIP, doseId)
        assertEquals("Metformina: saltata", title(h.answeredCards.getValue(doseId.toInt())))
    }

    @Test fun annulla_returns_the_dose_and_the_reminder() {
        val (medicineId, _, doseId) = pending()
        h.action(ACTION_TAKE, doseId)
        assertEquals(9.0, h.medicineRow(medicineId).stockInfo.quantity, 0.0)
        h.action(ACTION_UNDO, doseId)
        assertEquals(DoseStatus.PENDING, h.doseEvent(doseId).status)
        assertEquals("the stock given back", 10.0, h.medicineRow(medicineId).stockInfo.quantity, 0.0)
        assertTrue("no card left", h.answeredCards.isEmpty())
        assertEquals("the reminder shows again", listOf(doseId.toInt()), h.notificationIds.filter { it > 0 })
    }

    @Test fun hidden_names_hide_it_on_the_card() {
        runBlocking { h.container.settingsRepository.setHideMedicineNames(true) }
        val (_, _, doseId) = pending()
        h.action(ACTION_TAKE, doseId)
        assertFalse(title(h.answeredCards.getValue(doseId.toInt())).contains("Metformina"))
    }
}
