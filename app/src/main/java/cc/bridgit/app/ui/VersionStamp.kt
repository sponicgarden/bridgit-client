package cc.bridgit.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cc.bridgit.app.BuildConfig

/**
 * A quiet version label in the top-right corner of every Bridgit screen, so any screenshot shows
 * which build it came from. It sits just below the status bar, ignores touches and is hidden from
 * screen readers.
 */
object VersionStamp : Application.ActivityLifecycleCallbacks {
    private const val TAG = "bridgit_version_stamp"
    private const val ALPHA = 0.45f

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    fun label(): String = "v${BuildConfig.VERSION_NAME}" + if (BuildConfig.DIRTY_BUILD) "*" else ""

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG) != null) return
        val dp = activity.resources.displayMetrics.density
        val stamp = TextView(activity).apply {
            tag = TAG
            text = label()
            // Diagnostic screenshot watermark, excluded from accessibility: retain its compact
            // size so large-text navigation remains unobscured. Operable UI still scales in sp.
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9f)
            // Keep the stamp quiet but readable in both themes.
            setTextColor(textColor(activity))
            alpha = ALPHA
            includeFontPadding = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = 100 * dp
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        val gap = (8 * dp).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(stamp) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = bars.top + (2 * dp).toInt()
                it.rightMargin = bars.right + gap * 2
                v.layoutParams = it
            }
            insets
        }
        decor.addView(stamp, lp)
        ViewCompat.requestApplyInsets(stamp)
    }

    private fun textColor(activity: Activity): Int {
        val tv = TypedValue()
        if (!activity.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) return Color.GRAY
        return if (tv.resourceId != 0) activity.getColorStateList(tv.resourceId).defaultColor else tv.data
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
