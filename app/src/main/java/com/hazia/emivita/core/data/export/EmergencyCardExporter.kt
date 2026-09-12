package com.hazia.emivita.core.data.export

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.content.res.Resources
import com.hazia.emivita.R

/**
 * The emergency/medical card (spec §9's parking lot, promoted in §7 R48).
 *
 * The parking-lot entry is explicit that this is deliberately NOT a
 * lock-screen widget: Android's own Emergency Information / Personal Safety
 * feature already does that better than any third-party app can, because it is
 * readable without unlocking the phone at all. What the OS feature cannot
 * produce is a document -- a page to hand a new doctor, a card to fold into a
 * wallet. That is the whole of this feature, and the reason it is an exporter
 * and not a screen with a big red button.
 *
 * Nothing here is clinical. The card prints what the user typed, in a fixed
 * order, and never checks a medicine against an allergy or a dose against
 * anything. §9's entry calls that out as the boundary ("informational text
 * only, never algorithmically checked against anything"), and a card that
 * silently checked would be making a claim this app is not entitled to make.
 *
 * One page, always. A single sheet is what a wallet and a consultation desk
 * both want, so the medicine list truncates with a count rather than flowing
 * onto a second page nobody will keep with the first -- see [MAX_MEDICINES].
 */
object EmergencyCardExporter {

    /**
     * Every fixed string the card prints, resolved by the caller (spec §9 #19).
     *
     * [buildLines] is deliberately pure -- no Context, no resources -- so the
     * whole document can be asserted line by line in a JVM test. Translating
     * it therefore cannot mean calling getString() inside; it means handing
     * the strings in. [ENGLISH] is what the test uses and what production
     * used to hardcode; [fromContext] is what production uses now. The two
     * must say the same thing in English, and `EmergencyCardExporterTest`
     * pins that on the ENGLISH side.
     */
    data class CardLabels(
        val title: String,
        val prepared: (String) -> String,
        val bloodType: (String) -> String,
        val allergies: (String) -> String,
        val notRecorded: String,
        val currentMedicines: (Int) -> String,
        val noneRecorded: String,
        val indication: (String) -> String,
        val andMore: (Int) -> String,
        val doctor: String,
        val pharmacy: String,
    ) {
        companion object {
            val ENGLISH = CardLabels(
                title = "Emergency medical card",
                prepared = { "Prepared $it – Emivita" },
                bloodType = { "Blood type: $it" },
                allergies = { "Allergies: $it" },
                notRecorded = "Not recorded",
                currentMedicines = { "Current medicines ($it)" },
                noneRecorded = "  None recorded",
                indication = { " – for $it" },
                andMore = { "  … and $it more – see the full dose history export" },
                doctor = "Doctor",
                pharmacy = "Pharmacy",
            )

            /** From [Resources] rather than a Context so a composable can hand
             *  in `LocalResources.current`, which tracks configuration changes. */
            fun from(resources: Resources) = CardLabels(
                title = resources.getString(R.string.card_title),
                prepared = { resources.getString(R.string.card_prepared, it) },
                bloodType = { resources.getString(R.string.card_blood_type, it) },
                allergies = { resources.getString(R.string.card_allergies, it) },
                notRecorded = resources.getString(R.string.card_not_recorded),
                currentMedicines = { resources.getString(R.string.card_current_medicines, it) },
                noneRecorded = resources.getString(R.string.card_none_recorded),
                indication = { resources.getString(R.string.card_indication, it) },
                andMore = { resources.getQuantityString(R.plurals.card_and_more, it, it) },
                doctor = resources.getString(R.string.card_doctor),
                pharmacy = resources.getString(R.string.card_pharmacy),
            )
        }
    }

    /** Fits one A4 page beside the contact sections at the sizes below, with
     *  room for the truncation line. A reader with more medicines than this
     *  is better served by the full dose-history export. */
    const val MAX_MEDICINES = 22

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

    /**
     * Rendered as text so the whole card can be asserted in a unit test
     * without a PDF parser or an Android canvas. [buildPdf] draws exactly
     * these lines, so a test of this function is a test of the document --
     * the split exists so the layout arithmetic is the only untested part.
     */
    fun buildLines(
        profile: Profile,
        medicines: List<Medicine>,
        today: LocalDate = LocalDate.now(),
        labels: CardLabels = CardLabels.ENGLISH,
    ): List<String> {
        val lines = mutableListOf<String>()
        lines += labels.title
        lines += profile.name
        lines += labels.prepared(today.format(DATE_FORMAT))
        lines += ""

        // Blood type and allergies lead, in that order: they are the two
        // things a clinician reads first and the two whose absence is itself
        // worth stating, so unlike every section below they print even when
        // empty. "Not recorded" is information; a missing line is ambiguous
        // between "none" and "nobody filled this in", and those differ a great
        // deal for an allergy.
        lines += labels.bloodType(profile.bloodType.orNotRecorded(labels))
        lines += labels.allergies(profile.allergies.orNotRecorded(labels))
        lines += ""

        lines += labels.currentMedicines(medicines.size)
        if (medicines.isEmpty()) {
            lines += labels.noneRecorded
        } else {
            medicines.take(MAX_MEDICINES).forEach { m ->
                // The measurement label only -- never a dose amount. A dose
                // lives on a reminder, not on the medicine, and a card that
                // printed one would be asserting a regimen this exporter has
                // not read. Name, form and reason are what a clinician needs
                // from a card; the amounts are in the dose-history export.
                val form = m.doseUnit?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                val indication = m.indication?.takeIf { it.isNotBlank() }?.let { labels.indication(it) } ?: ""
                lines += "  ${m.name}$form$indication"
            }
            val hidden = medicines.size - MAX_MEDICINES
            if (hidden > 0) lines += labels.andMore(hidden)
        }

        // Contact blocks, unlike the two above, are omitted entirely when
        // nothing was entered. An empty "Doctor" heading on a card handed to a
        // doctor is worse than no heading.
        section(labels.doctor, listOf(profile.doctorName, profile.doctorPhone, profile.doctorAddress))?.let {
            lines += ""
            lines += it
        }
        section(labels.pharmacy, listOf(profile.pharmacyName, profile.pharmacyPhone))?.let {
            lines += ""
            lines += it
        }
        return lines.flatMap { it.split("\n") }
    }

    private fun String?.orNotRecorded(labels: CardLabels): String =
        this?.takeIf { it.isNotBlank() } ?: labels.notRecorded

    private fun section(heading: String, fields: List<String?>): String? {
        val present = fields.mapNotNull { it?.takeIf { v -> v.isNotBlank() } }
        if (present.isEmpty()) return null
        return (listOf(heading) + present.map { "  $it" }).joinToString("\n")
    }

    fun buildPdf(
        profile: Profile,
        medicines: List<Medicine>,
        today: LocalDate = LocalDate.now(),
        labels: CardLabels = CardLabels.ENGLISH,
    ): PdfDocument {
        val pageWidth = 595 // A4 at 72dpi, same basis as HistoryExporter
        val pageHeight = 842
        val marginX = 40f

        val titlePaint = Paint().apply { textSize = 20f; isFakeBoldText = true }
        val namePaint = Paint().apply { textSize = 15f; isFakeBoldText = true }
        val metaPaint = Paint().apply { textSize = 10f; color = 0xFF666666.toInt() }
        val headingPaint = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 12f }

        val lines = buildLines(profile, medicines, today, labels = labels)
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        val canvas = page.canvas
        var y = 56f

        lines.forEachIndexed { index, line ->
            if (line.isBlank()) {
                y += 10f
                return@forEachIndexed
            }
            // Index-based rather than content-based: the first three lines are
            // the fixed header buildLines always emits in that order, so this
            // cannot be thrown off by a user whose name happens to match a
            // heading.
            val paint = when {
                index == 0 -> titlePaint
                index == 1 -> namePaint
                index == 2 -> metaPaint
                !line.startsWith("  ") -> headingPaint
                else -> bodyPaint
            }
            y += paint.textSize + 6f
            if (y > pageHeight - 40f) return@forEachIndexed // one page, by design
            canvas.drawText(line, marginX, y, paint)
        }

        document.finishPage(page)
        return document
    }

    fun writePdf(document: PdfDocument, outputStream: OutputStream) {
        document.writeTo(outputStream)
        document.close()
    }
}
