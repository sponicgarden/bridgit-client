package cc.bridgit.app.lan

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Wrap service pills to the available width without overlapping or horizontal scrolling. */
class ServicePills(context: Context) : ViewGroup(context) {
    private val gap = (6 * resources.displayMetrics.density).toInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0; var y = 0; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > width) { y += rowHeight + gap; x = 0; rowHeight = 0 }
            x += child.measuredWidth + gap; rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(width, resolveSize(y + rowHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        var x = 0; var y = 0; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > width) { y += rowHeight + gap; x = 0; rowHeight = 0 }
            val start = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - x - child.measuredWidth else x
            child.layout(start, y, start + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap; rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
