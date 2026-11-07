package cc.bridgit.app.satellite

import cc.bridgit.app.core.Net
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The pairing link a satellite shows (docs/spec-satellites.md, SAT-06):
 * `bridgit://satellite#v=1&host=<tailnet ip>&port=<n>&claim=sct_…&name=<phone name>&install=bgi_…`.
 * The claim token works once, for 15 minutes: the hub trades it for its real key (POST /v1/satellite/claim), so a
 * photographed QR code is useless afterwards. The hub only ever calls a Tailscale address, so a link with any other
 * host is refused here. Pure, unit-tested.
 */
object SatelliteLink {
    const val PREFIX = "bridgit://satellite#"

    data class Parsed(val host: String, val port: Int, val claim: String, val name: String, val install: String?)

    fun build(host: String, port: Int, claim: String, name: String, install: String?): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return PREFIX + listOfNotNull("v=1", "host=${e(host)}", "port=$port", "claim=${e(claim)}", "name=${e(name)}",
            install?.let { "install=${e(it)}" }).joinToString("&")
    }

    /** Throws IllegalArgumentException with a plain message for anything that isn't a valid satellite link. */
    fun parse(link: String): Parsed {
        val t = link.trim()
        require(t.startsWith(PREFIX)) { "That isn't a Bridgit satellite link. On the spare phone, open Settings › Satellite." }
        val f = t.removePrefix(PREFIX).split('&').mapNotNull { p ->
            val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i) to URLDecoder.decode(p.substring(i + 1), "UTF-8")
        }.toMap()
        require(f["v"] == "1") { "This link is from a newer Bridgit. Update Bridgit on this phone and try again." }
        val host = Net.normalise(f["host"].orEmpty())
        require(Net.isTailnet(host)) { "The link's address isn't a Tailscale address. Turn Tailscale on on the spare phone and make a new link." }
        val port = f["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("The link's port isn't valid.")
        val claim = f["claim"].orEmpty()
        require(CLAIM_RE.matches(claim)) { "The link's pairing code isn't valid. Make a new link on the spare phone." }
        val install = f["install"]?.takeIf { INSTALL_RE.matches(it) }
        val name = f["name"].orEmpty().filterNot { it.isISOControl() }.trim().take(60).ifBlank { "Satellite" }
        return Parsed(host, port, claim, name, install)
    }

    /** "Garage phone" → "garage-phone"; never empty. Used for the `sat:` id (SAT-16). */
    fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "phone" }

    /** A free `sat:` id for [name], given the ids already taken: sat:garage-phone, sat:garage-phone-2, … */
    fun newId(name: String, taken: Set<String>): String {
        val base = slug(name).take(36).trim('-').ifEmpty { "phone" }
        return generateSequence(1) { it + 1 }.map { if (it == 1) "sat:$base" else "sat:$base-$it" }.first { it !in taken }
    }

    val ID_RE = Regex("sat:[a-z0-9-]{1,40}")
    val CLAIM_RE = Regex("sct_[A-Za-z0-9_-]{40,64}")

    fun newClaimToken(rnd: java.security.SecureRandom = java.security.SecureRandom()): String =
        "sct_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { rnd.nextBytes(it) })
    private val INSTALL_RE = Regex("bgi_[a-z2-7]{24}")
}
