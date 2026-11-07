package cc.bridgit.app.relay

import android.app.Activity
import android.content.Intent
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A failure that needed the relay, as a pop-up (design pass with Sol 6.1, 5 Nov 2026). When the relay address is
 * missing or isn't this phone's ([RelaySetup.AddressProblem]) it says so plainly and offers Relay settings, which
 * opens More › Relay at Advanced; anything else shows [message] with OK.
 */
object RelayErrorDialog {
    fun show(a: Activity, title: Int, message: String, failure: Throwable) {
        val d = MaterialAlertDialogBuilder(a).setTitle(title)
        if (failure is RelaySetup.AddressProblem) {
            d.setMessage(if (failure.missing) a.getString(R.string.relay_address_missing_popup)
                else a.getString(R.string.relay_address_wrong_popup, RelayStore(a.application as BridgeApp).device))
            d.setPositiveButton(R.string.relay_open_settings) { _, _ ->
                a.startActivity(Intent(a, RelayActivity::class.java).putExtra(RelayActivity.EXTRA_ADVANCED, true))
            }.setNegativeButton(R.string.not_now, null)
        } else d.setMessage(message).setPositiveButton(android.R.string.ok, null)
        d.show()
    }
}
