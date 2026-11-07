package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.outbound.Outbound
import org.json.JSONObject

/** Registry metadata. Each browser owns its cursor and the page currently on screen. */
object AppletLibrary {
    data class Entry(val id: String, val key: String, val revision: Int, val title: String, val desc: String,
        val icon: Int = R.drawable.ic_nav_applets) {
        val installUri get() = "bridgit://install?template=$key&revision=$revision"
    }
    data class Page(val entries: List<Entry>, val next: String?)

    /** Fetch one bounded catalog page, on an IO dispatcher. No automatic download of definitions. */
    fun page(app: BridgeApp, after: String? = null, query: String = "", owned: Boolean = false): Page {
        val scope = if (owned) "owned" else "public"
        val path = "/v1/templates/catalog?kind=applet&scope=$scope&limit=50" +
            (after?.let { "&after=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "") +
            "&q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val link = AppletPackage.Link("https://${AppletPackage.REGISTRY_HOST}$path", null, "catalog", null)
        val data = JSONObject(AppletFetch.fetch(app, link, Outbound.Actor.OWNER, raw = true))
        if (data.optString("format") != "bridgit-applet-catalog/1") throw AppletFetch.Failed("Unsupported applet catalog.")
        if (data.optString("scope", "public") != scope) throw AppletFetch.Failed("The registry returned a different catalog scope.")
        val rows = data.getJSONArray("applets")
        if (rows.length() > 50) throw AppletFetch.Failed("Catalog page is too large.")
        val entries = (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            val revision = it.opt("revision")?.toString()?.toIntOrNull()
            if (revision == null || AppletPackage.registryLink(it.getString("key"), revision) == null) throw AppletFetch.Failed("Invalid catalog entry.")
            Entry(it.getString("id"), it.getString("key"), revision, it.getString("name"), it.getString("summary"))
        } }
        return Page(entries, data.optString("next").takeUnless { it.isEmpty() || it == "null" })
    }
}
