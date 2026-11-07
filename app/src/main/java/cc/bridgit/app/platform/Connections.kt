package cc.bridgit.app.platform

import org.json.JSONArray
import org.json.JSONObject
import cc.bridgit.app.api.ApiError
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Connections (docs/applet-reach.md A2-A5): an owner-approved destination plus a preset that says how to talk to
 * it. Pure: no Android and no storage, so the rules are unit-tested on their own; [DeliveryManager] stores and
 * sends. A destination saved before connections has no `preset` and reads as `bridgit-signed`, so its headers and
 * secret don't change.
 *
 * Agents never see a connection's URL, headers or secrets: only [public].
 */
object Connections {
    const val MAX = 25
    const val MAX_HEADERS = 8
    const val MAX_HEADER_CHARS = 500
    const val MAX_DATA_KEYS = 32
    const val MAX_DATA_BYTES = 16 * 1024
    const val MAX_TEXT = 1000
    const val PER_HOUR = 60
    const val REPLY_WAIT_MS = 15_000L
    const val MAX_REPLY_BYTES = 16 * 1024
    const val MAX_REPLY_TEXT = 2000
    const val PHOTO_MAX_SIDE = 1280
    const val PHOTO_MAX_BYTES = 1_000_000
    /** After a secret rotation the old secret signs too, this long (Standard Webhooks allows several signatures). */
    const val ROTATION_MS = 24 * 3600_000L
    const val LEGACY = "bridgit-signed"

    /** How a photo travels: in the request, as a 7-day link (needs an upload), or not at all. */
    enum class Photo { INLINE, LINK, NONE }

    /**
     * One preset. [chat]: the service shows text, so a send needs `text` and `data` goes as lines under it.
     * [signs]: Standard Webhooks headers are added. [extra]: owner-entered fields besides the URL.
     */
    class Preset(val id: String, val label: String, val chat: Boolean, val photo: Photo, val signs: Boolean, val free: Boolean,
                 val note: String, val textMax: Int = MAX_TEXT, val extra: List<String> = emptyList(), val headers: Boolean = false)

    val PRESETS: Map<String, Preset> = listOf(
        Preset("home_assistant", "Home Assistant", false, Photo.INLINE, false, true, "JSON body; an automation with a webhook trigger reads it as trigger.json."),
        Preset("n8n", "n8n", false, Photo.INLINE, true, true, "JSON body with a Standard Webhooks signature (n8n's Crypto node can check it); optional header auth.", headers = true),
        Preset("discord", "Discord", true, Photo.INLINE, false, true, "Message text (up to 2,000 characters) and the photo as a file.", textMax = 2000),
        Preset("slack", "Slack", true, Photo.LINK, false, true, "Message text. Slack incoming webhooks take no files, so a photo needs a link."),
        Preset("telegram", "Telegram", true, Photo.INLINE, false, true, "A bot message to one chat (needs the chat id), with the photo.", textMax = 1000, extra = listOf("chat_id")),
        Preset("ntfy", "ntfy", true, Photo.INLINE, false, true, "Push notification with title and priority; the photo as an attachment. ntfy.sh allows 250 messages a day."),
        Preset("pushover", "Pushover", true, Photo.INLINE, false, true, "Push notification (up to 1,024 characters) with the photo; needs your user key and app token. 10,000 a month free.", textMax = 1024, extra = listOf("user", "token")),
        Preset("zapier", "Zapier", false, Photo.LINK, false, false, "Flat JSON. Zapier webhooks need a paid plan."),
        Preset("make", "Make", false, Photo.LINK, false, false, "JSON. Depends on your Make plan."),
        Preset("ifttt", "IFTTT", false, Photo.LINK, false, false, "value1 (the text), value2 (the data) and value3. Webhooks need IFTTT Pro."),
        Preset("json", "Any JSON webhook", false, Photo.INLINE, true, true, "JSON body with a Standard Webhooks signature and the headers you set.", headers = true),
        Preset(LEGACY, "Bridgit-signed (older destination)", false, Photo.INLINE, false, true, "JSON body with Bridgit's original X-Bridgit-Signature headers."),
    ).associateBy { it.id }

    fun preset(conn: JSONObject): Preset = PRESETS[conn.optString("preset").ifEmpty { LEGACY }] ?: PRESETS.getValue(LEGACY)

    private fun bad(message: String): Nothing = throw ApiError(422, "invalid_connection", message)

    /** The preset a pasted URL belongs to ("json" when nothing matches). */
    fun recognise(url: String): String {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return "json"
        val host = u.host?.lowercase().orEmpty(); val path = u.rawPath.orEmpty()
        return when {
            (host == "discord.com" || host == "discordapp.com" || host.endsWith(".discord.com")) && path.startsWith("/api/webhooks/") -> "discord"
            host == "hooks.slack.com" && path.startsWith("/services/") -> "slack"
            host == "api.telegram.org" && path.startsWith("/bot") -> "telegram"
            host == "api.pushover.net" -> "pushover"
            host == "ntfy.sh" -> "ntfy"
            host == "hooks.zapier.com" && path.startsWith("/hooks/catch/") -> "zapier"
            host.startsWith("hook.") && host.endsWith(".make.com") -> "make"
            host == "maker.ifttt.com" && path.startsWith("/trigger/") -> "ifttt"
            Regex("^/api/webhook/[^/]+/?$").matches(path) -> "home_assistant"
            path.contains("/webhook/") || path.contains("/webhook-test/") -> "n8n"
            else -> "json"
        }
    }

    /** True for a host that names the owner's own network by its address or a `.local` name. */
    fun privateHostName(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")) return true
        val literal = h.contains(':') || h.matches(Regex("^[0-9.]+$"))
        return literal && runCatching { cc.bridgit.app.core.SafeHttp.isPrivate(InetAddress.getByName(h)) }.getOrDefault(false)
    }

    /**
     * Checks a URL for approval. Without [allowPrivate]: https on port 443 only. With it: https or http on any port,
     * and plain http only to a private address or name (checked again against the real address on every send).
     */
    fun checkUrl(url: String, allowPrivate: Boolean, preset: String) {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: bad("That isn't a web address")
        val scheme = u.scheme?.lowercase()
        if (u.host.isNullOrBlank()) bad("The address needs a host, like https://example.com/hook")
        if (u.rawUserInfo != null) bad("Put no user:password in the address (use a header instead)")
        if (u.rawFragment != null) bad("Leave the #part off the address")
        if (url.length > 2048) bad("The address is longer than 2,048 characters")
        when {
            scheme == "https" -> if (!allowPrivate && u.port !in listOf(-1, 443)) bad("Use port 443, or tick \"on my private network\" for another port")
            scheme == "http" -> {
                if (!allowPrivate) bad("Plain http is only for your private network: tick \"on my private network\", or use https")
                if (!privateHostName(u.host)) bad("Plain http works only to a private-network address (192.168.x.x, 10.x.x.x, 100.x Tailscale or a .local name)")
            }
            else -> bad("Use an https:// address (or http:// on your private network)")
        }
        if (preset == "telegram" && !Regex("^/bot[0-9]+:[A-Za-z0-9_-]+/?(sendMessage|sendPhoto)?$").matches(u.rawPath.orEmpty()))
            bad("A Telegram address looks like https://api.telegram.org/bot<token>")
    }

    /** Owner-entered headers: up to 8, a value is fixed text or {"$send":"text"} / {"$send":"<data key>"}. */
    fun checkHeaders(h: JSONObject?): JSONObject {
        val out = JSONObject()
        if (h == null) return out
        if (h.length() > MAX_HEADERS) bad("At most $MAX_HEADERS headers")
        for (k in h.keys()) {
            if (!Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$").matches(k)) bad("Header name $k isn't valid")
            val lower = k.lowercase()
            if (lower in setOf("host", "content-length", "content-type", "connection", "transfer-encoding") || lower.startsWith("webhook-") || lower.startsWith("x-bridgit-"))
                bad("Bridgit sets the $k header itself")
            when (val v = h.opt(k)) {
                is String -> if (v.length > MAX_HEADER_CHARS || v.any { it == '\r' || it == '\n' }) bad("Header $k: one line of at most $MAX_HEADER_CHARS characters")
                is JSONObject -> {
                    if (lower == "authorization") bad("Authorization must be fixed text you type, not a fill")
                    val f = v.opt("\$send") as? String
                    if (v.length() != 1 || f == null || !Regex("^[A-Za-z0-9_]{1,40}$").matches(f)) bad("Header $k: a fill is {\"\$send\":\"text\"} or {\"\$send\":\"<data key>\"}")
                }
                else -> bad("Header $k must be text or a {\"\$send\":…} fill")
            }
            out.put(k, h.opt(k))
        }
        return out
    }

    /** Checks the owner's extra fields for [preset] (Telegram chat id, Pushover keys). */
    fun checkExtra(preset: String, extra: JSONObject?): JSONObject {
        val out = JSONObject()
        for (k in PRESETS[preset]?.extra ?: emptyList()) {
            val v = extra?.opt(k)?.toString()?.trim().orEmpty()
            if (v.isEmpty() || v.length > 200 || v.any { it.isWhitespace() }) bad(when (k) { "chat_id" -> "Telegram needs the chat id (a number like 123456789 or -100…)"; "user" -> "Pushover needs your user key"; else -> "Pushover needs the app's API token" })
            out.put(k, v)
        }
        return out
    }

    // ---- secrets and signing (Standard Webhooks, https://www.standardwebhooks.com) -------------------------

    fun newSecret(): String = "whsec_" + Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** `v1,<base64 HMAC-SHA256(key, "id.timestamp.body")>`, the key being the base64 after `whsec_`. */
    fun sign(secret: String, id: String, timestamp: Long, body: ByteArray): String {
        val key = Base64.getDecoder().decode(secret.removePrefix("whsec_"))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        mac.update("$id.$timestamp.".toByteArray(Charsets.UTF_8)); mac.update(body)
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal())
    }

    /** The webhook-signature header: the current secret, and during a rotation the previous one too. */
    fun signatures(conn: JSONObject, id: String, timestamp: Long, body: ByteArray, now: Long): String {
        val list = mutableListOf(sign(conn.getString("whsec"), id, timestamp, body))
        val prev = conn.optString("whsec_previous")
        if (prev.isNotEmpty() && now < conn.optLong("whsec_previous_until")) list += sign(prev, id, timestamp, body)
        return list.joinToString(" ")
    }

    // ---- what a send says ---------------------------------------------------------------------------------

    /**
     * One message as the send step made it. [data] already holds Bridgit's own fields (applet, flow, run_id,
     * sent_at, phone). [photo] is a JPEG of at most [PHOTO_MAX_BYTES].
     */
    class Message(val id: String, val data: JSONObject, val text: String, val photo: ByteArray?, val title: String)

    class Request(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray)

    /** Bridgit's own fields in every send (they win over the step's `data` keys of the same name). */
    val OWN_FIELDS = listOf("applet", "flow", "run_id", "sent_at", "phone")

    /** The step's data keys as "key: value" lines, for presets that show text. */
    fun lines(data: JSONObject, skip: Set<String> = emptySet()): String = data.keys().asSequence().filter { it !in OWN_FIELDS && it !in skip }.joinToString("\n") { k ->
        val v = data.opt(k); "$k: ${if (v is Double && v == Math.floor(v) && !v.isInfinite()) v.toLong() else v}"
    }

    /** ntfy reads these data keys as its title, priority and click link, so they aren't repeated as lines. */
    private val NTFY_KEYS = setOf("title", "priority", "click")
    private fun chatText(m: Message, max: Int, skip: Set<String> = emptySet()): String = listOf(m.text, lines(m.data, skip)).filter { it.isNotBlank() }.joinToString("\n").take(max)

    private fun jsonBody(m: Message, flat: Boolean): JSONObject {
        val o = JSONObject()
        for (k in m.data.keys()) o.put(k, m.data.opt(k))
        if (m.text.isNotEmpty()) o.put("text", m.text)
        if (m.photo != null && !flat) o.put("photo", JSONObject().put("type", "image/jpeg").put("data_base64", Base64.getEncoder().encodeToString(m.photo)))
        return o
    }

    private class Multipart {
        val boundary = "bridgit" + java.util.UUID.randomUUID().toString().replace("-", "")
        private val out = java.io.ByteArrayOutputStream()
        fun field(name: String, value: String) = part("Content-Disposition: form-data; name=\"$name\"\r\nContent-Type: ${if (value.startsWith("{")) "application/json" else "text/plain; charset=utf-8"}", value.toByteArray(Charsets.UTF_8))
        fun file(name: String, filename: String, bytes: ByteArray) = part("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: image/jpeg", bytes)
        private fun part(head: String, bytes: ByteArray) { out.write("--$boundary\r\n$head\r\n\r\n".toByteArray(Charsets.UTF_8)); out.write(bytes); out.write("\r\n".toByteArray()) }
        fun bytes(): ByteArray { out.write("--$boundary--\r\n".toByteArray()); return out.toByteArray() }
        val type get() = "multipart/form-data; boundary=$boundary"
    }

    /** A header-safe value: one line, and RFC 2047 when it isn't plain ASCII (ntfy reads that). */
    fun headerValue(s: String): String {
        val one = s.replace(Regex("[\\r\\n]+"), " ").trim().take(MAX_HEADER_CHARS)
        return if (one.all { it.code in 32..126 }) one else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(one.toByteArray(Charsets.UTF_8)) + "?="
    }

    private fun fill(v: Any?, m: Message): String = when (v) {
        is String -> v
        is JSONObject -> v.optString("\$send").let { f -> if (f == "text") m.text else m.data.opt(f)?.toString().orEmpty() }
        else -> ""
    }

    /**
     * The request [conn] gets for [m]. [waitReply] asks services that can answer with the message (Discord) to do so.
     * [now] is the signing time (ms). Link-mode photos are refused before this (see [photoProblem]).
     */
    fun build(conn: JSONObject, m: Message, waitReply: Boolean, now: Long): Request {
        val p = preset(conn); val url = conn.getString("url").trim()
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = "Bridgit-Connections/1"
        val photo = m.photo.takeIf { p.photo == Photo.INLINE }
        fun json(o: JSONObject) = o.toString().toByteArray(Charsets.UTF_8).also { h["Content-Type"] = "application/json" }
        var method = "POST"; var target = url
        val body: ByteArray = when (p.id) {
            "discord" -> {
                target = url + (if (url.contains('?')) "&" else "?") + "wait=" + waitReply
                val payload = JSONObject().put("content", chatText(m, p.textMax).ifBlank { "(no text)" }).put("allowed_mentions", JSONObject().put("parse", JSONArray()))
                if (photo == null) json(payload) else Multipart().run { field("payload_json", payload.toString()); file("files[0]", "photo.jpg", photo); bytes().also { h["Content-Type"] = type } }
            }
            "slack" -> json(JSONObject().put("text", chatText(m, 3000)))
            "telegram" -> {
                val base = url.trimEnd('/').removeSuffix("/sendMessage").removeSuffix("/sendPhoto")
                val chat = conn.optJSONObject("extra")?.optString("chat_id").orEmpty()
                if (photo == null) { target = "$base/sendMessage"; json(JSONObject().put("chat_id", chat).put("text", chatText(m, 4096))) }
                else { target = "$base/sendPhoto"; Multipart().run { field("chat_id", chat); field("caption", chatText(m, 1024)); file("photo", "photo.jpg", photo); bytes().also { h["Content-Type"] = type } } }
            }
            "ntfy" -> {
                h["X-Title"] = headerValue(m.data.optString("title").ifEmpty { m.title })
                m.data.opt("priority")?.toString()?.takeIf { it in setOf("1", "2", "3", "4", "5", "min", "low", "default", "high", "max", "urgent") }?.let { h["X-Priority"] = it }
                m.data.optString("click").takeIf { it.startsWith("https://") && it.length < 400 }?.let { h["X-Click"] = headerValue(it) }
                if (photo == null) { h["Content-Type"] = "text/plain; charset=utf-8"; chatText(m, 4096, NTFY_KEYS).ifBlank { "(no text)" }.toByteArray(Charsets.UTF_8) }
                else { method = "PUT"; h["Filename"] = "photo.jpg"; h["X-Message"] = headerValue(chatText(m, 400, NTFY_KEYS).ifBlank { m.title }); h["Content-Type"] = "image/jpeg"; photo }
            }
            "pushover" -> {
                val ex = conn.optJSONObject("extra") ?: JSONObject()
                val text = chatText(m, p.textMax).ifBlank { "(no text)" }
                if (photo == null) json(JSONObject().put("token", ex.optString("token")).put("user", ex.optString("user")).put("message", text).put("title", m.title.take(250)))
                else Multipart().run { field("token", ex.optString("token")); field("user", ex.optString("user")); field("message", text); field("title", m.title.take(250)); file("attachment", "photo.jpg", photo); bytes().also { h["Content-Type"] = type } }
            }
            "zapier", "make" -> json(jsonBody(m, flat = true))
            "ifttt" -> json(JSONObject().put("value1", m.text.ifEmpty { lines(m.data) }).put("value2", JSONObject().apply { for (k in m.data.keys()) put(k, m.data.opt(k)) }.toString()).put("value3", ""))
            else -> json(jsonBody(m, flat = false))
        }
        conn.optJSONObject("headers")?.let { owner -> for (k in owner.keys()) h[k] = headerValue(fill(owner.opt(k), m)) }
        val seconds = now / 1000
        if (p.signs) {
            h["webhook-id"] = m.id; h["webhook-timestamp"] = seconds.toString(); h["webhook-signature"] = signatures(conn, m.id, seconds, body, now)
        }
        if (p.id == LEGACY) {
            // Exactly today's destination headers (DeliveryManager.postPinned), over the same text.
            val ts = now.toString(); val nonce = java.util.UUID.randomUUID().toString()
            h["Idempotency-Key"] = m.id; h["X-Bridgit-Timestamp"] = ts; h["X-Bridgit-Nonce"] = nonce; h["X-Bridgit-Event"] = m.id
            h["X-Bridgit-Signature"] = DeliveryManager.hmac(conn.getString("secret"), "$ts.$nonce.${m.id}.${String(body, Charsets.UTF_8)}")
        } else h["Idempotency-Key"] = m.id
        return Request(method, target, h, body)
    }

    /** Why [preset] can't take a photo in wave 1 (null when it can). */
    fun photoProblem(preset: Preset): String? = when (preset.photo) {
        Photo.INLINE -> null
        Photo.LINK -> "${preset.label} can't take a photo file, only a link, and photo links aren't available yet: leave photo out, or send the photo to Discord, Telegram, ntfy, Pushover, Home Assistant or a JSON webhook"
        Photo.NONE -> "${preset.label} can't take a photo"
    }

    /** A reply as the step's result: status, and reply (JSON, at most 16 KiB) or reply_text (2,000 characters). */
    fun reply(status: Int, bytes: ByteArray?, type: String?): JSONObject {
        val out = JSONObject().put("status", status).put("queued", false)
        val b = bytes ?: return out
        if (b.isEmpty()) return out
        val parsed = if (b.size <= MAX_REPLY_BYTES) runCatching { org.json.JSONTokener(String(b, Charsets.UTF_8)).nextValue() }.getOrNull() else null
        if (parsed is JSONObject || parsed is JSONArray) out.put("reply", parsed)
        else out.put("reply_text", String(b.copyOf(minOf(b.size, MAX_REPLY_TEXT * 4)), Charsets.UTF_8).take(MAX_REPLY_TEXT))
        return out
    }

    /** What agents see: no URL, headers or secrets. */
    fun public(conn: JSONObject): JSONObject {
        val p = preset(conn)
        return JSONObject().put("id", conn.optString("id")).put("name", conn.optString("name")).put("preset", p.id).put("service", p.label)
            .put("photos", p.photo == Photo.INLINE).put("needs_text", p.chat).put("private_network", conn.optBoolean("allow_private"))
            .put("enabled", conn.optBoolean("enabled")).put("failing", conn.optBoolean("failing"))
    }

    /** The connection in the owner's review words: "Home Assistant (your private network)". */
    fun words(conn: JSONObject): String = conn.optString("name").ifBlank { preset(conn).label } + if (conn.optBoolean("allow_private") && conn.optString("url").startsWith("http:")) " (your private network)" else ""

    /** The URL with its secret parts hidden, for the owner's list: scheme and host only (ntfy, Discord and HA keep secrets in the path). */
    fun masked(url: String): String {
        val u = runCatching { URI(url) }.getOrNull() ?: return "•••"
        return "${u.scheme}://${u.host}${if (u.port != -1) ":${u.port}" else ""}/•••"
    }

    /** What the outbound log records instead of the URL (the path often holds the service's secret). */
    fun logUrl(conn: JSONObject): String {
        val u = runCatching { URI(conn.getString("url")) }.getOrNull()
        return "${u?.scheme ?: "https"}://${u?.host ?: "connection"}${if ((u?.port ?: -1) != -1) ":${u!!.port}" else ""}/connection/${URLEncoder.encode(preset(conn).id, "UTF-8")}"
    }
}
