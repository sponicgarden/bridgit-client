package cc.bridgit.app.ui.applets

import android.net.Uri
import org.json.JSONObject

/**
 * Pure parts of the Applets screen's three tabs (owner, 4 Nov 2026): which web portals the Portals tab offers and
 * which cards the Cards tab lists. Kept free of Android views so unit tests pin them down.
 */
object AppletLists {
    const val PORTAL_PAGE = "https://bridgit.cc/myapps/"

    /** Navigation only: the website still checks the signed-in owner's/viewer's scope. */
    fun settingsTarget(uri: Uri?): String? {
        uri ?: return null
        val web = uri.scheme == "https" && uri.host == "bridgit.cc" && uri.path in setOf("/applet-settings", "/applet-settings/")
        val custom = uri.scheme == "bridgit" && uri.host == "settings" && uri.path.orEmpty() in setOf("", "/")
        if ((!web && !custom) || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
        if (uri.getQueryParameters("device").size != 1 || uri.getQueryParameters("applet").size != 1) return null
        val phone = uri.getQueryParameter("device") ?: return null
        val applet = uri.getQueryParameter("applet") ?: return null
        if (!Regex("^bgi_[a-z2-7]{24}$").matches(phone) || (applet != "dog" && !PACKAGE_APPLET.matches(applet))) return null
        return Uri.parse(PORTAL_PAGE).buildUpon().appendQueryParameter("device", phone).appendQueryParameter("screen", "settings").fragment(applet).build().toString()
    }

    /** One web portal into an applet: [id] is its page section on bridgit.cc/myapps. */
    data class Portal(val id: String, val name: String, val url: String)

    /**
     * The applet portals this device can open (owner, 5 Nov 2026): Pet monitor while this device runs it with a web
     * portal ([dogHere]), or while any device of its account does or one was shared with it ([dogOnAccount]). Opened
     * from another device, the link names no device and the page picks the one that runs it. Applets only: agent
     * activity is on the Agents tab. There is no "all applets" entry, since the picker is how you choose an applet.
     *
     * Installed package applets follow (owner, 5 Nov 2026: Kitchen Cleanliness was missing): each one whose portal is
     * on here ([packagesHere], package id to name), then each other one on the account ([accountApplets] holds their
     * "pkg.<id>" ids), named from [names] when this device has it installed, else from its id. The page opens one
     * from "#pkg.<id>" and only for an id it accepts ([PACKAGE_APPLET]), so any other id is left out.
     */
    fun portals(dogHere: Boolean, dogOnAccount: Boolean, device: String?, packagesHere: Map<String, String> = emptyMap(),
                accountApplets: Set<String> = emptySet(), names: Map<String, String> = emptyMap()): List<Portal> {
        fun url(section: String, here: Boolean) = PORTAL_PAGE +
            (if (here && device != null) "?device=" + Uri.encode(device) else "") + "#$section"
        val out = mutableListOf<Portal>()
        if (dogHere || dogOnAccount) out += Portal("dog", "Pet monitor", url("dog", dogHere))
        val here = packagesHere.mapKeys { "pkg." + it.key }.filterKeys { PACKAGE_APPLET.matches(it) }
        val elsewhere = accountApplets.filter { PACKAGE_APPLET.matches(it) && it !in here }
        out += (here.map { (id, name) -> Portal(id, name.ifBlank { nameFromId(id) }, url(id, true)) } +
            elsewhere.map { id -> Portal(id, names[id.removePrefix("pkg.")]?.ifBlank { null } ?: nameFromId(id), url(id, false)) })
            .sortedBy { it.name.lowercase() }
        return out
    }

    /** The current applet identity must agree with the trusted page and selected picker entry. */
    fun portalTitle(title: String?, url: Uri?, selected: String?): String? {
        if (url?.scheme != "https" || url.host != "bridgit.cc" || selected == null ||
            url.fragment?.substringBefore('&') != selected) return null
        val suffix = " — Bridgit"
        if (title == null || !title.endsWith(suffix)) return null
        val name = title.removeSuffix(suffix).trim()
        return name.takeIf { it.length in 1..40 && it.none { c -> c.code < 32 || c.code == 127 } }
    }

    fun portalIdentity(raw: String?, url: Uri?, selected: String?): String? {
        val identity = runCatching { JSONObject(raw ?: return null) }.getOrNull() ?: return null
        if (identity.optString("applet") != selected) return null
        return portalTitle(identity.optString("name") + " — Bridgit", url, selected)
    }

    /** The portal page's own rule for an installed applet's tab (bridgit.cc/myapps watch.js isPackage). */
    val PACKAGE_APPLET = Regex("^pkg\\.[a-z][a-z0-9-]{1,63}$")

    /** "pkg.kitchen-cleanliness" → "Kitchen cleanliness", for an applet this device doesn't have installed. */
    fun nameFromId(id: String): String = id.removePrefix("pkg.").replace('-', ' ').trim().replaceFirstChar { it.uppercase() }

    /** The shape of a portal sign-in code from bridgit-feedback (32 random bytes, base64url). */
    val LOGIN_CODE = Regex("^[A-Za-z0-9_-]{43}$")

    /**
     * [url] with a sign-in code in its fragment ("#dog&login=..."): the page takes it out of the address at once and
     * trades it for a session. A fragment never reaches a server, so the code stays out of request logs.
     */
    fun withLogin(url: String, code: String): String {
        require(LOGIN_CODE.matches(code))
        return if ('#' in url) "$url&login=$code" else "$url#login=$code"
    }

    /** Whether a bridgit.cc cookie header already carries the portal's sign-in session. */
    fun hasPortalSession(cookies: String?): Boolean =
        cookies?.split(';')?.any { it.trim().startsWith("__Host-bridgit-session=") && it.trim().length > 23 } == true

    /** Pages a portal may show in place: bridgit.cc and its subdomains, https only. Anything else opens outside. */
    fun staysInPortal(url: Uri): Boolean {
        val host = url.host?.lowercase() ?: return false
        return url.scheme == "https" && (host == "bridgit.cc" || host.endsWith(".bridgit.cc"))
    }

    /** One card the Cards tab shows; [html] is the whole page, as an agent would post it. */
    data class Card(val id: String, val title: String, val summary: String, val html: String)

    /**
     * The HTML cards in a samples file (site-content/public/samples/samples.json, copied into the APK): the sample
     * interactions, until a set of preloaded cards for agents replaces them. Titles drop the "HTML card: " prefix.
     */
    fun cards(file: String): List<Card> {
        val list = JSONObject(file).optJSONArray("samples") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            val html = s.optJSONObject("request")?.optString("html").orEmpty()
            if (s.optString("kind") != "html" || html.isBlank()) return@mapNotNull null
            val title = s.optString("title").removePrefix("HTML card: ").replaceFirstChar { it.uppercase() }
            Card(s.optString("id"), title, s.optString("summary"), html)
        }
    }
}
