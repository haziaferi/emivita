package com.hazia.emivita.ui.form

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import com.hazia.emivita.R
import com.hazia.emivita.core.data.settings.StartPage
import com.hazia.emivita.core.di.LocalAppContainer
import com.hazia.emivita.core.di.LocalCurrentProfileId
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.navigation.EmivitaNavHost
import com.hazia.emivita.ui.theme.EmivitaTheme
import com.hazia.emivita.ui.theme.ThemeMode
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlin.math.abs

/**
 * The real app on the 6T's screen in Italian (§7 R124): EmivitaNavHost from the Medicines list over
 * the production container with an in-memory database, drawn with Robolectric's native graphics so
 * the form checks can read pixels. The clock is driven by hand; Room answers on its own threads.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(application = TestEmivitaApplication::class, qualifiers = "it-rIT-w411dp-h891dp-xxhdpi")
abstract class FormScreenTestBase {

    // Effects on a standard dispatcher, run on the main thread as the clock advances: a form that
    // saves through Room and then navigates must resume where the NavController lives.
    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java, effectContext = StandardTestDispatcher())

    private val harness = lazy { ReceiverHarness() }
    protected val h by harness
    protected val fontScale = mutableFloatStateOf(1f)
    protected var themeMode = ThemeMode.LIGHT
    protected val dp get() = compose.density.density
    protected val res get() = compose.activity.resources

    @After
    fun closeHarness() {
        if (harness.isInitialized()) h.close()
    }

    protected fun s(id: Int) = res.getString(id)
    protected fun s(id: Int, vararg args: Any) = res.getString(id, *args)

    protected fun hostApp() {
        h.container // built before the composition reads it
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale.floatValue)) {
                EmivitaTheme(themeMode = themeMode) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        CompositionLocalProvider(LocalAppContainer provides h.container, LocalCurrentProfileId provides h.profileId) {
                            EmivitaNavHost(startPage = StartPage.MEDICINES)
                        }
                    }
                }
            }
        }
        waitFor("the Medicines list") { inTop(described(s(R.string.medicines_add_medicine))) }
    }

    protected fun settle() {
        repeat(4) {
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(400)
            FormProbe.pump(compose.mainClock)
            compose.waitForIdle()
        }
    }

    /** Room and the projection cache answer on their own threads: wait in real time. */
    protected fun waitFor(what: String, seconds: Int = 30, cond: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!cond() && System.nanoTime() < deadline) {
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
            FormProbe.pump(compose.mainClock)
            compose.waitForIdle()
        }
        check(cond()) { "never: $what; the top window shows " + FormProbe.frame().merged.mapNotNull { it.text ?: it.description } }
        FormProbe.pump(compose.mainClock)
        compose.waitForIdle()
    }

    /** Of the nodes [m] matches, the one in the topmost window: a sheet or a dialog is modal. */
    protected fun top(m: SemanticsMatcher): SemanticsNodeInteraction {
        val w = FormProbe.topWindow()
        val inTop = w.roots.flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }.map { it.id to it.boundsInWindow }.toSet()
        val all = compose.onAllNodes(m)
        for (i in 0 until all.fetchSemanticsNodes().size) {
            val n = all[i].fetchSemanticsNode()
            if ((n.id to n.boundsInWindow) in inTop) return all[i]
        }
        error("nothing in the top window matches ${m.description}")
    }

    protected fun inTop(m: SemanticsMatcher) = runCatching { top(m) }.isSuccess
    protected fun windows() = FormProbe.windows().count { it.roots.isNotEmpty() }

    protected fun button(text: String) = hasText(text) and hasClickAction()
    protected fun described(text: String) = hasContentDescription(text) and hasClickAction()
    protected fun field(label: String) = hasSetTextAction() and hasText(label)
    protected val toggle = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    /** Brings a node of the top window fully into view with its scroll container's own ScrollBy. */
    protected fun reveal(m: SemanticsMatcher): SemanticsNodeInteraction {
        val height = FormProbe.topWindow().view.height
        fun fully(): Boolean {
            val n = runCatching { top(m).fetchSemanticsNode() }.getOrNull() ?: return false
            return abs(n.boundsInWindow.height - n.size.height) < 1.5f && n.boundsInWindow.height > 0f && n.boundsInWindow.bottom <= height
        }
        var i = 0
        while (!fully() && i++ < 16) {
            val n = runCatching { top(m).fetchSemanticsNode() }.getOrNull()
            val dy = if (n == null) height * 0.4f else (n.positionInWindow.y + n.size.height) - height * 0.6f
            top(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasScrollAction())
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
            settle()
        }
        check(fully()) { "${m.description} never came fully into view" }
        return top(m)
    }

    protected fun tapNode(n: SemanticsNodeInteraction) { n.performSemanticsAction(SemanticsActions.OnClick); settle() }
    protected fun tap(m: SemanticsMatcher) = tapNode(reveal(m))
    protected fun typeInto(m: SemanticsMatcher, text: String) { reveal(m).performTextReplacement(text); settle() }
    protected fun disabled(m: SemanticsMatcher) = top(m).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) != null

    /** Back as the system delivers it: to the top window's own dispatcher. */
    protected fun pressBack() {
        val w = FormProbe.topWindow()
        val window = ReflectionHelpers.getField<android.view.Window>(w.view, "mWindow")
        (window.callback as OnBackPressedDispatcherOwner).onBackPressedDispatcher.onBackPressed()
        settle()
    }

    // ---- the form checks ----------------------------------------------------------------------

    protected val names by lazy {
        val locale = res.configuration.locales[0]
        FormNames(
            save = setOf(s(R.string.common_save), s(R.string.common_save_changes)),
            cancel = s(R.string.common_cancel),
            weekdays = (1..7).map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }.toSet(),
            textButtons = setOf(s(R.string.reminder_delete)),
            groups = listOf(R.string.reminder_group_taking, R.string.reminder_group_notification, R.string.reminder_group_unanswered, R.string.reminder_group_duration).map { s(it) },
            chips = listOf(R.string.common_daily, R.string.add_reminder_specific_days, R.string.add_reminder_every_n_days, R.string.add_reminder_active_pause_cycle).map { s(it) }.toSet(),
            rules = listOf(
                R.string.form_min_one, R.string.form_min_one_minute, R.string.form_min_zero, R.string.form_time_invalid, R.string.form_date_invalid,
                R.string.form_until_before_from, R.string.quick_add_dose_invalid, R.string.add_reminder_minutes_after_range,
            ).map { s(it) }.toSet(),
        )
    }

    protected val rules by lazy { FormRules(dp, names, onIdle = { compose.runOnIdle(it) }, settle = ::settle) }

    /** C3's placeholder pass: each field focused in turn; a text that appears inside its box is a placeholder. */
    protected fun placeholders(sw: FormRules.Swept): Map<Int, List<String>> {
        val out = HashMap<Int, List<String>>()
        for (f in rules.controls(sw).filter { it.kind == "field" }) {
            val node = runCatching { compose.onNode(SemanticsMatcher("id ${f.node.id}") { it.id == f.node.id }) }.getOrNull() ?: continue
            val before = runCatching { node.fetchSemanticsNode() }.getOrNull() ?: continue
            if (SemanticsActions.RequestFocus !in before.config) continue
            node.performSemanticsAction(SemanticsActions.RequestFocus); settle()
            val fr = FormProbe.frame()
            val now = runCatching { node.fetchSemanticsNode() }.getOrNull() ?: continue
            val b = now.boundsInWindow.translate(fr.top.left.toFloat(), fr.top.top.toFloat())
            val boxH = (f.borderBottom ?: f.c.bottom) - f.c.top
            val known = sw.leaves.filter { it.c.center.x in f.c.left..f.c.right && it.c.center.y in f.c.top..f.c.bottom }.map { it.text }.toSet()
            val news = fr.leaves.filter { l ->
                !l.editable && l.isText && l.text !in known && l.unclipped.center.x in b.left..b.right && l.unclipped.center.y in b.top..(b.top + boxH) && f.node.id in l.ancestors
            }.mapNotNull { it.text }
            if (news.isNotEmpty()) out[f.node.id] = news
        }
        return out
    }

    /** Sweeps the top window's form, runs C1-C10 and returns the verdicts. */
    protected fun audit(sheet: Boolean, decl: FormDecl = FormDecl()): FormRules.Audit {
        val sw = rules.sweep(sheet)
        val first = rules.audit(sw, decl = decl)
        val ph = placeholders(sw)
        return if (ph.isEmpty()) first else rules.audit(sw, ph, decl)
    }
}
