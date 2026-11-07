package cc.bridgit.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * A scroll view that grows with its content up to [maxHeightPx], then scrolls inside that height.
 * Used where a long list must not push the buttons under it off the screen.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : NestedScrollView(context, attrs, defStyle) {

    var maxHeightPx: Int = (180 * resources.displayMetrics.density).toInt()
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (mode == View.MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}
