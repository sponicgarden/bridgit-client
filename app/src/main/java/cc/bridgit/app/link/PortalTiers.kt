package cc.bridgit.app.link

import android.content.Context
import org.json.JSONObject

/**
 * Trust tiers (§16) of the links that aren't [Links] records: Portal pairings (an iPhone, iPad or phone viewing this
 * one: key [viewer]) and this phone's own views of other phones (key [viewing]). A Portal pairing is made from a
 * one-time link that can be handed to anyone, so one without a tier reads as [LinkTier.ASSOCIATE] with a review date
 * [LinkTierPolicy.MIGRATED_REVIEW_MS] away (never a cut-off) until the owner says it is one of their own devices.
 */
class PortalTiers(context: Context) {
    data class Entry(val tier: LinkTier, val reviewAt: Long? = null, val expiresAt: Long? = null, val associate: String? = null, val associateEmail: String? = null) {
        fun expired(now: Long = System.currentTimeMillis()) = expiresAt != null && now >= expiresAt
        fun toJson(): JSONObject = JSONObject().put("tier", tier.wire).put("review_at", reviewAt ?: JSONObject.NULL)
            .put("expires_at", expiresAt ?: JSONObject.NULL).put("associate", associate ?: JSONObject.NULL).put("associate_email", associateEmail ?: JSONObject.NULL)
        companion object {
            fun fromJson(o: JSONObject) = Entry(LinkTier.parseOrPublic(o.optString("tier")),
                o.optLong("review_at").takeIf { !o.isNull("review_at") && it > 0 }, o.optLong("expires_at").takeIf { !o.isNull("expires_at") && it > 0 },
                o.optString("associate").takeIf { !o.isNull("associate") && it.isNotEmpty() },
                o.optString("associate_email").takeIf { !o.isNull("associate_email") && it.isNotEmpty() })
        }
    }

    private val prefs = context.getSharedPreferences("portal_tiers_v1", Context.MODE_PRIVATE)

    /** The entry for [key]; a pairing seen for the first time is classified (and saved) as described above. */
    @Synchronized fun get(key: String, now: Long = System.currentTimeMillis()): Entry {
        prefs.getString(key, null)?.let { raw -> runCatching { return Entry.fromJson(JSONObject(raw)) } }
        val e = if (key.startsWith(VIEWER)) Entry(LinkTier.ASSOCIATE, reviewAt = now + LinkTierPolicy.MIGRATED_REVIEW_MS) else Entry(LinkTier.ASSOCIATE)
        set(key, e); return e
    }

    @Synchronized fun set(key: String, e: Entry) { check(prefs.edit().putString(key, e.toJson().toString()).commit()) }
    @Synchronized fun forget(key: String) { prefs.edit().remove(key).apply() }
    fun known(key: String) = prefs.contains(key)

    companion object {
        const val VIEWER = "viewer:"
        const val VIEWING = "viewing:"
        fun viewer(agentId: String) = VIEWER + agentId
        fun viewing(device: String) = VIEWING + device

        /** Ids the configuration API accepts for these: portal:<agent id> and viewing:<device id>. */
        fun keyFromApiId(id: String): String? = when {
            id.startsWith("portal:") -> viewer(id.removePrefix("portal:"))
            id.startsWith(VIEWING) -> id
            else -> null
        }
    }
}
