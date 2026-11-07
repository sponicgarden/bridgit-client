package cc.bridgit.app.satellite

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import cc.bridgit.app.portal.PairDeviceActivity

/**
 * Bridgit Satellites are paired on Linked devices › Pair a device (owner, 5 Nov 2026; PairDeviceActivity).
 * This stays only as the exported target for tapped bridgit://satellite#… links: it hands the link over and closes.
 */
class SatelliteActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, PairDeviceActivity::class.java).setData(intent?.data?.takeIf { it.toString().startsWith(SatelliteLink.PREFIX) }))
        finish()
    }
}
