package cc.bridgit.app.email

/**
 * Builds an email from what an agent gives (A0054 phase 1): validates it, renders text or markdown into a styled HTML
 * card (inline CSS, table layout, light and dark safe), sanitises agent-supplied HTML, and always makes a plain-text
 * alternative. Pure Kotlin, no network and no Android types: nothing here sends anything.
 */
object EmailComposer {
    const val MAX_RECIPIENTS = 20
    const val MAX_SUBJECT = 200
    const val MAX_BODY_BYTES = 100 * 1024
    const val TAGLINE = "Distributed sensorial applications \u2014 built by your favorite agent."

    enum class Style(val id: String) {
        CARD("card"), PLAIN("plain");
        companion object { fun of(s: String?): Style? = entries.firstOrNull { it.id == s } }
    }

    data class Composed(val subject: String, val html: String, val text: String, val style: Style)
    data class ComposeError(val field: String, val message: String)

    /** Exactly one of the two: the composed mail or what is wrong with the request. */
    sealed class Result {
        data class Ok(val mail: Composed) : Result()
        data class Bad(val error: ComposeError) : Result()
    }

    private val ADDRESS = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")

    /** The cleaned, de-duplicated list, or the first problem. Addresses keep their case; duplicates compare lower-case. */
    fun validateRecipients(list: List<String>, field: String = "to"): Recipients {
        val cleaned = list.map { it.trim() }
        if (cleaned.isEmpty()) return Recipients.Bad(ComposeError(field, "Give at least one recipient."))
        cleaned.firstOrNull { !validAddress(it) }?.let { return Recipients.Bad(ComposeError(field, "\"${it.take(60)}\" isn't a valid email address.")) }
        val unique = cleaned.distinctBy { it.lowercase() }
        if (unique.size > MAX_RECIPIENTS) return Recipients.Bad(ComposeError(field, "At most $MAX_RECIPIENTS recipients per message."))
        return Recipients.Ok(unique)
    }

    sealed class Recipients {
        data class Ok(val addresses: List<String>) : Recipients()
        data class Bad(val error: ComposeError) : Recipients()
    }

    fun validAddress(a: String): Boolean = a.length <= 254 && ADDRESS.matches(a) && !a.contains("..") && !a.startsWith(".") && !a.substringBefore('@').endsWith(".")

    /**
     * [html] wins over [markdown], which wins over [text]; at least one is needed. [style] card (default) frames the body in the
     * Bridgit card; plain keeps the agent's own body without the frame.
     */
    fun compose(subject: String?, text: String? = null, markdown: String? = null, html: String? = null,
                style: Style = Style.CARD, tagline: String = TAGLINE): Result {
        val subj = subject?.trim().orEmpty()
        if (subj.isEmpty()) return bad("subject", "Give a subject.")
        if (subj.length > MAX_SUBJECT) return bad("subject", "The subject is over $MAX_SUBJECT characters.")
        if (subj.any { it == '\r' || it == '\n' || it == '\u0000' }) return bad("subject", "The subject must be one line.")
        val given = listOf("html" to html, "markdown" to markdown, "text" to text).firstOrNull { !it.second.isNullOrBlank() }
            ?: return bad("body", "Give a body: text, markdown or html.")
        for ((f, v) in listOf("html" to html, "markdown" to markdown, "text" to text)) {
            if (v != null && v.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) return bad(f, "The $f is over ${MAX_BODY_BYTES / 1024} KB.")
        }
        val (kind, raw) = given
        val bodyHtml: String
        val plain: String
        when (kind) {
            "html" -> { bodyHtml = sanitizeHtml(raw!!); plain = htmlToText(bodyHtml) }
            "markdown" -> { bodyHtml = markdownToHtml(raw!!); plain = markdownToText(raw) }
            else -> { bodyHtml = textToHtml(raw!!); plain = raw.replace("\r\n", "\n").trim() }
        }
        if (plain.isBlank() && !bodyHtml.contains("<img", true)) return bad("body", "The body is empty once formatting is removed.")
        val out = if (style == Style.CARD) frame(subj, bodyHtml, tagline) else wrapPlain(subj, bodyHtml)
        return Result.Ok(Composed(subj, out, plain, style))
    }

    private fun bad(field: String, message: String) = Result.Bad(ComposeError(field, message))

    // ---- escaping --------------------------------------------------------------------------------------------------

    fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c) }
    }

    private fun unesc(s: String): String = s.replace(Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z]+);")) { m ->
        val e = m.groupValues[1]
        when {
            e == "amp" -> "&"
            e == "lt" -> "<"
            e == "gt" -> ">"
            e == "quot" -> "\""
            e == "apos" -> "'"
            e == "nbsp" -> " "
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            else -> m.value
        }
    }

    // ---- text and markdown -----------------------------------------------------------------------------------------

    private const val P = "margin:0 0 14px 0;"

    private fun textToHtml(t: String): String = t.replace("\r\n", "\n").trim().split(Regex("\n{2,}")).filter { it.isNotBlank() }
        .joinToString("") { "<p style=\"$P\">${linkify(esc(it.trim())).replace("\n", "<br>")}</p>" }

    private fun linkify(escaped: String): String = Regex("https?://[^\\s<]+").replace(escaped) { m ->
        val url = m.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
        "<a href=\"$url\" style=\"color:#6d4aff;\">$url</a>" + m.value.substring(url.length)
    }

    private val LINK = Regex("\\[([^\\]\\n]+)]\\((https?://[^)\\s]+|mailto:[^)\\s]+)\\)")

    private fun inline(raw: String): String {
        var s = esc(raw)
        s = LINK.replace(s) { "<a href=\"${it.groupValues[2]}\" style=\"color:#6d4aff;\">${it.groupValues[1]}</a>" }
        s = Regex("`([^`\\n]+)`").replace(s) { "<code style=\"font-family:Menlo,Consolas,monospace;font-size:90%;background:#efeaff;padding:1px 4px;border-radius:4px;\">${it.groupValues[1]}</code>" }
        s = Regex("\\*\\*([^*\\n]+)\\*\\*").replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        s = Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])").replace(s) { "<em>${it.groupValues[1]}</em>" }
        return s
    }

    private fun markdownToHtml(md: String): String {
        val out = StringBuilder()
        val para = ArrayList<String>()
        var list: String? = null
        fun flushPara() { if (para.isNotEmpty()) { out.append("<p style=\"$P\">").append(para.joinToString("<br>") { inline(it) }).append("</p>"); para.clear() } }
        fun closeList() { list?.let { out.append("</$it>") }; list = null }
        for (line in md.replace("\r\n", "\n").lines()) {
            val t = line.trim()
            val h = Regex("^(#{1,3})\\s+(.+)$").matchEntire(t)
            val ul = Regex("^[-*]\\s+(.+)$").matchEntire(t)
            val ol = Regex("^\\d+[.)]\\s+(.+)$").matchEntire(t)
            when {
                t.isEmpty() -> { flushPara(); closeList() }
                h != null -> {
                    flushPara(); closeList()
                    val n = h.groupValues[1].length
                    val size = when (n) { 1 -> 22; 2 -> 18; else -> 16 }
                    out.append("<h$n style=\"margin:0 0 10px 0;font-size:${size}px;line-height:1.25;color:#1d1640;\">${inline(h.groupValues[2])}</h$n>")
                }
                ul != null || ol != null -> {
                    flushPara()
                    val want = if (ul != null) "ul" else "ol"
                    if (list != want) { closeList(); list = want; out.append("<$want style=\"margin:0 0 14px 0;padding-left:22px;\">") }
                    out.append("<li style=\"margin:3px 0;\">${inline((ul ?: ol)!!.groupValues[1])}</li>")
                }
                else -> { closeList(); para.add(t) }
            }
        }
        flushPara(); closeList()
        return out.toString()
    }

    private fun markdownToText(md: String): String = md.replace("\r\n", "\n").lines().joinToString("\n") { line ->
        var s = line.trim()
        s = s.replace(Regex("^#{1,3}\\s+"), "")
        s = LINK.replace(s) { "${it.groupValues[1]} (${it.groupValues[2]})" }
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1").replace(Regex("(?<![*\\w])\\*([^*]+)\\*(?![*\\w])"), "$1").replace(Regex("`([^`]+)`"), "$1")
        s.replace(Regex("^[-*]\\s+"), "- ")
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    // ---- HTML sanitiser --------------------------------------------------------------------------------------------

    private val DROP_WITH_CONTENT = setOf("script", "style", "iframe", "object", "embed", "form", "textarea", "select", "button",
        "noscript", "template", "svg", "math", "head", "title", "applet", "frameset", "frame", "audio", "video", "canvas")
    private val ALLOWED_TAGS = setOf("a", "abbr", "b", "blockquote", "br", "caption", "code", "div", "em", "h1", "h2", "h3", "h4", "h5", "h6",
        "hr", "i", "img", "li", "ol", "p", "pre", "small", "span", "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul", "center", "font")
    private val VOID = setOf("br", "hr", "img")
    private val ALLOWED_ATTRS = setOf("href", "src", "alt", "title", "width", "height", "colspan", "rowspan", "align", "valign", "style", "color", "size", "border", "cellpadding", "cellspacing", "dir", "lang")
    private val TAG = Regex("<(/?)([A-Za-z][A-Za-z0-9]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>|<!--.*?-->|<![^>]*>|<\\?[^>]*>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val ATTR = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?")

    private fun safeUrl(v: String, forImg: Boolean): String? {
        val u = unesc(v).filter { it.code > 32 && it.code != 127 }
        val l = u.lowercase()
        val ok = l.startsWith("https://") || (!forImg && (l.startsWith("http://") || l.startsWith("mailto:"))) || (forImg && l.startsWith("cid:"))
        return if (ok) u else null
    }

    /** Declarations that can fetch resources, run code or leave the card are dropped; everything else passes. */
    private fun safeStyle(v: String): String? {
        val decls = unesc(v).split(';').map { it.trim() }.filter { it.isNotEmpty() }.filter { d ->
            val l = d.lowercase().replace(Regex("\\s+"), "").replace("\\", "")
            !(l.contains("url(") || l.contains("expression") || l.contains("@import") || l.contains("javascript:") || l.contains("behavior")
                || l.contains("-moz-binding") || l.startsWith("position:") || l.contains("<") || l.contains(">") || l.startsWith("content:"))
        }
        return decls.takeIf { it.isNotEmpty() }?.joinToString(";")
    }

    fun sanitizeHtml(input: String): String {
        val src = input.filter { it != '\u0000' }
        val out = StringBuilder()
        val open = ArrayDeque<String>()
        var pos = 0
        var skip: String? = null
        var skipDepth = 0
        for (m in TAG.findAll(src)) {
            if (skip == null) out.append(esc(unesc(src.substring(pos, m.range.first))))
            pos = m.range.last + 1
            val name = m.groupValues[2].lowercase()
            if (name.isEmpty()) continue // comment, doctype or processing instruction
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.groupValues[3].trimEnd().endsWith("/")
            if (skip != null) {
                if (name == skip) { if (closing) { skipDepth--; if (skipDepth == 0) skip = null } else if (!selfClosing) skipDepth++ }
                continue
            }
            if (name in DROP_WITH_CONTENT) { if (!closing && !selfClosing) { skip = name; skipDepth = 1 }; continue }
            if (name !in ALLOWED_TAGS) continue // unknown tag: keep its text, lose the tag
            if (closing) {
                if (name in VOID) continue
                val i = open.lastIndexOf(name)
                if (i < 0) continue
                while (open.size > i) out.append("</").append(open.removeLast()).append('>')
                continue
            }
            val tag = StringBuilder("<").append(name)
            var hasSrc = false
            for (a in ATTR.findAll(m.groupValues[3])) {
                val an = a.groupValues[1].lowercase()
                if (an !in ALLOWED_ATTRS) continue
                val raw = a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value ?: ""
                val value = when (an) {
                    "href" -> if (name == "a") safeUrl(raw, false) else null
                    "src" -> if (name == "img") safeUrl(raw, true) else null
                    "style" -> safeStyle(raw)
                    else -> unesc(raw)
                } ?: continue
                if (an == "src") hasSrc = true
                tag.append(' ').append(an).append("=\"").append(esc(value)).append('"')
            }
            if (name == "img" && !hasSrc) continue // an image without a safe source is nothing
            tag.append('>')
            out.append(tag)
            if (name !in VOID) open.addLast(name)
        }
        if (skip == null) out.append(esc(unesc(src.substring(pos))))
        while (open.isNotEmpty()) out.append("</").append(open.removeLast()).append('>')
        return out.toString().trim()
    }

    fun htmlToText(html: String): String {
        var s = html.filter { it != '\u0000' }
        s = Regex("<(script|style|head|title)[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, "")
        s = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        s = Regex("<a\\b[^>]*?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>(.*?)</a\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s) { m ->
            val url = unesc(m.groupValues[1].ifEmpty { m.groupValues[2] })
            val label = Regex("<[^>]*>").replace(m.groupValues[3], "").trim()
            if (label.isEmpty() || unesc(label) == url) url else "$label ($url)"
        }
        s = Regex("<img\\b[^>]*?alt\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>", RegexOption.IGNORE_CASE).replace(s) { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        s = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n- ")
        s = Regex("<br\\s*/?>|</(p|div|h[1-6]|tr|ul|ol|blockquote|table|pre)\\s*>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE).replace(s, "  ")
        s = Regex("<[^>]*>").replace(s, "")
        s = unesc(s).replace(Regex("[ \\t]+\n"), "\n").replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\n{3,}"), "\n\n")
        return s.lines().joinToString("\n") { it.trim() }.trim()
    }

    // ---- frames ----------------------------------------------------------------------------------------------------

    private fun head(subject: String) = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<meta name=\"color-scheme\" content=\"light dark\"><meta name=\"supported-color-schemes\" content=\"light dark\"><title>${esc(subject)}</title></head>"

    /** The Bridgit card: a dark-violet header band with the lockup, a light body panel, inline CSS and tables only. */
    private fun frame(subject: String, body: String, tagline: String): String = head(subject) +
        "<body style=\"margin:0;padding:0;background:#1a1440;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:#1a1440;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">" +
        "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:560px;border-radius:18px;overflow:hidden;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">" +
        "<tr><td style=\"background:#3b2a7a;padding:20px 24px;\"><div style=\"font-size:22px;font-weight:700;letter-spacing:.02em;color:#ffffff;\">bridgit</div>" +
        "<div style=\"font-size:12px;line-height:1.35;color:#d8d0ff;margin-top:4px;\">${esc(tagline)}</div></td></tr>" +
        "<tr><td style=\"background:#ffffff;padding:24px;color:#1d1640;font-size:16px;line-height:1.5;\"><h1 style=\"margin:0 0 14px 0;font-size:24px;line-height:1.25;color:#1d1640;\">${esc(subject)}</h1>$body</td></tr>" +
        "<tr><td style=\"background:#ece8ff;height:6px;line-height:6px;font-size:0;\">&nbsp;</td></tr></table></td></tr></table></body></html>"

    private fun wrapPlain(subject: String, body: String): String = head(subject) +
        "<body style=\"margin:0;padding:16px;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:16px;line-height:1.5;\">$body</body></html>"
}
