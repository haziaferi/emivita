package com.hazia.emivita.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Locale-aware rendering and parsing (spec §7 R60 D F3 / E F6 / E F7 / F 2).
 * Robolectric because [LocaleFormats] goes through Android's
 * `DateFormat.getBestDateTimePattern` for date skeletons. Two locales are
 * enough to prove the seam: en-US (12-hour, month first, `.`) and it-IT
 * (24-hour, day first, `,`) disagree on every axis this file touches.
 */
@RunWith(RobolectricTestRunner::class)
class LocaleFormatsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val eightPm = LocalDateTime.of(2026, 9, 12, 20, 5).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `time follows the locale's clock -- 12-hour in en-US, 24-hour in it-IT`() {
        val us = LocaleFormats.time(eightPm, zone, Locale.US)
        val it = LocaleFormats.time(eightPm, zone, Locale.ITALY)
        assertTrue(us, us.contains("8:05") && us.uppercase().contains("PM"))
        assertEquals("20:05", it)
    }

    @Test
    fun `weekday and month-day come out in the locale's own order`() {
        val date = LocalDate.of(2026, 9, 12)
        val us = LocaleFormats.weekdayMonthDay(date, Locale.US)
        val it = LocaleFormats.weekdayMonthDay(date, Locale.ITALY)
        assertTrue(us, us.startsWith("Sat") && us.contains("Sep 12"))
        assertTrue(it, it.startsWith("sab") && it.contains("12 set"))
    }

    @Test
    fun `a dose prints with the locale's decimal separator`() {
        assertEquals("0.5", formatDecimalLocalized(0.5, Locale.US))
        assertEquals("0,5", formatDecimalLocalized(0.5, Locale.ITALY))
        assertEquals("2", formatDecimalLocalized(2.0, Locale.ITALY))
    }

    @Test
    fun `a dose typed with a comma parses under a comma locale, and a dot parses everywhere`() {
        assertEquals(0.5, parseDecimal("0,5", Locale.ITALY)!!, 0.0)
        assertEquals(0.5, parseDecimal("0.5", Locale.ITALY)!!, 0.0)
        assertEquals(0.5, parseDecimal("0.5", Locale.US)!!, 0.0)
        assertEquals(1.5, parseDecimal(" 1,5 ", Locale.GERMANY)!!, 0.0)
    }

    @Test
    fun `partial numbers and empty input are rejected, not truncated`() {
        // NumberFormat.parse on its own would read "1a" as 1.
        assertNull(parseDecimal("1a", Locale.ITALY))
        assertNull(parseDecimal("", Locale.ITALY))
        assertNull(parseDecimal("abc", Locale.US))
    }

    @Test
    fun `format and parse round-trip in both locales`() {
        for (locale in listOf(Locale.US, Locale.ITALY)) {
            for (v in listOf(0.25, 1.0, 2.5, 75.0)) {
                assertEquals(locale.toString(), v, parseDecimal(formatDecimalLocalized(v, locale), locale)!!, 0.0)
            }
        }
    }
}
