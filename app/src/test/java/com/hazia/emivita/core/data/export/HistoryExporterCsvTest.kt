package com.hazia.emivita.core.data.export

import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.StockInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The CSV row's timestamps (spec §7 R60 E F8): the scheduled slot and the
 *  recorded answer are two facts, so they are two columns. */
class HistoryExporterCsvTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private fun at(iso: String): Long = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    /** A fixed clock (§7 R119): a day after every slot here, so a pending one is missed. */
    private val now = at("2026-09-14T08:00")

    private val medicine = Medicine(
        id = 1L, profileId = 1L, name = "Sertraline", iconKey = "pill", doseUnit = "mg",
        stockInfo = StockInfo(), sortOrder = 0.0, createdAtEpochMillis = 0L,
    )

    private fun row(scheduled: String, recorded: String?, status: DoseStatus) = DoseEventWithMedicine(
        doseEvent = DoseEvent(
            id = 5L, reminderId = 2L, medicineId = 1L,
            scheduledEpochMillis = at(scheduled), doseAmount = 50.0, status = status,
            resolvedAtEpochMillis = recorded?.let { at(it) }, createdAtEpochMillis = 0L,
        ),
        medicine = medicine,
    )

    @Test
    fun `a dose scheduled before midnight and taken after it keeps both timestamps whole`() {
        // The old row paired the scheduled DATE with the resolved TIME:
        // "2026-09-12,00:10" -- a moment that never happened.
        val csv = HistoryExporter.buildCsv(listOf(row("2026-09-12T23:30", "2026-09-13T00:10", DoseStatus.TAKEN)), zone, now)
        assertEquals(
            "Date,Time,Recorded,Medicine,Status,Dose,Measurement,Notes\n2026-09-12,23:30,2026-09-13 00:10,Sertraline,Taken,50,mg,\n",
            csv,
        )
    }

    @Test
    fun `an unanswered dose has a scheduled slot and an empty Recorded column`() {
        val csv = HistoryExporter.buildCsv(listOf(row("2026-09-12T08:00", null, DoseStatus.PENDING)), zone, now)
        assertEquals("2026-09-12,08:00,,Sertraline,Missed,50,mg,", csv.lines()[1])
    }

    @Test
    fun `a dose still within its repeats is Due, and Missed after the last one`() {
        // §7 R119: the CSV writes the status the screen shows, in English.
        val pending = row("2026-09-14T07:50", null, DoseStatus.PENDING)
        val due = pending.copy(doseEvent = pending.doseEvent.copy(missedAfterEpochMillis = at("2026-09-14T08:10")))
        assertEquals("2026-09-14,07:50,,Sertraline,Due,50,mg,", HistoryExporter.buildCsv(listOf(due), zone, now).lines()[1])
        assertEquals("2026-09-14,07:50,,Sertraline,Missed,50,mg,", HistoryExporter.buildCsv(listOf(due), zone, at("2026-09-14T08:10")).lines()[1])
    }

    @Test
    fun `a note with a comma is quoted in the last column`() {
        val row = row("2026-09-12T08:00", "2026-09-12T08:05", DoseStatus.TAKEN)
        val noted = row.copy(doseEvent = row.doseEvent.copy(notes = "dizzy, mild"))
        assertEquals("2026-09-12,08:00,2026-09-12 08:05,Sertraline,Taken,50,mg,\"dizzy, mild\"", HistoryExporter.buildCsv(listOf(noted), zone, now).lines()[1])
    }
}
