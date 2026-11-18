package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/**
 * A camera applet's enforced settings (A0451 phases 1.2-1.3, docs/plans/a0451-camera-applet-privacy.md), stored with
 * the installed applet as `camera_privacy` so the window survives a restart: the tables whose rows justify keeping a
 * photo, the package's window, the backend's override (shorten-only, none yet) and each table's effective window
 * ([CameraPrivacy.effectiveDays]). Only applets whose package declares `privacy.camera` have one; the others keep
 * today's behaviour.
 */
data class CameraPolicy(val instanceId: String, val keepWhen: Set<String>, val retentionDays: Int, val backendOverride: Int?, val tableDays: Map<String, Int>) {
    /** Days rows of [table] (a keep_when table) and their photos are kept. */
    fun days(table: String): Int? = tableDays[table]

    fun json(): JSONObject = JSONObject().put("keep_when", JSONArray(keepWhen.toList())).put("retention_days", retentionDays)
        .put("backend_override", backendOverride ?: JSONObject.NULL).put("effective_days", JSONObject().apply { tableDays.forEach { (t, d) -> put(t, d) } })

    companion object {
        const val FIELD = "camera_privacy"

        fun build(instanceId: String, privacy: CameraPrivacy, tables: List<AppletTables.Table>, backendOverride: Int?): CameraPolicy {
            val byId = tables.associateBy { it.id }
            return CameraPolicy(instanceId, privacy.keepWhenTables, privacy.retentionDays, backendOverride,
                privacy.keepWhenTables.associateWith { CameraPrivacy.effectiveDays(privacy, byId[it], backendOverride) })
        }

        /** The stored settings of an installed applet record, or null when it has none. */
        fun fromJson(instanceId: String, o: JSONObject?): CameraPolicy? {
            if (o == null) return null
            val kw = o.optJSONArray("keep_when") ?: return null
            val eff = o.optJSONObject("effective_days") ?: JSONObject()
            return CameraPolicy(instanceId, (0 until kw.length()).map { kw.optString(it) }.filter { it.isNotEmpty() }.toSet(),
                o.optInt("retention_days", CameraPrivacy.MAX_DAYS),
                if (o.isNull("backend_override") || !o.has("backend_override")) null else o.optInt("backend_override"),
                eff.keys().asSequence().associateWith { eff.optInt(it, CameraPrivacy.MIN_DAYS).coerceIn(CameraPrivacy.MIN_DAYS, CameraPrivacy.MAX_DAYS) })
        }

        /** The installed applet's settings: the stored ones, else derived from its definition; null for an applet without privacy.camera. */
        fun of(instance: JSONObject?): CameraPolicy? {
            if (instance == null) return null
            val id = instance.optString("id")
            fromJson(id, instance.optJSONObject(FIELD))?.let { return it }
            return derive(instance, null)
        }

        private fun derive(instance: JSONObject, backendOverride: Int?): CameraPolicy? {
            val d = instance.optJSONObject("definition") ?: return null
            val privacy = runCatching { CameraPrivacy.parse(d) }.getOrNull() ?: return null
            val tables = runCatching { AppletTables.parse(d.optJSONObject("processing") ?: JSONObject()) }.getOrDefault(emptyList())
            return build(instance.optString("id"), privacy, tables, backendOverride)
        }

        /**
         * Writes [FIELD] on an installed applet record from its current definition (keeping a backend override already
         * stored), or removes it when the definition has no privacy.camera. Called whenever the record is saved.
         */
        fun stamp(instance: JSONObject): JSONObject {
            val old = instance.optJSONObject(FIELD)
            val override = old?.takeIf { it.has("backend_override") && !it.isNull("backend_override") }?.optInt("backend_override")
            val p = derive(instance, override)
            if (p == null) instance.remove(FIELD) else instance.put(FIELD, p.json())
            return instance
        }
    }
}

/**
 * One keep, discard or delete of camera material, for the privacy log (phase 1.4 logs and ships these). Never bytes:
 * [ref] is an opaque kept-photo ref (`kept:<id>`) or null, [count] how many items one entry stands for (a flow run's
 * discards are one entry, D5), [photos] how many kept photos went with deleted rows.
 */
data class PrivacyEvent(val ts: Long, val appletInstanceId: String, val agentId: String, val table: String?, val ref: String?,
                        val action: String, val reason: String, val count: Int = 1, val photos: Int = 0) {
    fun json(): JSONObject = JSONObject().put("ts", ts).put("applet_instance_id", appletInstanceId).put("agent_id", agentId)
        .put("table", table ?: JSONObject.NULL).put("ref", ref ?: JSONObject.NULL).put("action", action).put("reason", reason)
        .put("count", count).put("photos", photos)
}

/** The recent privacy events on this phone, in memory and bounded (the durable log is phase 1.4). */
class PrivacyEvents(private val max: Int = 1000) {
    private val list = ArrayDeque<PrivacyEvent>()
    @Synchronized fun add(e: PrivacyEvent) { list.addLast(e); while (list.size > max) list.removeFirst() }
    @Synchronized fun all(): List<PrivacyEvent> = list.toList()
    @Synchronized fun forInstance(id: String): List<PrivacyEvent> = list.filter { it.appletInstanceId == id }
}

/**
 * The only route from a camera frame to persistent storage for applets that declare privacy.camera (A0451 §3.2).
 * A flow's `keep: true` photo is [hold]-ed in memory under a reserved ref; a row of this run written to a keep_when
 * table that names the ref [claim]s it, and only then does [AppletTableStore] write it. When the run ends, [endRun]
 * drops whatever is still held and records one discard with the count. Pure: the store does the file writes.
 */
class CameraPrivacyGate(val events: PrivacyEvents = PrivacyEvents(), private val now: () -> Long = System::currentTimeMillis) {
    class Held(val policy: CameraPolicy, val agentId: String, val run: String, val stepId: String, val key: String, val bytes: ByteArray) {
        val ref get() = "kept:$key"
    }

    private val held = LinkedHashMap<String, Held>()
    private fun id(agentId: String, ref: String) = "$agentId\u0000$ref"

    /** Holds [bytes] for flow run [run] and returns the reserved ref `kept:<key>` the run's rows may name. */
    @Synchronized fun hold(policy: CameraPolicy, agentId: String, run: String, stepId: String, key: String, bytes: ByteArray): String {
        val h = Held(policy, agentId, run, stepId, key, bytes)
        held[id(agentId, h.ref)] = h
        return h.ref
    }

    @Synchronized fun isHeld(agentId: String, ref: String): Boolean = held.containsKey(id(agentId, ref))

    /** The bytes of a held photo (a later step of the same run reads them, as it would a kept file). */
    @Synchronized fun bytes(agentId: String, ref: String): ByteArray? = held[id(agentId, ref)]?.bytes

    /** Whether a row of [table] written by [run] may keep held photo [ref]: same run, and [table] is in keep_when. */
    @Synchronized fun mayClaim(agentId: String, run: String?, table: String, ref: String): Boolean {
        val h = held[id(agentId, ref)] ?: return false
        return run != null && h.run == run && table in h.policy.keepWhen
    }

    /** Takes held [ref] out of memory for writing (call [mayClaim] first); null when it isn't held. */
    @Synchronized fun claim(agentId: String, ref: String): Held? = held.remove(id(agentId, ref))

    /** A claimed photo was written: one keep. */
    fun kept(h: Held, table: String, reason: String = "keep_when_row") =
        events.add(PrivacyEvent(now(), h.policy.instanceId, h.agentId, table, h.ref, "keep", reason))

    /** A claimed photo couldn't be kept after all (its row was refused): it is gone, one discard. */
    fun lost(h: Held, table: String, reason: String) =
        events.add(PrivacyEvent(now(), h.policy.instanceId, h.agentId, table, null, "discard", reason))

    /** Run [run] ended: every photo it still holds is dropped (never stored). Returns how many; one event when any. */
    fun endRun(agentId: String, run: String, reason: String = "no_keep_when_row"): Int {
        val gone = synchronized(this) {
            val g = held.values.filter { it.agentId == agentId && it.run == run }
            g.forEach { held.remove(id(agentId, it.ref)) }
            g
        }
        if (gone.isNotEmpty()) events.add(PrivacyEvent(now(), gone.first().policy.instanceId, agentId, null, null, "discard", reason, gone.size))
        return gone.size
    }

    /** The clean-up deleted [rows] rows of [table] (and [photos] kept photos) because the window passed. */
    fun deleted(policy: CameraPolicy, agentId: String, table: String?, rows: Int, photos: Int, reason: String) {
        if (rows > 0 || photos > 0) events.add(PrivacyEvent(now(), policy.instanceId, agentId, table, null, "delete", reason, rows, photos))
    }
}
