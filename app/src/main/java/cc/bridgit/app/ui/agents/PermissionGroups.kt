package cc.bridgit.app.ui.agents

import android.content.Context
import cc.bridgit.app.R

/**
 * The grouped permission list (A0130): the same groups, order, counts and
 * master states when an agent is added and on its Agents screen afterwards.
 * Pure logic, so both surfaces (and tests) agree on what a group master does.
 */
object PermissionGroups {
    /** [description] says what the permission does, where and when (A0219); a bare title said none of that. */
    data class Item(val id: String, val title: String, val group: String, val description: String = "")
    enum class State { ON, PARTIAL, OFF }
    data class Group(val key: String, val items: List<Item>, val granted: Int) {
        val state: State get() = when (granted) { 0 -> State.OFF; items.size -> State.ON; else -> State.PARTIAL }
    }

    /** One group per capability group, in the order the groups first appear. */
    fun of(items: List<Item>, allows: (String) -> Boolean): List<Group> =
        items.groupBy { normalize(it.group) }.map { (key, list) -> Group(key, list, list.count { allows(it.id) }) }

    /** The grant set after a group master (or one item) is switched; null [caps] means every capability. */
    fun set(caps: Set<String>?, all: Collection<String>, ids: Collection<String>, on: Boolean): Set<String> {
        val current = caps ?: all.toSet()
        return if (on) current + ids else current - ids.toSet()
    }

    /** "control" and "controls" are one group to a person. */
    fun normalize(group: String): String = if (group == "control") "controls" else group

    /** The line under a permission's title, or null when there is nothing more to say. */
    fun detail(item: Item): String? = item.description.trim().takeIf { it.isNotEmpty() && !it.equals(item.title.trim(), ignoreCase = true) }

    /** A person's name for every capability group; null only for a group added without one. */
    fun titleRes(group: String): Int? = when (normalize(group)) {
        "capture" -> R.string.features_group_capture
        "photos" -> R.string.features_group_photos
        "talk" -> R.string.features_group_talk
        "where" -> R.string.features_group_where
        "status" -> R.string.agents_perm_group_status
        "apps" -> R.string.agents_perm_group_apps
        "files" -> R.string.agents_perm_group_files
        "context" -> R.string.agents_perm_group_context
        "private context" -> R.string.agents_perm_group_private
        "controls" -> R.string.agents_perm_group_controls
        else -> null
    }

    fun title(context: Context, group: String): String =
        titleRes(group)?.let { context.getString(it) } ?: group.replaceFirstChar { it.uppercase() }
}
