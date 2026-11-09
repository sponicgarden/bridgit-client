package cc.bridgit.app.feedback

import android.os.Build
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.update.UpdateLogic
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The More page's filed/fixed counts for the owner across all their devices (A0170). The backend counts reports
 * by filing device (GET /v1/issues/device-counts, signed by this install) for every Bridgit linked to the same
 * Google account. This device's own numbers always come from its local store (they include reports not uploaded
 * yet); the other devices' numbers come from the backend's last answer, cached so the page opens instantly.
 */
object OwnerFeedbackCounts {
    data class Counts(val filed: Int, val fixed: Int) {
        operator fun plus(o: Counts) = Counts(filed + o.filed, fixed + o.fixed)
    }
    data class Device(val domain: String?, val label: String?, val thisDevice: Boolean, val bug: Counts, val feature: Counts) {
        fun of(kind: IssueKind) = if (kind == IssueKind.FEATURE) feature else bug
        val name: String get() = domain ?: label ?: "unnamed device"
    }
    data class Owner(val linked: Boolean, val devices: List<Device>)

    private const val CACHE = "owner_counts_json"
    private const val CACHE_AT = "owner_counts_at"
    private const val FRESH_MS = 5 * 60_000L
    private val quick by lazy { FeedbackUploader.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build() }

    /** Parses the backend's answer. Pure, so it is unit-tested. Null when it isn't one. */
    fun parse(text: String): Owner? = runCatching {
        val o = JSONObject(text)
        val arr = o.getJSONArray("devices")
        fun counts(d: JSONObject, k: String) = d.optJSONObject(k)?.let { Counts(it.optInt("filed"), it.optInt("fixed")) } ?: Counts(0, 0)
        Owner(o.optBoolean("owner_linked"), (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Device(d.optString("domain").takeIf { !d.isNull("domain") && it.isNotBlank() },
                d.optString("label").takeIf { !d.isNull("label") && it.isNotBlank() },
                d.optBoolean("this_device"), counts(d, "bug"), counts(d, "feature"))
        })
    }.getOrNull()

    /**
     * The owner's totals for [kind]: this device's [local] counts plus every other device's from [owner].
     * Without an owner answer (or with only this device), just [local].
     */
    fun total(owner: Owner?, kind: IssueKind, local: Counts): Counts =
        (owner?.devices ?: emptyList()).filter { !it.thisDevice }.fold(local) { acc, d -> acc + d.of(kind) }

    /** "pixel-pal 3 · 1, cream-tab 2 · 0": each device's filed · fixed, this device (local numbers) first. Null for one device. */
    fun breakdown(owner: Owner?, kind: IssueKind, local: Counts): String? {
        val devices = owner?.devices ?: return null
        if (devices.size < 2) return null
        return devices.sortedByDescending { it.thisDevice }.joinToString(", ") { d ->
            val c = if (d.thisDevice) local else d.of(kind)
            "${d.name} ${c.filed} · ${c.fixed}"
        }
    }

    /** The last answer, or null. */
    fun cached(app: BridgeApp): Owner? = FeedbackUploader.prefs(app).getString(CACHE, null)?.let(::parse)

    /** Asks the backend again (at most every few minutes). Blocking; call off the main thread. True when the cache changed. */
    fun refresh(app: BridgeApp): Boolean {
        if (!FeedbackUploader.configured || !app.settings.feedbackUpload) return false
        val prefs = FeedbackUploader.prefs(app)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(CACHE_AT, 0L) < FRESH_MS) return false
        prefs.edit().putLong(CACHE_AT, now).apply()
        val url = (BuildConfig.FEEDBACK_URL.trimEnd('/') + "/v1/issues/device-counts").toHttpUrl()
        val text = runCatching {
            // Only an install that has uploaded before is known to the backend.
            val headers = FeedbackUploader.auth(app, "GET", url, ByteArray(0), register = false) ?: return false
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("User-Agent", UpdateLogic.userAgent(app.appVersion(), Build.VERSION.RELEASE)).get().build()
            quick.newCall(req).execute().use { res -> if (res.code == 200) res.body?.string() else null }
        }.getOrNull() ?: return false
        if (parse(text) == null || text == prefs.getString(CACHE, null)) return false
        prefs.edit().putString(CACHE, text).apply()
        return true
    }
}
