package cc.bridgit.app.ui.applets

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import cc.bridgit.app.R
import cc.bridgit.app.databinding.ItemAgentAppBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp")
class LibraryLayoutTest {
    @Test fun runningBadgeFollowsTheNameAndWrapsWithoutOverlap() {
        val context = ContextThemeWrapper(org.robolectric.RuntimeEnvironment.getApplication(), R.style.Theme_Bridgit)
        val screen = cc.bridgit.app.databinding.FragmentAppletConfigBinding.inflate(LayoutInflater.from(context))
        val badge = screen.dogMonitorRunning
        badge.visibility = View.VISIBLE
        val line = badge.parent as android.view.ViewGroup
        val name = line.getChildAt(0) as android.widget.TextView
        fun measure(widthDp: Int) {
            val width = (widthDp * context.resources.displayMetrics.density).toInt()
            line.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            line.layout(0, 0, width, line.measuredHeight)
        }
        measure(480)
        assertEquals(name.top, badge.top)
        assertTrue(badge.left > name.right)
        name.textSize *= 2
        badge.textSize *= 2
        measure(240)
        assertTrue(badge.top >= name.bottom)
        assertTrue(badge.right <= line.width)
        assertTrue(badge.height >= badge.layout.height)
    }

    @Test fun largeTextPreservesHomeDeviceAndVersionWithOneTrailingAction() {
        val context = ContextThemeWrapper(org.robolectric.RuntimeEnvironment.getApplication(), R.style.Theme_Bridgit)
        val row = ItemAgentAppBinding.inflate(LayoutInflater.from(context))
        row.agentAppTitle.text = "Insurance inventory for the whole household"
        row.agentAppToday.text = "Last activity: 5 minutes ago"
        row.agentAppHome.text = "Home: Google Pixel 2 XL"
        row.agentAppMeta.text = "Version 2.0.1"
        for (v in listOf(row.agentAppTitle, row.agentAppToday, row.agentAppHome, row.agentAppMeta)) v.textSize = 24f
        val width = (328 * context.resources.displayMetrics.density).toInt()
        row.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.root.layout(0, 0, width, row.root.measuredHeight)
        val column = row.agentAppTitle.parent as View
        assertEquals(row.agentAppContent, row.agentAppAction.parent)
        assertTrue(column.right <= row.agentAppAction.left)
        assertEquals(row.agentAppHome.parent, row.agentAppMeta.parent)
        assertTrue(row.agentAppMeta.top >= row.agentAppHome.bottom)
        assertTrue(row.agentAppHome.height >= row.agentAppHome.layout.height)
        assertTrue(row.agentAppAction.width >= (48 * context.resources.displayMetrics.density).toInt())
        assertTrue(row.agentAppTitle.height >= row.agentAppTitle.layout.height)
        assertTrue(row.agentAppMeta.height >= row.agentAppMeta.layout.height)
        assertTrue(row.agentAppToday.height >= row.agentAppToday.layout.height)
    }
}
