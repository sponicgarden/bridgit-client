package cc.bridgit.app.portal

import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.relay.RelayActivity
import cc.bridgit.app.relay.RelayErrorDialog
import cc.bridgit.app.relay.RelayStore
import org.junit.Assert.assertEquals
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
 * "Pair an iPhone or iPad" that fails says why in a pop-up. It used to write the reason at the top of the list,
 * off-screen under the button, so the tap looked like it did nothing (A9, 5 Nov 2026).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairDeviceErrorTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    @Test fun aFailedPairingShowsItsReasonInAPopUp() {
        RelayStore(app).base = "https://not-a-relay.invalid/d/someOtherPhone" // not this phone: turning the relay on fails at once
        val a = Robolectric.buildActivity(PairDeviceActivity::class.java).setup().get()
        val go = a.findViewById<android.view.View>(R.id.addDevice).findViewById<android.widget.Button>(R.id.go)
        go.performClick()
        val end = System.currentTimeMillis() + 5_000
        while (ShadowDialog.getLatestDialog() !is AlertDialog && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(d.isShowing)
        val msg = d.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertEquals(a.getString(R.string.relay_address_wrong_popup, RelayStore(app).device), msg)
        assertTrue("the button is usable again", go.isEnabled)
        assertEquals(a.getString(R.string.linkpair_show_code), go.text.toString())

        // The address is the problem, so the pop-up offers Relay settings, which opens More › Relay at Advanced.
        val settings = d.getButton(DialogInterface.BUTTON_POSITIVE)
        assertEquals(a.getString(R.string.relay_open_settings), settings.text.toString())
        assertEquals(a.getString(R.string.not_now), d.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
        settings.performClick(); shadowOf(Looper.getMainLooper()).idle()
        val opened = shadowOf(a).nextStartedActivity
        assertEquals(RelayActivity::class.java.name, opened.component?.className)
        assertTrue(opened.getBooleanExtra(RelayActivity.EXTRA_ADVANCED, false))
    }

    @Test fun otherFailuresKeepJustOk() {
        val a = Robolectric.buildActivity(PairDeviceActivity::class.java).setup().get()
        RelayErrorDialog.show(a, R.string.portal_pair_failed_title, "Couldn't set up the portal: HTTP 500", IllegalStateException("HTTP 500"))
        shadowOf(Looper.getMainLooper()).idle()
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(a.getString(android.R.string.ok), d.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        assertTrue(d.getButton(DialogInterface.BUTTON_NEGATIVE).text.isNullOrEmpty())
    }
}
