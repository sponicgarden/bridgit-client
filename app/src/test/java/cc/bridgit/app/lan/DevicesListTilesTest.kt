package cc.bridgit.app.lan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import cc.bridgit.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Devices tab List (owner, 4 Nov 2026): four summary tiles fit a narrow phone; Groups folds every group. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DevicesListTilesTest {
    class Parent : Fragment(), NetworkDevicesFragment.Host {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) = FrameLayout(requireContext()).apply { id = R.id.devices_list_container }
        override fun listHeader(): View? = null
        override fun listFooter(): View? = null
        override fun onScanStarted() {}
        override fun onScanProgress(found: List<NetworkDevices.Device>) {}
        override fun onScanFinished() {}
        override fun setUpCamera(host: String) {}
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun fourTilesFitAndGroupsFoldsEveryGroup() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.Theme_Bridgit)
        val activity = controller.setup().get()
        val parent = Parent()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, parent).commitNow()
        val list = NetworkDevicesFragment()
        parent.childFragmentManager.beginTransaction().add(R.id.devices_list_container, list).commitNow()
        val devices = listOf(
            NetworkDevices.Device("192.168.1.85", "Tapo C210", listOf("tapo_camera"), true),
            NetworkDevices.Device("192.168.1.98", "Denon Home 350", listOf("_sonos._tcp."), false, connected = false),
            NetworkDevices.Device("192.168.1.76", "HP LaserJet", listOf("_ipp._tcp."), false))
        list.javaClass.getDeclaredField("displayedDevices").apply { isAccessible = true }.set(list, devices)
        fun call(name: String) = list.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(list)
        call("updateConnectionOverview"); call("renderGroups")
        val root = list.requireView()
        val density = activity.resources.displayMetrics.density
        for (width in listOf(320, 412, 800)) {
            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((900 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val texts = descendants(root).filterIsInstance<TextView>()
            listOf("Devices", "Groups", "Connected", "Unconnected").forEach { name ->
                val tile = texts.single { it.text.toString() == name }
                assertEquals("$name on one line at $width dp", 1, tile.lineCount)
                assertEquals("$name not cut at $width dp", 0, tile.layout.getEllipsisCount(0))
                assertTrue("$name fits at $width dp", tile.paint.measureText(name) <= tile.width - tile.totalPaddingLeft - tile.totalPaddingRight + 1)
            }
            // Tiles in order: Devices, Groups, Connected, Unconnected.
            val xs = listOf("Devices", "Groups", "Connected", "Unconnected").map { n -> texts.single { it.text.toString() == n }.let { v -> IntArray(2).also { v.getLocationInWindow(it) }[0] } }
            assertEquals(xs.sorted(), xs)
        }
        assertFalse(descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() in setOf("Collapse all", "Expand all") })
        val groupsTile = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == "Groups" }.parent as View
        assertEquals("3", ((groupsTile as ViewGroup).getChildAt(0) as TextView).text.toString())
        groupsTile.performClick()
        val expanded = list.javaClass.getDeclaredField("expanded").apply { isAccessible = true }.get(list) as Map<*, *>
        assertTrue("every group folded", expanded.isNotEmpty() && expanded.values.all { it == false })
        assertTrue("Groups is the selected tab", groupsTile.isSelected)
        val devicesTile = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == "Devices" }.parent as View
        devicesTile.performClick()
        assertTrue("Devices opens every group", expanded.values.all { it == true })
        assertTrue(devicesTile.isSelected); assertFalse(groupsTile.isSelected)
        controller.pause().stop().destroy()
    }
}
