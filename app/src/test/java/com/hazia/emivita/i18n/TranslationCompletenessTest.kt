package com.hazia.emivita.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage C's gate (spec §9 #19, #28): a translation ships complete or not at
 * all. For every `values-xx` folder: the same string and plurals names as
 * English both ways (a missing key would fall back to English on the phone,
 * an extra one is dead); the same set of format arguments per string (a
 * dropped `%2$s` is a crash at runtime, an added one a garbage value); every
 * plurals with `one` and `other`; and no value byte-identical to its English
 * one outside the allow-list below -- the allow-list is the record of the
 * deliberate identities, so an untranslated string cannot pass as translated.
 * Plain text parsing on purpose: no Android, no resource compiler, one file
 * against another.
 */
class TranslationCompletenessTest {
    private val res = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/res") }.first { it.isDirectory }

    /** Values that are the same word in Italian, or are not words. */
    private val identical = setOf(
        "app_name", "common_ok", "unit_ml", "field_label_value", "widget_stock_row", "today_progress_line",
        "a11y_calendar_day", "a11y_count_status", "history_entry_summary", "history_entry_summary_no_amount", "today_entry_summary", "reminder_label_at",
        "backup_sync_password", "tier_standard", "snooze_picker_minutes", "history_table_dose",
        "pdf_col_dose", "dose_default_unit.one",
        // Format-only: two placeholders and a middle dot, nothing to translate.
        "appearance_theme_summary", "today_layout_timeline",
    )

    private data class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun parse(file: File): Res {
        val text = file.readText()
        val strings = Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        val plurals = Regex("<plurals name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).findAll(text).associate { m ->
            m.groupValues[1] to Regex("<item quantity=\"(\\w+)\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        }
        return Res(strings, plurals)
    }

    /** Every Formatter conversion, not only `%s`/`%d`: `%%`, `%.1f` and `%,d` are arguments too (§7 R81). */
    private fun args(value: String): List<String> = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXeEgGcboaAhHn%]").findAll(value).map { it.value }.sorted().toList()

    private fun translations(): List<Pair<String, File>> = res.listFiles()!!
        .filter { it.name.startsWith("values-") && !it.name.startsWith("values-night") }
        .map { it.name.removePrefix("values-") to File(it, "strings.xml") }

    @Test
    fun `there is at least one translation, and each is complete both ways`() {
        val en = parse(File(res, "values/strings.xml"))
        val all = translations()
        assertTrue("Stage C ships a translation", all.isNotEmpty())
        for ((lang, file) in all) {
            val tr = parse(file)
            assertEquals("$lang: string names", en.strings.keys.sorted(), tr.strings.keys.sorted())
            assertEquals("$lang: plurals names", en.plurals.keys.sorted(), tr.plurals.keys.sorted())
        }
    }

    @Test
    fun `every translated string keeps exactly the English format arguments`() {
        val en = parse(File(res, "values/strings.xml"))
        for ((lang, file) in translations()) {
            val tr = parse(file)
            for ((k, v) in en.strings) assertEquals("$lang: $k", args(v), args(tr.strings.getValue(k)))
            for ((name, items) in en.plurals) {
                val enUnion = items.values.flatMap { args(it) }.toSet()
                val trUnion = tr.plurals.getValue(name).values.flatMap { args(it) }.toSet()
                assertEquals("$lang: plurals $name", enUnion, trUnion)
                assertTrue("$lang: plurals $name has one and other", tr.plurals.getValue(name).keys.containsAll(setOf("one", "other")))
            }
        }
    }

    @Test
    fun `nothing is left in English except the listed identities`() {
        val en = parse(File(res, "values/strings.xml"))
        for ((lang, file) in translations()) {
            val tr = parse(file)
            val same = en.strings.filter { (k, v) -> tr.strings[k] == v && k !in identical }.keys +
                en.plurals.flatMap { (name, items) -> items.filter { (q, v) -> tr.plurals[name]?.get(q) == v && "$name.$q" !in identical }.keys.map { "$name.$it" } }
            assertEquals("$lang: untranslated (add to `identical` only if it is the same word)", emptyList<String>(), same.sorted())
        }
    }

    /**
     * The allow-list is a record, so it must be exact both ways: an entry whose
     * value has since been translated is a false record (§7 R81 found three).
     */
    @Test
    fun `every listed identity is still identical in every translation`() {
        val en = parse(File(res, "values/strings.xml"))
        for ((lang, file) in translations()) {
            val tr = parse(file)
            val stale = identical.filter { key ->
                val (name, quantity) = key.split(".", limit = 2).let { it[0] to it.getOrNull(1) }
                val enValue = if (quantity == null) en.strings[name] else en.plurals[name]?.get(quantity)
                val trValue = if (quantity == null) tr.strings[name] else tr.plurals[name]?.get(quantity)
                enValue == null || enValue != trValue
            }
            assertEquals("$lang: listed as identical but translated or missing (remove from `identical`)", emptyList<String>(), stale.sorted())
        }
    }
}
