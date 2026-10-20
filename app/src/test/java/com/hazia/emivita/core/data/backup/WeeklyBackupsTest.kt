package com.hazia.emivita.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** §7 R101: a dated copy within seven days of the last, the four newest kept. */
class WeeklyBackupsTest {

    private val zone = ZoneId.of("Europe/Rome")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `due when none was written, seven days have passed, or the clock went back`() {
        val last = at(2026, 10, 1)
        assertTrue(WeeklyBackups.isDue(null, last))
        assertFalse(WeeklyBackups.isDue(last, last + 7 * day - 1))
        assertTrue(WeeklyBackups.isDue(last, last + 7 * day))
        assertTrue(WeeklyBackups.isDue(last, last - day))
    }

    @Test
    fun `the next alarm is seven days after the last copy, never in the past`() {
        val last = at(2026, 10, 1)
        assertEquals(last + 7 * day, WeeklyBackups.nextDue(last, last + day))
        assertEquals(last + 30 * day, WeeklyBackups.nextDue(last, last + 30 * day))
        assertEquals(last, WeeklyBackups.nextDue(null, last))
    }

    @Test
    fun `the file is named for the local date`() {
        assertEquals("emivita_weekly_2026-10-20.zip", WeeklyBackups.fileName(at(2026, 10, 20, 0), zone))
        assertEquals("emivita_weekly_2026-10-20.zip", WeeklyBackups.fileName(at(2026, 10, 20, 23), zone))
    }

    @Test
    fun `only the oldest weekly copies beyond four are removed, nothing else in the folder`() {
        val names = listOf(
            "emivita_weekly_2026-09-01.zip", "emivita_weekly_2026-10-13.zip", "emivita_weekly_2026-09-08.zip",
            "emivita_weekly_2026-09-29.zip", "emivita_weekly_2026-10-06.zip", "emivita_weekly_2026-09-22.zip",
            AUTO_SYNC_FILE_NAME, "emivita_weekly_2026-10-20.zip.new", "emivita_weekly_notes.zip", "holiday.jpg",
        )
        assertEquals(
            listOf("emivita_weekly_2026-09-08.zip", "emivita_weekly_2026-09-01.zip"),
            WeeklyBackups.toPrune(names + "emivita_weekly_2026-09-15.zip").takeLast(2),
        )
        assertEquals(3, WeeklyBackups.toPrune(names + "emivita_weekly_2026-09-15.zip").size)
        assertEquals(emptyList<String>(), WeeklyBackups.toPrune(names.take(4)))
    }
}
