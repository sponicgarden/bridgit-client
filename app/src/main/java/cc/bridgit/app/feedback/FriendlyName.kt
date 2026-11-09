package cc.bridgit.app.feedback

/**
 * A0151, two-name device identity. Every Bridgit has a Device Domain Name (globally unique, network-level; shown
 * only where devices must be told apart across the network) and a friendly name (any style, unique among the
 * owner's devices; the name displayed nearly everywhere). They are chosen in the same step. Pure, no Android types.
 * Mirrors feedback/src/names.mjs parseFriendly in the backend, which decides uniqueness.
 */
object FriendlyName {
    const val MAX = 40

    const val ADVICE = "Also give this Bridgit a friendly name (friendly_name, up to 40 characters, any style, unique among this owner's devices). " +
        "People see the friendly name everywhere; the domain name is only for telling devices apart on the network."

    /** [text] as the backend stores it (inner spaces collapsed, trimmed); null when empty, too long or with control characters. */
    fun parse(text: String?): String? {
        if (text == null || text.length > MAX * 3) return null
        val name = text.replace(Regex("\\s+"), " ").trim()
        return name.takeIf { it.isNotEmpty() && it.length <= MAX && name.none { c -> c.code < 32 || c.code == 127 } }
    }

    /** The comparison form the backend uses for per-owner uniqueness. */
    fun key(name: String): String = name.lowercase()

    /**
     * What to show for a device: its friendly name; the domain name only when there is no friendly name yet, or when
     * [confusable] says another device on the screen has the same friendly name (then "Cracky (pixel-pal)").
     */
    fun display(friendly: String?, domain: String?, confusable: Boolean = false): String {
        val f = friendly?.trim()?.takeIf { it.isNotEmpty() }
        val d = domain?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            f == null -> d ?: ""
            confusable && d != null -> "$f ($d)"
            else -> f
        }
    }

    /** True for each entry whose friendly name (compared as [key]) another entry shares; such rows need the domain name too. */
    fun confusable(friendlyNames: List<String?>): List<Boolean> {
        val counts = friendlyNames.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() }?.let(::key) }.groupingBy { it }.eachCount()
        return friendlyNames.map { n -> n?.trim()?.takeIf { it.isNotEmpty() }?.let { (counts[key(it)] ?: 0) > 1 } ?: false }
    }
}
