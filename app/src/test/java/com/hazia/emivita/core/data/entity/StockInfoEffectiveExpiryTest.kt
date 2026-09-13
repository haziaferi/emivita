package com.hazia.emivita.core.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one expiry date everything reads (spec §9 #27; HM-09/10). */
class StockInfoEffectiveExpiryTest {
    @Test
    fun `nothing set is nothing`() {
        assertNull(StockInfo().effectiveExpirationEpochDay)
    }

    @Test
    fun `the printed expiry alone is the effective one`() {
        assertEquals(20000L, StockInfo(expirationDateEpochDay = 20000L).effectiveExpirationEpochDay)
    }

    @Test
    fun `opened plus shelf life wins when it comes first, and loses when it does not`() {
        assertEquals("opened 19900 + 28 days = 19928 < 20000", 19928L, StockInfo(expirationDateEpochDay = 20000L, openedDateEpochDay = 19900L, useWithinDaysAfterOpening = 28).effectiveExpirationEpochDay)
        assertEquals(20000L, StockInfo(expirationDateEpochDay = 20000L, openedDateEpochDay = 19990L, useWithinDaysAfterOpening = 28).effectiveExpirationEpochDay)
    }

    @Test
    fun `an opened date without a shelf life, or a shelf life without a date, changes nothing`() {
        assertEquals(20000L, StockInfo(expirationDateEpochDay = 20000L, openedDateEpochDay = 19900L).effectiveExpirationEpochDay)
        assertEquals(20000L, StockInfo(expirationDateEpochDay = 20000L, useWithinDaysAfterOpening = 28).effectiveExpirationEpochDay)
        assertNull(StockInfo(openedDateEpochDay = 19900L).effectiveExpirationEpochDay)
    }

    @Test
    fun `opened plus shelf life counts even with no printed expiry`() {
        assertEquals(19928L, StockInfo(openedDateEpochDay = 19900L, useWithinDaysAfterOpening = 28).effectiveExpirationEpochDay)
    }
}
