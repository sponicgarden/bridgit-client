package cc.bridgit.app.update

import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * Pure checks for the in-app update flow (spec 12.14, D21, UPD-01..08). No android.* here,
 * so all of it is unit-tested on the JVM. Ported in concept from Spotka's AppUpdateLogic.
 *
 * Every rule fails closed (UPD-03): anything unexpected is a [Problem], never a fallback.
 */
object UpdateLogic {
    const val HOST = "pub-3a79691572bc4abc93fce31910fe320b.r2.dev"
    const val MANIFEST_URL = "https://$HOST/bridget/latest.json"
    const val PATH_PREFIX = "/bridget/"
    /** The manifest is a few hundred bytes; anything this big is not ours. */
    const val MAX_MANIFEST_BYTES = 64L * 1024
    const val MAX_NOTES_CHARS = 2000
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Why a check, download or install was refused. The dialog maps each to plain copy. */
    enum class Problem {
        NETWORK, HTTP_STATUS, FORBIDDEN, REDIRECT, BAD_MANIFEST, WRONG_PACKAGE, UNSAFE_URL, BAD_SHA256, BAD_SIZE,
        SIZE_MISMATCH, CHECKSUM, NOT_AN_APK, ARCHIVE_PACKAGE, ARCHIVE_VERSION, NOT_NEWER,
        UNSIGNED, CERT_MISMATCH, STORAGE,
    }

    enum class Compare { NEWER, CURRENT, LOCAL_AHEAD, UNCOMPARABLE }

    /** `bridget/latest.json`, UPD-01. */
    data class Manifest(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val publishedAt: String?,
        val notes: String?,
    )

    sealed class Parsed {
        data class Ok(val manifest: Manifest) : Parsed()
        data class Refused(val problem: Problem, val detail: String) : Parsed()
    }

    /** Android versionCode is the only authority for an in-place update. */
    fun compare(installed: Long, remote: Long): Compare = when {
        installed <= 0 || remote <= 0 -> Compare.UNCOMPARABLE
        remote > installed -> Compare.NEWER
        remote == installed -> Compare.CURRENT
        else -> Compare.LOCAL_AHEAD
    }

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val VERSION_NAME = Regex("^[0-9A-Za-z.+_-]{1,32}$")

    fun isValidSha256(value: String?): Boolean = value != null && SHA256.matches(value)

    /** 408, 429 and 5xx are worth one more try; every other status fails fast. */
    fun isTransientHttpStatus(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

    /** Any 3xx is refused: no redirects at all (UPD-03). */
    fun isRedirect(code: Int): Boolean = code in 300..399

    /** Which refusal a non-200 status is: any 3xx is a redirect, 403 is the server refusing us. */
    fun problemForStatus(code: Int): Problem = when {
        isRedirect(code) -> Problem.REDIRECT
        code == 403 -> Problem.FORBIDDEN
        else -> Problem.HTTP_STATUS
    }

    /**
     * Sent on every request. Cloudflare answers 403 to some default user agents, so Bridgit
     * names itself. Only printable ASCII, so an odd version string can't break the header.
     */
    fun userAgent(versionName: String, androidRelease: String): String {
        fun clean(s: String) = s.filter { it in ' '..'~' && it != '(' && it != ')' }.trim().take(32).ifEmpty { "?" }
        return "Bridgit/${clean(versionName)} (Android ${clean(androidRelease)})"
    }

    fun isSafeApkUrl(url: String?): Boolean = isSafeUrl(url, ".apk")
    fun isSafeManifestUrl(url: String?): Boolean = isSafeUrl(url, ".json")

    /**
     * HTTPS, exactly [HOST], default port, no userinfo, no query or fragment, a path under
     * [PATH_PREFIX] with no `..`, empty or encoded segments, ending in [suffix].
     */
    private fun isSafeUrl(url: String?, suffix: String): Boolean {
        if (url == null || url.length > 512 || url != url.trim()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.isAbsolute || uri.isOpaque) return false
        if (uri.scheme != "https") return false
        if (uri.rawUserInfo != null) return false
        if (uri.host != HOST) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath ?: return false
        if (!path.startsWith(PATH_PREFIX) || !path.endsWith(suffix) || path.contains('%')) return false
        val segments = path.removePrefix("/").split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return path.length > PATH_PREFIX.length + suffix.length
    }

    /** Host only, for the log (UPD-07). Never the path or query. */
    fun hostOf(url: String?): String = runCatching { URI(url ?: "").host }.getOrNull() ?: "?"

    /**
     * Parses and validates the manifest. [installedPackage] is the running app's package:
     * a manifest for another package (a debug build reading the release manifest) is refused.
     */
    fun parseManifest(body: String, installedPackage: String): Parsed {
        val o = try { JSONObject(body) } catch (e: JSONException) {
            return Parsed.Refused(Problem.BAD_MANIFEST, "not JSON")
        }
        val pkg = o.optString("package", "")
        if (pkg.isEmpty()) return Parsed.Refused(Problem.BAD_MANIFEST, "no package")
        if (pkg != installedPackage) return Parsed.Refused(Problem.WRONG_PACKAGE, "package $pkg")
        val name = o.optString("version_name", "")
        if (!VERSION_NAME.matches(name)) return Parsed.Refused(Problem.BAD_MANIFEST, "bad version_name")
        val code = longField(o, "version_code")
        if (code == null || code <= 0) return Parsed.Refused(Problem.BAD_MANIFEST, "bad version_code")
        val url = o.optString("url", "")
        if (!isSafeApkUrl(url)) return Parsed.Refused(Problem.UNSAFE_URL, "url host ${hostOf(url)}")
        val sha = o.optString("sha256", "")
        if (!isValidSha256(sha)) return Parsed.Refused(Problem.BAD_SHA256, "bad sha256")
        val size = longField(o, "size_bytes")
        if (size == null || size <= 0) return Parsed.Refused(Problem.BAD_SIZE, "bad size_bytes")
        val notes = o.optString("notes", "").trim().take(MAX_NOTES_CHARS).ifEmpty { null }
        val published = o.optString("published_at", "").trim().take(64).ifEmpty { null }
        return Parsed.Ok(Manifest(pkg, name, code, url, sha.lowercase(), size, published, notes))
    }

    /** A whole-number JSON field. Strings, fractions and booleans are refused, not coerced. */
    private fun longField(o: JSONObject, key: String): Long? {
        val v = o.opt(key) ?: return null
        return when (v) {
            is Int -> v.toLong()
            is Long -> v
            is java.math.BigInteger -> v.takeIf { it.bitLength() < 64 }?.toLong()
            else -> null
        }
    }

    /** Once a day at most, and again if the clock went backwards. */
    fun isAutoCheckDue(now: Long, lastCheck: Long): Boolean =
        lastCheck <= 0 || lastCheck > now || now - lastCheck >= DAY_MS

    /** The screens of the update dialog (UPD-02). */
    enum class Screen { CHECKING, UP_TO_DATE, LOCAL_AHEAD, NEWER, DOWNLOADING, READY, ERROR, DEBUG }

    /** How long a "nothing to do" message stays before it fades away by itself. */
    const val AUTO_CLOSE_MS = 10_000L

    /** Length of that fade. */
    const val FADE_MS = 700L

    /**
     * Milliseconds before [screen] closes by itself, or null if it waits for the owner.
     * Only the "nothing to do" messages close themselves; anything with a choice
     * (Download, Install), a running download or an error stays until the owner acts.
     */
    fun autoCloseAfterMs(screen: Screen): Long? = when (screen) {
        Screen.UP_TO_DATE, Screen.LOCAL_AHEAD -> AUTO_CLOSE_MS
        else -> null
    }

    /** Countdown bar fill (0..1) after [elapsedMs] of an [AUTO_CLOSE_MS] wait: full at start, empty at the end. */
    fun countdownFraction(elapsedMs: Long, totalMs: Long = AUTO_CLOSE_MS): Float =
        if (totalMs <= 0) 0f else (1f - elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)

    /** Lower-case hex without separators, or null if it isn't a SHA-256. */
    fun normalizeCertDigest(d: String): String? {
        val hex = d.replace(":", "").trim().lowercase()
        return if (isValidSha256(hex)) hex else null
    }

    /**
     * The archive's signing certificates must equal the installed app's, as sets of SHA-256
     * digests. Empty sets (unsigned, or unreadable) and malformed digests never match.
     */
    fun certSetsMatch(archive: Collection<String>, installed: Collection<String>): Boolean {
        if (archive.isEmpty() || installed.isEmpty()) return false
        val a = archive.map { normalizeCertDigest(it) ?: return false }.toSet()
        val b = installed.map { normalizeCertDigest(it) ?: return false }.toSet()
        return a == b
    }

    /** The archive must be the advertised build and newer than what's installed (UPD-03). */
    fun checkArchive(archivePackage: String?, archiveVersion: Long, manifest: Manifest,
                     installedPackage: String, installedVersion: Long): Problem? = when {
        archivePackage == null -> Problem.NOT_AN_APK
        archivePackage != installedPackage || archivePackage != manifest.packageName -> Problem.ARCHIVE_PACKAGE
        archiveVersion != manifest.versionCode -> Problem.ARCHIVE_VERSION
        compare(installedVersion, archiveVersion) != Compare.NEWER -> Problem.NOT_NEWER
        else -> null
    }
}
