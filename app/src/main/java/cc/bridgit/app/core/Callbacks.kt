package cc.bridgit.app.core

import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Callback rules (EVT-05, EVT-05a, EVT-06). Pure: no Android, unit-tested.
 * Delivery itself goes through [SafeHttp], which re-checks the resolved address on every attempt.
 */
object CallbackRules {
    /** Result of checking a callback URL. */
    sealed class Check {
        /** Allowed: deliver with [policy]; [extraPort] is the URL's explicit port (the agent's registered port). */
        data class Ok(val policy: SafeHttp.Policy, val host: String, val extraPort: Int?) : Check()
        data class Bad(val reason: String) : Check()
    }

    const val MAX_URL = 2000
    const val SECRET_PREFIX = "cbs_"
    const val TIMEOUT_MS = 10_000L
    /** Retries after the first attempt (EVT-06): 10 s, 60 s, 5 min, then give up. */
    val RETRY_DELAYS_MS = listOf(10_000L, 60_000L, 300_000L)
    const val MAX_PENDING_PER_AGENT = 500

    /**
     * The phone's tailnet domain from its MagicDNS name: `pixel.tail1234.ts.net` -> `tail1234.ts.net`.
     * Null if there's no MagicDNS name or it isn't under ts.net.
     */
    fun tailnetDomain(magicDns: String?): String? {
        val n = magicDns?.trim()?.trimEnd('.')?.lowercase() ?: return null
        if (!n.endsWith(".ts.net")) return null
        val d = n.substringAfter('.', "")
        return d.takeIf { it.endsWith(".ts.net") && it.count { c -> c == '.' } >= 2 }
    }

    /**
     * Checks [url] for a callback (EVT-05): tailnet targets (100.64.0.0/10, fd7a:115c:a1e0::/48, or a name under
     * [tailnetDomain]) always; a public HTTPS URL only when [allowInternet]. Ports: 80, 443 or the URL's own
     * explicit port, which becomes the agent's registered port.
     */
    fun check(url: String, tailnetDomain: String?, allowInternet: Boolean): Check {
        val u = url.trim()
        if (u.isEmpty()) return Check.Bad("Enter a URL.")
        if (u.length > MAX_URL) return Check.Bad("That URL is too long.")
        if (u.any { it.isWhitespace() || it.code < 0x20 }) return Check.Bad("A URL can't contain spaces.")
        val uri = runCatching { URI(u) }.getOrNull() ?: return Check.Bad("That isn't a valid URL.")
        val scheme = uri.scheme?.lowercase() ?: return Check.Bad("Start with http:// or https://.")
        if (scheme != "http" && scheme != "https") return Check.Bad("Only http:// and https:// URLs work.")
        if (uri.rawUserInfo != null) return Check.Bad("Names or passwords in the URL aren't allowed.")
        if (uri.rawFragment != null) return Check.Bad("Leave out the # part.")
        val rawHost = uri.host?.takeIf { it.isNotEmpty() } ?: return Check.Bad("That URL has no host name.")
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase().trimEnd('.')
        val port = uri.port.takeIf { it != -1 }
        if (port != null && port !in 1..65535) return Check.Bad("Port $port isn't valid.")

        val literal = literalAddress(host)
        val tailnet = when {
            literal != null -> SafeHttp.isTailnet(literal)
            else -> tailnetDomain != null && host.endsWith(".${tailnetDomain.lowercase()}")
        }
        val policy = if (tailnet) SafeHttp.Policy.TAILNET else {
            when {
                literal != null && !SafeHttp.isPublic(literal) -> return Check.Bad("That address is on a private network, not the tailnet.")
                literal == null && host.endsWith(".ts.net") -> return Check.Bad(
                    if (tailnetDomain == null) "Bridgit doesn't know its tailnet name yet. Use the 100.x address instead."
                    else "That name isn't on your tailnet ($tailnetDomain).")
                literal == null && (host == "localhost" || !host.contains('.')) -> return Check.Bad("Use a full host name or a tailnet address.")
                !allowInternet -> return Check.Bad("Only tailnet addresses are allowed. Turn on Allow internet callbacks in Settings for an internet URL.")
                scheme != "https" -> return Check.Bad("Internet callbacks must use https://.")
                else -> SafeHttp.Policy.PUBLIC
            }
        }
        SafeHttp.urlProblem(u, policy, port)?.let { return Check.Bad("Not allowed: $it.") }
        return Check.Ok(policy, host, port)
    }

    /** The host for logs and replies: never the path or query (EVT-06). */
    fun hostOf(url: String): String = runCatching { URI(url.trim()).host?.removePrefix("[")?.removeSuffix("]") }.getOrNull() ?: "?"

    /** An address only if [host] is an IP literal (never a DNS lookup). */
    private fun literalAddress(host: String): InetAddress? {
        val isV4 = host.isNotEmpty() && host.all { it.isDigit() || it == '.' } && host.count { it == '.' } == 3
        val isV6 = host.contains(':') && host.all { it.isDigit() || it in "abcdef:." }
        if (!isV4 && !isV6) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    /** A new callback secret: `cbs_` + 32 random bytes, base64url without padding. */
    fun newSecret(rnd: SecureRandom = SecureRandom()): String {
        val b = ByteArray(32).also { rnd.nextBytes(it) }
        return SECRET_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** `sha256=<hex HMAC-SHA256(secret, "<timestamp>.<raw body>")>` (EVT-06). */
    fun sign(secret: String, timestampS: Long, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$timestampS.".toByteArray(Charsets.UTF_8))
        return "sha256=" + mac.doFinal(body).toHex()
    }

    /** The POST body: `{"event": {id, type, at, data}, "agent": "<name>"}`. */
    fun body(eventJson: JSONObject, agentName: String): ByteArray =
        JSONObject().put("event", eventJson).put("agent", agentName).toString().toByteArray(Charsets.UTF_8)

    /** Every header of one attempt; the same [timestampS] is signed and sent. */
    fun headers(secret: String, timestampS: Long, eventId: String, body: ByteArray, appVersion: String): Map<String, String> = cc.bridgit.app.api.BrandHeaders.withLegacy(linkedMapOf(
        "Content-Type" to "application/json",
        "User-Agent" to "Bridgit/$appVersion",
        "X-Bridgit-Timestamp" to timestampS.toString(),
        "X-Bridgit-Event-Id" to eventId,
        "X-Bridgit-Signature" to sign(secret, timestampS, body),
    ))

    /** Delay before the next try after [attemptsSoFar] failed attempts (1 = the first try failed), or null to give up. */
    fun nextDelayMs(attemptsSoFar: Int): Long? = RETRY_DELAYS_MS.getOrNull(attemptsSoFar - 1)

    fun success(status: Int) = status in 200..299

    /** "60 s" / "5 min" for the retry log line. */
    fun delayWords(ms: Long): String = if (ms >= 60_000 && ms % 60_000 == 0L && ms > 60_000) "${ms / 60_000} min" else "${ms / 1000} s"
}

/**
 * Pending deliveries, at most [cap] per agent; a full agent drops its oldest (EVT-06 queue). Pure, unit-tested.
 * Not thread-safe: the dispatcher guards it.
 */
class CallbackQueue<T>(private val cap: Int = CallbackRules.MAX_PENDING_PER_AGENT) {
    class Item<T>(val agentId: String, val value: T, val attempt: Int, val dueAt: Long)

    private val items = ArrayList<Item<T>>()

    val size get() = items.size
    fun countFor(agentId: String) = items.count { it.agentId == agentId }

    /** Adds [item]; returns how many of that agent's oldest items were dropped to stay under the cap. */
    fun add(item: Item<T>): Int {
        var dropped = 0
        while (countFor(item.agentId) >= cap) {
            val oldest = items.indexOfFirst { it.agentId == item.agentId }
            items.removeAt(oldest); dropped++
        }
        items += item
        return dropped
    }

    /** The earliest due item, removed, if it's due at [now]. */
    fun takeDue(now: Long): Item<T>? {
        val i = items.indices.minByOrNull { items[it].dueAt } ?: return null
        if (items[i].dueAt > now) return null
        return items.removeAt(i)
    }

    /** When the next item is due, or null when empty. */
    fun nextDueAt(): Long? = items.minOfOrNull { it.dueAt }

    fun removeAgent(agentId: String) { items.removeAll { it.agentId == agentId } }
}
