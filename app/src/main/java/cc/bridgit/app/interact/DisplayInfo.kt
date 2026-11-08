package cc.bridgit.app.interact

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface

/**
 * A0074–A0078: the phone's display as Android reports it, for [CardViewport]. Reads the default display (real size in
 * its current rotation, density, cutout) from any thread; no visual context needed. The card screen reports the
 * measured standard card frame with [recordStandard], so later answers use the real size instead of an estimate.
 */
object DisplayInfo {
    @Volatile private var measured: Pair<Boolean, Px>? = null

    /** The card screen measured its standard card frame ([portrait] orientation). */
    fun recordStandard(portrait: Boolean, size: Px) { if (size.w > 0 && size.h > 0) measured = portrait to size }

    /** The measured standard card frame in the current orientation, or null. */
    fun standard(g: DisplayGeometry): Px? = measured?.takeIf { it.first == g.portrait }?.second

    /** The standard card frame: measured when known, else estimated. */
    fun standardOrEstimate(g: DisplayGeometry): Px = standard(g) ?: CardViewport.standardEstimate(g)

    @Suppress("DEPRECATION")
    fun read(context: Context): DisplayGeometry {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val res = context.resources
        if (display == null) {
            val m = res.displayMetrics
            return DisplayGeometry(m.widthPixels, m.heightPixels, m.densityDpi, m.density, 0)
        }
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        val rotation = when (display.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val cutout = display.cutout
        val portrait = m.heightPixels >= m.widthPixels
        val cutoutLong = cutout?.let { if (portrait) it.safeInsetTop + it.safeInsetBottom else it.safeInsetLeft + it.safeInsetRight } ?: 0
        fun dimen(name: String) = res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let { res.getDimensionPixelSize(it) } ?: 0
        val refresh = runCatching { display.supportedModes.maxOf { it.refreshRate } }.getOrDefault(display.refreshRate)
        return DisplayGeometry(m.widthPixels, m.heightPixels, m.densityDpi, m.density, rotation, cutoutLong,
            dimen("status_bar_height"), dimen(if (portrait) "navigation_bar_height" else "navigation_bar_height_landscape"),
            Math.round(refresh).takeIf { it > 0 })
    }
}
