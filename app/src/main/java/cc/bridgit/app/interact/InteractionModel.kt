package cc.bridgit.app.interact

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Interactions (8.13, D26): what agents send the owner. Pure Kotlin; validation is unit-tested. */
enum class IKind(val id: String) {
    NOTICE("notice"), QUESTION("question"), HTML("html"), TEXT("text"),
    /** Phase 2 (docs/applet-cards.md): a card the phone draws from pieces; the card JSON is kept in the html field. */
    PIECES("pieces");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

enum class IStatus(val id: String) {
    PENDING("pending"), SEEN("seen"), ANSWERED("answered"), DISMISSED("dismissed"), EXPIRED("expired"), CANCELLED("cancelled");
    val open get() = this == PENDING || this == SEEN
    /** A question in this state still waits for the owner: open, or expired with no answer yet (late answers are allowed). */
    val awaitsOwner get() = open || this == EXPIRED
    fun acceptsAnswer(resubmission: Boolean = false) = awaitsOwner || (resubmission && this == ANSWERED)
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

sealed class Control(val id: String, val label: String) {
    class Button(id: String, label: String, val style: String) : Control(id, label)
    class Text(id: String, label: String, val placeholder: String?) : Control(id, label)
    class Choice(id: String, label: String, val options: List<String>) : Control(id, label)
    /** [target] is a URL (https/geo/tel/mailto) or null when [pkg] names an app. [shownAs] is drawn by Bridgit under the button. */
    class Link(id: String, label: String, val target: String?, val pkg: String?, val shownAs: String) : Control(id, label)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).apply {
        when (val c = this@Control) {
            is Button -> put("type", "button").put("style", c.style)
            is Text -> put("type", "text").put("placeholder", c.placeholder ?: JSONObject.NULL)
            is Choice -> put("type", "choice").put("options", JSONArray(c.options))
            is Link -> put("type", "link").put("url", c.target ?: JSONObject.NULL).put("package", c.pkg ?: JSONObject.NULL).put("shown_as", c.shownAs)
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Control = when (o.getString("type")) {
            "button" -> Button(o.getString("id"), o.getString("label"), o.optString("style", "normal"))
            "text" -> Text(o.getString("id"), o.getString("label"), o.optString("placeholder").ifEmpty { null }.takeIf { !o.isNull("placeholder") })
            "choice" -> Choice(o.getString("id"), o.getString("label"), o.getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) } })
            else -> Link(o.getString("id"), o.getString("label"), o.optString("url").takeIf { !o.isNull("url") && it.isNotEmpty() },
                o.optString("package").takeIf { !o.isNull("package") && it.isNotEmpty() }, o.optString("shown_as"))
        }
    }
}

/** A validated request, before it is stored. Image bytes are decoded by the API module. */
data class Draft(
    val kind: IKind, val title: String, val text: String?, val controls: List<Control>, val priority: String,
    val speak: Boolean, val expiresInS: Long, val imageGalleryId: Long?, val imageBase64: String?, val html: String?,
    val stack: String? = null, val stackIndex: Int? = null, val swipe: SwipeAnswer? = null,
    val noTakeover: Boolean = false,
    /** The agent will post another card once the owner answers or closes this one: the screen waits for it. */
    val followUp: Boolean = false,
    /** A0074: standard (inside Bridgit's box) or full_screen (edge to edge, bars hidden). */
    val presentation: Presentation = Presentation.STANDARD,
    /** A0076: the page's width/height ratio the agent declared (`content_aspect`), and the text it sent. */
    val contentAspect: Double? = null, val contentAspectLabel: String? = null,
)

/** #0005: a stacked card that a sideways swipe answers while it waits. [left]/[right] are the labels Bridgit shows. */
data class SwipeAnswer(val left: String, val right: String) {
    fun label(dir: String) = if (dir == "left") left else right
    fun toJson(): JSONObject = JSONObject().put("left", left).put("right", right)
    companion object {
        fun fromJson(s: String?): SwipeAnswer? = s?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { o -> SwipeAnswer(o.optString("left"), o.optString("right")).takeIf { it.left.isNotEmpty() && it.right.isNotEmpty() } }
        /** answer.data for a swipe in [dir] ("left" or "right"). */
        fun data(dir: String, s: SwipeAnswer): JSONObject = JSONObject().put("swipe", dir).put("label", s.label(dir))
    }
}

data class Interaction(
    val id: Long, val agentId: String, val agent: String, val kind: IKind, val title: String, val text: String?,
    val image: String?, val controls: List<Control>, val html: String?, val priority: String, val speak: Boolean,
    val status: IStatus, val answer: JSONObject?, val createdAt: Long, val expiresAt: Long,
    val seenAt: Long?, val answeredAt: Long?, val closedAt: Long?, val streamText: String = "", val streamDone: Boolean = false,
    /** #0005: the agent's stack id (cards with the same id from the same agent swipe together), position, and card count. */
    val stack: String? = null, val stackIndex: Int = 0, val stackSize: Int = 1, val swipe: SwipeAnswer? = null,
    /** #0005, phone UI only: how many cards of the stack are answered. */
    val stackAnswered: Int = 0,
    val noTakeover: Boolean = false,
    val followUp: Boolean = false,
    val presentation: Presentation = Presentation.STANDARD,
) {
    fun toJson(iso: (Long) -> String): JSONObject = JSONObject()
        .put("interaction_id", id).put("kind", kind.id).put("status", status.id).put("title", title)
        .put("priority", priority).put("no_takeover", noTakeover).put("follow_up", followUp).put("presentation", presentation.id).put("created_at", iso(createdAt)).put("expires_at", iso(expiresAt))
        .put("seen_at", seenAt?.let(iso) ?: JSONObject.NULL)
        .put("answer", answer ?: JSONObject.NULL).put("answered_at", answeredAt?.let(iso) ?: JSONObject.NULL)
        .put("closed_at", closedAt?.let(iso) ?: JSONObject.NULL)
        .apply { if (kind == IKind.TEXT) put("length", streamText.length).put("done", streamDone) }
        .apply { if (stack != null) put("stack", stack).put("stack_index", stackIndex).put("stack_size", stackSize).put("swipe_answer", swipe?.toJson() ?: JSONObject.NULL) }
}

class InteractionError(val code: String, message: String) : Exception(message)

object InteractionRules {
    const val TITLE_MAX = 80
    const val TEXT_MAX = 2000
    const val IMAGE_MAX_BYTES = 2 * 1024 * 1024
    const val HTML_MAX_BYTES = 256 * 1024
    const val MAX_CONTROLS = 6
    const val LABEL_MAX = 40
    const val MIN_EXPIRES_S = 60L
    const val MAX_EXPIRES_S = 7L * 24 * 3600
    const val DEFAULT_EXPIRES_S = 24L * 3600
    const val OPEN_QUESTIONS_PER_AGENT = 5
    const val NOTICES_PER_HOUR = 30
    const val ANSWER_TEXT_MAX = 2000
    /** #0005: cards per stack id (all statuses count), and the longest swipe label. */
    const val STACK_MAX = 10
    const val SWIPE_LABEL_MAX = 20
    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val PKG_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    private fun bad(msg: String): Nothing = throw InteractionError("invalid_parameter", msg)

    /** Parses and checks a POST /v1/interactions body (CAP-31). */
    fun parse(b: JSONObject, piecesScope: CardPieces.Scope? = null): Draft {
        val kind = IKind.of(b.optString("kind")) ?: bad("kind must be notice, question, html, pieces or text.")
        if (kind == IKind.PIECES) return pieces(b, piecesScope ?: agentScope())
        // CAP-34: the raw HTML size is checked before anything else is looked at.
        val html = if (kind == IKind.HTML) CardRules.checkHtml(b.opt("html")) else null
        if (kind != IKind.HTML && b.has("html") && !b.isNull("html")) bad("html is only for kind html.")
        val title = b.optString("title", "").trim()
        if (title.isEmpty()) bad("title is required.")
        if (title.length > TITLE_MAX) bad("title is limited to $TITLE_MAX characters.")
        val text = b.optString("text", "").takeIf { b.has("text") && !b.isNull("text") }?.trim()
        if (text != null && text.length > TEXT_MAX) bad("text is limited to $TEXT_MAX characters.")
        val arr = b.optJSONArray("controls") ?: JSONArray()
        if (arr.length() > MAX_CONTROLS) bad("At most $MAX_CONTROLS controls.")
        val ids = HashSet<String>()
        val controls = (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: bad("controls[$i] must be an object.")
            val id = o.optString("id", "")
            if (!ID_RE.matches(id)) bad("controls[$i].id must be 1-32 letters, digits, - or _.")
            if (!ids.add(id)) bad("controls ids must be unique (\"$id\" twice).")
            val label = o.optString("label", "").trim()
            if (label.isEmpty() || label.length > LABEL_MAX) bad("controls[$i].label must be 1-$LABEL_MAX characters.")
            when (o.optString("type")) {
                "button" -> Control.Button(id, label, o.optString("style", "normal").let { if (it == "primary") "primary" else "normal" })
                "text" -> Control.Text(id, label, o.optString("placeholder", "").take(LABEL_MAX).ifEmpty { null })
                "choice" -> {
                    val opts = o.optJSONArray("options") ?: bad("controls[$i].options is required for a choice.")
                    if (opts.length() !in 2..8) bad("controls[$i].options must have 2 to 8 entries.")
                    Control.Choice(id, label, (0 until opts.length()).map { opts.optString(it, "").trim().take(LABEL_MAX).ifEmpty { bad("controls[$i].options can't be empty.") } })
                }
                "link" -> link(o, i, id, label)
                else -> bad("controls[$i].type must be button, text, choice or link.")
            }
        }
        when (kind) {
            IKind.NOTICE -> if (controls.any { it !is Control.Link }) bad("A notice can only have link controls.")
            IKind.QUESTION -> {
                if (controls.isEmpty()) bad("A question needs 1 to $MAX_CONTROLS controls.")
                if (controls.none { it is Control.Button || it is Control.Text || it is Control.Choice })
                    bad("A question needs at least one button, text or choice control.")
            }
            IKind.HTML -> if (controls.isNotEmpty()) bad("An html card draws its own controls; leave controls out and call Bridgit.respond(json).")
            IKind.TEXT -> if (controls.isNotEmpty()) bad("A text panel has no controls; stream text with POST /v1/text/stream.")
            IKind.PIECES -> Unit
        }
        val priority = b.optString("priority", "normal")
        if (priority != "normal" && priority != "urgent") bad("priority must be normal or urgent.")
        val exp = if (b.has("expires_in_seconds")) (b.opt("expires_in_seconds") as? Number)?.toLong()
            ?: bad("expires_in_seconds must be a number.") else DEFAULT_EXPIRES_S
        if (exp !in MIN_EXPIRES_S..MAX_EXPIRES_S) bad("expires_in_seconds must be 60 to 604800 (7 days).")
        val img = b.optJSONObject("image")
        val galleryId = img?.takeIf { it.has("gallery_id") }?.let { it.optString("gallery_id").toLongOrNull() ?: bad("image.gallery_id must be a gallery id.") }
        val b64 = img?.takeIf { it.has("data_base64") }?.optString("data_base64")
        if (b64 != null && b64.length > IMAGE_MAX_BYTES * 4 / 3 + 4) bad("image is limited to 2 MB.")
        if (img != null && galleryId == null && b64 == null) bad("image needs gallery_id or data_base64.")
        if (img != null && (kind == IKind.HTML || kind == IKind.TEXT)) bad("image is for notices and questions; put pictures in the card as data:image/* instead.")
        val speak = b.optBoolean("speak", false)
        val (stack, stackIndex, swipe) = stackFields(b, kind)
        val noTakeover = if (b.has("no_takeover")) (b.opt("no_takeover") as? Boolean)
            ?: bad("no_takeover must be true or false.") else false
        val followUp = if (b.has("follow_up")) (b.opt("follow_up") as? Boolean)
            ?: bad("follow_up must be true or false.") else false
        if (followUp && kind == IKind.TEXT) bad("follow_up is for questions, notices and html cards.")
        val presentation = presentation(b, kind)
        val (aspect, aspectLabel) = contentAspect(b, kind)
        return Draft(kind, title, text, controls, priority, speak, exp, galleryId, b64, html, stack, stackIndex, swipe, noTakeover, followUp,
            presentation, aspect, aspectLabel)
    }

    /**
     * A0074: optional `presentation`, "standard" (default) or "full_screen". Full screen is for html cards: a picture
     * goes in the page as a data: image URL (fit it with object-fit: contain).
     */
    fun presentation(b: JSONObject, kind: IKind): Presentation {
        if (!b.has("presentation") || b.isNull("presentation")) return Presentation.STANDARD
        val p = Presentation.of(b.opt("presentation") as? String) ?: bad("presentation must be ${Presentation.IDS.joinToString(" or ")}.")
        if (p == Presentation.FULL_SCREEN && kind != IKind.HTML)
            bad("presentation full_screen is for kind html; put a picture in the page as data:image/* with object-fit: contain.")
        return p
    }

    /** A0076: optional `content_aspect` ("16:9", "1280x720"): the page's fixed shape, checked against the canvas. html only. */
    private fun contentAspect(b: JSONObject, kind: IKind): Pair<Double?, String?> {
        if (!b.has("content_aspect") || b.isNull("content_aspect")) return null to null
        if (kind != IKind.HTML) bad("content_aspect is only for kind html.")
        val text = b.opt("content_aspect") as? String ?: bad("content_aspect must be text like \"16:9\" or \"1280x720\".")
        val r = CardViewport.parseAspect(text) ?: bad("content_aspect must be width:height like \"16:9\" or \"1280x720\".")
        return r to text.trim()
    }

    /** Who may be named by an agent's own pieces card when the caller gives no scope: watch ids only by shape. */
    fun agentScope() = CardPieces.Scope(watch = { it is String && ID_WATCH.matches(it) })
    private val ID_WATCH = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val PIECES_BODY = setOf("kind", "title", "pieces", "sources", "fresh_s", "states", "expires_in_seconds", "no_takeover", "priority")

    /** A `kind: pieces` card: checked by [CardPieces]; the card JSON is stored in the draft's html field. */
    private fun pieces(b: JSONObject, scope: CardPieces.Scope): Draft {
        b.keys().asSequence().firstOrNull { it !in PIECES_BODY }?.let { bad("$it isn't a field of a pieces card (${PIECES_BODY.filter { k -> k != "kind" }.joinToString()}).") }
        val title = b.optString("title", "").trim()
        if (title.isEmpty()) bad("title is required.")
        if (title.length > TITLE_MAX) bad("title is limited to $TITLE_MAX characters.")
        val card = JSONObject().put("title", title)
        for (k in listOf("pieces", "sources", "fresh_s", "states")) b.opt(k)?.let { card.put(k, it) }
        try { CardPieces.check(card, scope) } catch (e: CardPieces.Bad) { bad(e.message ?: "Invalid pieces card.") }
        val priority = b.optString("priority", "normal")
        if (priority != "normal" && priority != "urgent") bad("priority must be normal or urgent.")
        val exp = if (b.has("expires_in_seconds")) (b.opt("expires_in_seconds") as? Number)?.toLong() ?: bad("expires_in_seconds must be a number.") else DEFAULT_EXPIRES_S
        if (exp !in MIN_EXPIRES_S..MAX_EXPIRES_S) bad("expires_in_seconds must be 60 to 604800 (7 days).")
        val noTakeover = if (b.has("no_takeover")) (b.opt("no_takeover") as? Boolean) ?: bad("no_takeover must be true or false.") else false
        return Draft(IKind.PIECES, title, null, emptyList(), priority, false, exp, null, null, card.toString(), noTakeover = noTakeover)
    }

    /** #0005: optional `stack`, `stack_index` and `swipe_answer`, for html cards only. */
    private fun stackFields(b: JSONObject, kind: IKind): Triple<String?, Int?, SwipeAnswer?> {
        fun given(k: String) = b.has(k) && !b.isNull(k)
        if (kind != IKind.HTML) {
            listOf("stack", "stack_index", "swipe_answer").firstOrNull(::given)?.let { bad("$it is only for kind html.") }
            return Triple(null, null, null)
        }
        val stack = if (given("stack")) (b.opt("stack") as? String)?.trim()?.takeIf { ID_RE.matches(it) }
            ?: bad("stack must be 1-32 letters, digits, - or _.") else null
        val index = if (given("stack_index")) {
            if (stack == null) bad("stack_index needs stack.")
            val n = (b.opt("stack_index") as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toInt()
            if (n == null || n !in 0 until STACK_MAX) bad("stack_index must be a whole number 0 to ${STACK_MAX - 1}.")
            n
        } else null
        val swipe = if (given("swipe_answer")) {
            if (stack == null) bad("swipe_answer needs stack (a single card is never swiped).")
            val o = b.optJSONObject("swipe_answer") ?: bad("swipe_answer must be {\"left\": label, \"right\": label}.")
            fun lbl(k: String) = (o.opt(k) as? String)?.trim()?.takeIf { it.length in 1..SWIPE_LABEL_MAX }
                ?: bad("swipe_answer.$k must be a label of 1-$SWIPE_LABEL_MAX characters.")
            SwipeAnswer(lbl("left"), lbl("right"))
        } else null
        return Triple(stack, index, swipe)
    }

    /** CAP-31 link allowlist: https, geo, tel (dial only), mailto, or an app package. Everything else is refused. */
    fun link(o: JSONObject, i: Int, id: String, label: String): Control.Link {
        val pkg = o.optString("package", "").trim()
        if (pkg.isNotEmpty()) {
            if (!PKG_RE.matches(pkg) || pkg.length > 120) bad("controls[$i].package must be an Android package name.")
            return Control.Link(id, label, null, pkg, pkg)
        }
        val url = o.optString("url", "").trim()
        if (url.isEmpty() || url.length > 2000) bad("controls[$i] needs url or package.")
        val shown = linkShownAs(url) ?: bad("controls[$i].url must be https:, geo:, tel: or mailto:.")
        return Control.Link(id, label, url, null, shown)
    }

    /** What Bridgit draws under a link button, or null if the URL isn't allowed. */
    fun linkShownAs(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        return when (u.scheme?.lowercase()) {
            "https" -> u.host?.lowercase()?.takeIf { it.isNotEmpty() && u.userInfo == null }
            "geo" -> "Map: " + url.removePrefix("geo:").take(40)
            "tel" -> "Phone: " + url.removePrefix("tel:").take(30)
            "mailto" -> "Email: " + url.removePrefix("mailto:").substringBefore('?').take(40)
            else -> null
        }
    }

    /**
     * Checks the owner's answer against the question's controls: [button] must be one of its buttons
     * (or null when a text/choice is enough); values only for its text/choice controls.
     */
    fun checkAnswer(controls: List<Control>, button: String?, values: Map<String, String>): JSONObject {
        if (button != null && controls.none { it is Control.Button && it.id == button }) throw InteractionError("invalid_parameter", "No button \"$button\".")
        val out = JSONObject()
        for ((k, v) in values) {
            when (val c = controls.firstOrNull { it.id == k }) {
                is Control.Text -> out.put(k, v.take(ANSWER_TEXT_MAX))
                is Control.Choice -> if (v in c.options) out.put(k, v) else throw InteractionError("invalid_parameter", "\"$v\" is not an option of $k.")
                else -> throw InteractionError("invalid_parameter", "No text or choice control \"$k\".")
            }
        }
        if (button == null && out.length() == 0) throw InteractionError("invalid_parameter", "An answer needs a button or a value.")
        return JSONObject().put("button", button ?: JSONObject.NULL).put("values", out)
    }
}

/** D26 live text panel: limits and trimming. Pure, unit-tested. */
object TextPanelRules {
    /** Most text one POST /v1/text/stream may add, in UTF-8 bytes. */
    const val APPEND_MAX_BYTES = 16 * 1024
    /** The panel keeps the last this many characters. */
    const val KEEP_CHARS = 200 * 1024
    /** POST /v1/text/stream calls per agent per second. */
    const val CALLS_PER_SECOND = 20
    /** How far [keepTail] looks for a line break before it cuts mid-line instead. */
    const val LINE_SEARCH = 4096

    /**
     * The last [max] characters of [text], dropped from the front. The cut moves forward to just after the next
     * line break when there is one within [LINE_SEARCH] characters, so the panel rarely starts mid-line; it never splits a
     * surrogate pair.
     */
    fun keepTail(text: String, max: Int = KEEP_CHARS): String {
        if (text.length <= max) return text
        var cut = text.length - max
        if (text[cut - 1] == '\n') return text.substring(cut) // already at the start of a line
        val nl = text.indexOf('\n', cut)
        if (nl >= 0 && nl < text.length - 1 && nl - cut <= LINE_SEARCH) cut = nl + 1
        else if (Character.isLowSurrogate(text[cut])) cut++
        return text.substring(cut)
    }

    /** Checks one append; returns an error message or null. */
    fun checkAppend(append: String): String? =
        if (append.toByteArray(Charsets.UTF_8).size > APPEND_MAX_BYTES) "append is limited to 16 KB per call." else null
}

