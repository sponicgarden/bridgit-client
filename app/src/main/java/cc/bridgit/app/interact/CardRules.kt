package cc.bridgit.app.interact

import org.json.JSONTokener

/**
 * CAP-34 HTML cards: the pure half of the sandbox (checks on the agent's HTML, the forced CSP, and the
 * one-time Bridgit.respond gate). No Android types, so every rule here is unit-tested. The WebView half is
 * [cc.bridgit.app.ui.interactions.CardWebView].
 */
object CardRules {
    /** 256 KB of raw HTML (UTF-8 bytes), checked before anything else. */
    const val HTML_MAX_BYTES = 256 * 1024
    /** Bridgit.respond(json): at most 16 KB (UTF-8 bytes). */
    const val RESPOND_MAX_BYTES = 16 * 1024
    /** Deeper JSON than this is refused before parsing (a 16 KB "[[[[…" would overflow the parser's stack). */
    const val RESPOND_MAX_DEPTH = 64

    /**
     * Forced by Bridgit (CAP-34): page scripts and styles run inline, images only from data:, nothing else
     * loads. base-uri, form-action, frame-src and object-src are extra hardening (base-uri and form-action do
     * not fall back to default-src).
     */
    const val CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
        "base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'"

    /**
     * Runs before any of the page's own script: removes WebRTC (CSP doesn't cover peer connections, so a
     * page could otherwise reach a STUN server) and the other network constructors, locked so the page can't
     * put them back, and hides the windows of frames made at run time. Best effort on top of the CSP and the
     * blocked network. The page reaches out only through Bridgit's bridge: Bridgit.respond, and Bridgit.act
     * (feature #0007), which runs the posting agent's own endpoints with its grants ([CardActions]).
     */
    const val GUARD = "(function(){var w=window;['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel'," +
        "'RTCSessionDescription','RTCIceCandidate','RTCRtpSender','RTCRtpReceiver','WebSocket','WebTransport','EventSource'," +
        "'XMLHttpRequest','fetch','Worker','SharedWorker','BroadcastChannel'].forEach(function(k){try{Object.defineProperty(w,k," +
        "{value:undefined,writable:false,configurable:false})}catch(e){}});" +
        "['HTMLIFrameElement','HTMLFrameElement','HTMLObjectElement','HTMLEmbedElement'].forEach(function(t){var P=w[t]&&w[t].prototype;" +
        "if(!P)return;['contentWindow','contentDocument'].forEach(function(k){try{Object.defineProperty(P,k," +
        "{get:function(){return null},configurable:false})}catch(e){}})});" +
        "try{if(navigator.serviceWorker)Object.defineProperty(navigator,'serviceWorker',{value:undefined,configurable:false})}catch(e){}" +
        "try{if(navigator.sendBeacon)Object.defineProperty(Navigator.prototype,'sendBeacon',{value:function(){return false},configurable:false})}catch(e){}" +
        "})();"

    /**
     * Runs right after [GUARD]. The native Bridgit.respond takes a String, so a page calling
     * Bridgit.respond({button:"go"}) used to hand Android a non-JSON string and the answer was dropped with no
     * error (bug report #0006). This wraps it: strings pass through as before, any other JSON value is
     * JSON.stringify'd first, and undefined or unserializable values are refused here. The wrapper replaces
     * both window.Bridgit and the old alias window.Bridget. The 16 KB and strict-JSON checks still run in Kotlin.
     *
     * Bridgit.act(request) (feature #0007, [CardActions]) calls one of the agent's endpoints on the phone and
     * returns a Promise of {ok, status, body, ref}; the native side answers through window.__bridgitActed and
     * a bridgit:acted event. A request that can't be serialized resolves at once with ok:false.
     */
    const val RESPOND_SHIM = "(function(){var w=window,n=w.Bridgit||w.Bridget;if(!n||typeof n.respond!=='function')return;" +
        "var p={},bad=function(){return Promise.resolve({ok:false,status:400,error:'invalid_parameter',body:null,ref:0})};" +
        "try{Object.defineProperty(w,'__bridgitActed',{value:function(r){var f=r&&p[r.ref];if(f){delete p[r.ref];f(r)}}," +
        "writable:false,configurable:false})}catch(e){}" +
        "var b={respond:function(v){var s=v;if(typeof v!=='string'){if(v===undefined)return;" +
        "try{s=JSON.stringify(v)}catch(e){return}if(typeof s!=='string')return}n.respond(s)}," +
        "act:function(v){if(typeof n.act!=='function')return bad();var s=v;if(typeof v!=='string'){" +
        "try{s=JSON.stringify(v)}catch(e){return bad()}if(typeof s!=='string')return bad()}" +
        "var ref=n.act(s);return new Promise(function(res){p[ref]=res})}};" +
        "['Bridgit','Bridget'].forEach(function(k){try{Object.defineProperty(w,k,{value:b,writable:false,configurable:false})}" +
        "catch(e){try{w[k]=b}catch(e2){}}})})();"

    /** The four directives CAP-34 names; [CSP] must contain each. */
    val CSP_REQUIRED = listOf("default-src 'none'", "img-src data:", "style-src 'unsafe-inline'", "script-src 'unsafe-inline'")

    /** data: types an html card may not contain anywhere (frames, scripts, SVG with scripts). */
    private val FORBIDDEN_DATA = listOf("data:text/html", "data:image/svg", "data:application/", "data:text/xml",
        "data:text/javascript", "data:text/ecmascript", "data:multipart/")

    /** Elements that embed other documents; the CSP blocks them too, refused up front for a clear error. */
    private val FORBIDDEN_TAGS = listOf("<iframe", "<frame", "<object", "<embed", "<portal", "<base", "<applet")

    private fun bad(msg: String): Nothing = throw InteractionError("invalid_parameter", msg)

    /** Checks the `html` field of a kind=html request and returns it. Size first (CAP-34), then content. */
    fun checkHtml(v: Any?): String {
        val html = v as? String ?: bad("html is required for kind html (a string, up to 256 KB).")
        if (html.length > HTML_MAX_BYTES || html.toByteArray(Charsets.UTF_8).size > HTML_MAX_BYTES)
            throw InteractionError("payload_too_large", "html is limited to 256 KB.")
        if (html.isBlank()) bad("html is required for kind html.")
        val n = normalize(html)
        FORBIDDEN_DATA.firstOrNull { it in n }?.let {
            bad("html may only embed data:image/* pictures (png, jpeg, gif, webp); $it… is refused.")
        }
        FORBIDDEN_TAGS.firstOrNull { it in n }?.let { bad("html may not use ${it}> (cards are one page with no frames).") }
        return html
    }

    /**
     * Lowercase, common character references decoded, whitespace and control characters dropped, so
     * "DATA :\ttext/html" or "data&#58;text/html" still match. Over-matching only refuses a card, never lets one through.
     */
    fun normalize(html: String): String {
        var s = html.lowercase()
        s = s.replace(Regex("&#x0*([0-9a-f]{1,6});?")) { m -> m.groupValues[1].toInt(16).let { c -> if (c in 1..0xFFFF) c.toChar().toString() else "" } }
        s = s.replace(Regex("&#0*([0-9]{1,7});?")) { m -> m.groupValues[1].toInt().let { c -> if (c in 1..0xFFFF) c.toChar().toString() else "" } }
        s = s.replace("&colon;", ":").replace("&sol;", "/").replace("&newline;", "").replace("&tab;", "")
        return s.filter { !it.isWhitespace() && !it.isISOControl() }
    }

    /**
     * The document the WebView loads: Bridgit's head with the CSP meta first, then the agent's HTML with every
     * `http-equiv` attribute renamed so no meta in the page can set a policy, refresh or redirect. Attribute
     * names can't be escaped in HTML, so renaming every case-insensitive occurrence catches all of them.
     */
    fun prepare(html: String, portal: Boolean = false): String {
        val neutral = html.replace(Regex("http-equiv", RegexOption.IGNORE_CASE), "data-blocked-equiv")
        return "<!DOCTYPE html><html><head>" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<script>$GUARD</script>" +
            "<script>$RESPOND_SHIM</script>" +
            // Applet portals (docs/spec-portals-and-cards.md): Bridgit.portal, live in a portal, a no-op elsewhere.
            "<script>${if (portal) cc.bridgit.app.applets.PortalRules.SHIM else cc.bridgit.app.applets.PortalRules.STUB}</script>" +
            "</head>" + neutral
    }

    /**
     * Parses one Bridgit.respond(json) argument: at most 16 KB, strict JSON (object, array, string, number,
     * true, false or null) with nothing after it. Returns the parsed value (JSONObject.NULL for null) or null if refused.
     */
    fun parseRespond(json: String?): Any? {
        if (json == null || json.length > RESPOND_MAX_BYTES || json.toByteArray(Charsets.UTF_8).size > RESPOND_MAX_BYTES) return null
        val t = json.trim()
        if (t.isEmpty() || depth(t) > RESPOND_MAX_DEPTH) return null
        return try {
            val tok = JSONTokener(t)
            val v = tok.nextValue()
            if (tok.nextClean() != 0.toChar()) return null
            // JSONTokener is lenient: unquoted words come back as strings. Only real JSON is accepted.
            if (v is String && t[0] != '"') return null
            if (v is Boolean && t != "true" && t != "false") return null
            if (v == org.json.JSONObject.NULL && t != "null") return null
            v
        } catch (_: Exception) { null }
    }

    /**
     * Deepest [ / { nesting outside strings, or Int.MAX_VALUE when something outside a double-quoted string
     * isn't JSON (comments, single quotes, other words). The lenient JSONTokener accepts those, so they are
     * refused here first; that also keeps this count honest.
     */
    fun depth(s: String): Int {
        var d = 0; var max = 0; var inStr = false; var esc = false
        val word = StringBuilder()
        fun wordOk(): Boolean { val w = word.toString(); word.clear(); return w.isEmpty() || w in JSON_WORDS }
        for (c in s) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            if (c in 'a'..'z' || c in 'A'..'Z') { word.append(c); continue }
            if (!wordOk()) return Int.MAX_VALUE
            when (c) {
                '"' -> inStr = true
                '[', '{' -> { d++; if (d > max) max = d }
                ']', '}' -> d--
                ',', ':', '-', '+', '.', ' ', '\t', '\n', '\r', in '0'..'9' -> {}
                else -> return Int.MAX_VALUE
            }
        }
        return if (wordOk()) max else Int.MAX_VALUE
    }

    /** The only bare words JSON has: the literals and the exponent marker. */
    private val JSON_WORDS = setOf("true", "false", "null", "e", "E")
}

/**
 * Bridgit.respond is accepted once per card (CAP-34): the first valid call wins; refused calls (too big,
 * not JSON) don't use up the slot; every later call is ignored. Called from the WebView's JavaBridge thread.
 */
class RespondGate {
    private var used = false
    val accepted: Boolean @Synchronized get() = used

    /** The parsed answer if this call is the one accepted, else null. */
    @Synchronized
    fun offer(json: String?): Any? {
        if (used) return null
        val v = CardRules.parseRespond(json) ?: return null
        used = true
        return v
    }
}
