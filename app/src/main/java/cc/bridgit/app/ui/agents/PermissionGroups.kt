package cc.bridgit.app.ui.agents

import android.content.Context
import cc.bridgit.app.R

/**
 * The grouped permission list (A0130): the same groups, order, counts and
 * master states when an agent is added and on its Agents screen afterwards.
 * Pure logic, so both surfaces (and tests) agree on what a group master does.
 */
object PermissionGroups {
    data class Item(val id: String, val title: String, val group: String)
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

    fun title(context: Context, group: String): String = when (normalize(group)) {
        "capture" -> context.getString(R.string.features_group_capture)
        "photos" -> context.getString(R.string.features_group_photos)
        "talk" -> context.getString(R.string.features_group_talk)
        "where" -> context.getString(R.string.features_group_where)
        else -> group.replaceFirstChar { it.uppercase() }
    }
}
