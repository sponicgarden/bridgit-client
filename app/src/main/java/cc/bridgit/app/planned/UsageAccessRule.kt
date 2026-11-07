package cc.bridgit.app.planned

import android.app.AppOpsManager

/** Match Android Settings: default AppOps mode falls back to the package permission. */
object UsageAccessRule {
    fun allowed(mode: Int, permissionGranted: Boolean): Boolean =
        mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT && permissionGranted)
}
