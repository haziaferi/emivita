package com.hazia.emivita.ui.form

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.MainTestClock
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.drawToBitmap
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A semantics node as the form checks read it; every rectangle in screen pixels. */
data class ProbeNode(
    val id: Int,
    val ancestors: Set<Int>,
    val bounds: Rect,
    val unclipped: Rect,
    val touch: Rect,
    val text: String?,
    /** An editable node's value. */
    val value: String?,
    val editable: Boolean,
    val clickable: Boolean,
    val description: String?,
    val state: String?,
    val role: Role?,
    val lines: Int,
    val disabled: Boolean,
    val error: Boolean,
    val on: Boolean?,
    /** Its text ends in an ellipsis, or is taller than the room it was given and clipped (§7 R126, R129): drawn short of itself. */
    val cut: Boolean = false,
    /** A line ends between two letters or digits: a word drawn in two pieces (§7 R136), whatever else the label holds. */
    val brokenWord: Boolean = false,
) {
    val visible get() = bounds.width > 0f && bounds.height > 0f
    val isText get() = !text.isNullOrBlank()
    val isControl get() = clickable || editable
    /** A text laid out at no width at all: it has lines and height but nothing drawn, so "visible" skips it (§7 R136). */
    val squeezed get() = isText && unclipped.width < 1f && unclipped.height > 0f
    fun fullyVisible() = visible && abs(bounds.left - unclipped.left) <= 0.5f && abs(bounds.top - unclipped.top) <= 0.5f &&
        abs(bounds.right - unclipped.right) <= 0.5f && abs(bounds.bottom - unclipped.bottom) <= 0.5f
    val label get() = text ?: description ?: "#$id"
}

/** The drawn screen as ARGB ints, row-major. */
class Pixels(val width: Int, val height: Int, private val argb: IntArray) {
    operator fun get(x: Int, y: Int) = argb[y * width + x]
}

/** A pixel rectangle, right and bottom exclusive. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun clampTo(p: Pixels) = PxRect(left.coerceIn(0, p.width), top.coerceIn(0, p.height), right.coerceIn(0, p.width), bottom.coerceIn(0, p.height))

    companion object {
        fun of(r: Rect) = PxRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
    }
}

class ProbeWindow(val view: View, val left: Int, val top: Int, val params: WindowManager.LayoutParams?, val roots: List<ViewRootForTest>)

/** One frame: every window drawn bottom-up, and the topmost window's merged nodes and text leaves. */
class ProbeFrame(val px: Pixels, val top: ProbeWindow, val merged: List<ProbeNode>, val leaves: List<ProbeNode>)

/**
 * The screen as the user sees it under Robolectric: a dialog or a bottom sheet draws in a window
 * of its own, which the activity's decor view never shows, so every window is drawn and placed.
 */
object FormProbe {

    fun windows(includeEmpty: Boolean = false): List<ProbeWindow> {
        val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
        val views = ReflectionHelpers.callInstanceMethod<java.util.ArrayList<View>>(global, "getWindowViews").filter { it.isShown && (includeEmpty || it.width > 0) }
        val screen = views.first()
        return views.map { v ->
            val lp = v.layoutParams as? WindowManager.LayoutParams
            // Robolectric lays every window out at (0, 0); the platform places it by its gravity.
            val g = lp?.gravity ?: 0
            val x = when (g and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.CENTER_HORIZONTAL -> (screen.width - v.width) / 2
                Gravity.RIGHT, Gravity.END -> screen.width - v.width
                else -> 0
            } + (lp?.x ?: 0)
            val y = when (g and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.CENTER_VERTICAL -> (screen.height - v.height) / 2
                Gravity.BOTTOM -> screen.height - v.height
                else -> 0
            } + (lp?.y ?: 0)
            val roots = mutableListOf<ViewRootForTest>()
            fun walk(w: View) {
                if (w is ViewRootForTest) roots += w
                if (w is ViewGroup) for (i in 0 until w.childCount) walk(w.getChildAt(i))
            }
            walk(v)
            ProbeWindow(v, x, y, lp, roots)
        }
    }

    fun topWindow() = windows().last { it.roots.isNotEmpty() }

    /** Frames by hand until no window is left pending: Robolectric never re-lays a dialog window out itself. */
    fun pump(clock: MainTestClock) {
        repeat(6) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            clock.advanceTimeByFrame()
            for (w in windows(includeEmpty = true)) for (r in w.roots) if (r.hasPendingMeasureOrLayout) r.measureAndLayoutForTest()
        }
    }

    fun frame(): ProbeFrame {
        val ws = windows()
        val base = ws.first().view
        val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        ws.forEachIndexed { i, w ->
            val lp = w.params
            if (i > 0 && lp != null && (lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                canvas.drawColor(Color.argb((lp.dimAmount * 255).roundToInt(), 0, 0, 0))
            }
            canvas.drawBitmap(w.view.drawToBitmap(), w.left.toFloat(), w.top.toFloat(), null)
        }
        val a = IntArray(bmp.width * bmp.height)
        bmp.getPixels(a, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val top = ws.last { it.roots.isNotEmpty() }
        val dx = top.left.toFloat(); val dy = top.top.toFloat()
        val merged = top.roots.flatMap { collect(it.semanticsOwner.rootSemanticsNode, dx, dy) }
        val leaves = top.roots.flatMap { collect(it.semanticsOwner.unmergedRootSemanticsNode, dx, dy) }
        return ProbeFrame(Pixels(bmp.width, bmp.height, a), top, merged, leaves)
    }

    /** Some line but the last ends inside a word: the character before the break and the one after are both letters or digits. */
    fun breaksAWord(layout: TextLayoutResult): Boolean {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount - 1).any { i ->
            val end = layout.getLineEnd(i)
            end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
        }
    }

    private fun collect(root: SemanticsNode, dx: Float, dy: Float): List<ProbeNode> {
        val origin = root.positionInWindow - root.positionInRoot
        val out = mutableListOf<ProbeNode>()
        fun walk(n: SemanticsNode, anc: Set<Int>) {
            val c = n.config
            val pos = n.positionInWindow
            val layout = c.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { a ->
                val l = mutableListOf<TextLayoutResult>()
                if (a(l)) l.firstOrNull() else null
            }
            out += ProbeNode(
                id = n.id, ancestors = anc,
                bounds = n.boundsInWindow.translate(dx, dy),
                unclipped = Rect(pos.x, pos.y, pos.x + n.size.width, pos.y + n.size.height).translate(dx, dy),
                touch = n.touchBoundsInRoot.translate(origin).translate(dx, dy),
                text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() },
                value = c.getOrNull(SemanticsProperties.EditableText)?.text,
                editable = c.getOrNull(SemanticsProperties.EditableText) != null,
                clickable = SemanticsActions.OnClick in c,
                description = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
                state = c.getOrNull(SemanticsProperties.StateDescription),
                role = c.getOrNull(SemanticsProperties.Role),
                lines = layout?.lineCount ?: 1,
                disabled = SemanticsProperties.Disabled in c,
                error = SemanticsProperties.Error in c,
                on = c.getOrNull(SemanticsProperties.ToggleableState)?.let { it == androidx.compose.ui.state.ToggleableState.On },
                cut = layout != null && layout.lineCount > 0 && (layout.isLineEllipsized(layout.lineCount - 1) || layout.didOverflowHeight),
                brokenWord = layout != null && breaksAWord(layout),
            )
            n.children.forEach { walk(it, anc + n.id) }
        }
        walk(root, emptySet())
        return out
    }

    // ---- colour ---------------------------------------------------------------------------------

    private const val OPAQUE = 0xFF shl 24

    /** The modal colour of [area], sampled every [step] pixels. */
    fun groundColour(px: Pixels, area: PxRect, step: Int = 2): Int {
        val r = area.clampTo(px)
        val counts = HashMap<Int, Int>()
        for (y in r.top until r.bottom step step) for (x in r.left until r.right step step) counts.merge(px[x, y] or OPAQUE, 1, Int::plus)
        return counts.maxByOrNull { it.value }?.key ?: OPAQUE
    }

    /** The modal colour of a [ring]-pixel band just outside [inner]: a control's local ground. */
    fun ringColour(px: Pixels, inner: PxRect, ring: Int = 3): Int {
        val counts = HashMap<Int, Int>()
        val o = PxRect(inner.left - ring, inner.top - ring, inner.right + ring, inner.bottom + ring).clampTo(px)
        for (y in o.top until o.bottom) for (x in o.left until o.right) {
            if (x >= inner.left && x < inner.right && y >= inner.top && y < inner.bottom) continue
            counts.merge(px[x, y] or OPAQUE, 1, Int::plus)
        }
        return counts.maxByOrNull { it.value }?.key ?: groundColour(px, inner)
    }

    /** WCAG 2.x contrast ratio of two opaque sRGB colours. */
    fun wcagRatio(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val la = lum(a); val lb = lum(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun lin(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

    /** sRGB (D65) to CIE L*a*b*. */
    fun lab(argb: Int): DoubleArray {
        val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val b = lin(argb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /** OKLCH chroma of an sRGB colour (Ottosson 2020). */
    fun chroma(argb: Int): Double {
        val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val b = lin(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return hypot(1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s, 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    /** CIEDE2000 (Sharma, Wu, Dalal 2005), kL = kC = kH = 1. */
    fun deltaE2000(p: DoubleArray, q: DoubleArray): Double {
        val (l1, a1, b1) = Triple(p[0], p[1], p[2]); val (l2, a2, b2) = Triple(q[0], q[1], q[2])
        val c7 = ((hypot(a1, b1) + hypot(a2, b2)) / 2).pow(7)
        val g = 0.5 * (1 - sqrt(c7 / (c7 + 25.0.pow(7))))
        val a1p = (1 + g) * a1; val a2p = (1 + g) * a2
        val c1p = hypot(a1p, b1); val c2p = hypot(a2p, b2)
        fun hue(b: Double, a: Double) = if (a == 0.0 && b == 0.0) 0.0 else Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
        val h1p = hue(b1, a1p); val h2p = hue(b2, a2p)
        val dLp = l2 - l1; val dCp = c2p - c1p
        val dhp = if (c1p * c2p == 0.0) 0.0 else (h2p - h1p).let { if (it > 180) it - 360 else if (it < -180) it + 360 else it }
        val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))
        val lbp = (l1 + l2) / 2; val cbp = (c1p + c2p) / 2
        val hbp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) > 180 -> if (h1p + h2p < 360) (h1p + h2p + 360) / 2 else (h1p + h2p - 360) / 2
            else -> (h1p + h2p) / 2
        }
        fun cosd(d: Double) = cos(Math.toRadians(d))
        val t = 1 - 0.17 * cosd(hbp - 30) + 0.24 * cosd(2 * hbp) + 0.32 * cosd(3 * hbp + 6) - 0.20 * cosd(4 * hbp - 63)
        val dTheta = 30 * exp(-((hbp - 275) / 25).pow(2))
        val cb7 = cbp.pow(7)
        val rc = 2 * sqrt(cb7 / (cb7 + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lbp - 50).pow(2) / sqrt(20 + (lbp - 50).pow(2))
        val sc = 1 + 0.045 * cbp
        val sh = 1 + 0.015 * cbp * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt((dLp / sl).pow(2) + (dCp / sc).pow(2) + (dHp / sh).pow(2) + rt * (dCp / sc) * (dHp / sh))
    }

    /** ΔE2000 from one fixed colour, memoised: a screen has few distinct colours. */
    class DeltaCache(from: Int) {
        private val ref = lab(from or OPAQUE)
        private val memo = HashMap<Int, Double>()
        fun distance(c: Int): Double = memo.getOrPut(c or OPAQUE) { deltaE2000(ref, lab(c or OPAQUE)) }
    }
}
