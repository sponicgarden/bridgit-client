package cc.bridgit.app.ui.common

import android.view.Window
import android.view.WindowManager
import cc.bridgit.app.BuildConfig

/** Private distribution permits screenshots, including setup and credential screens. */
object ScreenshotProtection {
    fun setSecure(window: Window?, secure: Boolean = true) {
        window ?: return
        if (secure && BuildConfig.PLAY_STORE) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
