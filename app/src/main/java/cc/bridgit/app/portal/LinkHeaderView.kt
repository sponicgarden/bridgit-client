package cc.bridgit.app.portal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import cc.bridgit.app.R
import cc.bridgit.app.link.LinkDiagram
import cc.bridgit.app.link.LinkDiagram.Arrow
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.R as M

/**
 * A0149: the top of a link's detail sheet. This device on the left, the other on the right (a device icon and its name
 * each), and between them one arrow per direction. An arrow says by itself whether the direction is on (solid green,
 * check), paused (dashed, pause), waiting for a permission (dashed amber, hourglass) or off (faint, cross).
 * Spec: docs/design/linked-device-sheet.md.
 */
class LinkHeaderView(ctx: Context, private val model: LinkDiagram.Model) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(14) * 1f; textAlign = Paint.Align.CENTER; color = ctx.themeColor(M.attr.colorOnSurface) }
    private val box = RectF()

    init {
        contentDescription = describe(ctx, model)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), ctx().dp(150))
    private fun ctx() = context

    override fun onDraw(c: Canvas) {
        val ctx = context
        val w = width.toFloat(); val side = ctx.dp(64).toFloat(); val top = ctx.dp(8).toFloat()
        val leftX = side / 2 + ctx.dp(8); val rightX = w - side / 2 - ctx.dp(8)
        endpoint(c, ctx, leftX, top, side, model.thisName)
        endpoint(c, ctx, rightX, top, side, model.farName)
        val from = leftX + side / 2 + ctx.dp(8); val to = rightX - side / 2 - ctx.dp(8)
        arrow(c, ctx, from, to, top + side / 2 - ctx.dp(15), model.out.arrow, toRight = true)
        arrow(c, ctx, from, to, top + side / 2 + ctx.dp(15), model.inn.arrow, toRight = false)
    }

    private fun endpoint(c: Canvas, ctx: Context, cx: Float, top: Float, side: Float, name: String) {
        box.set(cx - side / 2, top, cx + side / 2, top + side)
        fill.style = Paint.Style.FILL; fill.color = ctx.themeColor(M.attr.colorSurfaceContainerHigh)
        c.drawRoundRect(box, ctx.dp(16).toFloat(), ctx.dp(16).toFloat(), fill)
        icon(c, ctx, R.drawable.ic_nav_device, cx, top + side / 2, ctx.dp(34), ctx.themeColor(M.attr.colorPrimary))
        val max = (width / 2f - ctx.dp(12))
        c.drawText(TextUtils.ellipsize(name, label, max, TextUtils.TruncateAt.END).toString(), cx, top + side + ctx.dp(22), label)
    }

    private fun colour(ctx: Context, a: Arrow) = ctx.themeColor(when (a) {
        Arrow.ON -> R.attr.colorSuccess; Arrow.WAITING -> R.attr.colorWarning; Arrow.PAUSED -> M.attr.colorOnSurfaceVariant; Arrow.OFF -> M.attr.colorOutlineVariant })

    private fun arrow(c: Canvas, ctx: Context, from: Float, to: Float, y: Float, a: Arrow, toRight: Boolean) {
        val col = colour(ctx, a)
        line.color = col; fill.color = col; fill.style = Paint.Style.FILL
        line.pathEffect = if (a == Arrow.PAUSED || a == Arrow.WAITING) DashPathEffect(floatArrayOf(ctx.dp(7).toFloat(), ctx.dp(6).toFloat()), 0f) else null
        val head = ctx.dp(9).toFloat()
        val tip = if (toRight) to else from; val tail = if (toRight) from else to; val dir = if (toRight) -1 else 1
        c.drawLine(tail, y, tip + dir * head * 0.6f, y, line)
        c.drawPath(Path().apply { moveTo(tip, y); lineTo(tip + dir * head, y - head * 0.75f); lineTo(tip + dir * head, y + head * 0.75f); close() }, fill)
        // The state mark sits on the arrow's middle, on a surface disc so the line doesn't run through it.
        val mx = (from + to) / 2
        fill.color = ctx.themeColor(M.attr.colorSurface); c.drawCircle(mx, y, ctx.dp(11).toFloat(), fill)
        icon(c, ctx, mark(a), mx, y, ctx.dp(16), col)
    }

    private fun icon(c: Canvas, ctx: Context, res: Int, cx: Float, cy: Float, size: Int, tint: Int) {
        val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: return
        d.setTint(tint); val h = size / 2
        d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt()); d.draw(c)
    }

    companion object {
        fun mark(a: Arrow) = when (a) {
            Arrow.ON -> R.drawable.ic_check; Arrow.PAUSED -> R.drawable.agents_ic_pause
            Arrow.WAITING -> R.drawable.agentact_ic_hourglass; Arrow.OFF -> R.drawable.ic_close }

        fun stateWord(ctx: Context, a: Arrow) = ctx.getString(when (a) {
            Arrow.ON -> R.string.link_state_on; Arrow.PAUSED -> R.string.link_state_paused
            Arrow.WAITING -> R.string.link_state_waiting; Arrow.OFF -> R.string.link_state_off })

        fun serviceIcon(s: String) = when (s) {
            "camera" -> R.drawable.ic_camera; "mic" -> R.drawable.ic_mic; "speaker" -> R.drawable.agentact_ic_speaker
            "interactions" -> R.drawable.agentact_ic_chat; "location" -> R.drawable.ic_place; "gallery" -> R.drawable.ic_photo
            "sounds" -> R.drawable.ic_notif; "view" -> R.drawable.ic_eye; else -> R.drawable.ic_toggle }

        fun serviceWord(ctx: Context, s: String) = ctx.getString(when (s) {
            "camera" -> R.string.link_service_camera; "mic" -> R.string.link_service_mic; "speaker" -> R.string.link_service_speaker
            "interactions" -> R.string.link_service_interactions; "location" -> R.string.link_service_location; "gallery" -> R.string.link_service_gallery
            "sounds" -> R.string.link_service_sounds; else -> R.string.link_service_view })

        private fun describe(ctx: Context, m: LinkDiagram.Model): String {
            fun dir(from: String, to: String, d: LinkDiagram.Direction) =
                ctx.getString(R.string.link_diagram_a11y, from, to, stateWord(ctx, d.arrow) + d.services.joinToString("") { ", " + serviceWord(ctx, it) })
            return dir(m.thisName, m.farName, m.out) + ". " + dir(m.farName, m.thisName, m.inn)
        }

        /**
         * The details under the diagram, one row per direction that is not off: its arrow, an icon chip per service
         * and the state mark. Null when both directions are off.
         */
        fun details(ctx: Context, m: LinkDiagram.Model): View? {
            val rows = listOf(m.out to true, m.inn to false).filter { it.first.arrow != Arrow.OFF }
            if (rows.isEmpty()) return null
            val onVar = ctx.themeColor(M.attr.colorOnSurfaceVariant)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
                background = GradientDrawable().apply { setColor(ctx.themeColor(M.attr.colorSurfaceContainerLow)); cornerRadius = ctx.dp(16).toFloat()
                    setStroke(ctx.dp(1), ctx.themeColor(M.attr.colorOutlineVariant)) }
                rows.forEach { (d, outward) ->
                    val col = ctx.themeColor(when (d.arrow) { Arrow.ON -> R.attr.colorSuccess; Arrow.WAITING -> R.attr.colorWarning; else -> M.attr.colorOnSurfaceVariant })
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ctx.dp(48)
                        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(col)
                            scaleX = if (outward) 1f else -1f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
                        addView(ChipGroup(ctx).apply { chipSpacingVertical = 0; chipSpacingHorizontal = ctx.dp(6)
                            d.services.forEach { s -> addView(Chip(ctx).apply {
                                text = serviceWord(ctx, s); isClickable = false; isCheckable = false
                                setChipIconResource(serviceIcon(s)); chipIconTint = ColorStateList.valueOf(onVar); isChipIconVisible = true }) } },
                            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(8); marginEnd = ctx.dp(8) })
                        addView(ImageView(ctx).apply { setImageResource(mark(d.arrow)); imageTintList = ColorStateList.valueOf(col)
                            contentDescription = stateWord(ctx, d.arrow) }, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
    }
}
