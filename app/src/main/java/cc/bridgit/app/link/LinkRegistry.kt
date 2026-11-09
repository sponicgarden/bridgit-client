package cc.bridgit.app.link

import android.content.Context
import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registering links with the backend by tier (docs/spec-linked-bridgits.md §16.3; bridgit-cloud
 * `feedback/src/device-links.mjs`, sponicgarden/bridgit-cloud#221). Each call is signed by this install, as the
 * other team-backend calls ([cc.bridgit.app.feedback.DeviceNames.call]).
 *
 * The host of a link (the device whose services the other one uses: this device's "in" direction) registers it with
 * `POST /v1/device-links {tier, kind, peer, label, services, approval, expires_at, idempotency_key}` and keeps it
 * current with `PATCH /v1/device-links/{id}`; the other device confirms its end with
 * `POST /v1/device-links/{id}/confirm` once the host tells it the id (link events carry `state.registration`). Either
 * revokes with `DELETE`. The backend decides whether the tier fits from verified accounts; changing the tier there is
 * revoking and registering again. A refusal (for example `tier_not_verifiable` until this phone is linked to its
 * owner's Google account, or `tier_mismatch`) is recorded and shown with the link; it never widens anything here.
 *
 * Operations wait in [Outbox] (the latest per link) until sent, so a phone offline or not yet signed in loses none.
 * Never sent: keys, codes or the claim token. An associate's email is sent because the backend names the person by it.
 */
interface LinkRegistry {
    fun register(context: Context, r: Links.Rec)
    fun update(context: Context, r: Links.Rec)
    fun revoke(context: Context, r: Links.Rec, reason: String)

    /** One link as the backend's host registration needs it. */
    data class Registration(val op: String, val linkId: String, val tier: LinkTier, val peerInstall: String?, val inServices: Set<String>,
                            val expiresAt: Long?, val reviewAt: Long?, val reason: String? = null, val at: Long = System.currentTimeMillis(),
                            val kind: String = "linked_bridgit", val label: String? = null, val email: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("op", op).put("link_id", linkId).put("tier", tier.wire)
            .put("peer_install", peerInstall ?: JSONObject.NULL).put("services", LinkModel.json(inServices))
            .put("expires_at", expiresAt ?: JSONObject.NULL).put("review_at", reviewAt ?: JSONObject.NULL)
            .put("reason", reason ?: JSONObject.NULL).put("at", at).put("kind", kind).put("label", label ?: JSONObject.NULL)
            .put("email", email ?: JSONObject.NULL)

        companion object {
            fun of(op: String, r: Links.Rec, reason: String? = null) = Registration(op, r.id, r.effectiveTier, r.peerInstall,
                LinkTierPolicy.effectiveServices(r.effectiveTier, r.inServices), r.expiresAt, r.reviewAt, reason,
                kind = if (r.inGranter?.kind == Granter.MIGRATED) "satellite" else "linked_bridgit", label = r.peerName, email = r.associateEmail)

            fun fromJson(o: JSONObject): Registration? = runCatching {
                fun s(k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotEmpty() }
                val a = o.optJSONArray("services") ?: JSONArray()
                Registration(o.getString("op"), o.getString("link_id"), LinkTier.parseOrPublic(o.optString("tier")), s("peer_install"),
                    (0 until a.length()).map { a.getString(it) }.toSet(), o.optLong("expires_at").takeIf { !o.isNull("expires_at") && it > 0 },
                    o.optLong("review_at").takeIf { !o.isNull("review_at") && it > 0 }, s("reason"), o.optLong("at"), s("kind") ?: "linked_bridgit", s("label"), s("email"))
            }.getOrNull()
        }
    }

    /** Operations waiting to be sent: the latest per link, at most [MAX] links. */
    object Outbox : LinkRegistry {
        const val MAX = 200
        private const val PREFS = "link_registry_outbox_v1"

        override fun register(context: Context, r: Links.Rec) = put(context, Registration.of("register", r))
        override fun update(context: Context, r: Links.Rec) = put(context, Registration.of("update", r))
        override fun revoke(context: Context, r: Links.Rec, reason: String) = put(context, Registration.of("revoke", r, reason))

        @Synchronized fun pending(context: Context): List<JSONObject> {
            val a = runCatching { JSONArray(prefs(context).getString("q", "[]")) }.getOrDefault(JSONArray())
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }

        @Synchronized fun put(context: Context, reg: Registration) {
            runCatching {
                val list = pending(context).filter { it.optString("link_id") != reg.linkId } + reg.toJson()
                prefs(context).edit().putString("q", JSONArray(list.takeLast(MAX)).toString()).apply()
            }
        }

        /** Drops [linkId]'s operation once sent, unless a newer one replaced it meanwhile. */
        @Synchronized fun done(context: Context, linkId: String, at: Long) {
            val list = pending(context).filterNot { it.optString("link_id") == linkId && it.optLong("at") == at }
            prefs(context).edit().putString("q", JSONArray(list).toString()).apply()
        }

        fun has(context: Context, linkId: String) = pending(context).any { it.optString("link_id") == linkId }

        @Synchronized fun clear(context: Context) { prefs(context).edit().remove("q").apply() }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Queues in [Outbox] and sends in the background; [drain] also runs from the periodic link check. */
    object Backend : LinkRegistry {
        /** Test hook: unit tests never call the team backend. */
        @Volatile internal var enabled = true
        private const val PREFS = "link_registry_v1"
        /** Errors that are the backend's answer about this link, not a passing failure: kept and retried a day later. */
        private val REFUSALS = setOf("tier_not_verifiable", "tier_mismatch", "service_not_allowed", "invalid_parameter", "too_many_links", "idempotency_conflict",
            "wrong_account", "host_only")

        override fun register(context: Context, r: Links.Rec) { Outbox.register(context, r); kick(context) }
        override fun update(context: Context, r: Links.Rec) { Outbox.update(context, r); kick(context) }
        override fun revoke(context: Context, r: Links.Rec, reason: String) { Outbox.revoke(context, r, reason); kick(context) }

        private fun kick(context: Context) {
            val app = context.applicationContext as? BridgeApp ?: return
            if (enabled) kotlin.concurrent.thread(isDaemon = true, name = "link-registry") { runCatching { drain(app) } }
        }

        /** What the backend last said about the registration this device hosts for link [linkId]: {id (dlk_…), tier, state, role, error, at}, or null. */
        fun status(context: Context, linkId: String): JSONObject? =
            prefs(context).getString(linkId, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

        /** This device's confirmation of the other device's registration of link [linkId] (the direction it hosts), or null. */
        fun peerStatus(context: Context, linkId: String): JSONObject? = status(context, PEER + linkId)
        private const val PEER = "peer:"

        private fun save(context: Context, linkId: String, o: JSONObject?) {
            prefs(context).edit().apply { if (o == null) remove(linkId) else putString(linkId, o.put("at", System.currentTimeMillis()).toString()) }.apply()
        }

        /** Sends every waiting operation. Blocking; returns how many were settled (sent or refused for good). */
        @Synchronized fun drain(app: BridgeApp): Int {
            if (!enabled) return 0
            var settled = 0
            for (o in Outbox.pending(app)) {
                val reg = Registration.fromJson(o) ?: continue
                val ok = runCatching { send(app, reg) }.getOrElse { false }
                if (ok) { Outbox.done(app, reg.linkId, reg.at); settled++ }
            }
            return settled
        }

        /** True when settled; false to retry later. */
        private fun send(app: BridgeApp, reg: Registration): Boolean {
            val st = status(app, reg.linkId)
            val known = st?.optString("id")?.takeIf { DLK_RE.matches(it) }
            if (reg.op == "revoke" || reg.inServices.isEmpty()) {
                // Revoked, or this device no longer hosts anything on the link.
                if (known != null) {
                    val (code, _) = call(app, "DELETE", "/v1/device-links/$known", null)
                    if (code !in 200..299 && code != 404) return false
                }
                save(app, reg.linkId, null)
                if (reg.op == "revoke") peerStatus(app, reg.linkId)?.optString("id")?.takeIf { DLK_RE.matches(it) }?.let { dlk ->
                    // Either side can revoke: this device ends the other one's registration of the link too.
                    runCatching { call(app, "DELETE", "/v1/device-links/$dlk", null) }; save(app, PEER + reg.linkId, null)
                }
                return true
            }
            // A different tier is a new registration there: the old one is revoked first.
            if (known != null && st?.optString("tier") != reg.tier.wire) {
                val (code, _) = call(app, "DELETE", "/v1/device-links/$known", null)
                if (code !in 200..299 && code != 404) return false
            } else if (known != null) {
                val body = JSONObject().put("services", JSONArray(reg.inServices.sorted())).put("label", reg.label ?: JSONObject.NULL)
                if (reg.tier != LinkTier.OWN) body.put("expires_at", reg.expiresAt ?: JSONObject.NULL)
                val (code, r) = call(app, "PATCH", "/v1/device-links/$known", body)
                if (code == 404) { save(app, reg.linkId, null); return send(app, reg) }
                return settle(app, reg, code, r, known)
            }
            val body = JSONObject().put("tier", reg.tier.wire).put("kind", reg.kind)
                .put("services", JSONArray(reg.inServices.sorted())).put("label", reg.label ?: JSONObject.NULL)
                .put("approval", if (reg.tier == LinkTier.PUBLIC) "per_request" else "standing")
                .put("idempotency_key", "${reg.linkId}:${reg.tier.wire}")
                .put("peer", JSONObject().apply {
                    reg.peerInstall?.let { put("install", it) }
                    if (reg.tier == LinkTier.ASSOCIATE) reg.email?.let { put("email", it) }
                })
            if (reg.tier != LinkTier.OWN) body.put("expires_at", reg.expiresAt ?: JSONObject.NULL)
            val (code, r) = call(app, "POST", "/v1/device-links", body)
            return settle(app, reg, code, r, null)
        }

        private fun settle(app: BridgeApp, reg: Registration, code: Int, r: JSONObject, known: String?): Boolean {
            val link = r.optJSONObject("link")
            if (code in 200..299) {
                save(app, reg.linkId, JSONObject().put("id", link?.optString("id") ?: known).put("tier", reg.tier.wire).put("role", "host")
                    .put("state", link?.optString("state") ?: "pending").put("error", JSONObject.NULL))
                Links.changes.value = Links.changes.value + 1
                return true
            }
            val err = r.optString("error")
            // The backend says the ends don't fit this tier: register again in its narrower suggestion, never a wider one.
            val suggested = LinkTier.parse(r.optString("suggested_tier"))
            if (err == "tier_mismatch" && suggested != null && suggested.rank > reg.tier.rank && (suggested != LinkTier.ASSOCIATE || reg.email != null)) {
                val narrower = reg.copy(tier = suggested, inServices = LinkTierPolicy.effectiveServices(suggested, reg.inServices),
                    expiresAt = reg.expiresAt ?: LinkTierPolicy.expiry(suggested, null, System.currentTimeMillis()))
                return narrower.inServices.isEmpty() || send(app, narrower)
            }
            if (err in REFUSALS) {
                save(app, reg.linkId, JSONObject().put("id", known ?: JSONObject.NULL).put("tier", reg.tier.wire).put("role", "host")
                    .put("state", "refused").put("error", err).put("message", r.optString("message").take(300)))
                app.log.system("audit", "link", "The team backend didn't register a link (${LinkTierPolicy.label(reg.tier)}): ${r.optString("message").take(200)}")
                return true
            }
            return false
        }

        /** The other device hosts the link and registered it as [dlk]: this device confirms its end, once. */
        fun confirmSoon(context: Context, linkId: String, dlk: String) {
            val st = peerStatus(context, linkId)
            if (st?.optString("id") == dlk && st.optString("state") in setOf("active", "pending", "refused")) return
            if (status(context, linkId)?.optString("id") == dlk) return // its own registration, echoed back
            val app = context.applicationContext as? BridgeApp ?: return
            val key = PEER + linkId
            if (!enabled) { save(app, key, JSONObject().put("id", dlk).put("role", "peer").put("state", "to_confirm")); return }
            kotlin.concurrent.thread(isDaemon = true, name = "link-confirm") {
                runCatching {
                    val (code, r) = call(app, "POST", "/v1/device-links/$dlk/confirm", JSONObject())
                    val err = r.optString("error")
                    save(app, key, JSONObject().put("id", dlk).put("role", "peer").put("tier", r.optJSONObject("link")?.optString("tier") ?: JSONObject.NULL)
                        .put("state", if (code in 200..299) r.optJSONObject("link")?.optString("state") ?: "active" else if (err.isNotEmpty() && code in 400..499) "refused" else "to_confirm")
                        .put("error", err.ifEmpty { null } ?: JSONObject.NULL))
                }
            }
        }

        /**
         * From the periodic check: links this device hosts that the backend hasn't heard of (links from before tiers)
         * are registered, and a refusal is tried again a day later.
         */
        fun backfill(app: BridgeApp, links: List<Links.Rec>, now: Long = System.currentTimeMillis()) {
            for (r in links) {
                if (r.inServices.isEmpty() || r.inRevoked || Outbox.has(app, r.id)) continue
                val st = status(app, r.id)
                if (st == null || (st.optString("state") == "refused" && now - st.optLong("at") > LinkTierPolicy.DAY_MS)) Outbox.register(app, r)
            }
            drain(app)
        }

        /**
         * The backend's state is authoritative: links it reports revoked or expired are cut here, and suspended ones
         * (an end changed account) paused, through [cut]. Records each link's current state. Blocking.
         */
        fun sync(app: BridgeApp, cut: (linkId: String, state: String) -> Unit) {
            if (!enabled) return
            val (code, r) = call(app, "GET", "/v1/device-links", null)
            if (code !in 200..299) return
            val byDlk = mutableMapOf<String, JSONObject>()
            val tiers = r.optJSONArray("tiers") ?: JSONArray()
            for (i in 0 until tiers.length()) tiers.optJSONObject(i)?.optJSONArray("links")?.let { a ->
                for (j in 0 until a.length()) a.optJSONObject(j)?.let { byDlk[it.optString("id")] = it } }
            val all = prefs(app).all.keys.toList()
            for (key in all) {
                val st = status(app, key) ?: continue
                val dlk = st.optString("id").takeIf { DLK_RE.matches(it) } ?: continue
                val link = byDlk[dlk]
                val state = link?.optString("state") ?: if (st.optString("state") in setOf("active", "paused", "suspended")) "revoked" else continue
                if (state != st.optString("state")) save(app, key, JSONObject(st.toString()).put("state", state).put("state_reason", link?.optString("state_reason") ?: JSONObject.NULL))
                if (state in setOf("revoked", "expired", "suspended")) cut(key.removePrefix(PEER), state)
            }
        }

        private fun call(app: BridgeApp, method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> =
            cc.bridgit.app.feedback.DeviceNames.call(app, method, path, body)

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    companion object {
        val DLK_RE = Regex("dlk_[0-9a-f]{32}")
        @Volatile var current: LinkRegistry = Backend
    }
}
