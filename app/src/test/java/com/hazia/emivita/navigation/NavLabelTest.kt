package com.hazia.emivita.navigation

import com.hazia.emivita.R
import com.hazia.emivita.ui.form.FormProbe
import com.hazia.emivita.ui.form.FormScreenTestBase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The navigation labels stay whole on one line at every font size on the narrowest phone (§7 R129,
 * the owner: "Cap their size"): at 2.0 on the 6T "Impostazioni" broke mid-word.
 */
open class NavLabelTest : FormScreenTestBase() {

    @Test fun labels_are_whole_on_one_line() {
        fontScale.floatValue = res.configuration.fontScale
        hostApp()
        val names = listOf(R.string.nav_today, R.string.nav_medicines, R.string.nav_history, R.string.nav_settings).map { s(it) }
        val leaves = FormProbe.frame().leaves.filter { it.text in names }
        println("NAV-LABELS fs${res.configuration.fontScale}: " + leaves.map { "${it.text} lines=${it.lines} cut=${it.cut} ${it.bounds.width / dp} dp" })
        assertTrue("every label drawn: ${leaves.map { it.text }}", leaves.map { it.text }.toSet() == names.toSet())
        leaves.forEach { assertTrue("'${it.text}' runs ${it.lines} lines or is cut", it.lines == 1 && !it.cut) }
    }
}

@Config(fontScale = 2f)
class NavLabelFs2Test : NavLabelTest()
