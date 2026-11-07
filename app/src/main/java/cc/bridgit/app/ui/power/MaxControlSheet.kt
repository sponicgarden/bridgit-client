package cc.bridgit.app.ui.power

import android.content.Context
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cc.bridgit.app.R
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M
import com.google.android.material.chip.ChipGroup

/**
 * The body of the "Max Agentic Control?" dialog (power_confirm_dialog.xml): bolt badge, title, one-line lead,
 * accent line, then one shaded panel listing every feature and setting it turns on in two compact columns,
 * short notes on what happens next, and the amber Warning card. Every item has the same plain check mark in
 * the primary colour, and everything is sized to fit on one screen above the dialog's buttons.
 */
object MaxControlSheet {

    /** The Settings switches Max Agentic Control turns on, in the order Settings shows them. */
    private val settingRows = listOf(
        R.string.max_sheet_device_tools, R.string.max_sheet_always_on, R.string.max_sheet_callbacks,
        R.string.max_sheet_lock_screen, R.string.max_sheet_read_aloud, R.string.max_sheet_crash_reports,
        R.string.max_sheet_tallies, R.string.max_sheet_captures, R.string.max_sheet_open_on_arrival,
        R.string.max_sheet_most_capable,
    )

    /** Short names so each item fits on one line; other features use their full title. */
    private val shortNames = mapOf(
        "sounds" to R.string.max_sheet_short_sounds, "screen_share" to R.string.max_sheet_short_screen_share,
        "phone" to R.string.max_sheet_short_phone, "controls" to R.string.max_sheet_short_controls,
        "usage" to R.string.max_sheet_short_usage, "notifications" to R.string.max_sheet_short_notifications,
        "health_connect" to R.string.max_sheet_short_health, "shares" to R.string.max_sheet_short_shares,
        "downloads" to R.string.max_sheet_short_downloads, "clipboard" to R.string.max_sheet_short_clipboard,
        "video" to R.string.max_sheet_short_video, "ocr" to R.string.max_sheet_short_ocr,
        "schedule" to R.string.max_sheet_short_schedule,
    )

    private fun nameFor(ctx: Context, cap: Capability): String =
        shortNames[cap.id]?.let(ctx::getString) ?: cap.title

    fun build(ctx: Context, title: String, features: List<Capability>, agentName: String?, missing: cc.bridgit.app.power.PowerAccess.MissingAccess? = null): View {
        val root = LayoutInflater.from(ctx).inflate(R.layout.power_confirm_dialog, null)
        root.findViewById<TextView>(R.id.title).text = title
        root.findViewById<TextView>(R.id.lead).text = if (agentName != null)
            ctx.getString(R.string.max_sheet_intro_agent, agentName) else ctx.getString(R.string.max_sheet_intro)
        root.findViewById<TextView>(R.id.warningText).text = warning(ctx, ctx.getString(R.string.power_confirm_warning))

        val panel = root.findViewById<LinearLayout>(R.id.points)
        panel.setPadding(dp(ctx, 12), 0, dp(ctx, 10), dp(ctx, 8))
        if (missing != null && !missing.complete) {
            root.findViewById<TextView>(R.id.lead).setText(R.string.max_missing_intro)
            if (missing.permissions.isNotEmpty()) {
                panel.addView(header(ctx, ctx.getString(R.string.max_missing_access_title)))
                panel.addView(flow(ctx, missing.permissions, pending = true))
            }
            if (missing.settings.isNotEmpty()) {
                panel.addView(header(ctx, ctx.getString(R.string.max_missing_settings_title)))
                panel.addView(flow(ctx, missing.settings, pending = true))
            }
        } else {
            panel.addView(header(ctx, ctx.getString(R.string.max_sheet_features, features.size)))
            panel.addView(flow(ctx, features.map { nameFor(ctx, it) }))
            panel.addView(header(ctx, ctx.getString(R.string.max_sheet_settings)))
            panel.addView(flow(ctx, settingRows.map(ctx::getString)))
        }
        // What happens next: two short lines under the panel, no heading of their own.
        val notes = root.findViewById<LinearLayout>(R.id.notes)
        listOf(R.string.max_sheet_android_asks, R.string.max_sheet_pause)
            .forEach { notes.addView(note(ctx, ctx.getString(it))) }
        return root
    }

    /** Items flow left to right and wrap, so short names pack tightly and nothing is cut off. */
    internal fun flow(ctx: Context, items: List<String>, pending: Boolean = false): View =
        ChipGroup(ctx).apply {
            chipSpacingHorizontal = dp(ctx, 8)
            chipSpacingVertical = 0
            items.forEach { addView(cell(ctx, it, wrap = false, pending = pending), ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }

    /** "Warning." in bold amber, then the text, as on the welcome pages' Warning card. */
    private fun warning(ctx: Context, text: String): CharSequence {
        val head = ctx.getString(R.string.firstrun_warning_label) + ". "
        return SpannableStringBuilder(head + text).apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ctx.getColor(R.color.firstrun_amber)), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** A line under the panel: small text in the muted colour. */
    private fun note(ctx: Context, s: String) = text(ctx, s, 11f, M.attr.colorOnSurfaceVariant).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(ctx, 2), 0, 0)
    }

    /** One item: the shared check mark in the primary colour, then its name. */
    private fun cell(ctx: Context, label: String, wrap: Boolean, pending: Boolean): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (wrap) Gravity.TOP else Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
            addView(ImageView(ctx).apply {
                setImageResource(if (pending) R.drawable.ic_warning else R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(ctx.themeColor(M.attr.colorPrimary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(ctx, 13), dp(ctx, 13)).apply { topMargin = if (wrap) dp(ctx, 1) else 0 })
            addView(text(ctx, label, 10.5f, M.attr.colorOnSurface).apply {
                includeFontPadding = false
                setPadding(dp(ctx, 5), 0, 0, 0)
                // Allow long Android permission and service names to wrap.
            }, if (wrap) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    /** Small-caps heading, as Settings and the welcome page's THINGS TO TRY. */
    internal fun header(ctx: Context, label: String): View =
        text(ctx, label, 10.5f, M.attr.colorPrimary).apply {
            isAllCaps = true
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            isAccessibilityHeading = true
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 3))
        }

    private fun text(ctx: Context, s: String, sp: Float, colorAttr: Int) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(ctx.themeColor(colorAttr))
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
