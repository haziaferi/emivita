package com.hazia.emivita.feature.today

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.settings.StartPage
import com.hazia.emivita.navigation.EmivitaNavHost
import com.hazia.emivita.ui.form.FormScreenTestBase
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Today's undo bar (§7 R134, the owner's pick): Take on a row shows "Metformina: presa alle …" with Annulla, and
 * Annulla puts the dose back to due, as "Return to due" does, with its stock.
 */
class TodayUndoBarTest : FormScreenTestBase() {

    @Test fun take_then_annulla_puts_the_dose_back() {
        val clock = LocalTime.now()
        val due = clock.plusHours(1).let { if (it < clock) LocalTime.of(23, 59) else it }
        val med = h.medicine("Metformina", stock = 10.0, unit = "compresse")
        val rem = h.reminder(med, ReminderConfig.FixedTime(hour = due.hour, minute = due.minute, dose = 1.0))
        host { EmivitaNavHost(startPage = StartPage.TODAY) }
        waitFor("the row's Take") { inTop(hasText(s(R.string.today_take_now)) and hasClickAction()) }
        tap(hasText(s(R.string.today_take_now)) and hasClickAction())
        waitFor("the undo bar") { inTop(hasText("Metformina: presa alle", substring = true)) }
        assertEquals(9.0, h.medicineRow(med).stockInfo.quantity, 0.0)
        tap(hasText(s(R.string.common_undo)) and hasClickAction())
        waitFor("the dose back to due") { h.doseEvents(rem).single().status == DoseStatus.PENDING }
        assertEquals("the stock given back", 10.0, h.medicineRow(med).stockInfo.quantity, 0.0)
    }
}
