package cc.bridgit.app.lan

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import cc.bridgit.app.R
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NetworkDevicesLayoutTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun invoke(fragment: NetworkDevicesFragment, name: String) {
        fragment.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(fragment)
    }

    @Test fun countGraphicAndAlignedLabelsFitNarrowAndTabletScreens() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.Theme_Bridgit)
        val activity = controller.setup().get()
        val fragment = NetworkDevicesFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val devices = listOf(true, false).mapIndexed { i, connected ->
            NetworkDevices.Device("192.168.1.${85 + i}", "Tapo C210", listOf("tapo_camera"), true, connected = connected, lastSeen = System.currentTimeMillis() - if (connected) 0 else 300_000)
        }
        fragment.javaClass.getDeclaredField("displayedDevices").apply { isAccessible = true }.set(fragment, devices)
        invoke(fragment, "updateConnectionOverview")
        invoke(fragment, "renderGroups")
        val root = fragment.requireView()
        for (width in listOf(320, 412, 800)) {
            val density = activity.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((900 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val views = descendants(root)
            val texts = views.filterIsInstance<TextView>()
            val status = texts.first { it.text.toString() == "Just now" && it.compoundDrawablesRelative[0] != null }
            val integration = texts.first { it.text.toString() == "Bridgit interfaced" }
            assertEquals("Equal icon/text start at $width dp", status.left + status.totalPaddingLeft,
                integration.left + integration.totalPaddingLeft)
            assertEquals(status.width, integration.width)
            assertNotNull(status.compoundDrawablesRelative[0])
            val offline = texts.first { it.text.toString() == "5 min ago" && it.compoundDrawablesRelative[0] != null }
            val offlineInterface = texts.last { it.text.toString() == "Bridgit interfaced" }
            assertEquals(offline.currentTextColor, offlineInterface.currentTextColor)
            val button = texts.first { it.text.toString() == "Run Network Diagnostics" }
            assertTrue("No button text truncation at $width dp", (0 until button.layout.lineCount).all { button.layout.getEllipsisCount(it) == 0 })
            assertTrue(button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            val bar = views.filterIsInstance<LinearProgressIndicator>().first { !it.isIndeterminate }
            assertEquals("No decorative marker implying connected devices", 0, bar.trackStopIndicatorSize)
            assertEquals(2, bar.max)
            assertEquals(1, bar.progress)
            assertTrue(views.any { it.contentDescription?.toString() == "2 devices, 1 connected, 1 unconnected" })
            assertFalse(texts.any { it.text.contains("Category counts") })
        }
        controller.pause().stop().destroy()
    }

    @Test fun coverageShowsEveryCategoryAndClosesWithTopLeftButton() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.Theme_Bridgit)
        val activity = controller.setup().get()
        val fragment = NetworkDevicesFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        invoke(fragment, "showCoverage")
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        val views = descendants(dialog.window!!.decorView)
        val texts = views.filterIsInstance<TextView>().map { it.text.toString() }
        DiscoveryCatalog.mdns.map { it.group }.distinct().forEach { assertTrue(it, it in texts) }
        assertTrue("Other discovery" in texts)
        assertFalse("OK" in texts)
        views.single { it.contentDescription?.toString() == "Close discovery coverage" }.performClick()
        assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }
}
