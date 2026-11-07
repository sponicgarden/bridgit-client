package cc.bridgit.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M
import cc.bridgit.app.portal.PortalLinks

/**
 * The one device banner across the top of Home, Applets, Camera and Agents (owner, 5 Nov 2026): an eye and
 * "Google Pixel 2 XL" while showing another device, with a "Local phone" button to switch back; nothing on this
 * device's own screens. Home's freshness line ("Read-only · updated 7:52 PM") sits under the name.
 * The shared Lilac frost tonal surface. It sits above the
 * screens and takes over the status-bar inset, so the screens below start under it instead of under the status bar.
 */
class HubBar(private val app: BridgeApp, private val root: LinearLayout, private val content: View) {
    private val ctx = root.context
    private val dp = ctx.resources.displayMetrics.density
    private var statusTop = 0
    private var follows = false
    private var allowed = true
    private val label = TextView(ctx).apply {
        setTextColor(ctx.themeColor(M.attr.colorOnSurface)); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = (8 * dp).toInt()
    }
    /** Home only, while showing another device: how fresh its data is, or why it can't be reached. */
    private val status = TextView(ctx).apply {
        setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant)); textSize = 14f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        isVisible = false; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var statusText: String? = null
    private val textColumn = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(label); addView(status)
    }
    private val back = TextView(ctx).apply {
        text = ctx.getString(R.string.hub_bar_back, cc.bridgit.app.platform.DeviceType.of(ctx).noun)
        setTextColor(ctx.themeColor(M.attr.colorOnSurface)); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
        // A solid dark pill reads on every part of the gradient; 48dp touch area around a 28dp pill.
        background = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
            cornerRadius = 8 * dp; setColor(ctx.themeColor(M.attr.colorSurface)); setStroke((1 * dp).toInt(), ctx.themeColor(M.attr.colorOutlineVariant)) }, 0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        gravity = Gravity.CENTER; minHeight = (48 * dp).toInt()
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        setOnClickListener { PortalLinks(app).select(null) }
    }
    private val eye by lazy { androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_eye)?.mutate()?.apply {
        setTint(ctx.themeColor(M.attr.colorOnSurfaceVariant)); setBounds(0, 0, (18 * dp).toInt(), (18 * dp).toInt()) } }
    // One shared tonal surface across every screen.
    private val gradient = intArrayOf(ctx.themeColor(M.attr.colorSurfaceContainer), ctx.themeColor(M.attr.colorSurfaceContainer))
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isVisible = false
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, gradient)
        minimumHeight = (48 * dp).toInt()
        addView(textColumn); addView(back)
        setOnClickListener { /* the bar itself isn't a button; only "Local phone" is */ }
    }

    fun install(owner: LifecycleOwner) {
        root.addView(view, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            // A strip under the status bar (owner, 5 Nov 2026), not a block that runs up behind it.
            statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (v.layoutParams as? LinearLayout.LayoutParams)?.let { if (it.topMargin != statusTop) { it.topMargin = statusTop; v.layoutParams = it } }
            v.updatePadding(left = (16 * dp).toInt(), top = 0, right = (8 * dp).toInt(), bottom = 0)
            insets
        }
        // While shown, the screens below get no status-bar inset: the bar already covers it.
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            if (!view.isVisible) insets else {
                val zeroTop = { t: Int -> insets.getInsets(t).let { Insets.of(it.left, 0, it.right, it.bottom) } }
                WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.statusBars(), zeroTop(WindowInsetsCompat.Type.statusBars()))
                    .setInsets(WindowInsetsCompat.Type.systemBars(), zeroTop(WindowInsetsCompat.Type.systemBars()))
                    .build()
            }
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { PortalLinks(app).selected.collect { render() } }
        }
    }

    /**
     * [follows]: the screen shown is one that follows the other device (Home, Applets, Camera, Agents).
     * [show]: a screen where the bar belongs at all (not setup or full-screen flows).
     */
    fun onDestination(follows: Boolean, show: Boolean = true, home: Boolean = false) { this.follows = follows; allowed = show; onHome = home; render() }
    private var onHome = false

    /** Home's freshness or error line for the device it shows; null clears it. */
    fun setStatus(text: String?) { statusText = text; render() }

    /** Shown only while a tab shows another device (owner, 5 Nov 2026: no banner on this device's own screens). */
    private fun render() {
        val links = PortalLinks(app)
        val hub = links.selectedHub()
        val link = if (hub == null) links.selectedLink() else null
        val remote = hub?.name ?: link?.name
        val show = allowed && follows && remote != null
        if (show) {
            // An eye and the other device's name (owner, 5 Nov 2026: no "Showing", no "your hub").
            label.text = remote
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(eye, null, null, null)
            label.contentDescription = ctx.getString(R.string.hub_bar_viewing_cd, remote)
            status.text = statusText
            status.isVisible = onHome && !statusText.isNullOrEmpty()
        }
        if (show == view.isVisible) return
        view.isVisible = show
        ViewCompat.requestApplyInsets(root)
    }
}
