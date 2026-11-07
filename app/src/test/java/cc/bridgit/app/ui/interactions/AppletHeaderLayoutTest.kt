package cc.bridgit.app.ui.interactions

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FragmentAppletConfigBinding
import cc.bridgit.app.databinding.FragmentInteractionsBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h874dp")
class AppletHeaderLayoutTest {
    @Test fun profileAndTabsShareCenterAndCreateFitsBesideInstalledAtLargeText() {
        for (scale in listOf(1f, 1.15f, 1.3f)) {
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            controller.get().setTheme(R.style.Theme_Bridgit)
            controller.setup()
            try {
                val activity = controller.get()
                val density = activity.resources.displayMetrics.density
                fun layout(v: View) {
                    v.measure(View.MeasureSpec.makeMeasureSpec((393 * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((874 * density).toInt(), View.MeasureSpec.EXACTLY))
                    v.layout(0, 0, v.measuredWidth, v.measuredHeight)
                }
                val header = FragmentInteractionsBinding.inflate(activity.layoutInflater)
                header.toolbar.inflateMenu(R.menu.applets_portal_menu)
                header.toolbar.menu.findItem(R.id.portal_menu_account).isVisible = true
                layout(header.root)
                assertEquals(header.tabs.top + header.tabs.height / 2, header.toolbar.top + header.toolbar.height / 2)
                assertTrue(header.tabs.right <= header.toolbar.left)
                val config = FragmentAppletConfigBinding.inflate(activity.layoutInflater)
                layout(config.root)
                val button = config.createApplet
                val heading = (button.parent as android.view.ViewGroup).getChildAt(0)
                assertEquals(heading.parent, button.parent)
                assertTrue("Installed has room at font scale $scale", heading.width > 60 * density)
                assertTrue("Create stays beside Installed at font scale $scale", heading.right <= button.left)
                assertTrue(button.width < 220 * density)
                assertTrue(button.height >= 48 * density)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
