package com.hazia.emivita.core.data.export

import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The emergency card's content (spec section 7 R48). Pure JVM: [buildPdf] is a
 * thin layout pass over exactly these lines, so asserting the lines asserts
 * the document without a PDF parser or an Android canvas.
 */
class EmergencyCardExporterTest {

    private val today = LocalDate.of(2026, 9, 5)

    private fun profile(
        name: String = "Ada Lovelace",
        bloodType: String? = null,
        allergies: String? = null,
        doctorName: String? = null,
        doctorPhone: String? = null,
        doctorAddress: String? = null,
        pharmacyName: String? = null,
        pharmacyPhone: String? = null,
    ) = Profile(
        id = 1L,
        name = name,
        bloodType = bloodType,
        allergies = allergies,
        doctorName = doctorName,
        doctorPhone = doctorPhone,
        doctorAddress = doctorAddress,
        pharmacyName = pharmacyName,
        pharmacyPhone = pharmacyPhone,
        createdAtEpochMillis = 0L,
    )

    private fun medicine(name: String, doseUnit: String? = null, indication: String? = null) = Medicine(
        id = 0L,
        profileId = 1L,
        name = name,
        iconKey = "pill",
        doseUnit = doseUnit,
        indication = indication,
        sortOrder = 0.0,
        createdAtEpochMillis = 0L,
    )

    private fun lines(p: Profile, meds: List<Medicine> = emptyList()) =
        EmergencyCardExporter.buildLines(p, meds, today)

    @Test
    fun theNameIsPrintedUnderTheTitle() {
        val out = lines(profile())
        assertEquals("Emergency medical card", out[0])
        assertEquals("Ada Lovelace", out[1])
        // Built with the same formatter rather than a hard-coded "5 Sep 2026":
        // the month abbreviation is locale-dependent (en-GB's CLDR data gives
        // "Sept", not "Sep"), and the contract is that the card carries the
        // preparation date in the reader's own locale, not one spelling of it.
        assertTrue(
            "line was: ${out[2]}",
            out[2].contains(today.format(DateTimeFormatter.ofPattern("d MMM yyyy"))),
        )
        assertTrue(out[2].endsWith("Emivita"))
    }

    /**
     * Blood type and allergies print even when empty, unlike every other
     * section. A missing allergy line is ambiguous between "none" and "nobody
     * filled this in", and those two differ a great deal to a clinician.
     */
    @Test
    fun bloodTypeAndAllergiesSayWhenTheyWereNotRecorded() {
        val out = lines(profile())
        assertTrue(out.contains("Blood type: Not recorded"))
        assertTrue(out.contains("Allergies: Not recorded"))
    }

    @Test
    fun bloodTypeAndAllergiesPrintWhatWasEntered() {
        val out = lines(profile(bloodType = "O+", allergies = "Penicillin, latex"))
        assertTrue(out.contains("Blood type: O+"))
        assertTrue(out.contains("Allergies: Penicillin, latex"))
    }

    /** An empty "Doctor" heading on a card handed to a doctor is worse than
     *  no heading at all. */
    @Test
    fun contactBlocksAreOmittedEntirelyWhenEmpty() {
        val out = lines(profile())
        assertTrue(out.none { it == "Doctor" })
        assertTrue(out.none { it == "Pharmacy" })
    }

    @Test
    fun aPartlyFilledContactBlockPrintsOnlyWhatIsThere() {
        val out = lines(profile(doctorName = "Dr Somerville", doctorPhone = "01865 000000"))
        assertTrue(out.contains("Doctor"))
        assertTrue(out.contains("  Dr Somerville"))
        assertTrue(out.contains("  01865 000000"))
        // No address was entered, so the block is exactly two lines long and
        // no blank stands in for the missing one.
        assertEquals(1, out.count { it.startsWith("  01865") })
        val doctorAt = out.indexOf("Doctor")
        assertEquals(listOf("  Dr Somerville", "  01865 000000"), out.subList(doctorAt + 1, doctorAt + 3))
    }

    @Test
    fun medicinesAreListedWithFormAndReason() {
        val out = lines(
            profile(),
            listOf(medicine("Sertraline", doseUnit = "tablets", indication = "depression")),
        )
        assertTrue(out.contains("Current medicines (1)"))
        // En dash, not hyphen, since §9 #19 moved the card text into resources and
        // lint's TypographyDashes applies there. CardLabels.ENGLISH matches.
        assertTrue(out.contains("  Sertraline (tablets) – for depression"))
    }

    /**
     * Never a dose amount. A dose lives on a reminder, not on a medicine, so a
     * card printing one would be asserting a regimen this exporter has not
     * read.
     */
    @Test
    fun noDoseAmountIsEverPrinted() {
        val out = lines(profile(), listOf(medicine("Sertraline", doseUnit = "tablets")))
        assertTrue(out.none { it.contains("1 tablets") })
    }

    @Test
    fun anEmptyMedicineListSaysSo() {
        val out = lines(profile())
        assertTrue(out.contains("Current medicines (0)"))
        assertTrue(out.contains("  None recorded"))
    }

    /** One page by design -- a second sheet is a sheet that gets separated
     *  from the first. */
    @Test
    fun aLongMedicineListTruncatesWithACount() {
        val many = (1..30).map { medicine("Medicine $it") }
        val out = lines(profile(), many)
        assertTrue(out.contains("Current medicines (30)"))
        assertEquals(EmergencyCardExporter.MAX_MEDICINES, out.count { it.startsWith("  Medicine ") })
        assertTrue(out.any { it.contains("and 8 more") })
    }

    @Test
    fun blankStringsAreTreatedAsAbsentNotAsContent() {
        val out = lines(profile(bloodType = "   ", doctorName = "  ", doctorPhone = "  "))
        assertTrue(out.contains("Blood type: Not recorded"))
        assertTrue(out.none { it == "Doctor" })
    }
}
