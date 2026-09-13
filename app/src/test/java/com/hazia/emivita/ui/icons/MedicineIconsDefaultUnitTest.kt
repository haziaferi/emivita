package com.hazia.emivita.ui.icons

import com.hazia.emivita.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The icon is the dosage form (spec §9 #27; HM-05): it fills a blank unit
 *  and never overwrites a typed one. */
class MedicineIconsDefaultUnitTest {
    @Test
    fun `the counted forms carry a unit and the descriptive ones do not`() {
        assertEquals(R.string.unit_tablet, MedicineIcons.defaultUnitFor("tablet"))
        assertEquals(R.string.unit_puff, MedicineIcons.defaultUnitFor("inhaler"))
        assertNull(MedicineIcons.defaultUnitFor("cardiac"))
        assertNull(MedicineIcons.defaultUnitFor("no-such-key"))
    }

    @Test
    fun `a blank unit takes the form's, a typed unit is kept, a form without one changes nothing`() {
        assertEquals("tablet", MedicineIcons.unitAfterPickingForm("", "tablet"))
        assertEquals("tablet", MedicineIcons.unitAfterPickingForm("   ", "tablet"))
        assertEquals("caps", MedicineIcons.unitAfterPickingForm("caps", "tablet"))
        assertEquals("", MedicineIcons.unitAfterPickingForm("", null))
    }
}
