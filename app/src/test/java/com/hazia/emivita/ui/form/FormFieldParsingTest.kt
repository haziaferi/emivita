package com.hazia.emivita.ui.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** What "Ora", "Dal" and "Fino al" read from typed text (§7 R124). */
class FormFieldParsingTest {

    @Test
    fun `a time of day is read in the forms people type`() {
        for (t in listOf("8:00", "08:00", "08.00", "0800", "800", " 8:00 ")) assertEquals(t, 8 to 0, parseTimeOfDay(t))
        assertEquals(23 to 59, parseTimeOfDay("23:59"))
        assertEquals(7 to 30, parseTimeOfDay("7.30"))
    }

    @Test
    fun `an impossible or half-typed time is refused`() {
        for (t in listOf("", "8", "24:00", "25:00", "8:60", "8:5", "ab:cd", "8:00 pm", "-1:00")) assertNull(t, parseTimeOfDay(t))
    }

    @Test
    fun `a date is read in the locale's numeric form with a four-digit year`() {
        val march1 = LocalDate.of(2026, 3, 1).toEpochDay()
        assertEquals(march1, parseNumericDate("01/03/2026", Locale.ITALY).getOrThrow())
        assertEquals(march1, parseNumericDate("1/3/2026", Locale.ITALY).getOrThrow())
        assertEquals(march1, parseNumericDate("3/1/2026", Locale.US).getOrThrow())
        assertEquals("shown as typed back", "01/03/2026", LocalDate.ofEpochDay(march1).format(numericDateFormatter(Locale.ITALY)))
    }

    @Test
    fun `an empty date is no date, and an impossible one is refused`() {
        assertNull(parseNumericDate("  ", Locale.ITALY).getOrThrow())
        for (t in listOf("31/02/2026", "1/3", "2026-03-01", "01/13/2026", "x")) assertTrue(t, parseNumericDate(t, Locale.ITALY).isFailure)
    }
}
