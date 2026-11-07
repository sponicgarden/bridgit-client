package cc.bridgit.app.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.Perms
import cc.bridgit.app.capability.granted
import android.provider.Settings as SysSettings

/**
 * Small actions shared by Home, First run, Features and Settings: opening
 * system pages, the master-switch toggle with its audit entry, and runtime
 * permission requests.
 */
object UiActions {
    const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

    /**
     * Opens the Tailscale app, or its Play page if it isn't installed. A launch
     * intent with setPackage works without a <queries> entry (visibility only filters lookups).
     */
    /** True if the Tailscale app is on the phone (needs the <queries> entry in the manifest). */
    fun isTailscaleInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0); true }.getOrDefault(false)

    /** Opens Tailscale, or its Play Store page to install it when it's missing. */
    fun openTailscale(ctx: Context) {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(TAILSCALE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(ctx, launch)) return
        if (tryStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE")))) return
        tryStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE")))
    }

    fun openAppNotificationSettings(ctx: Context) {
        tryStart(ctx, Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName))
    }

    fun openChannelSettings(ctx: Context, channelId: String) {
        tryStart(ctx, Intent(SysSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(SysSettings.EXTRA_CHANNEL_ID, channelId))
    }

    fun openAppDetails(ctx: Context) {
        tryStart(ctx, Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
    }

    fun batteryOptimised(ctx: Context): Boolean =
        !(ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true)

    fun requestBatteryExemption(ctx: Context) {
        if (!tryStart(ctx, Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))))
            tryStart(ctx, Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Opens a web page in the browser; false if nothing can show it. */
    fun openUrl(ctx: Context, url: String): Boolean = tryStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** The privacy policy (Play: linked from first run, About, Settings and the Health rationale). */
    fun openPrivacyPolicy(ctx: Context): Boolean = openUrl(ctx, ctx.getString(R.string.settings_privacy_url))

    fun tryStart(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    fun copy(ctx: Context, label: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Makes a whole row read as one switch to TalkBack ("Camera, Photos when an agent asks, switch, on"). */
    fun asSwitch(row: View, checked: () -> Boolean) {
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = checked()
            }
        })
    }

    /** Sets a master switch and writes the audit entry (UI-18: every change is logged). */
    fun setSwitch(app: BridgeApp, module: Capability, on: Boolean) {
        val key = module.switchKey ?: return
        if (app.settings.isOn(key) == on) return
        app.settings.setOn(key, on)
        app.log.system("audit", "setting", app.getString(R.string.home_turned, module.title,
            app.getString(if (on) R.string.home_on else R.string.home_off)))
    }

    /** The Android permissions to ask for a module; Gallery also asks for the "Select photos" grant. */
    fun permissionsFor(module: Capability): Array<String> {
        val p = module.permissions.toMutableList()
        if (Manifest.permission.READ_MEDIA_VIDEO in p) p += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if (Manifest.permission.READ_MEDIA_IMAGES in p) p += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        return p.toTypedArray()
    }

    /** "Camera", "Microphone", "Photos", "Location", as used in "Camera permission was removed." */
    fun permissionTitle(ctx: Context, kind: PermissionBanners.Kind): String = ctx.getString(when (kind) {
        PermissionBanners.Kind.CAMERA -> R.string.perm_camera
        PermissionBanners.Kind.MIC -> R.string.perm_mic
        PermissionBanners.Kind.PHOTOS -> R.string.perm_photos
        PermissionBanners.Kind.LOCATION -> R.string.perm_location
    })

    /** Lower-case word for "Needs camera permission." */
    fun permissionWord(ctx: Context, permissionShort: String): String = ctx.getString(when (PermissionBanners.kindOf(permissionShort)) {
        PermissionBanners.Kind.CAMERA -> R.string.perm_word_camera
        PermissionBanners.Kind.MIC -> R.string.perm_word_mic
        PermissionBanners.Kind.PHOTOS -> R.string.perm_word_photos
        PermissionBanners.Kind.LOCATION -> R.string.perm_word_location
    })
}

/**
 * Runtime permission requests for a fragment. Register at construction time
 * (field initialiser). After a camera or mic grant the listener is restarted from
 * this visible screen so Android adds those service types (ARC-03). If Android
 * won't show the dialog any more, a Snackbar offers the app's settings page.
 */
class PermissionAsker(private val fragment: Fragment, private val onDone: () -> Unit = {}) {
    private var asked: Array<String> = emptyArray()
    private val launcher: ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> handle(result) }

    fun request(vararg permissions: String) {
        asked = Perms.forRequest(permissions)
        if (asked.isEmpty()) return
        launcher.launch(asked)
    }

    private fun handle(result: Map<String, Boolean>) {
        val ctx = fragment.context ?: return
        val app = ctx.applicationContext as BridgeApp
        val grantedAny = result.values.any { it }
        if (grantedAny && result.keys.any { it == Manifest.permission.CAMERA || it == Manifest.permission.RECORD_AUDIO }
            && app.settings.listenerEnabled) runCatching { BridgeService.start(ctx) }
        val main = asked.firstOrNull()
        val denied = main != null && !ctx.granted(main) &&
            !(main == Manifest.permission.READ_MEDIA_IMAGES && ctx.granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        if (denied && !fragment.shouldShowRequestPermissionRationale(main!!)) {
            fragment.view?.let { v ->
                Snackbar.make(v, R.string.perm_denied, Snackbar.LENGTH_LONG)
                    .setAction(R.string.perm_open_settings) { UiActions.openAppDetails(ctx) }.show()
            }
        }
        onDone()
    }
}
