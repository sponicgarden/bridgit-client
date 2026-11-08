package cc.bridgit.app.portal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import cc.bridgit.app.R
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as M

/**
 * What a tapped device on Linked devices shows (owner, 5 Nov 2026): the same sheet as the iOS Portal's device view,
 * with View in place of Attach. Header (name, model · Android · Bridgit, a Live/Offline pill and the battery), a
 * connection card, Where, Services, any controls, then the actions. Unknown values are left out, never guessed.
 * Spec: docs/design/linked-device-sheet.md.
 */
class DeviceDetailSheet(
    val name: String,
    /** "Google Pixel 2 XL · Android 11 · Bridgit 1.8.2943", from the known parts; null when none are known. */
    val subtitle: String?,
    /** Read again every 10 s while the sheet is open, so "Live" never outlasts the connection. */
    val status: () -> Status,
    val battery: Pair<Int, Boolean>?,
    val connection: () -> Connection,
    val facts: List<CharSequence> = emptyList(),
    /** Null: no Where card. */
    val where: Where? = null,
    val tiles: List<Pair<String, String>> = emptyList(),
    val services: List<Pair<String, String>> = emptyList(),
    /** A card of Android-only controls (a satellite's switch and agents). */
    val controls: ((LinearLayout) -> Unit)? = null,
    val primary: Action? = null,
    val primaryCaption: String? = null,
    /** Check now: tonal, not filled. */
    val secondary: Action? = null,
    /** Pause or Resume a linked Bridgit (docs/spec-linked-bridgits.md, LNK-27): tonal, after [secondary]. */
    val pause: Action? = null,
    /** Unlink, Remove or Disconnect, with the confirmation it asks first. */
    val destructive: Destructive? = null,
) {
    data class Status(val text: String, val live: Boolean)
    data class Connection(val title: String, val body: String, val live: Boolean)
    /** [connections]: label to state ("connected", "disconnected", …), in order. */
    data class Where(val address: String?, val connections: List<Pair<String, String>>)
    /** [spoken]: an accessible name when [label] alone is too short to say what it acts on. */
    data class Action(val label: String, val spoken: String? = null, val run: () -> Unit)
    /** [confirm] null: runs at once, and the caller offers Undo instead of a question (LNK-26). */
    data class Destructive(val label: String, val confirm: String?, val run: () -> Unit)

    fun show(ctx: Context, closeLabel: String, connectedWord: String): BottomSheetDialog {
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ctx.themeColor(M.attr.colorSurface)) }

        // Drag handle, then a 56dp bar: close on the start side, the name centred on its own.
        root.addView(View(ctx).apply { background = round(ctx, ctx.themeColor(M.attr.colorOutlineVariant), 2) },
            LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(8) })
        val bar = FrameLayout(ctx)
        bar.addView(TextView(ctx).apply {
            text = name; setTextAppearance(M.style.TextAppearance_Material3_TitleMedium); setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            ViewCompat.setAccessibilityHeading(this, true)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { marginStart = ctx.dp(56); marginEnd = ctx.dp(56) })
        bar.addView(MaterialButton(ctx, null, M.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_close); contentDescription = closeLabel
            iconTint = ColorStateList.valueOf(ctx.themeColor(M.attr.colorOnSurface))
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(4) })
        root.addView(bar, LinearLayout.LayoutParams(-1, ctx.dp(56)))

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(24)) }
        val capped = FrameLayout(ctx).apply { addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL)) }
        // At most 600dp wide on a tablet, centred.
        capped.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val max = ctx.dp(600); val w = if (r - l > max) max else -1
            (body.layoutParams as FrameLayout.LayoutParams).let { if (it.width != w) { it.width = w; body.post { body.layoutParams = it } } }
        }
        root.addView(NestedScrollView(ctx).apply { addView(capped) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun section(v: View, top: Int = 16) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(top) })

        // Header.
        section(text(ctx, name, M.style.TextAppearance_Material3_TitleLarge, M.attr.colorOnSurface).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }, 0)
        subtitle?.let { section(text(ctx, it, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant).apply { maxLines = 3 }, 4) }
        body.addView(View(ctx).apply { setBackgroundResource(R.drawable.design_rule) },
            LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(3)).apply { topMargin = ctx.dp(8) })
        val statusRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pill = pill(ctx, "", false)
        statusRow.addView(pill, LinearLayout.LayoutParams(-2, -2))
        statusRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ctx.dp(8) })
        battery?.let { (pct, charging) ->
            statusRow.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.linked_battery_percent, pct)
                setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_Label); setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
                setCompoundDrawablesRelativeWithIntrinsicBounds(if (charging) R.drawable.ic_sheet_battery_charging else R.drawable.ic_sheet_battery, 0, 0, 0)
                compoundDrawablePadding = ctx.dp(4); compoundDrawableTintList = ColorStateList.valueOf(ctx.themeColor(M.attr.colorOnSurfaceVariant))
                contentDescription = ctx.getString(if (charging) R.string.linked_battery_charging_a11y else R.string.linked_battery_a11y, pct)
            })
        }
        section(statusRow, 12)

        // Connection card: a soft wash of the primary colour.
        val connIcon = ImageView(ctx)
        val connTitle = text(ctx, "", M.style.TextAppearance_Material3_TitleMedium, M.attr.colorOnSurface)
        val connBody = text(ctx, "", cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurfaceVariant)
        section(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                ColorUtils.blendARGB(ctx.themeColor(M.attr.colorSurface), ctx.themeColor(M.attr.colorPrimary), 0.14f),
                ColorUtils.blendARGB(ctx.themeColor(M.attr.colorSurface), ctx.themeColor(M.attr.colorPrimary), 0.04f))).apply { cornerRadius = ctx.dp(16).toFloat() }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(connIcon, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).apply { marginEnd = ctx.dp(8) })
                addView(connTitle, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(connBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
        })
        fun refresh() {
            val s = status(); setPill(ctx, pill, s.text, s.live)
            val c = connection(); connTitle.text = c.title; connBody.text = c.body
            connIcon.setImageResource(if (c.live) R.drawable.ic_sheet_live else R.drawable.ic_cloud)
            connIcon.imageTintList = ColorStateList.valueOf(ctx.themeColor(if (c.live) M.attr.colorPrimary else M.attr.colorOnSurfaceVariant))
        }
        refresh()

        facts.forEachIndexed { i, f -> section(text(ctx, f, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurface), if (i == 0) 16 else 8) }

        where?.takeIf { it.address != null || it.connections.isNotEmpty() }?.let { w ->
            section(card(ctx, ctx.getString(R.string.linked_where)) { c ->
                w.address?.let { c.addView(row(ctx, ctx.getString(R.string.linked_network_address), text(ctx, it, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurfaceVariant))) }
                w.connections.forEach { (label, state) ->
                    val on = state == "connected"
                    c.addView(row(ctx, label, pill(ctx, if (on) connectedWord else state.replaceFirstChar { it.uppercase() }, on)))
                }
            })
        }
        if (tiles.isNotEmpty() || services.isNotEmpty()) section(card(ctx, ctx.getString(R.string.linked_services)) { c ->
            tiles.chunked(2).forEachIndexed { i, pair ->
                c.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEachIndexed { j, (value, label) -> addView(tile(ctx, value, label), LinearLayout.LayoutParams(0, -2, 1f).apply { if (j == 1) marginStart = ctx.dp(8) }) }
                    if (pair.size == 1) addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ctx.dp(8) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(if (i == 0) 4 else 8) })
            }
            services.forEach { (label, value) -> c.addView(row(ctx, label, text(ctx, value, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurfaceVariant))) }
        })
        controls?.let { add -> section(card(ctx, null) { add(it) }) }

        // Actions, inside the scroll.
        primary?.let { p ->
            section(MaterialButton(ctx, null, R.attr.bridgitPillStyle).apply { text = p.label; setOnClickListener { dialog.dismiss(); p.run() } }, 24)
            primaryCaption?.let { section(text(ctx, it, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant).apply { gravity = Gravity.CENTER }, 4) }
        }
        secondary?.let { s -> section(MaterialButton(ctx, null, R.attr.bridgitPillTonalStyle).apply { text = s.label; setOnClickListener { s.run() } }, if (primary == null) 24 else 12) }
        pause?.let { p -> section(MaterialButton(ctx, null, R.attr.bridgitPillTonalStyle).apply { text = p.label; p.spoken?.let { contentDescription = it }; setOnClickListener { dialog.dismiss(); p.run() } },
            if (primary == null && secondary == null) 24 else 12) }
        destructive?.let { d ->
            val error = ctx.themeColor(M.attr.colorError)
            section(MaterialButton(ctx, null, R.attr.bridgitPillTextStyle).apply {
                text = d.label; setTextColor(error); rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(error, 0x1F))
                setOnClickListener {
                    if (d.confirm == null) { dialog.dismiss(); d.run() }
                    else MaterialAlertDialogBuilder(ctx).setMessage(d.confirm)
                        .setPositiveButton(d.label) { _, _ -> dialog.dismiss(); d.run() }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            }, if (primary == null && secondary == null && pause == null) 24 else 8)
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(M.id.design_bottom_sheet)?.let { sheet ->
                sheet.layoutParams = sheet.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                sheet.setBackgroundColor(ctx.themeColor(M.attr.colorSurface))
            }
            root.announceForAccessibility(name)
        }
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        val ticker = Handler(Looper.getMainLooper())
        val tick = object : Runnable { override fun run() { refresh(); ticker.postDelayed(this, 10_000) } }
        ticker.postDelayed(tick, 10_000)
        dialog.setOnDismissListener { ticker.removeCallbacks(tick) }
        dialog.show()
        return dialog
    }

    private companion object {
        fun round(ctx: Context, color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = ctx.dp(r).toFloat() }

        fun text(ctx: Context, s: CharSequence, style: Int, colorAttr: Int) = TextView(ctx).apply {
            text = s; setTextAppearance(style); setTextColor(ctx.themeColor(colorAttr))
        }

        fun pill(ctx: Context, s: String, live: Boolean) = TextView(ctx).apply {
            setTextAppearance(M.style.TextAppearance_Material3_LabelMedium)
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(10), ctx.dp(4)); compoundDrawablePadding = ctx.dp(6); gravity = Gravity.CENTER_VERTICAL
            setPill(ctx, this, s, live)
        }

        /** Outlined, 16dp round, a 6dp dot: green while live, grey otherwise. The word carries the meaning. */
        fun setPill(ctx: Context, v: TextView, s: String, live: Boolean) {
            val c = ctx.themeColor(if (live) R.attr.colorSuccess else M.attr.colorOnSurfaceVariant)
            v.text = s; v.setTextColor(c)
            v.background = GradientDrawable().apply { cornerRadius = ctx.dp(16).toFloat(); setStroke(ctx.dp(1), c) }
            val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setSize(ctx.dp(6), ctx.dp(6)) }
            dot.setBounds(0, 0, ctx.dp(6), ctx.dp(6))
            v.setCompoundDrawablesRelative(dot, null, null, null)
        }

        fun card(ctx: Context, title: String?, fill: (LinearLayout) -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(if (title == null) 8 else 16), ctx.dp(16), ctx.dp(if (title == null) 8 else 12))
            background = GradientDrawable().apply {
                setColor(ctx.themeColor(M.attr.colorSurfaceContainerLow)); cornerRadius = ctx.dp(16).toFloat()
                setStroke(ctx.dp(1), ctx.themeColor(M.attr.colorOutlineVariant))
            }
            title?.let { addView(text(ctx, it, M.style.TextAppearance_Material3_TitleMedium, M.attr.colorOnSurface).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD); ViewCompat.setAccessibilityHeading(this, true) }) }
            fill(this)
        }

        /** A 48dp row: label at the start, the value (text or pill) at the end; both wrap. */
        fun row(ctx: Context, label: String, value: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ctx.dp(48)
            addView(text(ctx, label, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurface), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ctx.dp(12) })
            (value as? TextView)?.let { if (it.background == null) it.gravity = Gravity.END }
            addView(value, LinearLayout.LayoutParams(-2, -2))
        }

        fun tile(ctx: Context, value: String, label: String) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            background = round(ctx, ctx.themeColor(M.attr.colorSurfaceContainer), 12)
            addView(text(ctx, value, M.style.TextAppearance_Material3_TitleLarge, M.attr.colorOnSurface).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            addView(text(ctx, label, M.style.TextAppearance_Material3_LabelSmall, M.attr.colorOnSurfaceVariant))
            contentDescription = "$value $label"
        }
    }
}
