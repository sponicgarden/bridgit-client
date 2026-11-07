package cc.bridgit.app.portal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cc.bridgit.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as M

/**
 * The dialog a tapped device on Linked devices opens, one layout for every kind (design pass, 5 Nov 2026):
 * the name as title, "Role · Type", a dot and a status word, one fact per line, any controls, then the
 * destructive action in red at the end of the content. The button bar has only Close and one main action.
 */
class LinkedDeviceSheet(
    val title: CharSequence,
    /** "Portal · Phone"; null for this phone, whose title already says what it is. */
    val identity: CharSequence? = null,
    val status: Status? = null,
    val facts: List<CharSequence> = emptyList(),
    /** Controls below the facts (the satellite's switch and agent list). */
    val controls: ((LinearLayout) -> Unit)? = null,
    val destructive: Action? = null,
    val primary: Action? = null,
) {
    enum class Tone { GOOD, BAD, QUIET }
    data class Status(val word: CharSequence, val tone: Tone)
    data class Action(val label: CharSequence, val run: () -> Unit)

    fun show(ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val variant = MaterialColors.getColor(ctx, M.attr.colorOnSurfaceVariant, 0)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24), px(4), px(24), 0) }
        fun gap(v: View, top: Int) = box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) })
        fun text(s: CharSequence, style: Int, color: Int = variant) = TextView(ctx).apply { text = s; setTextAppearance(style); setTextColor(color) }

        var first = true
        fun next(v: View, top: Int) { gap(v, if (first) 0 else top); first = false }
        identity?.let { next(text(it, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium), 0) }
        status?.let { next(statusRow(ctx, it, ::px), 8) }
        facts.forEachIndexed { i, f -> next(text(f, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium), if (i == 0) 16 else 8) }
        controls?.let { add ->
            val group = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            add(group); if (group.childCount > 0) next(group, 16)
        }
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_Bridgit_DeviceDialog).setTitle(title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setNegativeButton(R.string.linked_close, null)
            .apply { primary?.let { p -> setPositiveButton(p.label) { _, _ -> p.run() } } }
            .create()
        destructive?.let { d ->
            val error = MaterialColors.getColor(ctx, M.attr.colorError, 0)
            val button = MaterialButton(ctx, null, R.attr.bridgitPillTextStyle).apply {
                text = d.label; setTextColor(error); rippleColor = ColorStateList.valueOf(error and 0x1FFFFFFF)
                setOnClickListener { dialog.dismiss(); d.run() }
            }
            // Its label lines up with the text above: the pill's own 14dp padding sits in the dialog's margin.
            box.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(if (first) 0 else 12); marginStart = -button.paddingStart })
        }
        dialog.show()
    }

    /** A 6dp dot and the word, read as one ("Online"); colour never carries the meaning alone. */
    private fun statusRow(ctx: Context, s: Status, px: (Int) -> Int): View {
        val color = MaterialColors.getColor(ctx, when (s.tone) {
            Tone.GOOD -> R.attr.colorSuccess; Tone.BAD -> M.attr.colorError; Tone.QUIET -> M.attr.colorOnSurfaceVariant }, 0)
        return TextView(ctx).apply {
            text = s.word; setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_Label); setTextColor(color)
            setCompoundDrawablesRelative(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(px(6), px(6)); setBounds(0, 0, px(6), px(6)) }, null, null, null)
            compoundDrawablePadding = px(8)
        }
    }

    companion object {
        /** "Portal · Phone", leaving out a type that only repeats the title ("iPhone" / "iPhone"). */
        fun identity(role: CharSequence, type: String?, title: CharSequence): CharSequence =
            if (type.isNullOrBlank() || type.trim().equals(title.toString().trim(), ignoreCase = true)) role else "$role · ${type.trim()}"
    }
}
