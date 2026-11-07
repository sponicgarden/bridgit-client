package cc.bridgit.app.lan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, app-private inventory. IP addresses are hints within one network, never stable identity. */
class DiscoveryHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("network_discovery_history", Context.MODE_PRIVATE)
    companion object {
        private val lock = Any()
        const val FRESH_MILLIS = 120_000L
    }
    private fun scopes() = runCatching { JSONObject(prefs.getString("scopes", "{}")!!) }.getOrDefault(JSONObject())
    fun read(scope: String, now: Long = System.currentTimeMillis()): List<NetworkDevices.Device> = synchronized(lock) {
        decode(scopes().optJSONObject(scope) ?: JSONObject(), now)
    }
    fun update(scope: String, observed: List<NetworkDevices.Device>, reliable: Boolean, now: Long = System.currentTimeMillis()): List<NetworkDevices.Device> = synchronized(lock) {
        val all = scopes()
        val previous = all.optJSONObject(scope) ?: JSONObject()
        val old = decode(previous, now).associateBy { it.host }.toMutableMap()
        observed.forEach { old[it.host] = it.copy(connected = true, lastSeen = now, present = true) }
        val seen = observed.map { it.host }.toSet()
        val merged = old.values.sortedByDescending { it.lastSeen }.take(256).map {
            if (reliable && it.host !in seen) it.copy(connected = false, present = false) else it
        }
        val rows = JSONArray(merged.map { d -> JSONObject().put("host", d.host).put("name", d.name)
            .put("services", JSONArray(d.services)).put("camera", d.cameraCandidate).put("models", JSONArray(d.modelHints))
            .put("connected", d.present).put("last_seen", d.lastSeen) })
        all.put(scope, JSONObject().put("updated", now).put("devices", rows))
        // Keep at most eight networks, never grow without bound.
        all.keys().asSequence().toList().sortedByDescending { all.getJSONObject(it).optLong("updated") }.drop(8).forEach(all::remove)
        check(prefs.edit().putString("scopes", all.toString()).commit()) { "Unable to save network inventory" }
        merged
    }
    private fun decode(record: JSONObject, now: Long): List<NetworkDevices.Device> {
        val rows = record.optJSONArray("devices") ?: return emptyList()
        fun strings(row: JSONObject, key: String): List<String> {
            val a = row.optJSONArray(key) ?: return emptyList()
            return (0 until minOf(a.length(), 40)).map { NetworkDevices.cleanName(a.optString(it)) }
        }
        return (0 until minOf(rows.length(), 256)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val host = row.optString("host")
            if (LanRules.ipv4(host) == null) return@mapNotNull null
            val seen = row.optLong("last_seen")
            NetworkDevices.Device(host, NetworkDevices.cleanName(row.optString("name")), strings(row,"services"),
                row.optBoolean("camera"), strings(row,"models"), row.optBoolean("connected") && now >= seen && now - seen <= FRESH_MILLIS, seen,
                present = row.optBoolean("connected"))
        }
    }
}
