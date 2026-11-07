package cc.bridgit.app.ui.more

import android.view.MotionEvent
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompactSearchHeaderTest {
    @Test fun aTapOutsideTheVisibleClearIconStillClearsThroughItsExpandedTarget() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val context = activity
        val header = CompactSearchHeader(context)
        val clear = Button(context).apply { id = com.google.android.material.R.id.text_input_end_icon }
        var clicks = 0
        clear.setOnClickListener { clicks++ }
        header.addView(clear)
        activity.setContentView(header)
        header.layout(0, 0, 400, 100)
        val density = context.resources.displayMetrics.density
        val size = (32 * density).toInt()
        clear.layout(200, 20, 200 + size, 20 + size)
        val x = 200 - 4 * density
        val y = 20 + size / 2f
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0)
        try { assertTrue(header.dispatchTouchEvent(down)); assertTrue(header.dispatchTouchEvent(up)); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); assertEquals(1, clicks) }
        finally { down.recycle(); up.recycle() }
    }
}
