package cc.bridgit.app.api

import android.app.KeyguardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.power.Battery
import cc.bridgit.app.power.BatteryRules
import org.json.JSONObject

/**
 * A0186: the curated device context attached to each timed call. Only what explains a slow call:
 * network type and metered, battery percent / charging / battery saver, thermal status, app build,
 * screen on and locked, listener ready. Never location, sensor contents or personal data.
 */
object LatencyContext {
    fun sample(app: BridgeApp): JSONObject {
        val pm = app.getSystemService(PowerManager::class.java)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.activeNetwork?.let(cm::getNetworkCapabilities) }.getOrNull()
        val network = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val b = Battery.sticky(app)
        val percent = b?.let { BatteryRules.percent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) }
        return JSONObject()
            .put("network", JSONObject().put("type", network).put("metered", runCatching { cm.isActiveNetworkMetered }.getOrNull() ?: JSONObject.NULL))
            .put("battery", JSONObject().put("percent", percent ?: JSONObject.NULL)
                .put("charging", (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0).put("power_save", pm.isPowerSaveMode))
            .put("thermal", BatteryRules.thermal(pm.currentThermalStatus))
            .put("app", JSONObject().put("version", BuildConfig.VERSION_NAME).put("build", BuildConfig.VERSION_CODE).put("commit", BuildConfig.GIT_COMMIT))
            .put("screen", JSONObject().put("on", pm.isInteractive).put("locked", app.getSystemService(KeyguardManager::class.java).isDeviceLocked))
            .put("listener_ready", app.listener.value.listening)
    }
}
