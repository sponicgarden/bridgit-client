package cc.bridgit.app.link

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The grants log (LNK-22..24): every link event on this device, plus the other device's events as it reported them
 * ([Entry.reportedBy] set, never edited here). Events made here are sent to the other device over the link
 * ([Entry.delivered] false until it acknowledged); one that waited for the next contact is marked late there.
 * Capped at [MAX] entries, oldest dropped first. Never holds a code or a key: only the code's id.
 */
class LinkLog(context: Context) {
    data class Entry(
        val id: String, val at: Long, val linkId: String, val direction: String?, val event: String,
        val before: Any?, val after: Any?, val granter: Granter?, val device: String?, val codeId: String? = null,
        val reportedBy: String? = null, val late: Boolean = false, val delivered: Boolean = true,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("link_id", linkId)
            .put("direction", direction ?: JSONObject.NULL).put("event", event)
            .put("before", before ?: JSONObject.NULL).put("after", after ?: JSONObject.NULL)
            .put("granter", granter?.toJson() ?: JSONObject.NULL).put("device", device ?: JSONObject.NULL)
            .put("code_id", codeId ?: JSONObject.NULL).put("reported_by", reportedBy ?: JSONObject.NULL).put("late", late)

        fun store(): JSONObject = toJson().put("delivered", delivered)

        companion object {
            fun fromJson(o: JSONObject): Entry = Entry(o.getString("id"), o.getLong("at"), o.getString("link_id"),
                o.optString("direction").takeIf { !o.isNull("direction") && it.isNotEmpty() }, o.getString("event"),
                o.opt("before")?.takeIf { it != JSONObject.NULL }, o.opt("after")?.takeIf { it != JSONObject.NULL },
                Granter.fromJson(o.optJSONObject("granter")), o.optString("device").takeIf { !o.isNull("device") && it.isNotEmpty() },
                o.optString("code_id").takeIf { !o.isNull("code_id") && it.isNotEmpty() },
                o.optString("reported_by").takeIf { !o.isNull("reported_by") && it.isNotEmpty() }, o.optBoolean("late"),
                o.optBoolean("delivered", true))
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences("link_log_v1", Context.MODE_PRIVATE)

    @Synchronized fun all(): List<Entry> {
        val a = runCatching { JSONArray(prefs.getString("log", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { runCatching { Entry.fromJson(it) }.getOrNull() } }
    }

    /**
     * Keeps at most [MAX]: the oldest entries reported by other devices go first, then this device's delivered ones;
     * this device's undelivered entries are never dropped, so a peer can't flush them out by reporting many.
     */
    @Synchronized private fun save(list: List<Entry>) {
        var keep = list
        if (keep.size > MAX) {
            val extra = keep.size - MAX
            val drop = (keep.filter { it.reportedBy != null } + keep.filter { it.reportedBy == null && it.delivered }).sortedBy { it.at }.take(extra).map { it.id + "|" + it.reportedBy }.toSet()
            keep = keep.filter { (it.id + "|" + it.reportedBy) !in drop }
        }
        check(prefs.edit().putString("log", JSONArray(keep.map { it.store() }).toString()).commit())
    }

    /** An event made on this device; it waits to be sent to the other device. */
    @Synchronized fun add(e: Entry): Entry { save(all() + e); return e }

    /** Events the other device reported (LNK-23). A repeat (same id) is ignored; [late] marks ones it held while offline. */
    @Synchronized fun receive(from: String, events: List<Entry>) {
        val list = all(); val ids = list.map { it.id + "|" + it.reportedBy }.toSet()
        val fresh = events.take(50).filter { (it.id + "|" + from) !in ids }.map { it.copy(reportedBy = from, delivered = true) }
        if (fresh.isEmpty()) return
        // At most [PER_PEER] reported entries per link: older ones from that device go first.
        val merged = list + fresh
        val theirs = merged.filter { it.reportedBy != null && it.linkId == fresh.first().linkId }
        val over = theirs.size - PER_PEER
        save(if (over > 0) { val gone = theirs.sortedBy { it.at }.take(over).toSet(); merged.filter { it !in gone } } else merged)
    }

    /** Ids of the entries the other device of [linkId] reported, newest last: the ack sent back to it. */
    fun reportedIds(linkId: String, max: Int): List<String> = all().filter { it.linkId == linkId && it.reportedBy != null }.takeLast(max).map { it.id }

    fun undelivered(linkId: String): List<Entry> = all().filter { it.linkId == linkId && it.reportedBy == null && !it.delivered }

    @Synchronized fun markDelivered(ids: Set<String>) {
        if (ids.isEmpty()) return
        save(all().map { if (it.reportedBy == null && it.id in ids) it.copy(delivered = true) else it })
    }

    /** A link's id changed (the other device told this one the shared id): its entries follow. */
    @Synchronized fun rename(from: String, to: String) {
        val list = all(); if (list.none { it.linkId == from }) return
        save(list.map { if (it.linkId == from) it.copy(linkId = to) else it })
    }

    /** Newest first, filtered (LNK-24). [before]: entries older than this time, for paging. */
    fun query(link: String?, direction: String?, granter: String?, before: Long?, limit: Int): List<Entry> =
        all().asReversed().asSequence()
            .filter { link == null || it.linkId == link }
            .filter { direction == null || it.direction == direction || it.direction == "both" }
            .filter { g -> granter == null || g.granter?.kind == granter || g.granter?.agentId == granter || g.granter?.agentName.equals(granter, ignoreCase = true) }
            .filter { before == null || it.at < before }
            .take(limit).toList()

    companion object {
        const val MAX = 2000
        const val PER_PEER = 500
        fun newId(): String = "lev_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
    }
}
