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

/**
 * The PDF report's subtitle names the range that was exported (§7 R113): it
 * said "last 30 days" whatever the Export dialog's range, over rows that were
 * 90 days, this year or everything. The range's own label from that dialog
 * is what names it.
 */
@RunWith(RobolectricTestRunner::class)
class ExportSubtitleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        /** A fixed clock (§7 R119), after every slot here. */
        const val NOW = 1_000_000L
    }

    private fun row(status: DoseStatus) = DoseEventWithMedicine(
        DoseEvent(reminderId = 1L, medicineId = 1L, scheduledEpochMillis = 0L, doseAmount = 1.0, status = status, createdAtEpochMillis = 0L),
        medicine = null,
    )

    private val rows = listOf(row(DoseStatus.TAKEN), row(DoseStatus.TAKEN), row(DoseStatus.TAKEN), row(DoseStatus.SKIPPED))

    @Test
    fun `the 30 and 90 day ranges say their days`() {
        assertEquals("75% of answered doses taken — 3 taken, 1 skipped, 0 missed, last 30 days", adherenceSubtitle(context, rows, ExportRange.LAST_30_DAYS, NOW))
        assertEquals("75% of answered doses taken — 3 taken, 1 skipped, 0 missed, last 90 days", adherenceSubtitle(context, rows, ExportRange.LAST_90_DAYS, NOW))
    }

    @Test
    fun `this year and everything say their range label`() {
        assertEquals("75% of answered doses taken — 3 taken, 1 skipped, 0 missed — This year", adherenceSubtitle(context, rows, ExportRange.THIS_YEAR, NOW))
        assertEquals("75% of answered doses taken — 3 taken, 1 skipped, 0 missed — Everything", adherenceSubtitle(context, rows, ExportRange.ALL, NOW))
    }

    @Test
    fun `a dose still due counts neither taken nor missed until its last repeat`() {
        // §7 R119: due until NOW + 1, then missed.
        val due = row(DoseStatus.PENDING).let { it.copy(doseEvent = it.doseEvent.copy(missedAfterEpochMillis = NOW + 1)) }
        assertEquals("75% of answered doses taken — 3 taken, 1 skipped, 0 missed, last 30 days", adherenceSubtitle(context, rows + due, ExportRange.LAST_30_DAYS, NOW))
        assertEquals("75% of answered doses taken — 3 taken, 1 skipped, 1 missed, last 30 days", adherenceSubtitle(context, rows + due, ExportRange.LAST_30_DAYS, NOW + 1))
    }

    @Test
    fun `with nothing answered the subtitle is the range alone`() {
        assertEquals("Last 90 days", adherenceSubtitle(context, emptyList(), ExportRange.LAST_90_DAYS, NOW))
        assertEquals("This year", adherenceSubtitle(context, emptyList(), ExportRange.THIS_YEAR, NOW))
    }
}
