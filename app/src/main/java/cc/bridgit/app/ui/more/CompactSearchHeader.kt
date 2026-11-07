package cc.bridgit.app.ui.more

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible

/** A short visual search field keeps a full-sized clear action, including outside its visible outline. */
class CompactSearchHeader @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private var clearGesture: TouchDelegate? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearGesture = null
            val clear = findViewById<View>(com.google.android.material.R.id.text_input_end_icon)
            if (clear != null && clear.isVisible && clear.isEnabled && clear.width > 0) {
                val bounds = Rect()
                clear.getDrawingRect(bounds)
                offsetDescendantRectToMyCoords(clear, bounds)
                val minimum = (48 * resources.displayMetrics.density).toInt()
                bounds.inset(-((minimum - bounds.width()) / 2).coerceAtLeast(0), -((minimum - bounds.height()) / 2).coerceAtLeast(0))
                if (bounds.contains(event.x.toInt(), event.y.toInt())) clearGesture = TouchDelegate(bounds, clear)
            }
        }
        val delegate = clearGesture ?: return super.dispatchTouchEvent(event)
        val handled = delegate.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) clearGesture = null
        return handled
    }
}
