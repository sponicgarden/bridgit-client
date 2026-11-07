package cc.bridgit.app.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Lays its children in a row from the start edge and moves a whole child to the next line when it doesn't fit
 * (buttons at a large font scale or on a narrow screen never shrink or overlap). Mirrors in RTL.
 */
class FlowRow(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    var alignLastChildToEnd = false

    constructor(context: Context, attrs: android.util.AttributeSet?) : this(
        context, (8 * context.resources.displayMetrics.density).toInt(),
        (4 * context.resources.displayMetrics.density).toInt())
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0; var y = 0; var lineH = 0; var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(if (unbounded) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && !unbounded && x + c.measuredWidth > maxW) { y += lineH + vGap; x = 0; lineH = 0 }
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
            widest = maxOf(widest, x - hGap)
        }
        val h = y + lineH + paddingTop + paddingBottom
        setMeasuredDimension(if (unbounded) widest + paddingLeft + paddingRight else MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = 0; var y = paddingTop; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { y += lineH + vGap; x = 0; lineH = 0 }
            if (alignLastChildToEnd && i == childCount - 1) x = maxW - c.measuredWidth
            val left = if (rtl) (r - l) - paddingRight - x - c.measuredWidth else paddingLeft + x
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}
