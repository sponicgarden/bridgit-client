package cc.bridgit.app.portal

import android.content.Context
import org.json.JSONObject

/**
 * What each Bridgit Portal pairing last said it is (owner, 5 Nov 2026: portals need real names and details on the
 * Linked devices map, not "Bridgit Portal 2"). Kept across restarts: [kind] (iPhone, iPad, Android) from `client`,
 * [device] ("iPhone 16 Pro · iOS 26.1 · Portal 1.4") from `client_device`, and when it was last heard.
 */
object PortalClients {
    data class Info(val kind: String?, val device: String?, val at: Long, val sentType: cc.bridgit.app.platform.DeviceType? = null) {
        /** The name for the map: the model ("iPhone 16 Pro"), else the kind. */
        val name: String? get() = device?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() } ?: kind
        /** What kind of device it is: what it sent (client_type), else read from its kind or model ("iPad" → tablet). */
        val type: cc.bridgit.app.platform.DeviceType? get() = sentType ?: guess(kind) ?: guess(device)
    }

    /** Portals that don't send client_type (the iPhone/iPad app, older versions): "iPad" is a tablet, "iPhone" a phone, "Mac" a computer. */
    fun guess(s: String?): cc.bridgit.app.platform.DeviceType? {
        val v = s?.lowercase() ?: return null
        return when {
            "ipad" in v -> cc.bridgit.app.platform.DeviceType.TABLET
            "iphone" in v -> cc.bridgit.app.platform.DeviceType.PHONE
            "mac" in v -> cc.bridgit.app.platform.DeviceType.COMPUTER
            else -> null
        }
    }

    private const val PREFS = "portal_clients"
    private const val SAVE_EVERY_MS = 60_000L
    private val KIND_RE = Regex("[A-Za-z0-9 ._-]{1,24}")
    private val DEVICE_RE = Regex("[\\p{L}\\p{N} ._,()+·-]{1,80}")
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Info>()

    fun note(context: Context, agentId: String, kind: String?, device: String?, type: String? = null, now: Long = System.currentTimeMillis()) {
        val k = kind?.trim()?.takeIf { KIND_RE.matches(it) }
        val d = device?.trim()?.takeIf { DEVICE_RE.matches(it) }
        val t = cc.bridgit.app.platform.DeviceType.parse(type)
        if (k == null && d == null && t == null) return
        val old = get(context, agentId)
        val next = Info(k ?: old?.kind, d ?: old?.device, now, t ?: old?.sentType)
        cache[agentId] = next
        // Same device and saved within the last minute: nothing new to write.
        if (old != null && old.kind == next.kind && old.device == next.device && old.sentType == next.sentType && now - old.at < SAVE_EVERY_MS) return
        prefs(context).edit().putString(agentId, JSONObject().put("kind", next.kind).put("device", next.device).put("at", now)
            .put("type", next.sentType?.wire).toString()).apply()
    }

    fun get(context: Context, agentId: String): Info? = cache[agentId] ?: prefs(context).getString(agentId, null)?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()?.let { o ->
            Info(o.optString("kind").ifEmpty { null }, o.optString("device").ifEmpty { null }, o.optLong("at"), cc.bridgit.app.platform.DeviceType.parse(o.optString("type"))).also { cache[agentId] = it }
        }
    }

    fun forget(context: Context, agentId: String) { cache.remove(agentId); prefs(context).edit().remove(agentId).apply() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
