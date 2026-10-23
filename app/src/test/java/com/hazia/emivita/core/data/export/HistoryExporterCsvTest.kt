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
        val csv = HistoryExporter.buildCsv(listOf(row("2026-09-12T23:30", "2026-09-13T00:10", DoseStatus.TAKEN)), zone)
        assertEquals(
            "Date,Time,Recorded,Medicine,Status,Dose,Measurement,Notes\n2026-09-12,23:30,2026-09-13 00:10,Sertraline,Taken,50,mg,\n",
            csv,
        )
    }

    @Test
    fun `an unanswered dose has a scheduled slot and an empty Recorded column`() {
        // §7 R121: the CSV writes the status the screen shows, in English,
        // "Due" whatever the dose's age; missed is a figure, not a row status.
        val csv = HistoryExporter.buildCsv(listOf(row("2026-09-12T08:00", null, DoseStatus.PENDING)), zone)
        assertEquals("2026-09-12,08:00,,Sertraline,Due,50,mg,", csv.lines()[1])
    }

    @Test
    fun `a skipped dose keeps Skipped in its row`() {
        val csv = HistoryExporter.buildCsv(listOf(row("2026-09-12T08:00", "2026-09-12T08:05", DoseStatus.SKIPPED)), zone)
        assertEquals("2026-09-12,08:00,2026-09-12 08:05,Sertraline,Skipped,50,mg,", csv.lines()[1])
    }

    @Test
    fun `a note with a comma is quoted in the last column`() {
        val row = row("2026-09-12T08:00", "2026-09-12T08:05", DoseStatus.TAKEN)
        val noted = row.copy(doseEvent = row.doseEvent.copy(notes = "dizzy, mild"))
        assertEquals("2026-09-12,08:00,2026-09-12 08:05,Sertraline,Taken,50,mg,\"dizzy, mild\"", HistoryExporter.buildCsv(listOf(noted), zone).lines()[1])
    }
}
