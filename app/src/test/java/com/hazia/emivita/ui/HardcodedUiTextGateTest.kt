package com.hazia.emivita.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails the build if a user-visible string literal reappears in Kotlin
 * (spec §9 #19, Stage A's gate).
 *
 * Android lint's `HardcodedText` fires on XML layouts and never on Compose,
 * which is how 300-odd strings sat in this codebase behind a lint gate
 * configured with `warningsAsErrors = true`. This test is the check lint does
 * not make. It reads `app/src/main/java` the way [com.hazia.emivita.ui.theme.PaletteSpecConsistencyTest]
 * reads `docs/SPEC.md`: from the repository, on the JVM, no Robolectric.
 *
 * **What it detects, precisely.** A string literal handed directly to a
 * *sink* -- a call whose argument reaches the user's eyes: `Text("...")`,
 * `contentDescription = "..."`, a notification builder's title/text/action, a
 * `NotificationChannel` name or description, `Canvas.drawText`, the biometric
 * prompt, the app's own text-taking composables, `MedicineIconOption`'s label,
 * an enum entry's `("...")` display argument, and a `title`/`label`/
 * `saveLabel`/`helper`/`description` named argument. Sink-based on purpose: a
 * rule that matched "anything prose-shaped" would flag palette names, the
 * CSV's English status column and the card exporter's test-pinned English
 * defaults, and then need an allowlist -- and a gate with an allowlist is a
 * lint baseline wearing a different hat.
 *
 * **What it cannot see, stated rather than hidden.** A literal assigned to a
 * `val` and passed to a sink later, or returned from a plain `when` and then
 * displayed. Stage A part 2 found ~70 of those by reading. The complement is
 * `isPseudoLocalesEnabled` on the debug build: switch the phone to en-XA and
 * anything this test misses renders plain English amid accented text. The two
 * together are the check; neither alone is.
 *
 * Comments are blanked before matching (offsets preserved, so a reported line
 * number is real), so a sink quoted in a KDoc does not trip it.
 */
class HardcodedUiTextGateTest {

    private val literal = """"((?:[^"\\\n]|\\.)+)""""

    /** (label, pattern). Every pattern's group 1 is the offending literal. */
    private val sinks: List<Pair<String, Regex>> = listOf(
        "Text(" to Regex("""\bText\s*\(\s*(?:text\s*=\s*)?$literal"""),
        "contentDescription" to Regex("""\bcontentDescription\s*=\s*$literal"""),
        "setContentTitle" to Regex("""\.setContentTitle\s*\(\s*$literal"""),
        "setContentText" to Regex("""\.setContentText\s*\(\s*$literal"""),
        "setTicker" to Regex("""\.setTicker\s*\(\s*$literal"""),
        "addAction" to Regex("""\.addAction\s*\([^,()]+,\s*$literal"""),
        "NotificationChannel" to Regex("""\bNotificationChannel\s*\(\s*[A-Za-z_][\w.]*\s*,\s*$literal"""),
        "drawText" to Regex("""\.drawText\s*\(\s*$literal"""),
        "setTitle" to Regex("""\.setTitle\s*\(\s*$literal"""),
        "setSubtitle" to Regex("""\.setSubtitle\s*\(\s*$literal"""),
        "setNegativeButtonText" to Regex("""\.setNegativeButtonText\s*\(\s*$literal"""),
        "DetailHeader" to Regex("""\bDetailHeader\s*\(\s*$literal"""),
        "NotificationTierRow" to Regex("""\bNotificationTierRow\s*\(\s*$literal"""),
        "Field" to Regex("""\bField\s*\(\s*$literal"""),
        "TimeOfDayField" to Regex("""\bTimeOfDayField\s*\(\s*$literal"""),
        "DateField" to Regex("""\bDateField\s*\(\s*$literal"""),
        "MedicineIconOption label" to Regex("""\bMedicineIconOption\s*\(\s*"[^"\n]*"\s*,\s*$literal"""),
        "enum display arg" to Regex("""^\s{4}[A-Z][A-Z_0-9]*\s*\(\s*$literal""", RegexOption.MULTILINE),
        "channel description" to Regex("""\bdescription\s*=\s*$literal"""),
        "named text arg" to Regex("""\b(?:title|label|saveLabel|helper)\s*=\s*$literal"""),
    )

    /**
     * The one rule with a package scope. `title = "..."` names UI text in the
     * feature, ui and navigation layers -- a form title, a section heading --
     * but in `core/` it is a data-class field, and
     * `EmergencyCardExporter.CardLabels.ENGLISH` is exactly that: the
     * test-pinned English side of the card, kept in Kotlin on purpose (see its
     * KDoc). Scoping the rule to where it is true is not an allowlist. The
     * first run of this gate caught that field, and the alternative was to
     * exempt one file by name.
     */
    private val uiLayerOnly = setOf("named text arg")
    private fun inUiLayer(rel: String) =
        rel.startsWith("com/hazia/emivita/feature/") ||
            rel.startsWith("com/hazia/emivita/ui/") ||
            rel.startsWith("com/hazia/emivita/navigation/")

    private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val lineComment = Regex("""^\s*//.*$""", RegexOption.MULTILINE)

    /** Blank comments out but keep every newline, so line numbers survive. */
    private fun stripComments(src: String): String {
        val blank: (MatchResult) -> String = { m -> m.value.replace(Regex("[^\n]"), " ") }
        return lineComment.replace(blockComment.replace(src, blank), blank)
    }

    private fun repoDir(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }

    @Test
    fun noUserVisibleStringLiteralReachesASinkInKotlin() {
        val root = repoDir("app/src/main/java")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected to find Kotlin sources under $root", sources.size > 50)

        val offences = mutableListOf<String>()
        for (file in sources) {
            val clean = stripComments(file.readText())
            val rel = file.relativeTo(root).path.replace('\\', '/')
            for ((label, rx) in sinks) {
                if (label in uiLayerOnly && !inUiLayer(rel)) continue
                for (m in rx.findAll(clean)) {
                    val line = clean.substring(0, m.range.first).count { it == '\n' } + 1
                    offences += "$rel:$line  [$label]  \"${m.groupValues[1]}\""
                }
            }
        }
        assertEquals(
            "user-visible text is hardcoded in Kotlin instead of strings.xml (§9 #19). " +
                "Move each into a resource: stringResource() in a composable, " +
                "LocalResources.current.getString() in a callback, @StringRes on a " +
                "top-level declaration.\n  " + offences.joinToString("\n  "),
            0, offences.size,
        )
    }

    @Test
    fun theGateItselfCanFail() {
        // §7.1 rule 5: a check that cannot fail is worse than none. Feed the
        // sink rules a literal each and make sure every one of them bites.
        val samples = listOf(
            "Text(\"Take now\")", "Text(text = \"Take now\")", "contentDescription = \"Back\"",
            ".setContentTitle(\"Low stock\")", ".addAction(0, \"Take\", pi)",
            "NotificationChannel(CHANNEL_ID, \"Reminders\", importance)",
            "canvas.drawText(\"Date\", x, y, p)", ".setTitle(\"Unlock\")",
            "DetailHeader(\"Alarms\", onBack)", "Field(\"Name\", value, onChange)",
            "MedicineIconOption(\"tablet\", \"Tablet\", icon)", "    LIST(\"List\"),",
            "title = \"Add medicine\"", "saveLabel = \"Save\"",
        )
        for (sample in samples) {
            assertTrue("gate failed to catch: $sample", sinks.any { it.second.containsMatchIn(sample) })
        }
        // And a commented-out sink must NOT trip it.
        assertTrue(sinks.none { it.second.containsMatchIn(stripComments("// Text(\"old\")")) })
    }
}
