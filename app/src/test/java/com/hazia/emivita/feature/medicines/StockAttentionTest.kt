package com.hazia.emivita.feature.medicines

import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The tab badge's rule (spec §9 #27; DS-05): empty, low, or expired. */
class StockAttentionTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 13)
    private fun m(stock: StockInfo, vararg reminders: Reminder) = MedicineWithReminders(
        Medicine(id = 1, profileId = 1, name = "M", iconKey = "pill", stockInfo = stock, sortOrder = 0.0, createdAtEpochMillis = 0), reminders.toList(),
    )
    private fun lowStock(threshold: Double, enabled: Boolean = true) = Reminder(
        medicineId = 1, type = ReminderType.LOW_STOCK_ALERT, enabled = enabled, createdAtEpochMillis = 0,
        configJson = encodeReminderConfig(ReminderConfig.LowStockAlert(threshold, LowStockTriggerMode.ONCE_WHEN_BELOW)),
    )

    @Test
    fun `tracked and empty counts, untracked never does`() {
        assertTrue(needsStockAttention(m(StockInfo(quantity = 0.0, unit = "tablet")), today))
        assertFalse(needsStockAttention(m(StockInfo(quantity = 0.0)), today))
        assertFalse(needsStockAttention(m(StockInfo(quantity = 5.0, unit = "tablet")), today))
    }

    @Test
    fun `at or below an enabled low-stock threshold counts, a paused one does not`() {
        assertTrue(needsStockAttention(m(StockInfo(quantity = 3.0, unit = "tablet"), lowStock(3.0)), today))
        assertFalse(needsStockAttention(m(StockInfo(quantity = 4.0, unit = "tablet"), lowStock(3.0)), today))
        assertFalse(needsStockAttention(m(StockInfo(quantity = 1.0, unit = "tablet"), lowStock(3.0, enabled = false)), today))
    }

    @Test
    fun `expired by the effective date counts, even untracked`() {
        assertTrue(needsStockAttention(m(StockInfo(expirationDateEpochDay = today.minusDays(1).toEpochDay())), today))
        assertFalse(needsStockAttention(m(StockInfo(expirationDateEpochDay = today.toEpochDay())), today))
        assertTrue(needsStockAttention(m(StockInfo(openedDateEpochDay = today.minusDays(40).toEpochDay(), useWithinDaysAfterOpening = 28)), today))
    }

    @Test
    fun `the count is per medicine, not per reason`() {
        val both = m(StockInfo(quantity = 0.0, unit = "tablet", expirationDateEpochDay = today.minusDays(1).toEpochDay()), lowStock(3.0))
        val fine = m(StockInfo(quantity = 9.0, unit = "tablet"), lowStock(3.0))
        assertEquals(1, stockAttentionCount(listOf(both, fine), today))
        assertEquals(0, stockAttentionCount(emptyList(), today))
    }
}
