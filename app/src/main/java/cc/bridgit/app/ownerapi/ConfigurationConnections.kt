package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.platform.Connections
import cc.bridgit.app.platform.PlatformStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Provision receiver-owned credentials without ever returning existing addresses, headers or signing keys. */
class ConfigurationConnections(private val app: BridgeApp, private val caller: Agent) {
    private val access = ConfigurationAccess(app, caller)
    private val store get() = app.platform.store
    private val delivery get() = app.platform.delivery
    private fun record(id: String) = store.find(id, "owner", "destination")
        ?: throw ErrorCatalog.error("not_found", "Unknown connection")
    private fun public(row: JSONObject) = Connections.public(row).put("write_only", JSONArray(listOf("url", "headers", "extra", "signing_secret", "alert_secret")))
        .put("signing_secret_configured", row.optString("whsec").isNotEmpty())
        .put("alert_secret_configured", row.optString("secret").isNotEmpty())

    fun list(): JSONObject {
        access.require()
        return JSONObject().put("connections", JSONArray(store.list("owner", "destination", 1000).map(::public)))
            .put("presets", JSONArray(Connections.PRESETS.values.filter { it.id != Connections.LEGACY }.map {
                JSONObject().put("id", it.id).put("name", it.label).put("headers", it.headers).put("extra_fields", JSONArray(it.extra.toList()))
            }))
    }

    private fun signing(value: Any?): String {
        val s = value as? String ?: throw ErrorCatalog.error("invalid_parameter", "signing_secret must be a Standard Webhooks whsec_ key")
        if (!s.startsWith("whsec_") || runCatching { Base64.getDecoder().decode(s.removePrefix("whsec_")).size }.getOrDefault(0) != 32)
            throw ErrorCatalog.error("invalid_parameter", "signing_secret must encode 32 random bytes with the whsec_ prefix")
        return s
    }

    private fun validated(body: JSONObject, previous: JSONObject? = null): JSONObject {
        ConfigurationAccess.only(body, "name", "url", "preset", "allow_private", "headers", "extra", "enabled", "signing_secret", "alert_secret")
        if (body.length() == 0) throw ErrorCatalog.error("invalid_parameter", "No connection fields supplied")
        val row = previous?.let { JSONObject(it.toString()) } ?: JSONObject().put("allow_private", false).put("enabled", true)
        for (field in listOf("name", "url", "preset")) if (body.has(field)) row.put(field, ConfigurationAccess.text(body, field, if (field == "url") 2048 else 80))
        for (field in listOf("allow_private", "enabled")) if (body.has(field)) row.put(field, ConfigurationAccess.bool(body, field))
        for (field in listOf("headers", "extra")) if (body.has(field)) {
            val value = body.opt(field)
            if (value != JSONObject.NULL && value !is JSONObject) throw ErrorCatalog.error("invalid_parameter", "$field must be an object or null to clear")
            row.put(field, if (value == JSONObject.NULL) JSONObject() else value)
        }
        val name = row.optString("name").trim()
        val url = row.optString("url").trim()
        if (name.isEmpty() || name.length > 80) throw ErrorCatalog.error("invalid_parameter", "Connection name must be 1-80 characters")
        val preset = row.optString("preset").ifEmpty { Connections.recognise(url) }
        val p = Connections.PRESETS[preset]?.takeUnless { it.id == Connections.LEGACY }
            ?: throw ErrorCatalog.error("invalid_parameter", "Unknown connection preset")
        // Native validators never include credential values in their messages. Return a fixed API error as a further boundary.
        try {
            Connections.checkUrl(url, row.optBoolean("allow_private"), preset)
            val headers = row.optJSONObject("headers") ?: JSONObject()
            if (!p.headers && headers.length() > 0) throw IllegalArgumentException()
            row.put("headers", if (p.headers) Connections.checkHeaders(headers) else JSONObject())
            val extra = row.optJSONObject("extra") ?: JSONObject()
            if (extra.keys().asSequence().any { it !in p.extra }) throw IllegalArgumentException()
            row.put("extra", Connections.checkExtra(preset, extra))
        } catch (_: Exception) { throw ErrorCatalog.error("invalid_parameter", "Connection fields do not match the preset's native URL/header/extra validation") }
        row.put("name", name).put("url", url).put("preset", preset)
        if (body.has("alert_secret")) {
            val secret = body.opt("alert_secret") as? String
            if (secret == null || !Regex("[0-9a-f]{64}").matches(secret)) throw ErrorCatalog.error("invalid_parameter", "alert_secret must be 32 random bytes encoded as 64 lowercase hex characters")
            row.put("secret", secret)
        }
        if (body.has("signing_secret")) {
            val secret = signing(body.opt("signing_secret"))
            val old = row.optString("whsec")
            if (old.isNotEmpty() && old != secret) row.put("whsec_previous", old).put("whsec_previous_until", System.currentTimeMillis() + Connections.ROTATION_MS)
            row.put("whsec", secret)
        }
        return row
    }

    fun create(body: JSONObject, key: String): JSONObject {
        access.require()
        val checked = validated(body)
        val digest = PlatformStore.digest(body)
        val normalizedKey = key.trim()
        if (!Regex("[A-Za-z0-9_-]{8,128}").matches(normalizedKey)) throw ErrorCatalog.error("invalid_parameter", "A valid Idempotency-Key is required")
        val reservation = "configuration-connection:${caller.id}:$normalizedKey"
        synchronized(store) {
            access.require()
            val saved = store.setting(reservation)
            if (saved.isNotEmpty()) {
                val r = JSONObject(saved)
                if (r.getString("digest") != digest) throw ErrorCatalog.error("conflict", "Idempotency-Key already created a different connection")
                return public(record(r.getString("id")))
            }
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                val native = delivery.destination(checked.getString("name"), checked.getString("url"), checked.getBoolean("allow_private"), checked.getString("preset"), checked.getJSONObject("headers"), checked.getJSONObject("extra"))
                for (field in listOf("whsec", "secret", "enabled")) if (checked.has(field)) native.put(field, checked.get(field))
                val row = store.save("owner", "destination", native)
                store.setting(reservation, JSONObject().put("id", row.getString("id")).put("digest", digest).toString(), true)
                db.setTransactionSuccessful()
                access.audit("connection")
                return public(row)
            } finally { db.endTransaction() }
        }
    }

    fun update(id: String, body: JSONObject): JSONObject {
        access.require()
        synchronized(store) {
            access.require()
            val old = record(id)
            val checked = validated(body, old)
            if (!old.optBoolean("enabled") && checked.optBoolean("enabled") && store.list("owner", "destination", 1000).count { it.optBoolean("enabled") } >= Connections.MAX)
                throw ErrorCatalog.error("rate_limited", "Connection limit reached")
            val row = store.save("owner", "destination", checked)
            if (!row.optBoolean("enabled")) delivery.revoke(id)
            access.audit("connection")
            return public(row)
        }
    }

    fun rotate(id: String, body: JSONObject): JSONObject {
        ConfigurationAccess.only(body, "signing_secret")
        if (!body.has("signing_secret")) throw ErrorCatalog.error("invalid_parameter", "Supply the new receiver-owned signing_secret; saved secrets are write-only")
        return update(id, body)
    }

    fun remove(id: String): JSONObject {
        access.require(); record(id)
        delivery.revoke(id)
        access.audit("connection")
        return public(record(id))
    }
}
