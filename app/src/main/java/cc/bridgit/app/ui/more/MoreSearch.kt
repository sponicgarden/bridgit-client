package cc.bridgit.app.ui.more

import java.text.Normalizer
import java.util.Locale

/** Transient navigation query; it never changes settings or leaves the device. */
object MoreSearch {
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    fun matches(query: String, label: String): Boolean {
        val haystack = normalize(label)
        return normalize(query).trim().split(Regex("\\s+")).all { it in haystack }
    }
}
