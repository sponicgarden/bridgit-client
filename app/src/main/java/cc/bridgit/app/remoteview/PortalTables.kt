package cc.bridgit.app.remoteview

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Applets whose web portal is their interface (owner, 5 Nov 2026: "The insurance app should just have the webportal
 * on a phone/tablet as its interface"). The package declares, under `ui.portal`:
 *
 * - `tables`: the applet's own tables (processing.tables ids, 1-3) the phone keeps copied to the portal, rows and
 *   photos, for as long as the portal is on;
 * - `actions`: ids of the card's buttons the portal page may press on this phone, through the app's web view
 *   ([PortalBridge]); the card itself then stays off the Cards inbox: the portal is where the owner uses it;
 * ([PortalRecords.always] says whether the portal comes with the applet, `ui.portal.include`.)
 *
 * Pure Kotlin, unit-tested: the declaration checks and the row diff that decides what each push carries.
 */
object PortalTables {
    private val ID = Regex("^[a-z][a-z0-9_-]{0,31}$")
    const val MAX_UPSERTS = 200
    /** Rows a portal keeps per table (the backend's cap too). */
    const val MAX_ROWS = 1000

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()
    private fun portal(definition: JSONObject?): JSONObject? = definition?.optJSONObject("ui")?.optJSONObject("portal")

    /** The tables [definition] shows on its portal (empty when none). */
    fun tablesOf(definition: JSONObject?): List<String> = strings(portal(definition)?.optJSONArray("tables")).filter { ID.matches(it) }

    /** The card buttons the portal may press. */
    fun actionsOf(definition: JSONObject?): List<String> = strings(portal(definition)?.optJSONArray("actions")).filter { ID.matches(it) }

    /** Whether the applet's card stays off the Cards inbox because its portal is its interface. */
    fun portalIsInterface(definition: JSONObject?): Boolean = tablesOf(definition).isNotEmpty()

    /**
     * Checks `ui.portal.tables` and `actions` against the package's [tableIds] and the card's
     * [buttonIds]. Null when fine (or absent), else the problem.
     */
    fun check(ui: JSONObject?, tableIds: Set<String>, buttonIds: Set<String>, maxRows: Map<String, Int> = emptyMap()): String? {
        val portal = ui?.optJSONObject("portal") ?: return null
        if (portal.has("tables") && portal.has("records")) return "ui.portal takes tables or records, not both."
        portal.opt("tables")?.let { t ->
            if (t !is JSONArray || t.length() !in 1..3) return "ui.portal.tables must list 1-3 of the applet's table ids."
            for (i in 0 until t.length()) { val id = t.opt(i) as? String
                if (id == null || id !in tableIds) return "ui.portal.tables: ${t.opt(i)} isn't one of the applet's tables (${tableIds.joinToString()})."
                if ((maxRows[id] ?: 0) > MAX_ROWS) return "ui.portal.tables: $id keeps up to ${maxRows[id]} rows; a portal shows at most $MAX_ROWS (set its max_rows to $MAX_ROWS or fewer)." }
        }
        portal.opt("actions")?.let { a ->
            if (!portal.has("tables")) return "ui.portal.actions needs ui.portal.tables (the portal they act on)."
            if (a !is JSONArray || a.length() > 8) return "ui.portal.actions must list at most 8 of the card's button ids."
            for (i in 0 until a.length()) { val id = a.opt(i) as? String
                if (id == null || id !in buttonIds) return "ui.portal.actions: ${a.opt(i)} isn't the id of a button on the applet's card." }
        }
        return null
    }

    /** The ids of every button on a pieces card. */
    fun buttonIds(card: JSONObject?): Set<String> {
        val pieces = card?.optJSONArray("pieces") ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until pieces.length()) {
            val p = pieces.optJSONObject(i) ?: continue
            if (p.optString("type") != "buttons") continue
            val items = p.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) items.optJSONObject(j)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    /** The button with [id] on a pieces card, or null. */
    fun button(card: JSONObject?, id: String): JSONObject? {
        val pieces = card?.optJSONArray("pieces") ?: return null
        for (i in 0 until pieces.length()) {
            val items = pieces.optJSONObject(i)?.takeIf { it.optString("type") == "buttons" }?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) items.optJSONObject(j)?.takeIf { it.optString("id") == id }?.let { return it }
        }
        return null
    }

    /** A row as the portal stores it: {id, at, values} (the reader's view minus `by`). */
    fun wire(row: JSONObject): JSONObject {
        val values = JSONObject()
        for (k in row.keys()) if (k != "id" && k != "at" && k != "by") values.put(k, row.opt(k))
        return JSONObject().put("id", row.optString("id")).put("at", row.optLong("at")).put("values", values)
    }

    fun digest(row: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(wire(row).toString().toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }

    data class Diff(val upserts: List<JSONObject>, val deletes: List<String>, val sent: Map<String, String>, val more: Boolean)

    /**
     * What to send for one table: rows that are new or changed since [sent] (row id → digest), at most [max], and the
     * ids that are gone. [sent] after this push comes back so the caller saves it once the push is confirmed. A row
     * that isn't [ready] yet is left for a later push (and never counted as gone).
     */
    fun diff(rows: List<JSONObject>, sent: Map<String, String>, max: Int = MAX_UPSERTS, ready: (JSONObject) -> Boolean = { true }): Diff {
        val now = rows.associate { it.optString("id") to it }
        val deletes = sent.keys.filter { it !in now }
        val next = HashMap(sent); deletes.forEach { next.remove(it) }
        val upserts = ArrayList<JSONObject>()
        var more = false
        for ((id, r) in now) {
            val d = digest(r)
            if (sent[id] == d) continue
            if (!ready(r)) { more = true; continue } // e.g. its photo isn't on the portal yet: next push
            if (upserts.size >= max) { more = true; continue }
            upserts += wire(r); next[id] = d
        }
        return Diff(upserts, deletes, next, more)
    }

    /** The photo refs [rows] hold in [photoColumns]. */
    fun photoRefs(rows: List<JSONObject>, photoColumns: Collection<String>): List<String> =
        rows.flatMap { r -> photoColumns.mapNotNull { c -> r.optString(c).takeIf { it.isNotEmpty() && it != "null" } } }.distinct()

    /** A `set` from the portal: only the table's own columns, never id, at or by. */
    fun cleanSet(set: JSONObject, columns: Collection<String>, photoColumns: Collection<String>): JSONObject {
        val out = JSONObject()
        for (k in set.keys()) if (k in columns && k !in photoColumns) out.put(k, set.opt(k))
        return out
    }
}
