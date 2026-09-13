package com.hazia.emivita.core.data.export

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.format.formatDecimal
import com.hazia.emivita.core.format.formatDoseWithUnit
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.content.Context
import com.hazia.emivita.R
import com.hazia.emivita.core.format.csvStatusLabel
import com.hazia.emivita.core.format.doseStatusLabelRes

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")


/**
 * Human-readable dose-history reports for sharing with a doctor (spec §9
 * #10) -- distinct from #9's JSON backup, which is a disaster-recovery
 * snapshot, not something a person is meant to read. CSV needs no library;
 * PDF uses Android's native `android.graphics.pdf.PdfDocument` (zero new
 * dependency, ADR: diverges from medTimer's third-party `SimplyPDF` on
 * purpose -- see docs/SPEC.md §5's reference-app comparison table).
 *
 * Both entry points take rows already sorted how the report should read
 * (HistoryScreen's own reverse-chronological order) -- this class doesn't
 * re-sort, just formats.
 */
object HistoryExporter {
    fun buildCsv(rows: List<DoseEventWithMedicine>, zone: ZoneId = ZoneId.systemDefault()): String {
        val builder = StringBuilder()
        // Dose/Measurement kept as separate columns here (unlike the in-app
        // Table view, which folds the unit into the Dose cell) -- CSV is a
        // structured/spreadsheet format, where a plain numeric Dose column
        // stays usable for SUM()/sorting and Measurement carries the label.
        // Date and Time are the SCHEDULED slot; Recorded is when the dose was
        // actually answered, blank while it is still unanswered (§7 R60 E F8).
        // The old row paired the scheduled date with the resolved time, so a
        // 23:30 dose taken at 00:10 exported as a timestamp that never
        // happened. medTimer writes reminded and taken as two full columns.
        builder.append("Date,Time,Recorded,Medicine,Status,Dose,Measurement\n")
        for (row in rows) {
            val scheduled = Instant.ofEpochMilli(row.doseEvent.scheduledEpochMillis).atZone(zone)
            val recorded = row.doseEvent.resolvedAtEpochMillis
                ?.let { Instant.ofEpochMilli(it).atZone(zone).format(DATE_FORMAT) + " " + Instant.ofEpochMilli(it).atZone(zone).format(TIME_FORMAT) }
                ?: ""
            builder.append(scheduled.format(DATE_FORMAT)).append(',')
            builder.append(scheduled.format(TIME_FORMAT)).append(',')
            builder.append(recorded).append(',')
            builder.append(csvEscape(row.medicine.name)).append(',')
            builder.append(csvStatusLabel(row.doseEvent.status)).append(',')
            builder.append(formatDecimal(row.doseEvent.doseAmount)).append(',')
            builder.append(csvEscape(row.medicine.doseUnit ?: "")).append('\n')
        }
        return builder.toString()
    }

    private fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    /** [subtitle] carries the adherence summary line HistoryScreen already
     *  computes (e.g. "82% adherence -- 41 taken, 9 skipped, last 30 days")
     *  so the report doesn't recompute it independently and risk drifting
     *  from what the screen shows for the same window. */
    //  [context] is here only to resolve the title and column headers. This
    //  is a document handed to a person, so its headers are translated;
    //  [buildCsv]'s deliberately are not, because a CSV is data for a
    //  spreadsheet and a renamed column breaks whatever reads it (§9 #19).
    fun buildPdf(
        context: Context,
        rows: List<DoseEventWithMedicine>,
        subtitle: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PdfDocument {
        val pageWidth = 595 // A4 at 72dpi
        val pageHeight = 842
        val marginX = 40f
        var y = 0f
        val rowHeight = 20f
        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val subtitlePaint = Paint().apply { textSize = 11f; color = 0xFF666666.toInt() }
        val headerPaint = Paint().apply { textSize = 11f; isFakeBoldText = true }
        val rowPaint = Paint().apply { textSize = 11f }
        val colDate = marginX
        val colTime = marginX + 70f
        val colMedicine = marginX + 130f
        val colStatus = marginX + 330f
        val colDose = marginX + 430f

        val document = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: android.graphics.Canvas? = null

        fun startPage() {
            page?.let { document.finishPage(it) }
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page!!.canvas
            y = 50f
            if (pageNumber == 1) {
                canvas!!.drawText(context.getString(R.string.pdf_title), marginX, y, titlePaint)
                y += 22f
                canvas!!.drawText(subtitle, marginX, y, subtitlePaint)
                y += 24f
            }
            canvas!!.drawText(context.getString(R.string.pdf_col_date), colDate, y, headerPaint)
            canvas!!.drawText(context.getString(R.string.pdf_col_time), colTime, y, headerPaint)
            canvas!!.drawText(context.getString(R.string.pdf_col_medicine), colMedicine, y, headerPaint)
            canvas!!.drawText(context.getString(R.string.pdf_col_status), colStatus, y, headerPaint)
            canvas!!.drawText(context.getString(R.string.pdf_col_dose), colDose, y, headerPaint)
            y += rowHeight
        }

        startPage()
        for (row in rows) {
            if (y > pageHeight - 50f) startPage()
            val scheduled = Instant.ofEpochMilli(row.doseEvent.scheduledEpochMillis).atZone(zone)
            val resolvedEpoch = row.doseEvent.resolvedAtEpochMillis ?: row.doseEvent.scheduledEpochMillis
            val time = Instant.ofEpochMilli(resolvedEpoch).atZone(zone).format(TIME_FORMAT)
            canvas!!.drawText(scheduled.format(DATE_FORMAT), colDate, y, rowPaint)
            canvas!!.drawText(time, colTime, y, rowPaint)
            canvas!!.drawText(row.medicine.name.take(28), colMedicine, y, rowPaint)
            canvas!!.drawText(context.getString(doseStatusLabelRes(row.doseEvent.status)), colStatus, y, rowPaint)
            canvas!!.drawText(formatDoseWithUnit(row.doseEvent.doseAmount, row.medicine.doseUnit, context.resources), colDose, y, rowPaint)
            y += rowHeight
        }
        page?.let { document.finishPage(it) }
        return document
    }

    fun writePdf(document: PdfDocument, outputStream: OutputStream) {
        document.writeTo(outputStream)
        document.close()
    }
}
