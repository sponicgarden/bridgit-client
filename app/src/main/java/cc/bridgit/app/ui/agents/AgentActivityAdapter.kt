package cc.bridgit.app.ui.agents

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cc.bridgit.app.R
import cc.bridgit.app.databinding.RowAgentActivityBinding
import cc.bridgit.app.ui.common.ActivityRows
import cc.bridgit.app.ui.common.ActivityRows.ChipStyle
import cc.bridgit.app.ui.common.ActivityRows.Kind
import cc.bridgit.app.ui.common.ActivityRows.Tone
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M

/**
 * Recent activity on an agent's page (#0003, #0004): rows already formatted and collapsed by
 * [ActivityRows], drawn with an icon per kind of action and colour-coded method and status.
 */
class AgentActivityAdapter(private val onClick: () -> Unit) :
    ListAdapter<ActivityRows.Row, AgentActivityAdapter.Holder>(DIFF) {

    class Holder(val b: RowAgentActivityBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(RowAgentActivityBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = getItem(position)
        val ctx = h.b.root.context
        val (bg, fg) = when (r.tone) {
            Tone.ERROR -> M.attr.colorErrorContainer to M.attr.colorOnErrorContainer
            Tone.WAITING -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
            Tone.SUCCESS -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
            Tone.PRIMARY -> M.attr.colorPrimaryContainer to M.attr.colorOnPrimaryContainer
            Tone.NEUTRAL -> M.attr.colorSurfaceContainerHigh to M.attr.colorOnSurfaceVariant
        }
        h.b.icon.setImageResource(icon(r.kind))
        h.b.icon.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(bg))
        h.b.icon.imageTintList = ColorStateList.valueOf(ctx.themeColor(fg))
        h.b.count.isVisible = r.count > 1
        h.b.count.text = if (r.count > 99) "99+" else r.count.toString()
        h.b.count.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(fg))
        h.b.count.setTextColor(ctx.themeColor(bg))
        h.b.title.text = r.title
        h.b.title.setTextColor(ctx.themeColor(if (r.isError) M.attr.colorError else M.attr.colorOnSurface))
        h.b.subtitle.isVisible = r.subtitle != null
        h.b.subtitle.text = r.subtitle
        h.b.time.text = Fmt.time(r.ts)
        h.b.errorStripe.isVisible = r.isError
        h.b.chips.removeAllViews()
        for (c in r.chips) h.b.chips.addView(chip(h.b.chips, c))
        h.b.root.contentDescription = (if (r.isError) "Error: " else "") + "${Fmt.time(r.ts)}, ${r.title}" +
            (r.subtitle?.let { ", $it" } ?: "") + ", " + r.chips.joinToString(", ") { it.text }
        h.b.root.setOnClickListener { onClick() }
    }

    private fun chip(parent: ViewGroup, c: ActivityRows.Chip): TextView {
        val ctx = parent.context
        val v = TextView(ctx, null, 0, R.style.Widget_Bridgit_MonoChip)
        v.setBackgroundResource(R.drawable.agentact_bg_chip)
        v.setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
        val (bg, fg) = when (c.style) {
            ChipStyle.OK -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
            ChipStyle.ERROR -> M.attr.colorErrorContainer to M.attr.colorOnErrorContainer
            ChipStyle.WAITING -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
            else -> M.attr.colorSurfaceContainerHigh to M.attr.colorOnSurfaceVariant
        }
        v.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(bg))
        v.setTextColor(ctx.themeColor(fg))
        val methodColor = when (c.style) {
            ChipStyle.GET -> M.attr.colorPrimary
            ChipStyle.POST -> R.attr.colorSuccess
            ChipStyle.DELETE -> M.attr.colorError
            ChipStyle.OTHER_METHOD -> M.attr.colorSecondary
            else -> null
        }
        val space = c.text.indexOf(' ')
        v.text = if (methodColor != null && space > 0) SpannableStringBuilder(c.text).apply {
            setSpan(ForegroundColorSpan(ctx.themeColor(methodColor)), 0, space, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, space, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else c.text
        return v
    }

    private fun icon(k: Kind) = when (k) {
        Kind.WAIT -> R.drawable.agentact_ic_hourglass
        Kind.EVENTS -> R.drawable.agentact_ic_bell
        Kind.INTERACTION -> R.drawable.agentact_ic_chat
        Kind.CAMERA -> R.drawable.agentact_ic_camera
        Kind.SCREEN -> R.drawable.agentact_ic_phone
        Kind.MIC -> R.drawable.agentact_ic_mic
        Kind.IMAGE, Kind.GALLERY -> R.drawable.agentact_ic_image
        Kind.HEALTH -> R.drawable.agentact_ic_heart
        Kind.GUIDE -> R.drawable.agentact_ic_book
        Kind.LOG -> R.drawable.agentact_ic_list
        Kind.PAIR -> R.drawable.agentact_ic_key
        Kind.NOTE -> R.drawable.agentact_ic_note
        Kind.SPEAKER -> R.drawable.agentact_ic_speaker
        Kind.LIGHT -> R.drawable.agentact_ic_bolt
        Kind.FEEDBACK -> R.drawable.agentact_ic_bug
        Kind.OTHER -> R.drawable.agentact_ic_api
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ActivityRows.Row>() {
            override fun areItemsTheSame(a: ActivityRows.Row, b: ActivityRows.Row) = a.ts == b.ts && a.kind == b.kind
            override fun areContentsTheSame(a: ActivityRows.Row, b: ActivityRows.Row) = a == b
        }
    }
}
