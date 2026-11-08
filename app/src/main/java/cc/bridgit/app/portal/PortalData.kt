package cc.bridgit.app.portal

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure parts of the Bridgit Portal (iPhone & iPad) support, so unit tests pin them down: the pairing link,
 * the Home node table and design tokens read from the Home scene (assets/diagnostics/scene.html), and the
 * shape of one log entry. Contract: docs/ios-portal-api.md.
 */
object PortalData {
    const val AGENT_NAME = "Bridgit Portal"
    const val SCHEME = "bridgitportal"
    const val LINK_VERSION = 1

    /**
     * The relay routes a portal agent may call. Read-only except two POSTs: a network scan, and a photo the owner
     * took on the portal device for the photo watches. No phone capture, taps or settings.
     */
    val ROUTES: List<String> = listOf(
        "GET /v1/portal/snapshot", "GET /v1/portal/home", "GET /v1/portal/devices", "GET /v1/portal/brands/{id}/logo",
        "GET /v1/portal/logs", "GET /v1/portal/agents", "GET /v1/portal/applets", "GET /v1/portal/theme",
        "GET /v1/lan/brands/{id}/logo",
        // Network Diagnostics from the portal: a bounded scan under the owner's Local network discovery switch.
        "POST /v1/lan/discover",
        // The Portal's Camera (5 Nov 2026): who checks photos, and send one.
        "GET /v1/portal/applets/inventory", "GET /v1/portal/applets/watches",
        "GET /v1/portal/photo/targets", "POST /v1/portal/photo",
        // Update prompts (A0050, 7 Nov 2026): the Portal reports its build and applies agents' requests.
        "POST /v1/portal/updates",
    )
    /** Capability grants of a portal agent: the portal endpoints, and lan_discovery for the existing logo route. */
    val GRANTS: Set<String> = setOf("portal", "lan_discovery")

    /** Whether a relay allowlist is a portal agent's (has the portal home route and nothing outside [ROUTES]). */
    fun isPortalConfig(routes: Set<String>, tools: Set<String>): Boolean =
        "GET /v1/portal/home" in routes && routes.all { it in ROUTES } && tools.isEmpty()

    /** A portal allowlist from any earlier release: only GET portal/logo routes (plus the current set). For in-place upgrades. */
    fun isLegacyPortalConfig(routes: Set<String>, tools: Set<String>): Boolean =
        "GET /v1/portal/home" in routes && tools.isEmpty() &&
            routes.all { it in ROUTES || (it.startsWith("GET /v1/portal/") ) }

    fun matchesPin(record: JSONObject, template: String, revision: Int): Boolean {
        val pin = record.optJSONObject("source") ?: return false
        return revision > 0 && pin.optString("kind") == "template" && pin.optString("template") == template &&
            pin.optInt("revision", -1) == revision
    }

    /** Only a reviewed registry install can lend its card's enrollment view to the owner's portal. */
    fun inventoryAgent(record: JSONObject, template: String, revision: Int): String? {
        if (!matchesPin(record, template, revision) || record.optString("status") != "active") return null
        val sources = record.optJSONObject("definition")?.optJSONObject("ui")?.optJSONObject("card")
            ?.optJSONObject("sources") ?: return null
        val declared = record.optJSONArray("declared") ?: return null
        if ((0 until declared.length()).none { declared.optString(it) == "lan_control" }) return null
        val inventory = sources.keys().asSequence().any { key ->
            val call = sources.optJSONObject(key)?.optJSONObject("call")
            call?.optString("method") == "GET" && call.optString("path") == "/v1/lan/devices" && call.length() == 2
        }
        return record.optString("agent_id").takeIf { inventory && it.isNotBlank() }
    }

    /** Card sources opt into their own watch data. Only display fields cross the pairing. */
    fun watchReads(record: JSONObject, key: String): Set<String> {
        val sources = record.optJSONObject("definition")?.optJSONObject("ui")?.optJSONObject("card")
            ?.optJSONObject("sources") ?: return emptySet()
        return sources.keys().asSequence().mapNotNull { name ->
            val source = sources.optJSONObject(name) ?: return@mapNotNull null
            source.optString("read").takeIf { source.optJSONObject("watch")?.optString("\$watch") == key }
        }.toSet()
    }

    fun checkForCard(check: JSONObject): JSONObject = JSONObject().put("at",check.getString("at")).apply {
        when {
            check.has("error") -> put("outcome","error")
            check.has("skipped") -> put("outcome","skipped")
            check.opt("matched") is Boolean -> put("matched",check.getBoolean("matched"))
            else -> put("outcome","checked")
        }
    }

    fun tallyForCard(tally: JSONObject): JSONObject? {
        val today = tally.optJSONObject("today") ?: return null
        val episodes = tally.optJSONArray("episodes") ?: return null
        val shortened = JSONArray()
        for (i in (episodes.length() - 20).coerceAtLeast(0) until episodes.length()) {
            val episode = episodes.getJSONObject(i)
            shortened.put(JSONObject(episode, arrayOf("start","label","minutes")))
        }
        val labels = today.optJSONObject("labels") ?: return null
        val counts = JSONObject()
        for (label in labels.keys()) {
            val values = labels.optJSONObject(label) ?: continue
            counts.put(label, JSONObject(values, arrayOf("episodes", "minutes", "readings")))
        }
        return JSONObject().put("today", JSONObject(today, arrayOf("checks", "watched_minutes")).put("labels", counts))
            .put("episodes", shortened)
    }

    // ---- Pairing link ----

    /** The landing page a shared pairing link points to: where to get the app and how to pair (iPhone, iPad and Android). */
    const val PAIRING_PAGE = "https://bridgit.cc/portalpairing/"

    /** The relay origin for [base] (`https://host/d/DEVICE`): the iOS app appends `/d/{device}/jobs` itself. */
    fun relayOrigin(base: String, device: String): String = base.removeSuffix("/d/$device").trimEnd('/')

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** `bridgitportal://pair#v=1&relay=…&device=…&token=…&name=…`; every value percent-encoded (space = %20). */
    fun pairLink(relay: String, device: String, token: String, name: String, install: String? = null, portal: Boolean = true, claim: Boolean = false): String =
        "$SCHEME://pair#v=$LINK_VERSION&relay=${enc(relay)}&device=${enc(device)}&token=${enc(token)}&name=${enc(name)}" +
            (install?.let { "&install=${enc(it)}" } ?: "") + (if (portal) "&portal=1" else "") + (if (claim) "&claim=1" else "")

    /**
     * Single-use links (claim=1): the portal makes its own token on the device and sends its SHA-256 here, through
     * the relay with the link's token; after that the link's token is dead. Not in [ROUTES]: the relay dispatcher
     * answers it itself, and only for a portal pairing still waiting to be claimed.
     */
    const val CLAIM_PATH = "/v1/portal/claim"

    /** The fields of a pairing link (the inverse of [pairLink]); null when it isn't one. */
    fun parsePairLink(link: String): Map<String, String>? {
        if (!link.startsWith("$SCHEME://pair#")) return null
        return link.substringAfter('#').split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    }

    /**
     * The devices added to Bridgit, for the Bridgit Portal's Devices tab ("Added to Bridgit" and the map's linked
     * nodes): cameras first, then smart devices. Names, addresses, which applets use each and the camera checks;
     * never credentials. State is "on" once verified, else "blocked" (the map's "not checked yet").
     */
    fun addedDevices(app: cc.bridgit.app.BridgeApp): JSONArray {
        val apps = cc.bridgit.app.appdevices.AppDeviceAccess.apps
        return addedDevices(runCatching { app.lanCameras.all() }.getOrDefault(emptyList()),
            runCatching { app.lanDevices.all() }.getOrDefault(emptyList())) { id ->
            apps.filter { runCatching { app.appDevices.allows(it.id, id) }.getOrDefault(false) }.map { app.getString(it.title) }
        }
    }

    internal fun addedDevices(cameras: List<cc.bridgit.app.lancamera.LanCamera>, smart: List<cc.bridgit.app.lan.LanDevice>,
                              appletsOf: (String) -> List<String>): JSONArray = JSONArray().also { out ->
        cameras.forEach { c ->
            val report = c.capabilityReport
            out.put(JSONObject().put("id", c.id).put("name", c.name).put("host", c.host).put("kind", "camera")
                .put("state", if (c.verifiedAt != null) "on" else "blocked").put("model", c.model ?: JSONObject.NULL)
                .put("verified_at", c.verifiedAt ?: JSONObject.NULL).put("applets", JSONArray(appletsOf(c.id)))
                .put("checks", JSONObject()
                    .put("audio_in", report?.optJSONObject("audio_in")?.optString("state") ?: "not_checked")
                    .put("audio_out", report?.optJSONObject("audio_out")?.optString("state") ?: "not_checked")
                    .put("tapo_talk", report?.optJSONObject("tapo_talk")?.optString("state") ?: "not_checked")))
        }
        smart.forEach { d ->
            out.put(JSONObject().put("id", d.id).put("name", d.name).put("host", d.host)
                .put("kind", cc.bridgit.app.lancamera.DevicesScene.kindOf(d.adapter)).put("adapter", d.adapter).put("state", "on")
                .put("verified_at", d.verifiedAt).put("applets", JSONArray(appletsOf(d.id))))
        }
    }

    /** Points every brand logo_url in a devices payload at the portal's own logo route (no LAN switch needed). */
    fun portalLogoUrls(v: Any?): Any? = when (v) {
        is JSONObject -> v.apply { keys().asSequence().toList().forEach { k ->
            val x = opt(k)
            if (k == "logo_url" && x is String) put(k, x.replace("/v1/lan/brands/", "/v1/portal/brands/")) else portalLogoUrls(x)
        } }
        is JSONArray -> v.apply { for (i in 0 until length()) portalLogoUrls(opt(i)) }
        else -> v
    }

    // ---- Home scene ----

    data class SceneNode(val id: String, val group: String, val icon: String, val label: String, val name: String,
                         val why: String, val caps: List<String>)

    private val STR = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    private fun field(line: String, key: String): String? =
        Regex("(?:^|[{,]\\s*)$key: \"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)?.let(::unescape)
    private fun list(line: String, key: String): List<String>? =
        Regex("(?:^|[{,]\\s*)$key: \\[([^\\]]*)\\]").find(line)?.groupValues?.get(1)?.let { inner -> STR.findAll(inner).map { unescape(it.groupValues[1]) }.toList() }
    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")

    /**
     * The Home scene's nodes in ring order (clockwise from the top), then the band (Tailscale, Jev, Clef,
     * Weather). "Your agent" is left out: agents have their own list. Wording is the scene's own.
     */
    fun sceneNodes(html: String, includeAgent: Boolean = false): List<SceneNode> {
        val from = html.indexOf("var NODES = [")
        val to = html.indexOf("var ALL = ", from)
        if (from < 0 || to < 0) return emptyList()
        return html.substring(from, to).lines().map { it.trim() }
            .filter { Regex("^(var [A-Z_]+ = )?\\{ id: \"").containsMatchIn(it) }
            .mapNotNull { l ->
                val id = field(l, "id") ?: return@mapNotNull null
                if (id == "agent" && !includeAgent) return@mapNotNull null
                SceneNode(id, field(l, "grp") ?: return@mapNotNull null, field(l, "icon").orEmpty(),
                    list(l, "label")?.joinToString(" ") ?: field(l, "name").orEmpty(),
                    field(l, "name").orEmpty(), field(l, "why").orEmpty(), list(l, "caps").orEmpty())
            }
    }

    /** The scene's CSS custom properties: `dark` from `:root`, `light` from `:root[data-theme="light"]`. */
    fun sceneTokens(html: String): Map<String, Map<String, String>> {
        fun block(start: String): Map<String, String> {
            val i = html.indexOf(start); if (i < 0) return emptyMap()
            val body = html.substring(i + start.length, html.indexOf('}', i))
            return Regex("--([A-Za-z0-9-]+):\\s*([^;]+);").findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }
        }
        return mapOf("dark" to block(":root {"), "light" to block(":root[data-theme=\"light\"] {"))
    }

    /** An Android colour int as `#RRGGBB`, or `#RRGGBBAA` (CSS order) when it isn't opaque. */
    fun hex(color: Int): String {
        val rgb = "#%06X".format(color and 0xFFFFFF)
        val a = (color ushr 24) and 0xFF
        return if (a == 0xFF) rgb else rgb + "%02X".format(a)
    }

    // ---- Logs ----

    /** One portal log entry from a remote-view line ([cc.bridgit.app.remoteview.RemoteViewLines.line]): same redaction. */
    fun logEntry(line: JSONObject, channel: String = "audit", level: String = "info", result: String = "ok"): JSONObject = JSONObject()
        .put("id", line.getLong("seq")).put("ts", line.getLong("ts"))
        .put("agent", line.opt("agent") ?: JSONObject.NULL).put("kind", line.getString("kind"))
        .put("method", line.opt("method") ?: JSONObject.NULL).put("path", line.opt("endpoint") ?: JSONObject.NULL)
        .put("status", line.opt("http") ?: JSONObject.NULL).put("caption", line.opt("caption") ?: JSONObject.NULL)
        // The same entry in the Home scene's own shape (DiagnosticsScene.entryJson), still redacted: no params, data or messages.
        .put("channel", channel).put("level", level).put("result", result)
        .put("endpoint", line.optString("endpoint").takeIf { line.opt("endpoint") is String } ?: "")
        .put("http", (line.opt("http") as? Int) ?: 0)
        .put("message", line.optString("caption").takeIf { line.opt("caption") is String } ?: "")
        .put("params", JSONObject()).put("data", JSONObject())

    /** Motion the APK uses, from the views and the Home scene (duration in ms, easing as a CSS timing name). */
    val MOTION: List<Triple<String, Int, String>> = listOf(
        Triple("card_settle", 180, "decelerate"),            // CardStackView.SHORT: a card springs back
        Triple("card_swipe", 260, "decelerate"),             // CardStackView.MEDIUM: a card flies off, the next one rises
        Triple("beacon_pulse", 1600, "ease-in-out"),         // BeaconDotView: live dot, repeats
        Triple("pair_pulse", 2400, "ease-in-out"),           // AddAgentFragment: waiting-for-agent rings, repeats
        Triple("update_fade", 700, "decelerate"),            // UpdateDialog: fades and shrinks to 0.96
        Triple("listen_halo", 2400, "ease-in-out"),          // scene @keyframes listen: opacity .5 → .12 → .5
        Triple("connection_pulse", 2400, "ease-in-out"),     // scene @keyframes connectionPulse: .2 → .9 → .2
        Triple("trace_flow", 2400, "linear"),                // scene @keyframes flow/flowT: dashes (2 6) move 16 px
        Triple("core_breathe", 4000, "ease-in-out"),         // scene @keyframes breathe: stroke opacity 1 → .55 → 1
        Triple("header_pulse", 1400, "ease-in-out"),         // scene @keyframes hdrPulse: opacity 1 → .35 → 1 while a run plays
        Triple("fade_quick", 150, "ease"),                   // scene: opacity transitions
    )

    fun animations(): JSONArray = JSONArray()
        .put(JSONObject().put("id", "home_header_pulse").put("where", "Home scene header line while the diagnostics run plays")
            .put("describe", "The header text turns the core colour and its opacity pulses 1 → 0.35 → 1, repeating. Off with reduced motion.")
            .put("motion", "header_pulse").put("color_token", "core"))
        .put(JSONObject().put("id", "home_core_breathe").put("where", "Home scene: the Bridgit core hexagon while live")
            .put("describe", "The core's outline stroke opacity breathes 1 → 0.55 → 1 every 4 s. A slow, faint glow; off with reduced motion.")
            .put("motion", "core_breathe").put("color_token", "core"))
        .put(JSONObject().put("id", "home_trace_flow").put("where", "Home scene: every connected line between a feature node and the core, and the Tailscale tunnel")
            .put("describe", "Lines are dashed 2 on / 6 off at 0.55 opacity; the dashes flow toward Bridgit 16 px every 2.4 s, linear, forever (the tunnel flows the other way).")
            .put("motion", "trace_flow"))
        .put(JSONObject().put("id", "listening_halo").put("where", "Home scene: Voice recognition and Sound alerts while listening")
            .put("describe", "A 1 px ring in the node's group colour at 0.5 opacity fades to 0.12 and back every 2.4 s.")
            .put("motion", "listen_halo"))
        .put(JSONObject().put("id", "connection_pulse").put("where", "Home scene: an agent box that has made a call")
            .put("describe", "A 1.5 px ring in the ok colour pulses opacity 0.2 → 0.9 → 0.2 every 2.4 s; the box fills dark green with a 2 px ok stroke.")
            .put("motion", "connection_pulse").put("color_token", "ok"))
        .put(JSONObject().put("id", "status_beacon").put("where", "Live status dots (listener state)")
            .put("describe", "A dot with a ring that grows outward while its opacity falls from 0.7 to 0 over 1.6 s, accelerate-decelerate, repeating.")
            .put("motion", "beacon_pulse"))
        .put(JSONObject().put("id", "card_stack").put("where", "Interactions: the stack of cards from agents")
            .put("describe", "Swiping flies the top card off by 1.3 widths, rotating up to 12°, in 260 ms decelerate; the next card scales from its stacked size to 1 and rises. A card let go springs back in 180 ms.")
            .put("motion", "card_swipe"))
        .put(JSONObject().put("id", "pairing_pulse").put("where", "Add agent: waiting for the agent's first call")
            .put("describe", "A ring scales 1 → 1.35 while its opacity falls 0.3 → 0 over 2.4 s, accelerate-decelerate, repeating.")
            .put("motion", "pair_pulse"))
        .put(JSONObject().put("id", "diagnostics_run").put("where", "Home: the full Device Diagnostics run (Rerun)")
            .put("describe", "Nodes are tested one by one in phases (Device basics, Media and access, Recognition and alerts, Controls and interaction); each prints check lines in the test bay, then lights in its group colour or goes grey (off) / amber (blocked). Tap skips.")
            .put("phases", JSONArray(listOf("Device basics", "Media and access", "Recognition and alerts", "Controls and interaction"))))
}
