package com.hazia.emivita.feature.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The PDF report's subtitle names the range that was exported (§7 R113): it
 * said "last 30 days" whatever the Export dialog's range, over rows that were
 * 90 days, this year or everything. The range's own label from that dialog
 * is what names it. Its figures follow §7 R121: taken ÷ (taken + missed),
 * where missed is skipped or unanswered after its day's midnight.
 */
@RunWith(RobolectricTestRunner::class)
class ExportSubtitleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A fixed zone and clock: every slot is on 14 September, read on the 15th. */
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private fun at(iso: String): Long = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-09-15T09:00")

    private fun row(status: DoseStatus, scheduled: String = "2026-09-14T08:00", reminderId: Long? = 1L) = DoseEventWithMedicine(
        DoseEvent(reminderId = reminderId, medicineId = 1L, scheduledEpochMillis = at(scheduled), doseAmount = 1.0, status = status, createdAtEpochMillis = 0L),
        medicine = null,
    )

    private val rows = listOf(row(DoseStatus.TAKEN), row(DoseStatus.TAKEN), row(DoseStatus.TAKEN), row(DoseStatus.SKIPPED))

    @Test
    fun `the 30 and 90 day ranges say their days`() {
        assertEquals("75% of doses taken — 3 taken, 1 missed, last 30 days", adherenceSubtitle(context, rows, ExportRange.LAST_30_DAYS, now, zone))
        assertEquals("75% of doses taken — 3 taken, 1 missed, last 90 days", adherenceSubtitle(context, rows, ExportRange.LAST_90_DAYS, now, zone))
    }

    @Test
    fun `this year and everything say their range label`() {
        assertEquals("75% of doses taken — 3 taken, 1 missed — This year", adherenceSubtitle(context, rows, ExportRange.THIS_YEAR, now, zone))
        assertEquals("75% of doses taken — 3 taken, 1 missed — Everything", adherenceSubtitle(context, rows, ExportRange.ALL, now, zone))
    }

    @Test
    fun `skipped counts as missed`() {
        // "For figures, skipped counts as missed" (§7 R121): one taken, one skipped is 50%.
        assertEquals("50% of doses taken — 1 taken, 1 missed, last 30 days", adherenceSubtitle(context, listOf(row(DoseStatus.TAKEN), row(DoseStatus.SKIPPED)), ExportRange.LAST_30_DAYS, now, zone))
    }

    @Test
    fun `an unanswered dose is in no figure before its day's midnight, and missed from it`() {
        val pending = row(DoseStatus.PENDING, "2026-09-15T08:00")
        val day = listOf(row(DoseStatus.TAKEN, "2026-09-15T07:00"), pending)
        assertEquals("before midnight", "100% of doses taken — 1 taken, 0 missed, last 30 days", adherenceSubtitle(context, day, ExportRange.LAST_30_DAYS, at("2026-09-15T23:59"), zone))
        assertEquals("at midnight", "50% of doses taken — 1 taken, 1 missed, last 30 days", adherenceSubtitle(context, day, ExportRange.LAST_30_DAYS, at("2026-09-16T00:00"), zone))
    }

    @Test
    fun `on a clock-change day the midnight is the local one`() {
        // 25 October 2026 is 25 hours long in Rome; a dose at 23:30 that day is
        // missed at the local midnight that ends it, not 24 hours after 00:00.
        val pending = listOf(row(DoseStatus.PENDING, "2026-10-25T23:30"))
        val localMidnight = at("2026-10-26T00:00")
        assertEquals("Last 30 days", adherenceSubtitle(context, pending, ExportRange.LAST_30_DAYS, localMidnight - 1, zone))
        assertEquals("0% of doses taken — 0 taken, 1 missed, last 30 days", adherenceSubtitle(context, pending, ExportRange.LAST_30_DAYS, localMidnight, zone))
    }

    @Test
    fun `a one-off or as-needed dose is in no figure`() {
        // Not planned (§7 R111, R118): taken or left unanswered for days, never counted.
        val asNeeded = row(DoseStatus.TAKEN, reminderId = null)
        val asNeededPending = row(DoseStatus.PENDING, "2026-09-01T08:00", reminderId = null)
        val oneOff = row(DoseStatus.TAKEN).let { it.copy(doseEvent = it.doseEvent.copy(reminderId = null, medicineId = null, customName = "Paracetamol", customProfileId = 1L, planned = false)) }
        assertEquals("Last 30 days", adherenceSubtitle(context, listOf(asNeeded, asNeededPending, oneOff), ExportRange.LAST_30_DAYS, now, zone))
    }

    @Test
    @Config(qualifiers = "it")
    fun `in Italian the counts show taken and missed only`() {
        assertEquals("75% delle dosi prese – 3 prese, 1 mancate, ultimi 30 giorni", adherenceSubtitle(context, rows, ExportRange.LAST_30_DAYS, now, zone))
    }

    @Test
    fun `with nothing counted the subtitle is the range alone`() {
        assertEquals("Last 90 days", adherenceSubtitle(context, emptyList(), ExportRange.LAST_90_DAYS, now, zone))
        assertEquals("This year", adherenceSubtitle(context, emptyList(), ExportRange.THIS_YEAR, now, zone))
    }
}
