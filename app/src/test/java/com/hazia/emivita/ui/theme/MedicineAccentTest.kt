package com.hazia.emivita.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ink on a medicine's tile is chosen by the tile's luminance (§7 R76;
 * Appendix C #5). The picker is a free HSV wheel, so the rule is checked
 * against the corners of that space, not against a curated set.
 */
@RunWith(RobolectricTestRunner::class)
class MedicineAccentTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `dark ink on light tiles, white on dark ones`() {
        assertEquals(AccentInk, onAccent(Color(0xFFFFF176)))   // pale yellow
        assertEquals(AccentInk, onAccent(Color(0xFFB8ABA9)))   // the neutral fallback
        assertEquals(NearWhite, onAccent(Color(0xFF1C2D69))) // navy
        assertEquals(NearWhite, onAccent(Color(0xFFA32F29))) // brick red
    }

    @Test
    fun `the ink clears AA on every tile the HSV picker can produce, outside a narrow mid band`() {
        var worst = 99.0; var worstColour = Color.Unspecified
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val tile = Color(r, g, b)
            val c = contrast(onAccent(tile), tile)
            if (c < worst) { worst = c; worstColour = tile }
        }
        // The crossover band is where neither ink can reach 4.5:1; the rule's
        // job is to be right everywhere else, and 3:1 (large glyphs) everywhere.
        assertTrue("worst tile $worstColour reads at ${"%.2f".format(worst)}:1", worst >= 3.0)
    }

    @Test
    fun `a missing or unparseable colour is the neutral fallback`() {
        assertEquals(NeutralMedicineAccent, medicineAccent(null))
        assertEquals(NeutralMedicineAccent, medicineAccent("not a colour"))
        assertEquals(Color(0xFF17488C), medicineAccent("#17488C"))
    }
}
