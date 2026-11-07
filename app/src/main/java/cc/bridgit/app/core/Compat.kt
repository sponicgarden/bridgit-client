package cc.bridgit.app.core

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.MediaRecorder
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Fallbacks for Android 10–13 (REQ-27): each picks the newer call where this Android version has it. */
object Compat {
    fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)

    @Suppress("DEPRECATION")
    fun mediaRecorder(ctx: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    /** Up to [max] bytes from [input] (InputStream.readNBytes arrives in Android 13). */
    fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    fun packageArchiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(path, flags)

    @Suppress("DEPRECATION")
    fun packageInfo(pm: PackageManager, pkg: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(pkg, flags)

    @Suppress("DEPRECATION")
    fun queryServices(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        else pm.queryIntentServices(intent, 0)

    @Suppress("DEPRECATION")
    fun serviceInfo(pm: PackageManager, cn: ComponentName): ServiceInfo =
        if (Build.VERSION.SDK_INT >= 33) pm.getServiceInfo(cn, PackageManager.ComponentInfoFlags.of(0))
        else pm.getServiceInfo(cn, 0)

    /** The store or installer that installed [pkg], or null for adb and file installs. */
    @Suppress("DEPRECATION")
    fun installer(pm: PackageManager, pkg: String): String? =
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else pm.getInstallerPackageName(pkg)

    /** Android 14 lets apps mark fields as sensitive; earlier versions only have password fields. */
    fun sensitive(node: android.view.accessibility.AccessibilityNodeInfo): Boolean =
        node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)

    /** The window's full size in pixels, for sizing a screen capture. */
    @Suppress("DEPRECATION")
    fun windowBounds(activity: Activity): Rect =
        if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds
        else android.util.DisplayMetrics().also { activity.windowManager.defaultDisplay.getRealMetrics(it) }.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
}
