package cc.bridgit.app.update

import android.view.View
import android.view.ViewGroup
import android.graphics.Rect
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import cc.bridgit.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateDialogLayoutTest {
    @Test fun longNotesScrollWithoutSqueezingVersionOrHidingActions() {
        for (scale in listOf(1f, 1.15f, 1.3f)) {
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            controller.get().setTheme(R.style.Theme_Bridgit)
            controller.setup()
            try {
                val activity = controller.get()
                val builder = MaterialAlertDialogBuilder(activity)
                val root = activity.layoutInflater.cloneInContext(builder.context).inflate(R.layout.update_card, null, false)
                root.findViewById<TextView>(R.id.update_heading).text = "Update available"
                root.findViewById<TextView>(R.id.update_text).text = "What's new: " + "Readable update details. ".repeat(80)
                val version = root.findViewById<TextView>(R.id.update_version).apply { text = "1.8.3256 → 1.8.3260" }
                root.findViewById<TextView>(R.id.update_hint).apply { text = "159 MB"; visibility = View.VISIBLE }
                val dialog = builder.setView(root).setPositiveButton("Download", null)
                    .setNegativeButton("Not now", null).setNeutralButton("Don't show again", null).create()
                dialog.show()
                try {
                    val d = activity.resources.displayMetrics.density
                    val decor = requireNotNull(dialog.window).decorView
                    val width = (393*d).toInt(); val maxHeight = (660*d).toInt()
                    decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
                    decor.layout(0,0,decor.measuredWidth,decor.measuredHeight)
                    val scroll = root as NestedScrollView
                    assertTrue("Long notes scroll at $scale: decor=${decor.height}, scroll=${scroll.height}, child=${scroll.getChildAt(0).height}, text=${root.findViewById<TextView>(R.id.update_text).height}", scroll.canScrollVertically(1))
                    assertTrue("Version retains its height", version.height >= version.lineHeight)
                    assertTrue(decor.height <= maxHeight)
                    val content = Rect(0, 0, scroll.width, scroll.height)
                    (decor as ViewGroup).offsetDescendantRectToMyCoords(scroll, content)
                    for (which in listOf(-1, -2, -3)) {
                        val button = dialog.getButton(which)
                        val position = Rect(0, 0, button.width, button.height)
                        decor.offsetDescendantRectToMyCoords(button, position)
                        assertTrue("Action remains below content at $scale", position.top >= content.bottom)
                        assertTrue("Action remains on screen at $scale", position.bottom <= decor.height)
                    }
                    scroll.fullScroll(View.FOCUS_DOWN)
                    assertTrue("Can reach the size and version at $scale", scroll.scrollY > 0)
                } finally { dialog.dismiss() }
            } finally { controller.pause().stop().destroy() }
        }
    }
}
