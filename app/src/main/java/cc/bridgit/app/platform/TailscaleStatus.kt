package cc.bridgit.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import cc.bridgit.app.core.TailscaleHelp

/** What Bridgit can see of the Tailscale app on this phone, and the one thing it can do to it without ADB. */
object TailscaleStatus {
    fun installed(c: Context): Boolean =
        runCatching { c.packageManager.getPackageInfo(TailscaleHelp.PACKAGE, 0); true }.getOrDefault(false)

    /** Never put to sleep by battery optimisation. */
    fun batteryExempt(c: Context): Boolean =
        runCatching { c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(TailscaleHelp.PACKAGE) }.getOrDefault(false)

    /** True or false if Android lets Bridgit read the always-on VPN setting; null if it doesn't (newer Android may not). */
    fun alwaysOn(c: Context): Boolean? =
        runCatching { Settings.Secure.getString(c.contentResolver, "always_on_vpn_app") == TailscaleHelp.PACKAGE }.getOrNull()

    /** Kept on: battery-exempt and, where Android shows it, the always-on VPN. */
    fun keptOn(c: Context): Boolean = batteryExempt(c) && alwaysOn(c) != false

    /**
     * Asks Tailscale to connect (its exported IPNReceiver, the same intent Tasker uses). It only works once
     * Tailscale is signed in and Android has allowed its VPN; otherwise nothing happens and the owner opens the app.
     */
    fun connect(c: Context): Boolean = runCatching {
        c.sendBroadcast(Intent(TailscaleHelp.ACTION_CONNECT).setComponent(ComponentName(TailscaleHelp.PACKAGE, TailscaleHelp.RECEIVER)))
        true
    }.getOrDefault(false)
}
