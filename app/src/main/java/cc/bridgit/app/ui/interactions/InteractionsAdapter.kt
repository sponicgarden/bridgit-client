package cc.bridgit.app.ui.interactions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Typeface
import cc.bridgit.app.R
import cc.bridgit.app.databinding.InteractionsRowBinding
import cc.bridgit.app.databinding.InteractionsSectionBinding
import cc.bridgit.app.interact.IStatus
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import cc.bridgit.app.interact.Control
import cc.bridgit.app.interact.IKind
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.ui.common.Fmt

/** One entry of the Interactions list: a section label or an interaction card. */
sealed class InteractionRow {
    data class Section(val waiting: Boolean, val count: Int) : InteractionRow()
    class Item(val i: Interaction) : InteractionRow()

    companion object {
        /** "Needs your attention · n" over rows that still want the owner, "Completed" over the rest. */
        fun of(list: List<Interaction>): List<InteractionRow> {
            // A notice the owner has already seen needs nothing more, so it sits in "Completed" (design pass, 4 Nov 2026).
            val (open, done) = list.partition { InteractionUi.waiting(it) && !(it.kind == IKind.NOTICE && it.status == IStatus.SEEN) }
            return buildList {
                if (open.isNotEmpty()) { add(Section(true, open.size)); open.forEach { add(Item(it)) } }
                if (done.isNotEmpty()) { add(Section(false, done.size)); done.forEach { add(Item(it)) } }
            }
        }
    }
}

/**
 * Interactions list (D26), owner 2 Nov 2026: rows grouped under "Needs you" and "Earlier", one shaded panel per
 * section (redesign 4 Nov 2026, drawn by SectionPanelDecoration).
 * A card shows the avatar (the agent), the title, a short age and one summary line (preview, the owner's answer,
 * or Closed/Missed) when collapsed; text, image and inline controls when expanded. Waiting rows get an accent
 * stripe and a stronger shade, so no row needs a "Waiting" chip.
 * Expanded state is kept per interaction id across refreshes; [drafts] keeps unsent answers.
 */
class InteractionsAdapter(
    private val drafts: Drafts,
    private val onOpen: (Interaction) -> Unit,
    private val onExpanded: (Interaction) -> Unit,
    private val onAnswer: (Interaction, String?, Map<String, String>) -> Unit,
    private val onClose: (Interaction) -> Unit,
    private val onLink: (Control.Link, View) -> Unit,
    private val loadThumb: (Interaction, ImageView) -> Unit,
) : ListAdapter<InteractionRow, RecyclerView.ViewHolder>(DIFF) {

    /** The owner's choice per id; ids not in here use [InteractionUi.defaultExpanded]. */
    val overrides = HashMap<Long, Boolean>()

    /** Rows no longer expand in the list (design pass, 4 Nov 2026); kept for saved state and callers. */
    fun isExpanded(i: Interaction) = false

    class Holder(val b: InteractionsRowBinding) : RecyclerView.ViewHolder(b.root) {
        var closeActionId = View.NO_ID
    }

    class SectionHolder(val b: InteractionsSectionBinding) : RecyclerView.ViewHolder(b.root)

    /** The interaction at [position], or null for a section label. */
    fun interactionAt(position: Int): Interaction? = (getItem(position) as? InteractionRow.Item)?.i

    override fun getItemViewType(position: Int) = if (getItem(position) is InteractionRow.Section) TYPE_SECTION else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) SectionHolder(InteractionsSectionBinding.inflate(inflater, parent, false))
        else Holder(InteractionsRowBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is InteractionRow.Section -> bindSection(holder as SectionHolder, row)
            is InteractionRow.Item -> bindItem(holder as Holder, row.i)
        }
    }

    private fun bindSection(h: SectionHolder, s: InteractionRow.Section) {
        val ctx = h.b.root.context
        h.b.label.text = if (s.waiting) ctx.getString(R.string.interactions_section_waiting, s.count) else ctx.getString(R.string.interactions_section_earlier)
        h.b.label.setTextColor(ctx.themeColor(if (s.waiting) M.attr.colorPrimary else M.attr.colorOnSurfaceVariant))
    }

    /**
     * Design pass (Sol 6.1 spec, 4 Nov 2026): every row is the agent's icon, the title, a status line, "agent · time"
     * and one chevron; the whole row opens the item (detail, card, stack or text panel), where it is answered or closed.
     */
    private fun bindItem(h: Holder, i: Interaction) {
        val b = h.b
        val ctx = b.root.context
        val headline = InteractionUi.headline(ctx, i)
        val title = i.title.ifBlank { headline.toString() }
        val agent = InteractionUi.agentName(i)
        val when_ = InteractionUi.listTime(ctx, i.createdAt)

        b.stripe.visibility = View.GONE
        b.avatar.setAgent(agent)
        b.headline.text = title
        val line = InteractionUi.listLine(ctx, i)
        b.summary.text = line
        b.summary.isVisible = line.isNotEmpty()
        b.summary.setTextColor(ctx.themeColor(if (i.status == IStatus.ANSWERED) R.attr.colorSuccess else M.attr.colorOnSurfaceVariant))
        b.time.text = ctx.getString(R.string.interactions_line_meta, agent, when_)

        b.open.isVisible = false
        b.closeIcon.isVisible = false
        b.chevron.isVisible = true
        b.chevron.rotation = 0f
        b.body.isVisible = false
        b.controls.removeAllViews(); b.image.setImageDrawable(null); b.image.tag = null

        // One focus stop with the full sentence (UI-29); the tap opens it, and a waiting row can also be closed from here.
        b.header.contentDescription = listOf(title, line, ctx.getString(R.string.interactions_line_meta, agent, when_)).joinToString(". ")
        b.header.setOnClickListener { onOpen(i) }
        ViewCompat.replaceAccessibilityAction(b.header, AccessibilityActionCompat.ACTION_CLICK,
            ctx.getString(R.string.interactions_open_short) + " " + title, null)
        ViewCompat.replaceAccessibilityAction(b.header, AccessibilityActionCompat.ACTION_EXPAND, null, null)
        ViewCompat.replaceAccessibilityAction(b.header, AccessibilityActionCompat.ACTION_COLLAPSE, null, null)
        ViewCompat.setStateDescription(b.header, null)
        if (h.closeActionId != View.NO_ID) { ViewCompat.removeAccessibilityAction(b.header, h.closeActionId); h.closeActionId = View.NO_ID }
        if (InteractionUi.waiting(i)) h.closeActionId = ViewCompat.addAccessibilityAction(b.header, ctx.getString(R.string.interactions_close)) { _, _ -> onClose(i); true }
    }

    private fun toggle(h: Holder) {
        val pos = h.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val i = interactionAt(pos) ?: return
        val now = !isExpanded(i)
        overrides[i.id] = now
        notifyItemChanged(pos)
        if (now) onExpanded(i)
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_ITEM = 1

        val DIFF = object : DiffUtil.ItemCallback<InteractionRow>() {
            override fun areItemsTheSame(a: InteractionRow, b: InteractionRow) = when {
                a is InteractionRow.Section && b is InteractionRow.Section -> a.waiting == b.waiting
                a is InteractionRow.Item && b is InteractionRow.Item -> a.i.id == b.i.id
                else -> false
            }
            @android.annotation.SuppressLint("DiffUtilEquals") // Section is a data class; contentKey returns a Kotlin List.
            override fun areContentsTheSame(a: InteractionRow, b: InteractionRow) = when {
                a is InteractionRow.Section && b is InteractionRow.Section -> a == b
                a is InteractionRow.Item && b is InteractionRow.Item -> InteractionUi.contentKey(a.i) == InteractionUi.contentKey(b.i)
                else -> false
            }
        }
    }
}
