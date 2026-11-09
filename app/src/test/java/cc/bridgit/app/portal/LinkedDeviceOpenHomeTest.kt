package cc.bridgit.app.portal

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.ui.MainActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import javax.crypto.KeyGenerator

/**
 * A0150: "Open its Home" on a linked device's sheet used to switch Home to that device and then just close Linked
 * devices, so the owner landed back on More. It now opens the Home tab with the device loaded (its name in the device
 * bar), and the same button on this phone's sheet switches straight back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkedDeviceOpenHomeTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val device = "a4e5f1c5-dd15-42c0"
    private val idle = { shadowOf(Looper.getMainLooper()).idle() }

    @Before fun pairOnePhone() {
        PortalLinks.keyOverride = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        PortalLinks(app).add(PortalData.pairLink("https://bridgit-relay.bridgit-app.workers.dev", device,
            "rly_" + "0123abcd-".repeat(8), "cracky", install = null, portal = true))
        app.settings.firstRunDone = true
    }

    @After fun clear() { PortalLinks(app).select(null); PortalLinks.keyOverride = null }

    private fun texts(v: View): List<View> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun MainActivity.destination() =
        (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController.currentDestination?.id

    /** The device bar above Home: visible, with the other device's name. */
    private fun MainActivity.deviceBarName(): String? {
        val bar = findViewById<ViewGroup>(R.id.root).getChildAt(0)
        if (!bar.isShown) return null
        return texts(bar).firstOrNull { it.isShown }?.let { (it as TextView).text.toString() }
    }

    @Test fun theSheetButtonSaysWhereItLeadsAndOpensTheHomeTab() {
        PortalLinks(app).select(device) // looking at cracky; this phone's sheet switches back
        val a = Robolectric.buildActivity(PortalActivity::class.java,
            Intent(app, PortalActivity::class.java).putExtra(PortalActivity.EXTRA_SAMPLE, "this")).setup().get()
        idle()
        val d = ShadowDialog.getLatestDialog() as BottomSheetDialog
        val open = texts(d.window!!.decorView).single { (it as TextView).text == "Open its Home" }
        open.performClick(); idle()
        assertNull("back on this phone", PortalLinks(app).selected.value)
        val next = shadowOf(a).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertEquals("home", next.getStringExtra("owner_setup_target"))
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue("Linked devices closes", a.isFinishing)
    }

    @Test fun fromMoreItLandsOnHomeShowingTheLinkedDevice() {
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java)).setup()
        val main = c.get()
        main.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.more; idle()
        assertEquals(R.id.more, main.destination())
        // What the hub sheet's button does: select the device, then bring the main screen forward.
        PortalLinks(app).select(device)
        c.newIntent(PortalActivity.homeIntent(app)); idle()
        assertEquals(R.id.home, main.destination())
        assertEquals("cracky", main.deviceBarName())
    }

    @Test fun aRestartedMainScreenAlsoOpensOnHome() {
        PortalLinks(app).select(device)
        val main = Robolectric.buildActivity(MainActivity::class.java, PortalActivity.homeIntent(app)).setup().get(); idle()
        assertEquals(R.id.home, main.destination())
        assertEquals("cracky", main.deviceBarName())
    }
}
