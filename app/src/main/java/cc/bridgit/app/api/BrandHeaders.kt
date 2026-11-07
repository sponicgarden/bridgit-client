package cc.bridgit.app.api

/**
 * Our own HTTP headers are `X-Bridgit-*`. Until 2026-11-01 they were spelled `X-Bridget-*`, and agents and
 * callback receivers still read that spelling, so every `X-Bridgit-` header is also sent under its old name
 * (API revision 17). Drop the copies once no agent reads them.
 */
object BrandHeaders {
    const val PREFIX = "X-Bridgit-"
    const val LEGACY_PREFIX = "X-Bridget-"

    /** The old spelling of one header name, or null when it isn't one of ours. */
    fun legacyName(name: String): String? =
        if (name.startsWith(PREFIX)) LEGACY_PREFIX + name.removePrefix(PREFIX) else null

    /** [headers] plus an `X-Bridget-` copy of each `X-Bridgit-` header, keeping their order. */
    fun withLegacy(headers: Map<String, String>): Map<String, String> {
        if (headers.keys.none { it.startsWith(PREFIX) }) return headers
        val out = LinkedHashMap<String, String>(headers)
        headers.forEach { (k, v) -> legacyName(k)?.let { out.putIfAbsent(it, v) } }
        return out
    }
}
