package com.hazia.emivita.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.ui.form.FormProbe
import com.hazia.emivita.ui.form.FormScreenTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Alarms & snooze and the archived medicines at the phone's font size on the narrowest phone (§7 R130,
 * found on the 6T at font 2.0). The grouped rows' titles and the switch help wrap rather than end in an
 * ellipsis (the owner: "wrap at large fonts"); every choice row is one node of at least 48 dp with its
 * radio clear of the label; an archived medicine's name has its own line, so its actions cannot crush it.
 */
open class AlarmsSheetLayoutTest : FormScreenTestBase() {

    private fun cutTexts() = FormProbe.frame().leaves.filter { it.isText && it.visible && it.cut }.map { it.label }

    private fun openAlarms() {
        fontScale.floatValue = res.configuration.fontScale
        hostApp()
        tap(hasText(s(R.string.nav_settings)) and hasClickAction())
        waitFor("Settings") { inTop(hasText(s(R.string.settings_row_alarms)) and hasClickAction()) }
        tap(hasText(s(R.string.settings_row_alarms)) and hasClickAction())
        waitFor("Alarms & snooze") { windows() > 1 && inTop(button(s(R.string.common_save))) && inTop(hasText(s(R.string.alarms_group_snooze))) }
    }

    @Test fun no_text_is_cut_on_the_sheet_or_its_groups() {
        openAlarms()
        val found = mutableMapOf("main" to cutTexts())
        for ((title, tag) in listOf(R.string.alarms_group_snooze to "snooze", R.string.settings_swipe_title to "swipe", R.string.settings_alarm_tier_title to "tier")) {
            tap(hasText(s(title)) and hasClickAction())
            waitFor("the $tag sheet") { windows() > 2 }
            found[tag] = cutTexts()
            tap(button(s(R.string.common_cancel)))
            waitFor("back on the main sheet") { windows() == 2 }
        }
        println("ALARMS-CUT fs${fontScale.floatValue}: $found")
        assertTrue("cut: $found", found.values.all { it.isEmpty() })
    }

    @Test fun choice_rows_are_one_node_of_48_dp_with_the_radio_clear_of_its_label() {
        openAlarms()
        tap(hasText(s(R.string.settings_swipe_title)) and hasClickAction())
        waitFor("the swipe sheet") { windows() > 2 }
        val fr = FormProbe.frame()
        val rows = fr.merged.filter { it.role == Role.RadioButton && it.visible }
        println("ALARMS-RADIO fs${fontScale.floatValue}: " + rows.map { "${it.label} ${it.bounds.height / dp} dp" })
        assertTrue("no choice rows found", rows.size >= 3)
        rows.forEach { r ->
            assertTrue("'${r.label}' is ${r.bounds.height / dp} dp", r.bounds.height / dp >= 47.5f)
            val label = fr.leaves.filter { it.isText && r.id in it.ancestors }.minOf { it.bounds.left }
            assertTrue("'${r.label}': label starts ${(label - r.bounds.left) / dp} dp in", (label - r.bounds.left) / dp >= 28f)
        }
    }

    @Test fun an_archived_name_keeps_its_own_line() {
        fontScale.floatValue = res.configuration.fontScale
        val a = h.medicine("WalkR130 archiviato", stock = 30.0, unit = "compresse")
        runBlocking { h.container.medicineRepository.archive(h.medicineRow(a)) }
        host { Column(Modifier.padding(16.dp)) { ArchivedMedicinesSection(h.profileId, h.container.medicineRepository) } }
        waitFor("the archived row") { inTop(hasText("WalkR130 archiviato")) }
        val fr = FormProbe.frame()
        val name = fr.leaves.first { it.text == "WalkR130 archiviato" }
        val actions = fr.leaves.filter { it.text == s(R.string.data_archived_restore) || it.text == s(R.string.data_archived_delete) }
        println("ARCHIVED fs${fontScale.floatValue}: name ${name.bounds.width / dp} dp, ${name.lines} lines; actions " + actions.map { "${it.text} cut=${it.cut}" })
        assertTrue("the name is ${name.lines} lines or cut", name.lines <= 2 && !name.cut)
        assertTrue("an action sits beside the name", actions.all { it.bounds.top >= name.bounds.bottom - 1f && !it.cut })
    }
}

@Config(fontScale = 2f)
class AlarmsSheetLayoutFs2Test : AlarmsSheetLayoutTest()
