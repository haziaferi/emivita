package com.hazia.emivita.ui.form

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The words the checks recognise controls by, resolved from the app's own strings: the form's
 * Salva labels, Annulla, the weekday cells, tertiary text actions, group rows, grid cells, and
 * every rule a field says only when broken (C9).
 */
class FormNames(
    val save: Set<String>,
    val cancel: String,
    val weekdays: Set<String>,
    val textButtons: Set<String>,
    val groups: List<String>,
    val chips: Set<String>,
    val rules: Set<String>,
)

/**
 * What the code says about a surface (C10): field label prefixes ignored in this state, or independent of the switch above;
 * (C8) chip flows, grids whose first cell is in [flows], which wrap ragged right by the owner's rule (§7 R125, the tags);
 * and (C2, C3, C4) [freeText], the label prefixes of the fields the code makes `FormTextField(freeText = true)`, whose
 * value may grow to several lines as it is typed (§7 R126, "Grow as you type"). Only the typed value may: such a field's
 * title, placeholder and help still fail on a second line, and empty or on one line it is held to the shared height.
 */
class FormDecl(
    val ignored: Set<String> = emptySet(),
    val independent: Map<String, String> = emptyMap(),
    val flows: Set<String> = emptySet(),
    val freeText: Set<String> = emptySet(),
)

/** One check's verdict: its violations (empty = pass) and what it measured, for the failure message. */
class Check(val name: String, val violations: List<String>, val detail: String = "") {
    val pass get() = violations.isEmpty()
    override fun toString() = "$name: " + (if (pass) "pass" else "${violations.size} violation(s): ${violations.joinToString("; ")}") +
        (if (detail.isNotEmpty()) " [$detail]" else "")
}

/** A control as classified and measured: its kind, its tier (null = exempt) and its drawn height and box. */
class Ctl(
    val node: ProbeNode, val kind: String, val tier: Int?, val c: Rect, val hDp: Double, val fill: Int?, val inScroll: Boolean,
    val drawn: Rect?, val borderTop: Float?, val borderBottom: Float?, val frame: Int, val dy: Float,
) {
    val label get() = node.label

    /** A declared free-text field whose typed value runs to several lines (§7 R126): exempt from the shared height. */
    var grown = false
}

/**
 * The owner's absolute checks C1-C10 (§7 R124) on one surface, ported from the scoring rig's
 * FormAuditBase. A whole form is read by a sweep of its scroll container in steps of 0.6 of the
 * viewport; each node is kept at the first frame that shows it whole, in content coordinates.
 */
class FormRules(private val dp: Float, private val names: FormNames, private val onIdle: (() -> Unit) -> Unit, private val settle: () -> Unit) {

    class Leaf(
        val id: Int, val text: String, val lines: Int, val editable: Boolean, val c: Rect, val frame: Int, val inScroll: Boolean,
        val ancestors: Set<Int> = emptySet(), val cut: Boolean = false,
    )
    class Kept(val n: ProbeNode, val frame: Int, val inScroll: Boolean, val dy: Float) {
        val c: Rect get() = n.unclipped.translate(0f, dy)
    }
    class Swept(
        val frames: List<ProbeFrame>, val scrolls: List<Float>, val nodes: List<Kept>, val leaves: List<Leaf>,
        val navTop: Float?, val windowBottom: Float, val sheet: Boolean,
        /** The scroll container's viewport in window pixels, null when nothing scrolls. */
        val viewport: Rect? = null,
    )

    private class ScrollInfo(val id: Int, val value: Float, val max: Float, val vp: Rect, val node: SemanticsNode)

    private fun scrollInfo(): ScrollInfo? {
        val w = FormProbe.topWindow()
        val all = w.roots.flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        val s = all.filter { SemanticsProperties.VerticalScrollAxisRange in it.config && SemanticsActions.ScrollBy in it.config }
            .maxByOrNull { it.size.width.toLong() * it.size.height } ?: return null
        val r = s.config[SemanticsProperties.VerticalScrollAxisRange]
        return ScrollInfo(s.id, r.value(), r.maxValue(), s.boundsInWindow.translate(w.left.toFloat(), w.top.toFloat()), s)
    }

    /** The top window's scroll container moved by its own ScrollBy, as a drag would. */
    private fun scroll(dy: Float) {
        val s = scrollInfo() ?: return
        onIdle { s.node.config[SemanticsActions.ScrollBy].action?.invoke(0f, dy) }
        settle()
    }

    fun sweep(sheet: Boolean): Swept {
        scrollInfo()?.let { if (it.value > 0f) scroll(-it.value - 10f) }
        val frames = mutableListOf<ProbeFrame>(); val scrolls = mutableListOf<Float>()
        var first: ScrollInfo? = null
        for (i in 0 until 24) {
            val f = FormProbe.frame(); val s = scrollInfo()
            if (first == null) first = s
            frames += f; scrolls += s?.value ?: 0f
            if (s == null || s.value >= s.max - 0.5f) break
            scroll(s.vp.height * 0.6f)
            val s2 = scrollInfo()
            if (s2 == null || abs(s2.value - s.value) < 0.5f) break
        }
        val sc = first
        val navTop = frames.first().merged.filter { it.role == Role.Tab }.minOfOrNull { it.unclipped.top }
        val seen = HashSet<Int>(); val nodes = mutableListOf<Kept>()
        val lseen = HashSet<Int>(); val leaves = mutableListOf<Leaf>()
        frames.forEachIndexed { fi, f ->
            val v = scrolls[fi]
            for (n in f.merged) {
                if (!n.fullyVisible() || n.id in seen) continue
                if (n.unclipped.width >= f.px.width - 2f) continue // the window's own containers and a sheet's scrim
                if (n.description?.let { it.contains("trascin", true) || it.contains("drag", true) } == true) continue // the sheet's drag handle
                if (sheet && n.isControl && n.text.isNullOrBlank() && n.description.isNullOrBlank()) continue
                val inScroll = sc != null && sc.id in n.ancestors
                if (inScroll && (n.unclipped.bottom > sc!!.vp.bottom + 0.5f || n.unclipped.top < sc.vp.top - 0.5f)) continue
                seen += n.id; nodes += Kept(n, fi, inScroll, if (inScroll) v else 0f)
            }
            for (l in f.leaves) {
                val t = l.text ?: l.value ?: continue
                if (t.isBlank() || l.id in lseen || l.bounds.width <= 0f || !l.fullyVisible()) continue
                if (navTop != null && !sheet && l.unclipped.top >= navTop - 0.5f) continue
                val inScroll = sc != null && l.unclipped.center.y in sc.vp.top..sc.vp.bottom && l.unclipped.center.x in sc.vp.left..sc.vp.right
                lseen += l.id
                leaves += Leaf(l.id, t, l.lines, l.editable, l.unclipped.translate(0f, if (inScroll) v else 0f), fi, inScroll, l.ancestors, l.cut)
            }
        }
        val top = frames.first().top
        return Swept(frames, scrolls, nodes, leaves, navTop, (top.top + top.view.height).toFloat(), sheet, sc?.vp)
    }

    // ---- classification and pixels --------------------------------------------------------------

    /** A control's function, from the composable that draws it: 48 primary, 42 secondary, 38 tertiary, null exempt. */
    private fun classify(n: ProbeNode): Pair<String, Int?>? {
        if (n.role == Role.Tab) return null
        if (n.editable) return "field" to null
        if (!n.clickable) return null
        val t = n.text ?: ""
        return when {
            n.role == Role.Switch -> "switch row" to null
            n.role == Role.RadioButton -> "radio row" to null
            t in names.save -> "save button" to 48
            t == names.cancel -> "cancel button" to 42
            t in names.weekdays || n.description in names.weekdays || t.toIntOrNull() in 1..31 -> "day cell" to 42
            t in names.textButtons -> "text button" to 38
            names.groups.any { t.startsWith(it) } -> "group row" to null
            t in names.chips || n.role == Role.Checkbox -> "chip" to 42
            t.isBlank() && !n.description.isNullOrBlank() -> "icon button" to null
            else -> "secondary button" to 42
        }
    }

    private val drawnKinds = setOf("save button", "cancel button", "day cell", "chip", "secondary button")

    private fun drawnBox(px: Pixels, r: Rect): PxRect? {
        val t = PxRect.of(r).clampTo(px)
        if (t.width <= 0 || t.height <= 0) return null
        val near = FormProbe.DeltaCache(FormProbe.ringColour(px, t))
        // A container's rows and columns are mostly drawn; a neighbour's text reaching into the
        // padded touch bounds is not, so it does not stretch the box.
        val rowN = IntArray(t.height); val colN = IntArray(t.width)
        for (y in t.top until t.bottom) for (x in t.left until t.right) if (near.distance(px[x, y]) > 2.0) { rowN[y - t.top]++; colN[x - t.left]++ }
        val rows = rowN.indices.filter { rowN[it] >= 0.25 * t.width }
        val cols = colN.indices.filter { colN[it] >= 0.25 * t.height }
        if (rows.isEmpty() || cols.isEmpty()) return null
        return PxRect(t.left + cols.first(), t.top + rows.first(), t.left + cols.last() + 1, t.top + rows.last() + 1)
    }

    /** An outlined field's frame: the rows its border occupies in the leftmost 6 dp strip (free of text). */
    private fun borderRows(px: Pixels, r: Rect): Pair<Int, Int>? {
        val t = PxRect.of(r).clampTo(px)
        val near = FormProbe.DeltaCache(FormProbe.ringColour(px, t))
        val strip = (6 * dp).roundToInt()
        var top = -1; var bot = -1
        for (y in t.top until t.bottom) for (x in t.left until min(t.right, t.left + strip)) if (near.distance(px[x, y]) > 2.0) { if (top < 0) top = y; bot = y; break }
        return if (top < 0) null else top to bot + 1
    }

    private fun modal(px: Pixels, b: PxRect): Int {
        val ix = b.width * 3 / 10; val iy = b.height * 3 / 10
        return FormProbe.groundColour(px, PxRect(b.left + ix, b.top + iy, b.right - ix, b.bottom - iy), 1)
    }

    private fun r1(x: Double) = (x * 10).roundToInt() / 10.0
    private fun r1(x: Float) = r1(x.toDouble())

    fun controls(sw: Swept): List<Ctl> = sw.nodes.mapNotNull { k ->
        // An icon inside a field's frame (the clock, the calendar, the menu arrow) belongs to that field.
        if (k.n.ancestors.any { a -> sw.nodes.any { it.n.id == a && it.n.editable } }) return@mapNotNull null
        val (kind, tier) = classify(k.n) ?: return@mapNotNull null
        if (sw.navTop != null && !sw.sheet && k.n.unclipped.top >= sw.navTop - 0.5f) return@mapNotNull null
        val px = sw.frames[k.frame].px
        when (kind) {
            "field" -> {
                val bb = borderRows(px, k.n.unclipped)
                val h = bb?.let { (it.second - it.first) / dp } ?: (k.n.unclipped.height / dp)
                Ctl(k.n, kind, tier, k.c, r1(h), null, k.inScroll, null, bb?.first?.toFloat()?.plus(k.dy), bb?.second?.toFloat()?.plus(k.dy), k.frame, k.dy)
            }
            in drawnKinds -> {
                // A control's padded touch box, cut to the scroll viewport when it scrolls: a cell kept
                // on the frame that first shows it whole can sit on the foot's edge, and the foot's
                // tone below the viewport is not the cell (§7 R127, found on the days of the month).
                val vp = sw.viewport
                val touch = if (k.inScroll && vp != null) k.n.touch.intersect(vp) else k.n.touch
                val d = drawnBox(px, touch)
                val h = d?.height?.div(dp) ?: (k.n.unclipped.height / dp)
                Ctl(k.n, kind, tier, k.c, r1(h), d?.let { modal(px, it) }, k.inScroll,
                    d?.let { Rect(it.left.toFloat(), it.top + k.dy, it.right.toFloat(), it.bottom + k.dy) }, null, null, k.frame, k.dy)
            }
            else -> Ctl(k.n, kind, tier, k.c, r1(k.n.unclipped.height / dp), null, k.inScroll, null, null, null, k.frame, k.dy)
        }
    }

    /** WCAG ratio of a text's glyph cores against the ring just outside it. */
    private fun textContrast(px: Pixels, r: Rect): Double? {
        val t = PxRect.of(r).clampTo(px)
        if (t.width <= 0 || t.height <= 0) return null
        val ground = FormProbe.ringColour(px, t)
        val near = FormProbe.DeltaCache(ground)
        var far = ground; var farD = 0.0
        for (y in t.top until t.bottom) for (x in t.left until t.right) { val d = near.distance(px[x, y]); if (d > farD) { farD = d; far = px[x, y] or (0xFF shl 24) } }
        if (farD <= 2.0) return null
        val core = FormProbe.DeltaCache(far)
        var sr = 0L; var sg = 0L; var sb = 0L; var k = 0
        for (y in t.top until t.bottom) for (x in t.left until t.right) {
            val c = px[x, y]
            if (core.distance(c) <= 6.0) { sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; k++ }
        }
        val mean = (0xFF shl 24) or ((sr / k).toInt() shl 16) or ((sg / k).toInt() shl 8) or (sb / k).toInt()
        return FormProbe.wcagRatio(mean, ground)
    }

    // ---- the checks ---------------------------------------------------------------------------------

    class Audit(val checks: List<Check>, val controls: List<Ctl>) {
        val failed get() = checks.filter { !it.pass }
        override fun toString() = checks.joinToString("\n")
    }

    fun audit(sw: Swept, placeholders: Map<Int, List<String>> = emptyMap(), decl: FormDecl = FormDecl(), oneLineSwitchHelp: Boolean = true, exactTiers: Boolean = true): Audit {
        val wDp = sw.frames.first().px.width / dp
        val ctls = controls(sw)
        val fields = ctls.filter { it.kind == "field" }
        fun leavesOf(f: Ctl) = sw.leaves.filter { it.inScroll == f.inScroll && it.c.center.x in f.c.left..f.c.right && it.c.center.y in (f.c.top - 1f)..(f.c.bottom + 1f) }
        fun freeText(f: Ctl) = decl.freeText.any { f.label.startsWith(it) }
        for (f in fields) f.grown = freeText(f) && leavesOf(f).any { it.editable && it.lines > 1 }
        val elements = sw.nodes.filter { k ->
            (k.n.isText || k.n.isControl) && k.n.role != Role.Tab && !(sw.navTop != null && !sw.sheet && k.n.unclipped.top >= sw.navTop - 0.5f)
        }.let { all -> val ids = all.map { it.n.id }.toSet(); all.filter { e -> e.n.ancestors.none { it in ids } } }

        // C1 one column grid. L is the most frequent left edge, R its mirror. A text line within 6 dp
        // under a field is that field's help line, placed by C7 at the field's text inset.
        val left = elements.map { (it.c.left / dp).roundToInt() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.toDouble() ?: 16.0
        val right = wDp - left
        fun underAField(e: Kept) = !e.n.isControl && fields.any { f -> e.c.top >= f.c.bottom - dp && e.c.top - f.c.bottom <= 6 * dp && e.c.left < f.c.right && e.c.right > f.c.left - dp }
        val rows = mutableListOf<MutableList<Kept>>()
        for (e in elements.filter { !underAField(it) }.sortedBy { it.c.top }) {
            val row = rows.lastOrNull()
            val fits = row != null && row.any { o -> min(o.c.bottom, e.c.bottom) - max(o.c.top, e.c.top) >= 0.5f * min(o.c.height, e.c.height) }
            if (fits) row!!.add(e) else rows += mutableListOf(e)
        }
        val c1 = mutableListOf<String>()
        for (row in rows) {
            val byLeft = row.sortedBy { it.c.left }
            val first = byLeft.first()
            val last = byLeft.maxBy { it.c.right }
            val footBar = byLeft.any { classify(it.n)?.first == "save button" } && abs(right - last.c.right / dp) <= 1.0
            val onGrid = abs(first.c.left / dp - left) <= 1.0 || footBar
            if (!onGrid) c1 += "'${first.n.label}' starts ${r1(first.c.left / dp - left)} dp off the left edge"
            val flow = byLeft.any { classify(it.n)?.first in setOf("chip", "day cell") }
            // A tertiary text action keeps to the left edge by design (FormFootBar's delete), however wide its label.
            val textOnly = byLeft.all { !it.n.isControl || classify(it.n)?.first == "text button" }
            if (!flow && !textOnly && (last.c.right - first.c.left) / dp >= 0.75 * (right - left) && abs(right - last.c.right / dp) > 1.0) {
                c1 += "row of '${first.n.label}' ends ${r1(right - last.c.right / dp)} dp short of the right edge"
            }
        }

        // C2 height by function; fields share one height. Past font 1.0 a tier is a minimum the
        // control grows from (§7 R129, the owner: "Grow with the font"), and no label may be cut.
        val c2 = mutableListOf<String>()
        ctls.filter { it.tier != null && (if (exactTiers) abs(it.hDp - it.tier) > 1.0 else it.hDp < it.tier - 1.0) }
            .forEach { c2 += "${it.kind} '${it.label}' is ${it.hDp} dp, its tier ${it.tier}" }
        ctls.filter { it.tier != null }.forEach { k -> sw.leaves.filter { it.cut && k.node.id in it.ancestors }.forEach { c2 += "${k.kind} '${k.label}': '${it.text}' is cut short" } }
        ctls.filter { !it.grown }.groupBy { it.kind }.filter { (k, l) -> k !in setOf("switch row", "radio row", "group row", "icon button") && l.map { it.hDp.roundToInt() }.distinct().size > 1 }
            .forEach { (k, l) -> c2 += "${k}s have several heights: ${l.map { "${it.label}=${it.hDp}" }}" }
        // No control taller than the field beside it.
        for (f in fields) ctls.filter { it.kind != "field" && it.tier != null && abs(it.c.center.y - f.c.center.y) < 8 * dp && it.hDp > f.hDp + 1.0 }
            .forEach { c2 += "'${it.label}' (${it.hDp} dp) is taller than the field '${f.label}' (${f.hDp} dp) beside it" }

        // C3 one field pattern: a label, small inside the frame; nothing inside or under the box on two lines; no placeholder.
        // A declared free-text field's typed value alone may grow (§7 R126); its label is then read in the frame's first line.
        val c3 = mutableListOf<String>()
        for (f in fields) {
            val bt = f.borderTop ?: f.c.top; val bb = f.borderBottom ?: f.c.bottom
            val inside = leavesOf(f)
            val inBox = inside.filter { !it.editable && it.c.center.y <= bb }
            val label = inBox.minByOrNull { it.c.top }
            val firstLine = if (f.grown) minOf(bb - bt, 56 * dp) else bb - bt
            if (label == null) c3 += "'${f.label}' has no label"
            else if (label.c.center.y >= bt + 0.25f * firstLine) c3 += "'${label.text}' shown full size inside the box (reads as a placeholder)"
            inBox.filter { it.lines > 1 }.forEach { c3 += "'${it.text}' wraps to ${it.lines} lines inside the box" }
            if (!freeText(f)) inside.filter { it.editable && it.lines > 1 }.forEach { c3 += "the value of '${f.label}' wraps to ${it.lines} lines" }
            inside.filter { !it.editable && it.c.center.y > bb && it.lines > 1 }.forEach { c3 += "help '${it.text}' runs ${it.lines} lines" }
            placeholders[f.node.id]?.forEach { c3 += "placeholder '$it' in '${f.label}' (help inside the box)" }
        }
        // A switch row's help is one line too (§7 R124 C3; §7 R126, found on the 6T in Weekend mode): every
        // text under the row's title, wrapped or cut short. Held at font 1.0, where the owner's rule is set.
        if (oneLineSwitchHelp) {
            for (sr in sw.nodes.filter { it.n.role == Role.Switch && !(sw.navTop != null && !sw.sheet && it.n.unclipped.top >= sw.navTop - 0.5f) }) {
                val texts = sw.leaves.filter { !it.editable && sr.n.id in it.ancestors }.sortedBy { it.c.top }
                texts.drop(1).filter { it.lines > 1 || it.cut }
                    .forEach { c3 += "switch help '${it.text}' under '${texts.first().text}' " + (if (it.lines > 1) "runs ${it.lines} lines" else "is cut short") }
            }
        }

        // C5 Salva / Annulla.
        val save = ctls.firstOrNull { it.kind == "save button" }
        val cancel = ctls.firstOrNull { it.kind == "cancel button" }
        val c5 = mutableListOf<String>()
        var c5detail = ""
        if (save == null) c5 += "no Salva on this surface" else if (cancel == null) c5 += "no Annulla beside Salva" else {
            val sd = save.drawn ?: save.c; val cd = cancel.drawn ?: cancel.c
            if (abs(sd.width - cd.width) / dp > 1f) c5 += "widths differ: Salva ${r1(sd.width / dp)} dp, Annulla ${r1(cd.width / dp)} dp"
            if (abs(right - sd.right / dp) > 1.0) c5 += "Salva ends ${r1(right - sd.right / dp)} dp from the right edge"
            if (!(cd.right <= sd.left && abs(cd.center.y - sd.center.y) < 30 * dp)) c5 += "Annulla is not to the left of Salva on its line"
            else if ((sd.left - cd.right) / dp < 14f) c5 += "Salva and Annulla are ${r1((sd.left - cd.right) / dp)} dp apart, under 14"
            val sf = save.fill; val cf = cancel.fill
            if (sf == null || cf == null || FormProbe.deltaE2000(FormProbe.lab(sf), FormProbe.lab(cf)) <= 2.0) c5 += "Salva and Annulla are not two colours"
            else if (FormProbe.chroma(cf) >= FormProbe.chroma(sf)) c5 += "Annulla is not more desaturated than Salva"
            if (save.inScroll) c5 += "Salva scrolls with the content instead of staying in a fixed foot"
            val anchor = if (sw.sheet) sw.windowBottom else (sw.navTop ?: sw.windowBottom)
            val gap = (anchor - (sd.bottom - save.dy)) / dp
            if (gap !in 0f..24f) c5 += "Salva's bottom is ${r1(gap)} dp from the ${if (sw.sheet) "sheet's bottom" else "navigation bar"}"
            c5detail = "Salva ${r1(sd.width / dp)}x${save.hDp} dp, Annulla ${r1(cd.width / dp)}x${cancel.hDp} dp, gap ${r1((sd.left - cd.right) / dp)} dp, bottom gap ${r1(gap)} dp"
        }

        // C6 the weekday picker: 7 cells in one row, 42 x 42 text toggles, column 1 on L and 7 on R.
        val wk = ctls.filter { it.kind == "day cell" && it.label in names.weekdays }.sortedBy { it.c.left }
        val c6 = mutableListOf<String>()
        if (wk.isNotEmpty()) {
            if (wk.size != 7 || wk.any { abs(it.c.top - wk.first().c.top) >= dp }) c6 += "the weekdays are not one row of 7 (${wk.size})"
            wk.filter { abs((it.drawn?.width ?: it.c.width) / dp - 42) > 1 || abs(it.hDp - 42) > 1 }.forEach { c6 += "'${it.label}' is drawn ${r1((it.drawn?.width ?: it.c.width) / dp)} x ${it.hDp} dp, not 42 x 42" }
            val d0 = wk.first().drawn ?: wk.first().c; val dn = wk.last().drawn ?: wk.last().c
            if (abs(d0.left / dp - left) > 1 || abs(right - dn.right / dp) > 1) c6 += "the weekdays span ${r1(d0.left / dp)}..${r1(dn.right / dp)} dp, not the form's edges ${r1(left)}..${r1(right)}"
        }

        // C7 help start: every line under a field starts at its field's text inset.
        fun labelLeaf(f: Ctl) = sw.leaves.filter { it.inScroll == f.inScroll && !it.editable && it.c.center.x in f.c.left..f.c.right && it.c.center.y in (f.c.top - 1f)..(f.borderBottom ?: f.c.bottom) }.minByOrNull { it.c.top }
        fun inset(f: Ctl) = labelLeaf(f)?.let { (it.c.left - f.c.left) / dp }
        class Help(val text: String, val under: List<Ctl>, val offset: Float, val inset: Float?)
        val helps = mutableListOf<Help>()
        for (f in fields) {
            val bb = f.borderBottom ?: f.c.bottom
            sw.leaves.filter { it.inScroll == f.inScroll && !it.editable && it.text.isNotBlank() && it.c.center.x in f.c.left..f.c.right && it.c.center.y in bb..(f.c.bottom + 1f) }
                .forEach { helps += Help(it.text, listOf(f), (it.c.left - f.c.left) / dp, inset(f)) }
        }
        for (e in elements.filter { !it.n.isControl && it.n.isText }) {
            val above = fields.filter { f -> e.c.top >= f.c.bottom - dp && e.c.top - f.c.bottom <= 6 * dp && e.c.left < f.c.right && e.c.right > f.c.left - dp }
            val f = above.minByOrNull { it.c.left } ?: continue
            helps += Help(e.n.text!!, above, (e.c.left - f.c.left) / dp, inset(f))
        }
        val c7 = mutableListOf<String>()
        helps.filter { h -> h.inset != null && abs(h.offset - h.inset) > 1f }.forEach { c7 += "'${it.text}' starts ${r1(it.offset)} dp in, its field's text ${r1(it.inset!!)}" }
        if (helps.isNotEmpty() && helps.maxOf { it.offset } - helps.minOf { it.offset } > 1f) c7 += "help lines start at ${helps.map { r1(it.offset) }.distinct()} dp"

        // C8 grid reach: every grid's outer cells lie on L and R.
        val cells = ctls.filter { it.kind in setOf("chip", "day cell") }
        val gridRows = cells.sortedBy { it.c.top }.fold(mutableListOf<MutableList<Ctl>>()) { acc, c ->
            if (acc.isNotEmpty() && abs(acc.last().first().c.top - c.c.top) <= dp) acc.last() += c else acc += mutableListOf(c); acc
        }
        val grids = gridRows.fold(mutableListOf<MutableList<Ctl>>()) { acc, row ->
            val prev = acc.lastOrNull()
            if (prev != null && row.first().c.top - prev.maxOf { (it.drawn ?: it.c).bottom } <= 24 * dp) acc.last() += row else acc += row.toMutableList(); acc
        }
        val c8 = mutableListOf<String>()
        for (g in grids) {
            if (g.any { it.label in decl.flows }) continue
            val gl = g.minOf { (it.drawn ?: it.c).left } / dp; val gr = g.maxOf { (it.drawn ?: it.c).right } / dp
            if (abs(gl - left) > 1.0 || abs(right - gr) > 1.0) c8 += "the grid of '${g.first().label}' spans ${r1(gl)}..${r1(gr)} dp, not ${r1(left)}..${r1(right)}"
        }

        // C9 help is not a rule: a rule shows only when its field is in error.
        val c9 = helps.filter { h -> h.text in names.rules && h.under.none { it.node.error } }.map { "'${it.text}' shown at rest under '${it.under.first().label}'" }

        // C10 disabled looks disabled: semantics and drawing agree, and nothing enabled hides under an off switch.
        val offSwitches = sw.nodes.filter { it.n.role == Role.Switch && it.n.on == false }
        val c10 = mutableListOf<String>()
        for (f in fields) {
            val disabled = f.node.disabled
            val lab = labelLeaf(f)
            val ratio = lab?.let { l -> textContrast(sw.frames[l.frame].px, l.c.translate(0f, if (l.inScroll) -sw.scrolls[l.frame] else 0f)) }
            if (ratio != null && disabled != (ratio < 3.0)) c10 += "'${f.label}' is ${if (disabled) "disabled but drawn at" else "enabled but drawn faint at"} ${"%.2f".format(ratio)}:1"
            val ignored = decl.ignored.any { f.label.startsWith(it) }
            if (ignored && !disabled) c10 += "'${f.label}' is ignored in this state, yet enabled"
            val under = offSwitches.firstOrNull { s -> f.c.top >= s.c.bottom - dp && f.c.top - s.c.bottom <= 24 * dp }
            if (under != null && !disabled && !ignored && decl.independent.keys.none { f.label.startsWith(it) }) c10 += "'${f.label}' is enabled under the off switch '${under.n.label}'"
        }

        return Audit(
            listOf(
                Check("C1 grid", c1, "L ${r1(left)} dp, R ${r1(right)} dp, ${rows.size} rows"),
                Check("C2 height by function", c2, ctls.filter { it.tier != null || it.kind == "field" }.joinToString { "${it.kind} ${it.label}=${it.hDp}" }),
                Check("C3 one field pattern", c3, "${fields.size} fields"),
                Check("C5 Salva/Annulla", c5, c5detail),
                Check("C6 weekdays", c6, if (wk.isEmpty()) "not on this surface" else "${wk.size} cells"),
                Check("C7 help start", c7, "${helps.size} lines"),
                Check("C8 grid reach", c8, "${grids.size} grids"),
                Check("C9 help is not a rule", c9),
                Check("C10 disabled looks disabled", c10, "${fields.count { it.node.disabled }} of ${fields.size} fields disabled"),
            ),
            ctls,
        )
    }

    /**
     * The dialog pair (§7 R125, the owner: "Yes, same order and look"): in the top window, the button
     * [confirm] and the button [dismiss] on its line are drawn the same width, the confirm rightmost
     * and the dismiss to its left at least 14 dp apart, 48 and 42 dp high, two colours with the
     * dismiss the more desaturated, and neither label cut short. A dialog keeps its own place, so no
     * edge or foot is checked. Every dialog is side by side (§7 R126, "Shorter labels, side by side");
     * at [largeFont] the heights are minimums and the pair may stack, the confirm on top, right edges
     * aligned, at least 14 dp apart (§7 R129: "Grow with the font", "B: stack when cut").
     */
    fun dialogPair(confirm: String, dismiss: String, largeFont: Boolean = false): Check {
        val f = FormProbe.frame()
        val v = mutableListOf<String>()
        val c = f.merged.firstOrNull { it.clickable && it.text == confirm && it.fullyVisible() }
        val d = c?.let { cc -> f.merged.filter { it.clickable && it.text == dismiss && it.fullyVisible() }.minByOrNull { abs(it.touch.center.y - cc.touch.center.y) } }
        if (c == null || d == null) return Check("dialog pair '$confirm'/'$dismiss'", listOf("not both on screen: confirm ${c != null}, dismiss ${d != null}"))
        val cd = drawnBox(f.px, c.touch); val dd = drawnBox(f.px, d.touch)
        if (cd == null || dd == null) return Check("dialog pair '$confirm'/'$dismiss'", listOf("not drawn"))
        val ch = cd.height / dp; val dh = dd.height / dp
        if (if (largeFont) ch < 47 else abs(ch - 48) > 1) v += "'$confirm' is ${r1(ch)} dp high, not 48"
        if (if (largeFont) dh < 41 else abs(dh - 42) > 1) v += "'$dismiss' is ${r1(dh)} dp high, not 42"
        if (abs(cd.width - dd.width) / dp > 1f) v += "widths differ: '$confirm' ${r1(cd.width / dp)} dp, '$dismiss' ${r1(dd.width / dp)} dp"
        val sameLine = abs((cd.top + cd.bottom) / 2f - (dd.top + dd.bottom) / 2f) < 30 * dp
        for (label in listOf(confirm, dismiss)) {
            if (f.leaves.any { it.text == label && it.cut && (c.id in it.ancestors || d.id in it.ancestors) }) v += "'$label' is cut short"
        }
        val stacked = largeFont && !sameLine && dd.top >= cd.bottom && abs(dd.right - cd.right) / dp <= 1f
        if (stacked) {
            if ((dd.top - cd.bottom) / dp < 14f) v += "'$confirm' and '$dismiss' are ${r1((dd.top - cd.bottom) / dp)} dp apart, under 14"
        } else if (!(sameLine && dd.right <= cd.left)) v += "'$dismiss' is not to the left of '$confirm' on its line"
        else if ((cd.left - dd.right) / dp < 14f) v += "'$confirm' and '$dismiss' are ${r1((cd.left - dd.right) / dp)} dp apart, under 14"
        val cf = modal(f.px, cd); val df = modal(f.px, dd)
        if (FormProbe.deltaE2000(FormProbe.lab(cf), FormProbe.lab(df)) <= 2.0) v += "'$confirm' and '$dismiss' are not two colours"
        else if (FormProbe.chroma(df) >= FormProbe.chroma(cf)) v += "'$dismiss' is not more desaturated than '$confirm'"
        val detail = "'$confirm' ${r1(cd.width / dp)}x${r1(ch)} dp, '$dismiss' ${r1(dd.width / dp)}x${r1(dh)} dp, ${if (stacked) "stacked, gap ${r1((dd.top - cd.bottom) / dp)}" else "gap ${r1((cd.left - dd.right) / dp)}"} dp, " +
            "chroma ${"%.3f".format(FormProbe.chroma(cf))} / ${"%.3f".format(FormProbe.chroma(df))}"
        return Check("dialog pair '$confirm'/'$dismiss'", v, detail)
    }

    /** C4 across surfaces: one look per kind (height, fill) for every control the surfaces share. */
    fun consistency(surfaces: Map<String, Audit>): Check {
        val v = mutableListOf<String>()
        val all = surfaces.flatMap { (s, a) -> a.controls.filter { !it.grown }.map { s to it } }
        for ((kind, list) in all.groupBy { it.second.kind }) {
            if (kind in setOf("switch row", "radio row", "icon button")) continue
            val heights = list.groupBy { it.second.hDp.roundToInt() }
            if (heights.size > 1) v += "$kind heights differ: " + heights.map { (h, l) -> "$h dp on ${l.map { it.first }.distinct()}" }
            // A toggle's fill says its state, so toggles are compared on and off apart.
            for ((on, same) in list.groupBy { it.second.node.on }) {
                val fills = same.mapNotNull { (s, c) -> c.fill?.let { Triple(s, c.label, it) } }
                val ref = fills.firstOrNull() ?: continue
                fills.filter { FormProbe.deltaE2000(FormProbe.lab(it.third), FormProbe.lab(ref.third)) > 2.0 }
                    .forEach { v += "$kind${on?.let { if (it) " (on)" else " (off)" } ?: ""} '${it.second}' on ${it.first} is not filled like '${ref.second}' on ${ref.first}" }
            }
        }
        return Check("C4 consistency", v, "${surfaces.size} surfaces")
    }
}
