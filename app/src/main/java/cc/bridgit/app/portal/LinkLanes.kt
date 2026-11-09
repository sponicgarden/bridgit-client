package cc.bridgit.app.portal

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.Drawable
import cc.bridgit.app.link.LinkModel.State
import kotlin.math.hypot

/**
 * A0243: how one direction of a link is drawn on the Linked devices map, so both halves of the double opt-in read at a
 * glance. Each direction is its own arrow, from the device that uses to the device that is used:
 * - on: solid green, filled head;
 * - paused: solid grey, filled head, a pause badge;
 * - waiting: amber, solid on the half of the device that said yes and dashed on the half still to answer, an open
 *   head and a clock badge;
 * - off: a faint dotted line, no head.
 * The map and its legend share this, so the legend's swatches are the map's own arrows.
 */
class LinkLanes(private val dark: Boolean, private val dp: Float) {
    val on = if (dark) 0xFF4FE0A0.toInt() else 0xFF006C46.toInt()
    val waiting = if (dark) 0xFFFFC266.toInt() else 0xFF8A5300.toInt()
    val paused = if (dark) 0xFF9AA7C0.toInt() else 0xFF465268.toInt()
    val off = if (dark) 0xFF3A4560.toInt() else 0xFF8F99AB.toInt()
    private val field = if (dark) 0xFF070B14.toInt() else 0xFFF8F9FF.toInt()

    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dashed = Paint(solid).apply { pathEffect = DashPathEffect(floatArrayOf(4f * dp, 4f * dp), 0f) }
    private val dotted = Paint(solid).apply { pathEffect = DashPathEffect(floatArrayOf(0.1f, 5f * dp), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun colour(s: State) = when (s) {
        State.ON -> on
        State.PAUSED -> paused
        State.WAITING_FOR_TARGET, State.WAITING_FOR_USER -> waiting
        State.OFF -> off
    }

    /**
     * Draws [pts] (tail = the using device, head = the used one) in [s]'s style. [badgeAt]: where along the lane (0..1)
     * the pause or clock badge sits, so the two directions' badges don't overlap.
     */
    fun draw(c: Canvas, pts: List<PointF>, s: State, badgeAt: Float = 0.5f, badges: Boolean = true) {
        if (pts.size < 2) return
        val col = colour(s)
        if (s == State.OFF) {
            dotted.color = col; dotted.strokeWidth = 1.6f * dp
            c.drawPath(path(pts), dotted); return
        }
        val head = HEAD * dp
        val body = trimEnd(pts, head * 0.7f)
        val mid = body.size / 2
        val tailHalf = body.subList(0, mid + 1); val headHalf = body.subList(mid, body.size)
        val w = if (s == State.ON) 2.6f * dp else 2.2f * dp
        solid.color = col; solid.strokeWidth = w; dashed.color = col; dashed.strokeWidth = w
        when (s) {
            // The device that said yes keeps its half solid; the half still to answer is dashed.
            State.WAITING_FOR_TARGET -> { c.drawPath(path(tailHalf), solid); c.drawPath(path(headHalf), dashed) }
            State.WAITING_FOR_USER -> { c.drawPath(path(tailHalf), dashed); c.drawPath(path(headHalf), solid) }
            else -> c.drawPath(path(body), solid)
        }
        arrowHead(c, pts, col, filled = s == State.ON || s == State.PAUSED)
        if (badges && s != State.ON) badge(c, at(pts, badgeAt), s, col)
    }

    private fun arrowHead(c: Canvas, pts: List<PointF>, col: Int, filled: Boolean) {
        val tip = pts.last(); val from = at(pts, 1f - 0.001f, back = HEAD * dp)
        val dx = tip.x - from.x; val dy = tip.y - from.y; val len = hypot(dx, dy).takeIf { it > 0 } ?: return
        val ux = dx / len; val uy = dy / len
        val l = HEAD * dp; val hw = 4.6f * dp
        val bx = tip.x - ux * l; val by = tip.y - uy * l
        val p = Path().apply { moveTo(tip.x, tip.y); lineTo(bx - uy * hw, by + ux * hw); lineTo(bx + uy * hw, by - ux * hw); close() }
        if (filled) { fill.color = col; c.drawPath(p, fill) }
        else { fill.color = field; c.drawPath(p, fill); solid.color = col; solid.strokeWidth = 1.6f * dp; c.drawPath(p, solid) }
    }

    /** A small disc on the lane: two bars for paused, a clock for waiting. */
    private fun badge(c: Canvas, p: PointF, s: State, col: Int) {
        val r = 7f * dp
        fill.color = field; c.drawCircle(p.x, p.y, r, fill)
        solid.color = col; solid.strokeWidth = 1.5f * dp; c.drawCircle(p.x, p.y, r, solid)
        fill.color = col
        if (s == State.PAUSED) {
            c.drawRoundRect(p.x - 2.8f * dp, p.y - 3.2f * dp, p.x - 1f * dp, p.y + 3.2f * dp, 0.8f * dp, 0.8f * dp, fill)
            c.drawRoundRect(p.x + 1f * dp, p.y - 3.2f * dp, p.x + 2.8f * dp, p.y + 3.2f * dp, 0.8f * dp, 0.8f * dp, fill)
        } else {
            solid.strokeWidth = 1.5f * dp
            c.drawLine(p.x, p.y, p.x, p.y - 3.6f * dp, solid)
            c.drawLine(p.x, p.y, p.x + 2.8f * dp, p.y + 1.2f * dp, solid)
        }
    }

    companion object {
        const val HEAD = 7.5f

        fun path(pts: List<PointF>) = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }

        /** The point [t] (0..1) of the way along [pts], or [back] px before the end when given. */
        fun at(pts: List<PointF>, t: Float, back: Float? = null): PointF {
            val seg = (1 until pts.size).map { hypot(pts[it].x - pts[it - 1].x, pts[it].y - pts[it - 1].y) }
            val total = seg.sum()
            var want = if (back != null) (total - back).coerceAtLeast(0f) else total * t.coerceIn(0f, 1f)
            for (i in seg.indices) {
                if (want <= seg[i] || i == seg.lastIndex) {
                    val f = if (seg[i] == 0f) 0f else (want / seg[i]).coerceIn(0f, 1f)
                    return PointF(pts[i].x + (pts[i + 1].x - pts[i].x) * f, pts[i].y + (pts[i + 1].y - pts[i].y) * f)
                }
                want -= seg[i]
            }
            return pts.last()
        }

        /** [pts] stopped [by] px short of its end, so a stroke doesn't poke through the arrow's tip. */
        fun trimEnd(pts: List<PointF>, by: Float): List<PointF> {
            val end = at(pts, 1f, back = by)
            val seg = (1 until pts.size).map { hypot(pts[it].x - pts[it - 1].x, pts[it].y - pts[it - 1].y) }
            var keep = seg.sum() - by; val out = mutableListOf(pts[0])
            for (i in seg.indices) { if (keep <= seg[i]) break; keep -= seg[i]; out += pts[i + 1] }
            out += end
            return out
        }

        /**
         * The curve from [a] to [b] (leaving and arriving vertically, as the map's lines do), in [n] points, shifted
         * [offset] px to the right of the a→b direction: two lanes with offsets ±d run side by side.
         */
        fun lane(a: PointF, b: PointF, offset: Float, n: Int = 32): List<PointF> {
            val ym = (a.y + b.y) / 2
            fun p(t: Float): PointF {
                val u = 1 - t
                return PointF(u * u * u * a.x + 3 * u * u * t * a.x + 3 * u * t * t * b.x + t * t * t * b.x,
                    u * u * u * a.y + 3 * u * u * t * ym + 3 * u * t * t * ym + t * t * t * b.y)
            }
            val base = (0..n).map { p(it / n.toFloat()) }
            return base.mapIndexed { i, q ->
                val prev = base[maxOf(0, i - 1)]; val next = base[minOf(n, i + 1)]
                val tx = next.x - prev.x; val ty = next.y - prev.y; val len = hypot(tx, ty).takeIf { it > 0 } ?: 1f
                // Right of the direction of travel, in screen coordinates (y down): (-ty, tx).
                PointF(q.x - ty / len * offset, q.y + tx / len * offset)
            }
        }
    }

    /** The legend's sample of [s]: a short lane left to right. */
    class Swatch(private val lanes: LinkLanes, private val s: State, private val w: Int, private val h: Int) : Drawable() {
        override fun draw(c: Canvas) {
            val b = bounds; val y = b.exactCenterY()
            val pts = (0..8).map { PointF(b.left + 2f + (b.width() - 4f) * it / 8f, y) }
            lanes.draw(c, pts, s, badgeAt = 0.4f)
        }
        override fun getIntrinsicWidth() = w
        override fun getIntrinsicHeight() = h
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
