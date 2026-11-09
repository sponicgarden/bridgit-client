package cc.bridgit.app.ui.common

import android.graphics.Paint
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.robolectric.RuntimeEnvironment
import cc.bridgit.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tagline fitting and the update card's shared branding width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FitWidthTextViewTest {

    @Test
    fun fitTextMakesTheTextExactlyTheWidth() {
        val paint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply { textSize = 40f } // same flags as FitWidthTextView
        val (size, scale) = FitWidthTextView.fitText(paint, "Distributed sensorial applications — built by your favorite agent.", 300f)!!
        val p = Paint(paint).apply { textSize = size; textScaleX = scale }
        assertEquals(300f, p.measureText("Distributed sensorial applications — built by your favorite agent."), 0.5f)
    }

    @Test
    fun twoLineTextFitsTheWiderLine() {
        val paint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply { textSize = 40f }
        val (size, scale) = FitWidthTextView.fitText(paint, "Distributed sensorial applications —\nbuilt by your favorite agent.", 300f)!!
        val p = Paint(paint).apply { textSize = size; textScaleX = scale }
        val widths = listOf("Distributed sensorial applications —", "built by your favorite agent.").map { p.measureText(it) }
        assertEquals(300f, widths.max(), 0.5f)
        assertTrue(widths.all { it <= 300.5f })
    }
    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun updateLeadMatchesWordmarkWithoutClippingOnNarrowPhones() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Bridgit)
        val card = LayoutInflater.from(context).inflate(R.layout.update_card, null, false)
        val density = context.resources.displayMetrics.density
        card.measure(View.MeasureSpec.makeMeasureSpec((312 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val wordmark = card.findViewById<ImageView>(R.id.update_wordmark)
        val lead = card.findViewById<FitWidthTextView>(R.id.update_tagline)
        assertEquals(wordmark.measuredWidth, lead.measuredWidth)
        assertTrue(lead.typeface.isBold)
        assertEquals(lead.measuredWidth.toFloat(), lead.paint.measureText(lead.text.toString()), 0.5f)
        assertTrue(lead.measuredWidth + 104 * density <= card.measuredWidth - card.paddingLeft - card.paddingRight)
    }

}
