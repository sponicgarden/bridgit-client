package cc.bridgit.app.ui.common

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs

/**
 * Text whose widest line is drawn exactly as wide as the view: the text size is scaled to the width the layout gives
 * it, and textScaleX closes the last fraction of a pixel. Lines split only at "\n", never by wrapping. Used for the
 * independently fitted tagline lines under the "bridgit" wordmark in the update box.
 * Give it an exact layout_width.
 */
class FitWidthTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        // No soft wrapping (a line that fits to the pixel must not wrap on rounding), but "\n" still breaks lines.
        setHorizontallyScrolling(true)
        includeFontPadding = false
        // Unhinted, fractional advances: text width then scales linearly with size, so the fit is exact at any density.
        paintFlags = paintFlags or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            val target = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
            val content = text?.toString().orEmpty()
            maxLines = content.count { it == '\n' } + 1
            val fit = fitText(paint, content, target.toFloat())
            if (fit != null) {
                if (abs(fit.first - textSize) > 0.01f) setTextSize(TypedValue.COMPLEX_UNIT_PX, fit.first)
                if (abs(fit.second - textScaleX) > 0.0001f) textScaleX = fit.second
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    companion object {
        /** Text size (px) and textScaleX at which the widest line of [text] drawn with [paint] is exactly [width] px wide, or null. */
        fun fitText(paint: Paint, text: String, width: Float): Pair<Float, Float>? {
            if (text.isEmpty() || width <= 0f) return null
            val p = Paint(paint).apply { textScaleX = 1f }
            val lines = text.split('\n')
            val widest = lines.maxByOrNull { p.measureText(it) } ?: return null
            val natural = p.measureText(widest)
            if (natural <= 0f) return null
            val size = p.textSize * width / natural
            p.textSize = size
            val measured = p.measureText(widest)
            return size to (if (measured > 0f) width / measured else 1f)
        }
    }
}
