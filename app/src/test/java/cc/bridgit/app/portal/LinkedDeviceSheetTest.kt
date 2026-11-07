package cc.bridgit.app.portal

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cc.bridgit.app.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The device sheet on Linked devices (owner, 5 Nov 2026): the iOS Portal's device view with View in place of Attach.
 * "Live" only while the device is actually connected; offline says when it was last seen; unknown facts are left out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkedDeviceSheetTest {
    private fun open(kind: String): Pair<PortalActivity, List<String>> {
        val a = Robolectric.buildActivity(PortalActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), PortalActivity::class.java).putExtra(PortalActivity.EXTRA_SAMPLE, kind)).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val d = ShadowDialog.getLatestDialog() as BottomSheetDialog
        return a to texts(d.window!!.decorView)
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun anOfflinePortalIsNotLive() {
        val (a, shown) = open("viewer")
        assertFalse(shown.contains(a.getString(R.string.linked_status_live)))
        assertTrue(shown.any { it.startsWith("Offline · ") && it.contains("2 hours ago") })
        assertTrue(shown.contains(a.getString(R.string.linked_conn_portal_off)))
        assertTrue(shown.contains(a.getString(R.string.portal_disconnect)))
        assertFalse("no Home to view on a portal", shown.contains(a.getString(R.string.linked_view)))
        assertFalse("nothing known, so no Where", shown.contains(a.getString(R.string.linked_where)))
    }

    @Test fun aLivePortalSaysLive() {
        val (a, shown) = open("viewer_live")
        assertTrue(shown.contains(a.getString(R.string.linked_status_live)))
        assertTrue(shown.contains(a.getString(R.string.linked_conn_portal_live)))
    }

    @Test fun thisPhoneMirrorsTheIosSheetWithView() {
        val (a, shown) = open("this")
        assertTrue(shown.contains("Google Pixel 2 XL · Android 11 · Bridgit 1.8.2943"))
        assertTrue(shown.contains("Hub for 3 satellites (1 view-only)."))
        listOf(R.string.linked_where, R.string.linked_services, R.string.linked_view).forEach { assertTrue(shown.contains(a.getString(it))) }
        assertTrue(shown.contains("21 of 22")); assertTrue(shown.contains(a.getString(R.string.linked_tile_features)))
        assertTrue(shown.contains("192.168.1.50/24"))
    }

    @Test fun aHubThatIsBothSaysSatelliteOnce() {
        val (a, shown) = open("hub")
        assertTrue(shown.contains(a.getString(R.string.linked_hub_satellite, "phone")) || shown.any { it.endsWith("is its satellite.") })
        assertFalse(shown.any { it.contains("view-only satellite") })
        assertTrue(shown.contains("Uses its 4 photo applets and 1 portal."))
        assertFalse("applets unknown for a hub", shown.contains(a.getString(R.string.linked_tile_applets)))
    }

    @Test fun aStaleHubOffersPairAgainNotView() {
        val (a, shown) = open("hub_stale")
        assertTrue(shown.contains(a.getString(R.string.linked_pair_again)))
        assertFalse(shown.contains(a.getString(R.string.linked_view)))
        assertTrue(shown.any { it.startsWith("Offline · ") })
    }

    @Test fun aSatelliteKeepsItsControls() {
        val (a, shown) = open("satellite")
        assertTrue(shown.contains(a.getString(R.string.satellite_agents)))
        assertTrue(shown.contains(a.getString(R.string.satellite_check)))
        assertTrue(shown.contains(a.getString(R.string.satellite_remove)))
        assertEquals(1, shown.count { it == a.getString(R.string.linked_conn_tailscale) })
    }
}
