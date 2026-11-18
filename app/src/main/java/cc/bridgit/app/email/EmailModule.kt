package cc.bridgit.app.email

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent email (A0054). Phase 1: POST /v1/email/preview renders what an email would look like (the styled Bridgit card by
 * default, with its plain-text alternative) without sending anything. Sending (POST /v1/email/send) comes in a later
 * phase, behind its own grant. The preview reads nothing on the phone and sends nothing off it, so it is always on and
 * has no owner setting.
 */
class EmailModule(app: BridgeApp) : Capability(app) {
    override val id = "email"
    override val titleRes = R.string.cap_email
    override val descriptionRes = R.string.cap_email_desc
    override val group = "status"
    override val endpoints = ALL

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(PREVIEW) {
            val out = EmailPreview.render(body)
            params.put("style", out.optString("style")).put("html_bytes", out.optInt("html_bytes"))
            Reply.Json(out)
        }
    }

    companion object {
        val PREVIEW = EndpointSpec("POST", "/email/preview", "email",
            "Render an email without sending it: the subject, the styled HTML (Bridgit's card design by default) and the plain-text alternative. Nothing leaves the phone",
            body = listOf(
                Param("subject", "string", "1-200 characters, one line", required = true),
                Param("text", "string", "Plain text body (up to 100 KB)"),
                Param("markdown", "string", "Markdown body: headings, bold, italic, links, lists, code (up to 100 KB)"),
                Param("html", "string", "Your own HTML body (up to 100 KB); scripts, iframes, forms, event handlers, remote styles and javascript: links are removed"),
                Param("style", "string", "card (default): framed in the Bridgit card; plain: your body without the frame", enum = listOf("card", "plain")),
                Param("to", "array", "Optional recipients to check (1-20 addresses); nothing is sent to them"),
            ),
            example = "POST /v1/email/preview {\"subject\": \"Front door\", \"markdown\": \"**Someone** is at the door.\"}",
            returns = "subject, style, html, text, html_bytes, to[] (when given, cleaned and de-duplicated), sent: false",
            errors = listOf("invalid_parameter"),
            privateFields = setOf("text", "markdown", "html", "to"))
        val ALL = listOf(PREVIEW)
    }
}

/** The preview's request handling, pure so it is unit tested without Android. */
object EmailPreview {
    private val FIELDS = setOf("subject", "text", "markdown", "html", "style", "to")

    fun render(body: JSONObject): JSONObject {
        val unknown = body.keys().asSequence().filter { it !in FIELDS }.toList()
        if (unknown.isNotEmpty()) throw bad("Unknown field: ${unknown.joinToString(", ")}. Allowed: ${FIELDS.sorted().joinToString(", ")}.", unknown.first())
        fun str(name: String): String? {
            if (!body.has(name) || body.isNull(name)) return null
            return body.opt(name) as? String ?: throw bad("$name must be text.", name)
        }
        val styleId = str("style")
        val style = if (styleId == null) EmailComposer.Style.CARD else EmailComposer.Style.of(styleId) ?: throw bad("style must be card or plain.", "style")
        val to: List<String>? = if (!body.has("to") || body.isNull("to")) null else {
            val raw = body.opt("to")
            val list = when (raw) {
                is String -> listOf(raw)
                is JSONArray -> (0 until raw.length()).map { raw.opt(it) as? String ?: throw bad("to must be a list of email addresses.", "to") }
                else -> throw bad("to must be a list of email addresses.", "to")
            }
            when (val r = EmailComposer.validateRecipients(list)) {
                is EmailComposer.Recipients.Ok -> r.addresses
                is EmailComposer.Recipients.Bad -> throw bad(r.error.message, r.error.field)
            }
        }
        return when (val r = EmailComposer.compose(str("subject"), str("text"), str("markdown"), str("html"), style)) {
            is EmailComposer.Result.Bad -> throw bad(r.error.message, r.error.field)
            is EmailComposer.Result.Ok -> JSONObject().put("subject", r.mail.subject).put("style", r.mail.style.id)
                .put("html", r.mail.html).put("text", r.mail.text).put("html_bytes", r.mail.html.toByteArray(Charsets.UTF_8).size)
                .apply { if (to != null) put("to", JSONArray(to)) }
                .put("sent", false)
                .put("note", "A preview only: nothing was sent. Sending email is not available yet.")
        }
    }

    private fun bad(message: String, field: String) = ErrorCatalog.error("invalid_parameter", message, mapOf("field" to field))
}
