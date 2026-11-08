package cc.bridgit.app.portal

import android.content.Context
import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONObject

/**
 * Update prompts on the owner's Bridgit Portal devices (A0050, 7 Nov 2026). Apple doesn't let an app install
 * itself, so the iPhone/iPad/Mac app checks for a newer TestFlight build and prompts the owner, on by default.
 * Agents reach those devices through this phone: an owner configuration action leaves a [Request] here, the
 * Portal picks it up from GET /v1/portal/snapshot (`updates`) the next time it syncs, applies it and reports
 * its state with POST /v1/portal/updates. Agents read that state in GET /v1/owner/settings → portal_updates.
 */
object PortalUpdates {
    /** What an agent asked one Portal to do: set its prompt switch, and/or show the update prompt now. */
    data class Request(val id: String, val prompts: Boolean?, val promptNow: Boolean, val at: Long) {
        fun json(): JSONObject = JSONObject().put("id", id).put("prompt_now", promptNow).put("at", at)
            .apply { prompts?.let { put("prompts", it) } }
    }

    /** What a Portal last said about its updates. */
    data class Report(
        val promptsEnabled: Boolean, val installedBuild: String?, val latestBuild: String?,
        val supported: Boolean, val lastResult: String?, val at: Long,
    ) {
        val updateReady: Boolean get() = PortalUpdatesLogic.newer(latestBuild, installedBuild)
    }

    private const val PREFS = "portal_updates"

    /** Leaves a request for [portal]; a newer one replaces the switch value and keeps a pending prompt. */
    fun request(context: Context, portal: String, prompts: Boolean?, promptNow: Boolean, now: Long = System.currentTimeMillis()): Request {
        val next = PortalUpdatesLogic.merge(pending(context, portal), prompts, promptNow, now)
        prefs(context).edit().putString("req_$portal", next.json().toString()).apply()
        return next
    }

    fun pending(context: Context, portal: String): Request? =
        prefs(context).getString("req_$portal", null)?.let { PortalUpdatesLogic.parseRequest(it) }

    fun report(context: Context, portal: String): Report? =
        prefs(context).getString("rep_$portal", null)?.let { PortalUpdatesLogic.parseStoredReport(it) }

    /** A Portal's POST /v1/portal/updates: save its state and clear the request it acknowledges. */
    fun receive(context: Context, portal: String, body: JSONObject, now: Long = System.currentTimeMillis()): JSONObject {
        val r = PortalUpdatesLogic.parseReport(body, now)
        val e = prefs(context).edit().putString("rep_$portal", PortalUpdatesLogic.storeReport(r).toString())
        val ack = body.optString("ack").ifEmpty { null }
        if (ack != null && pending(context, portal)?.id == ack) e.remove("req_$portal")
        e.apply()
        return JSONObject().put("saved", true).put("pending", pending(context, portal)?.json() ?: JSONObject.NULL)
    }

    fun forget(context: Context, portal: String) { prefs(context).edit().remove("req_$portal").remove("rep_$portal").apply() }

    /** One entry for an agent: which Portal, what it last reported, and what is still waiting for it. */
    fun status(context: Context, portal: String, name: String?): JSONObject {
        val r = report(context, portal)
        return JSONObject().put("portal_id", portal).put("name", name ?: JSONObject.NULL)
            .put("prompts_enabled", r?.promptsEnabled ?: JSONObject.NULL)
            .put("supported", r?.supported ?: JSONObject.NULL)
            .put("installed_build", r?.installedBuild ?: JSONObject.NULL)
            .put("latest_build", r?.latestBuild ?: JSONObject.NULL)
            .put("update_ready", r?.updateReady ?: JSONObject.NULL)
            .put("last_result", r?.lastResult ?: JSONObject.NULL)
            .put("reported_at", r?.at ?: JSONObject.NULL)
            .put("pending", pending(context, portal)?.json() ?: JSONObject.NULL)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The pure parts of [PortalUpdates], unit-tested on the JVM. */
object PortalUpdatesLogic {
    val RESULTS = setOf("up_to_date", "update_ready", "prompted", "opened_testflight", "later", "unsupported", "check_failed", "off")
    private val BUILD = Regex("[0-9]{1,12}")

    fun merge(old: PortalUpdates.Request?, prompts: Boolean?, promptNow: Boolean, now: Long): PortalUpdates.Request =
        PortalUpdates.Request("pur_$now", prompts ?: old?.prompts, promptNow || old?.promptNow == true, now)

    /** Build numbers are yymmddHHMM (publish-testflight.sh); compare them as numbers. */
    fun newer(latest: String?, installed: String?): Boolean {
        val l = latest?.toLongOrNull() ?: return false
        val i = installed?.toLongOrNull() ?: return false
        return l > i
    }

    fun parseReport(b: JSONObject, now: Long): PortalUpdates.Report {
        val allowed = setOf("prompts_enabled", "installed_build", "latest_build", "supported", "last_result", "ack")
        b.keys().forEach { if (it !in allowed) throw ErrorCatalog.error("invalid_parameter", "Unknown field $it.") }
        val on = b.opt("prompts_enabled") as? Boolean ?: throw ErrorCatalog.error("invalid_parameter", "prompts_enabled must be true or false.")
        val supported = b.opt("supported") as? Boolean ?: throw ErrorCatalog.error("invalid_parameter", "supported must be true or false.")
        fun build(k: String): String? = if (!b.has(k) || b.isNull(k)) null else
            b.optString(k).takeIf { BUILD.matches(it) } ?: throw ErrorCatalog.error("invalid_parameter", "$k must be a build number.")
        val result = if (!b.has("last_result") || b.isNull("last_result")) null else
            b.optString("last_result").takeIf { it in RESULTS } ?: throw ErrorCatalog.error("invalid_parameter", "Unknown last_result.")
        if (b.has("ack") && !b.optString("ack").matches(Regex("pur_[0-9]{1,15}"))) throw ErrorCatalog.error("invalid_parameter", "ack must be a request id.")
        return PortalUpdates.Report(on, build("installed_build"), build("latest_build"), supported, result, now)
    }

    fun storeReport(r: PortalUpdates.Report): JSONObject = JSONObject().put("prompts_enabled", r.promptsEnabled)
        .put("installed_build", r.installedBuild ?: JSONObject.NULL).put("latest_build", r.latestBuild ?: JSONObject.NULL)
        .put("supported", r.supported).put("last_result", r.lastResult ?: JSONObject.NULL).put("at", r.at)

    fun parseStoredReport(raw: String): PortalUpdates.Report? = runCatching {
        val o = JSONObject(raw)
        fun s(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
        PortalUpdates.Report(o.getBoolean("prompts_enabled"), s("installed_build"), s("latest_build"), o.getBoolean("supported"), s("last_result"), o.getLong("at"))
    }.getOrNull()

    fun parseRequest(raw: String): PortalUpdates.Request? = runCatching {
        val o = JSONObject(raw)
        PortalUpdates.Request(o.getString("id"), if (o.has("prompts")) o.getBoolean("prompts") else null, o.optBoolean("prompt_now"), o.getLong("at"))
    }.getOrNull()
}
