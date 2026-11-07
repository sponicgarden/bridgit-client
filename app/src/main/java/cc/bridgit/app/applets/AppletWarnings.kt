package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validate catches the known traps (docs/applet-dev-experience.md B1). Warnings never block an install; each says
 * what will go wrong and the exact fix. Card checks are fixed-pattern scans of the HTML asset's text (nothing is
 * run), so a false alarm is possible and the author may ignore it.
 *
 * Pure: no Android. [cardCaps] maps a card's "METHOD /v1/path" (or bare /v1/path) to its capability, when known.
 */
object AppletWarnings {
    data class Warning(val code: String, val where: String, val says: String, val fix: String) {
        fun json(): JSONObject = JSONObject().put("code", code).put("where", where).put("says", says).put("fix", fix)
    }

    private val V1_PATH = Regex("""/v1/[A-Za-z0-9_/{}.-]+""")

    /** The /v1/ endpoint paths written in a card's script text. */
    fun cardPaths(html: String): Set<String> = V1_PATH.findAll(html).map { it.value.trimEnd('/', '.') }.toSet()

    fun check(p: AppletPackage.Package, plan: AppletPackage.Plan, derived: Set<String>, cardCaps: (String) -> String? = { null }): List<Warning> {
        val out = mutableListOf<Warning>()
        val d = p.definition
        val assets = d.optJSONObject("assets") ?: JSONObject()
        val cardAsset = d.optJSONObject("ui")?.optJSONObject("card")?.optString("asset")?.takeIf { it.isNotEmpty() }
        val html = cardAsset?.let { assets.optJSONObject(it)?.optString("text") }
        if (html != null) {
            val where = "assets.$cardAsset"
            if ("data_base64" in html) out += Warning("card_request_too_big", where,
                "The card sends data_base64 to the phone. A card's request is at most 16 KB, so any real photo is refused.",
                "Take the photo with store: [\"memory\"] and send its capture_id (or a gallery_id) instead of the bytes.")
            if ("camera/snapshot" in html && "max_edge_px" !in html) out += Warning("card_reply_too_big", where,
                "The card takes a snapshot without max_edge_px. A reply to a card is at most 64 KB, so the photo can't come back to it.",
                "Add max_edge_px (1024 or less) and store: [\"memory\"], and use the capture_id.")
            if (Regex("""/v1/captures/""").containsMatchIn(html) && !Regex("""\braw\b""").containsMatchIn(html)) out += Warning("card_reply_too_big", where,
                "The card reads a capture. A whole capture is usually over the card's 64 KB reply limit.",
                "Read it with raw and a small size, or work with the capture_id on the phone (find, crop, appraise).")
            if (Regex("""<form\b""", RegexOption.IGNORE_CASE).containsMatchIn(html) &&
                Regex("""type\s*=\s*["']?submit|onsubmit""", RegexOption.IGNORE_CASE).containsMatchIn(html)) out += Warning("card_form_submit", where,
                "The card relies on a form submit. The card sandbox blocks form submission, so that button does nothing.",
                "Use a plain <button type=\"button\"> with a click handler.")
            if (Regex("""localStorage|sessionStorage|indexedDB""").containsMatchIn(html)) out += Warning("card_storage", where,
                "The card keeps data in browser storage. A card's storage is wiped whenever the card is drawn again.",
                "Keep it in the applet's own records (POST /v1/applet/records/{key}).")
        }
        // Permissions: declared but used by nothing the phone can see.
        val cardUses = html?.let { cardPaths(it).mapNotNull(cardCaps).toSet() } ?: emptySet()
        val unused = p.declared - derived - cardUses - setOf("interactions", "workflows")
        if (unused.isNotEmpty() && plan.native == null) out += Warning("permission_unused", "permissions.required",
            "${unused.sorted().joinToString()} ${if (unused.size == 1) "is" else "are"} declared but nothing in the applet seems to use ${if (unused.size == 1) "it" else "them"}. The owner is asked to approve every declared permission.",
            "Remove ${if (unused.size == 1) "it" else "them"} from permissions.required, unless the card calls ${if (unused.size == 1) "it" else "them"} in a way the check can't see.")
        val paid = AppletPackage.paid(plan, p.declared)
        val policy = d.optJSONObject("data_policy")
        if (paid && policy != null && policy.optString("backend_default") == "device_only" && policy.optJSONObject("device_only")?.opt("remote_models") == false)
            out += Warning("data_policy_contradicts", "data_policy.device_only.remote_models",
                "data_policy says remote_models: false, but the applet asks Jev, Clef or a cloud finder.",
                "Set remote_models to true (POST /v1/applets/validate fills the derived values once phase 2 is in).")
        if (paid && !d.has("budget")) out += Warning("paid_without_cap", "budget",
            "The applet makes paid cloud model calls and sets no budget, so the default applies: at most $1 a day and $10 a month.",
            "Add \"budget\": {\"day_usd\": …, \"month_usd\": …} sized for what it needs.")
        return out
    }

    fun json(list: List<Warning>): JSONArray = JSONArray(list.map { it.json() })
}
