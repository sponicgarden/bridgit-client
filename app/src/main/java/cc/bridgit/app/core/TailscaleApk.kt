package cc.bridgit.app.core

import org.json.JSONObject
import java.net.URI

/**
 * Pure side of Bridgit installing Tailscale itself (private build only), so the owner doesn't need
 * the Play Store. Tailscale publishes its Android app on pkgs.tailscale.com with a JSON index and a
 * .sha256 file next to each APK. Plain Kotlin, unit-tested.
 *
 * Trust: the download must match the published SHA-256 AND be signed with Tailscale's own key
 * ([CERT_SHA256], the same key as the Play Store copy, checked on 4 Nov 2026). The key pin is what
 * matters: a wrong file from anywhere is refused, and Play keeps updating the app afterwards.
 */
object TailscaleApk {
    const val HOST = "pkgs.tailscale.com"
    const val INDEX_URL = "https://$HOST/stable/?mode=json"
    private const val PATH_PREFIX = "/stable/"
    /** SHA-256 of Tailscale's signing certificate (com.tailscale.ipn on Play and on pkgs.tailscale.com). */
    const val CERT_SHA256 = "5cdb295551bfe1a087fed6acda07141c6c929fa7c29bd273a7092813acc434bf"
    /** The index is a few KB. */
    const val MAX_INDEX_BYTES = 256L * 1024
    /** The APK is about 100 MB; anything far bigger is not it. */
    const val MAX_APK_BYTES = 400L * 1024 * 1024

    private val FILE = Regex("^tailscale-android-universal-[0-9]+(\\.[0-9]+){1,3}\\.apk$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    data class Release(val version: String, val file: String) {
        val apkUrl get() = "https://$HOST$PATH_PREFIX$file"
        val shaUrl get() = "$apkUrl.sha256"
    }

    /** The universal APK from the index (`{"APKs":{"universal":"tailscale-android-universal-1.104.5.apk"},"APKsVersion":"1.104.5"}`), or null. */
    fun parseIndex(json: String?): Release? = runCatching {
        val o = JSONObject(json ?: return null)
        val file = o.optJSONObject("APKs")?.optString("universal").orEmpty()
        if (!FILE.matches(file)) return null
        val version = o.optString("APKsVersion").ifEmpty { file.removePrefix("tailscale-android-universal-").removeSuffix(".apk") }
        Release(version.take(32), file)
    }.getOrNull()

    /** The first word of the .sha256 file, lower-cased, if it is a SHA-256. */
    fun parseSha256(text: String?): String? =
        text?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()?.takeIf { SHA256.matches(it) }

    /** HTTPS on [HOST], default port, no query except the index's own, a plain path under /stable/. */
    fun isSafeUrl(url: String?): Boolean {
        if (url == null || url.length > 512) return false
        if (url == INDEX_URL) return true
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host != HOST || uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath ?: return false
        if (!path.startsWith(PATH_PREFIX)) return false
        val name = path.removePrefix(PATH_PREFIX)
        return FILE.matches(name) || (name.endsWith(".sha256") && FILE.matches(name.removeSuffix(".sha256")))
    }

    /** True only when the APK is signed by Tailscale's key and nothing else. */
    fun signedByTailscale(certDigests: Collection<String>): Boolean =
        certDigests.isNotEmpty() && certDigests.all { it.lowercase().replace(":", "") == CERT_SHA256 }

    /** "Downloading Tailscale… 42%" helper: percent of [total], or -1 when the length is unknown. */
    fun percent(done: Long, total: Long): Int = if (total <= 0) -1 else ((done * 100) / total).toInt().coerceIn(0, 100)
}
