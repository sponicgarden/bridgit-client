package cc.bridgit.app.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import cc.bridgit.app.R
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.R as M

/**
 * Home's phone chooser as a bottom sheet of cards, laid out like the Bridgit Portal "Phones" sheet on iOS (owner,
 * 5 Nov 2026): one card per phone with its name, role, details, how fresh its data is and its battery. Tapping a
 * card picks it at once; the ticked card is the phone Home shows now.
 */
object PhoneChooserSheet {
    /**
     * One card. [at] is when its Home was last read (null: never, or this phone, which has no pill); [waiting] is a
     * hub that hasn't checked in yet; [battery] is percent and charging when known.
     */
    class Row(
        val id: String?, val name: String, val role: String, val detail: String,
        val at: Long?, val thisPhone: Boolean, val waiting: Boolean, val battery: Pair<Int, Boolean>?, val selected: Boolean,
    )

    fun show(ctx: Context, rows: List<Row>, onPick: (Row) -> Unit, onLinked: () -> Unit) {
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(16))
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            setText(R.string.portal_chooser_title)
            appearance(ctx, M.attr.textAppearanceTitleLarge)
            setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(MaterialButton(ctx, null, R.attr.bridgitPillTextStyle).apply {
            setText(R.string.portal_chooser_done)
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.dp(8), 0, ctx.dp(4)) }
        rows.forEach { r ->
            list.addView(card(ctx, r) { dialog.dismiss(); onPick(r) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(12) })
        }
        root.addView(NestedScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(MaterialButton(ctx, null, R.attr.bridgitPillTonalStyle).apply {
            setText(R.string.portal_chooser_add)
            setIconResource(R.drawable.ic_add)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setOnClickListener { dialog.dismiss(); onLinked() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun card(ctx: Context, r: Row, onTap: () -> Unit): View {
        val primary = ctx.themeColor(M.attr.colorPrimary)
        val variant = ctx.themeColor(M.attr.colorOnSurfaceVariant)
        val card = MaterialCardView(ctx).apply {
            radius = ctx.dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = ctx.dp(if (r.selected) 2 else 1)
            strokeColor = if (r.selected) primary else ctx.themeColor(M.attr.colorOutlineVariant)
            setCardBackgroundColor(if (r.selected)
                ColorUtils.blendARGB(ctx.themeColor(M.attr.colorSurfaceContainerLow), ctx.themeColor(M.attr.colorPrimaryContainer), 0.6f)
                else ctx.themeColor(M.attr.colorSurfaceContainerLow))
            isClickable = true
            isFocusable = true
            isSelected = r.selected
            setOnClickListener { onTap() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(8), ctx.dp(14))
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(if (r.selected) R.drawable.ic_check_circle else R.drawable.agentact_ic_phone)
            imageTintList = ColorStateList.valueOf(if (r.selected) primary else variant)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply { marginEnd = ctx.dp(12) })

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = r.name
            appearance(ctx, M.attr.textAppearanceTitleMedium)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.themeColor(M.attr.colorOnSurface))
        })
        listOf(r.role, r.detail).filter { it.isNotEmpty() }.forEach { line ->
            texts.addView(TextView(ctx).apply {
                text = line
                appearance(ctx, M.attr.textAppearanceBodySmall)
                setTextColor(variant)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(2) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        val side = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val pill = pill(ctx, r)
        pill?.let { side.addView(it) }
        val battery = r.battery?.let { (p, charging) ->
            ctx.getString(if (charging) R.string.portal_chooser_battery_charging else R.string.portal_chooser_battery, p)
        }
        battery?.let {
            side.addView(TextView(ctx).apply {
                text = it
                appearance(ctx, M.attr.textAppearanceLabelSmall)
                setTextColor(variant)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(4); gravity = Gravity.END })
        }
        if (side.childCount > 0) row.addView(side, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dp(8) })

        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(ctx.themeColor(M.attr.colorOutline))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)).apply { marginStart = ctx.dp(4) })
        card.addView(row)

        // TalkBack reads one line per card: name, what it is, its state; "selected" comes from isSelected.
        val status = listOfNotNull(r.detail.takeIf { it.isNotEmpty() }, pill?.text?.toString(), battery,
            if (r.selected) ctx.getString(R.string.portal_chooser_selected) else null).joinToString(", ")
        card.contentDescription = ctx.getString(R.string.portal_chooser_card_desc, r.name, r.role, status)
        return card
    }

    /** How fresh the phone's data is: green within 2 minutes, gold when older, grey when never read or waiting. */
    private fun pill(ctx: Context, r: Row): TextView? {
        if (r.thisPhone) return null
        val age = r.at?.let { System.currentTimeMillis() - it }
        val (bg, fg) = when {
            r.waiting || age == null -> ctx.themeColor(M.attr.colorSurfaceContainerHighest) to ctx.themeColor(M.attr.colorOnSurfaceVariant)
            age < 2 * 60_000L -> ctx.themeColor(R.attr.colorSuccessContainer) to ctx.themeColor(R.attr.colorOnSuccessContainer)
            else -> ctx.themeColor(M.attr.colorSecondaryContainer) to ctx.themeColor(M.attr.colorOnSecondaryContainer)
        }
        return TextView(ctx).apply {
            text = if (r.waiting) ctx.getString(R.string.portal_chooser_waiting_pill) else cc.bridgit.app.portal.PortalViewer.freshness(r.at)
            appearance(ctx, M.attr.textAppearanceLabelMedium)
            setTextColor(fg)
            setPadding(ctx.dp(10), ctx.dp(3), ctx.dp(10), ctx.dp(3))
            background = GradientDrawable().apply { cornerRadius = ctx.dp(12).toFloat(); setColor(bg) }
        }
    }

    private fun TextView.appearance(ctx: Context, attr: Int) {
        val tv = TypedValue()
        if (ctx.theme.resolveAttribute(attr, tv, true) && tv.resourceId != 0) TextViewCompat.setTextAppearance(this, tv.resourceId)
    }
}
