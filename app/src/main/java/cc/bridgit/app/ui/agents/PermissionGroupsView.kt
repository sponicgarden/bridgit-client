package cc.bridgit.app.ui.agents

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cc.bridgit.app.R
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * The grouped permission switches (A0130), shared by agents, this device and linked devices (A0116: one treatment):
 * one row per group with its master (all, some or none), "n of m" and a chevron, expandable to the single
 * permissions. Each permission's line says what decided it when that isn't a specific choice (A0115 point 6):
 * an inherited default, on via Superpowers, or unavailable for a linked device.
 */
class PermissionGroupsView(
    private val context: Context,
    private val items: List<PermissionGroups.Item>,
    private val perms: () -> List<PermissionHome.Perm>,
    private val set: (ids: Collection<String>, on: Boolean) -> Unit,
) {
    val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val expanded = mutableSetOf<String>()
    private var binding = false

    private class Row(val header: LinearLayout, val title: String, val master: MaterialCheckBox, val count: TextView, val chevron: ImageView,
                      val children: LinearLayout, val boxes: Map<String, MaterialCheckBox>)
    private val rows = mutableMapOf<String, Row>()
    private val byId = items.associateBy { it.id }

    init {
        val initial = perms().associateBy { it.id }
        for (group in PermissionGroups.of(items) { initial[it]?.on == true }) {
            val title = PermissionGroups.title(context, group.key)
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(48)
                isClickable = true; isFocusable = true
                setBackgroundResource(android.util.TypedValue().also {
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId)
            }
            val master = MaterialCheckBox(context).apply { contentDescription = title }
            val name = TextView(context).apply {
                text = title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val count = TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(context.dp(8), 0, context.dp(4), 0)
            }
            val chevron = ImageView(context).apply {
                setImageResource(R.drawable.interactions_ic_expand)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            header.addView(master); header.addView(name); header.addView(count); header.addView(chevron)
            val children = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(32), 0, 0, 0)
                visibility = View.GONE
            }
            val boxes = group.items.associate { item ->
                item.id to MaterialCheckBox(context).apply {
                    minHeight = context.dp(48)
                    setPadding(paddingLeft, context.dp(8), paddingRight, context.dp(8))
                    setOnCheckedChangeListener { _, on -> if (!binding) { set(listOf(item.id), on); refresh() } }
                }
            }
            boxes.values.forEach { children.addView(it) }
            master.addOnCheckedStateChangedListener { _, state ->
                if (binding) return@addOnCheckedStateChangedListener
                val now = perms().associateBy { it.id }
                // A tap on a partial group turns the whole group on, as far as each permission can be changed.
                set(group.items.map { it.id }.filter { now[it]?.canChange != false }, state != MaterialCheckBox.STATE_UNCHECKED)
                refresh()
            }
            header.setOnClickListener {
                if (!expanded.remove(group.key)) expanded.add(group.key)
                refresh()
            }
            view.addView(header); view.addView(children)
            rows[group.key] = Row(header, title, master, count, chevron, children, boxes)
        }
        refresh()
    }

    /** Opens every group (the API-equivalent "show all" when the owner asked to see one permission). */
    fun expandAll() { expanded += rows.keys; refresh() }

    fun refresh() {
        val now = perms()
        val state = now.associateBy { it.id }
        binding = true
        for (group in PermissionGroups.of(items) { state[it]?.on == true }) {
            val row = rows[group.key] ?: continue
            row.master.checkedState = when (group.state) {
                PermissionGroups.State.ON -> MaterialCheckBox.STATE_CHECKED
                PermissionGroups.State.PARTIAL -> MaterialCheckBox.STATE_INDETERMINATE
                PermissionGroups.State.OFF -> MaterialCheckBox.STATE_UNCHECKED
            }
            row.master.isEnabled = group.items.any { state[it.id]?.canChange != false }
            row.count.text = context.getString(R.string.agents_group_count, group.granted, group.items.size)
            row.boxes.forEach { (id, box) ->
                val p = state[id] ?: return@forEach
                box.isChecked = p.on
                box.isEnabled = p.canChange
                box.text = label(byId[id] ?: return@forEach, p)
            }
            val open = group.key in expanded
            row.children.visibility = if (open) View.VISIBLE else View.GONE
            row.chevron.rotation = if (open) 180f else 0f
            row.header.contentDescription = "${row.title}, ${row.count.text}, " + if (open) "expanded" else "collapsed"
        }
        binding = false
    }

    /** Title, then what it does, then what decided it unless it was a specific choice. */
    private fun label(item: PermissionGroups.Item, p: PermissionHome.Perm): CharSequence {
        val tag = when (p.layer) {
            PermissionHome.Layer.DEFAULT -> context.getString(R.string.perm_layer_default)
            PermissionHome.Layer.SUPERPOWERS -> context.getString(R.string.perm_layer_super)
            PermissionHome.Layer.UNAVAILABLE -> context.getString(R.string.perm_layer_unavailable)
            else -> null
        }
        val detail = PermissionGroups.detail(item)
        if (detail == null && tag == null) return item.title
        return android.text.SpannableStringBuilder(item.title).apply {
            if (detail != null) {
                append('\n'); val start = length; append(detail)
                setSpan(android.text.style.TextAppearanceSpan(context, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium), start, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)), start, length, 0)
            }
            if (tag != null) {
                append('\n'); val start = length; append(tag)
                setSpan(android.text.style.TextAppearanceSpan(context, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium), start, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(context.themeColor(
                    if (p.layer == PermissionHome.Layer.SUPERPOWERS) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant)), start, length, 0)
            }
        }
    }
}
