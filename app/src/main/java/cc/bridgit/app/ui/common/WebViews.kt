package cc.bridgit.app.ui.common

import android.util.Log

/**
 * Creating a WebView throws when the device has no usable WebView provider: Fire OS / LineageOS speakers
 * (C0888, Echo Dot on 2.0.3863), a disabled or mid-update WebView package, or a missing native library.
 * Android throws UnsupportedOperationException, AndroidRuntimeException or a wrapped
 * MissingWebViewPackageException, so every WebView Bridgit builds goes through [create], and each caller
 * takes its existing "page didn't load" path when it returns null.
 */
object WebViews {
    @Volatile private var missing = false

    /** False once a WebView failed to start in this process; true otherwise (not yet known counts as usable). */
    val usable: Boolean get() = !missing

    fun <T> create(make: () -> T): T? {
        if (missing) return null
        return try {
            make()
        } catch (e: Exception) {
            if (!isProviderFailure(e)) throw e
            missing = true
            Log.w("Bridgit", "WebView unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Only provider failures are swallowed; a bug in our own setup code still crashes and reaches Sentry. */
    internal fun isProviderFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            if (t is UnsupportedOperationException) return true
            if (t.javaClass.name.contains("MissingWebViewPackageException")) return true
            if (t.stackTrace.any { it.className == "android.webkit.WebViewFactory" }) return true
            t = t.cause
            depth++
        }
        return false
    }

    internal fun resetForTest() { missing = false }
}
