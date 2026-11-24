package cc.bridgit.app.ui

import android.content.Context

/**
 * A0138: Bridgit reopens on the bottom-bar tab the owner last used, after it was closed or Android
 * ended it. Only the five tabs are remembered: setup, key and detail screens never reopen. A launch
 * that opens something else (a notification, a link, first run) wins.
 */
object LastTab {
    private const val PREFS = "navigation"
    private const val KEY = "last_tab"

    fun save(context: Context, tab: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tab).apply()

    fun saved(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** Owner, 24 Nov 2026: the Camera tab shows only while an applet checks photos, so it can't be reopened otherwise. */
    fun shown(tabs: Set<String>, cameraShown: Boolean): Set<String> = if (cameraShown) tabs else tabs - CAMERA

    const val CAMERA = "camera"

    /** The tab a plain launch reopens, or null to stay where the launch put Bridgit. */
    fun restore(saved: String?, plainLaunch: Boolean, current: String?, start: String, tabs: Set<String>): String? =
        saved?.takeIf { plainLaunch && current == start && it != start && it in tabs }
}
