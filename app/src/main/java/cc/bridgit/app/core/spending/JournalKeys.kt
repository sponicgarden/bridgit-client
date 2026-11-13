package cc.bridgit.app.core.spending

/**
 * The journal's MAC key and the epoch made with it (spending authority G5.3 rule 1). An epoch is one
 * unbroken life of a journal: a random id that exists exactly as long as its key does. On Android the key
 * belongs in the hardware key store, where it cannot leave the device and is deleted with the app's data.
 * Each namespace has its own key and epoch.
 *
 * The device implementation (Android Keystore HMAC) is not written yet; tests use a software one.
 */
interface JournalKeys {
    /** The epoch of the key in place for [namespace], or null when there is none: never made, or gone with the app's data. */
    fun epoch(namespace: Namespace): String?

    /** Makes a new key and a new random epoch (32 hex digits) for [namespace], replacing any earlier key, and returns the epoch. */
    fun create(namespace: Namespace): String

    /** HMAC-SHA256 of [data] under the key of [epoch], or null when that key is not the one in place. */
    fun mac(namespace: Namespace, epoch: String, data: ByteArray): ByteArray?
}

/** The three clocks the journal stamps with (G5.2): wall time, time since boot, and how many times the device has booted. */
interface SpendClock {
    fun wallMs(): Long
    fun elapsedMs(): Long
    fun bootCount(): Int
}

/** JSON with sorted keys, no spaces and whole numbers only: the bytes the journal hashes. Matches `JSON.stringify` on the same values. */
internal object Canonical {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, v: Any?) {
        when (v) {
            null -> out.append("null")
            is Boolean -> out.append(v)
            is Int -> out.append(v)
            is Long -> out.append(v)
            is String -> string(out, v)
            is Map<*, *> -> {
                out.append('{')
                v.keys.map { it as String }.sorted().forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    string(out, k); out.append(':'); write(out, v[k])
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                v.forEachIndexed { i, x -> if (i > 0) out.append(','); write(out, x) }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Not a journal value: ${v::class.java.simpleName}")
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
