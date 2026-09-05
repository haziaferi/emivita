package com.hazia.emivita.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §3's colour-picker hint, §7 R47. Pure JVM -- no Android runtime. */
class ColorDistinguishabilityTest {

    /**
     * Deliberately not [parseHexColor]. That goes through
     * android.graphics.Color.parseColor, and this module sets
     * `unitTests.isReturnDefaultValues = true`, so on the JVM it returns 0
     * silently rather than throwing -- every hex string here would have
     * become the same transparent black and half these assertions would have
     * passed by comparing it to itself.
     */
    private fun hex(s: String): Color {
        val v = s.removePrefix("#").toLong(16)
        return Color(
            red = ((v shr 16) and 0xFF) / 255f,
            green = ((v shr 8) and 0xFF) / 255f,
            blue = (v and 0xFF) / 255f,
        )
    }

    @Test
    fun identicalColorsAreZeroApart() {
        assertEquals(0.0, colorDeltaE(hex("#B9722E"), hex("#B9722E")), 0.0001)
    }

    /**
     * The case that makes ΔE the right metric and contrast ratio the wrong
     * one: these two sit at nearly the same relative luminance, so their
     * WCAG contrast ratio is about 1:1, and nobody has ever confused them.
     */
    @Test
    fun redAndGreenAtEqualLuminanceAreFarApart() {
        assertTrue(colorDeltaE(hex("#FF0000"), hex("#00B800")) > COLOR_CONFLICT_DELTA_E)
    }

    /** And the converse: two greys a contrast check would let through. */
    @Test
    fun twoNearbyGreysAreClose() {
        assertTrue(colorDeltaE(hex("#808080"), hex("#888888")) < COLOR_CONFLICT_DELTA_E)
    }

    @Test
    fun aDistinctPaletteProducesNoConflict() {
        val inUse = listOf(
            "Sertraline" to hex("#B9722E"),
            "Metformin" to hex("#2E6FB9"),
            "Vitamin D" to hex("#3EA05A"),
        )
        assertNull(nearestColorConflict(hex("#8E3EA0"), inUse))
    }

    @Test
    fun aNearDuplicateNamesTheMedicineItClashesWith() {
        val inUse = listOf(
            "Sertraline" to hex("#B9722E"),
            "Metformin" to hex("#2E6FB9"),
        )
        val conflict = nearestColorConflict(hex("#BB7530"), inUse)
        assertNotNull(conflict)
        assertEquals("Sertraline", conflict!!.otherName)
    }

    /** Naming one medicine is a hint; naming four is a scolding. */
    @Test
    fun theNearestConflictWinsWhenSeveralAreClose() {
        val inUse = listOf(
            "Further" to hex("#B9722E"),
            "Nearest" to hex("#B8712D"),
        )
        assertEquals("Nearest", nearestColorConflict(hex("#B8712E"), inUse)?.otherName)
    }

    @Test
    fun anEmptyPaletteNeverConflicts() {
        assertNull(nearestColorConflict(hex("#B9722E"), emptyList()))
    }
}
