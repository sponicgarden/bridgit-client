package cc.bridgit.app.api

import cc.bridgit.app.ui.more.WhatCanData

/**
 * The public API page (owner request, 1.5.1): one section per "What can Bridgit do?" card with
 * every call it uses, then the full endpoint reference, the error catalog and the API revisions.
 * Generated from the same EndpointSpecs as the router and openapi.json, so it can't drift.
 * Published next to the updates (tools/publish-apk.sh); each card's Share button links to its anchor.
 * No phone address or secret is in it: the host is a placeholder.
 */
object ApiDocs {
    const val URL = "https://pub-3a79691572bc4abc93fce31910fe320b.r2.dev/bridget/api.html"

    data class Card(val title: String, val stage: String, val technical: Boolean, val what: String, val useCase: String,
                    val options: List<String>, val endpoints: List<String>, val ref: String)

    /** Stable anchor for a card title, e.g. "Take a photo" -> "take-a-photo". */
    fun slug(s: String) = WhatCanData.stableTitle(s).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun link(title: String) = "$URL#${slug(title)}"

    /** The Applets screen's Config tab's "How to build a ready-made applet" card links here. */
    const val APPLET_TITLE = "Build an applet"
    const val APPLET_QUICKSTART_URL = "https://bridgit.cc/learn/builder/quickstart.md"
    const val APPLET_URL = "https://bridgit.cc/recipes/build-an-applet/"

    fun endpointKey(e: EndpointSpec) = "${e.method} ${e.fullPath}"
    private fun epAnchor(e: EndpointSpec) = "ep-" + slug("${e.method} ${e.fullPath}")
    fun find(key: String): EndpointSpec? = Endpoints.all.firstOrNull { endpointKey(it) == key }

    private fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    /** `code` in descriptions -> <code>. */
    private fun md(s: String) = h(s).replace(Regex("`([^`]+)`"), "<code>$1</code>")

    fun html(cards: List<Card>, appVersion: String): String = buildString {
        append("""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bridgit API</title><style>
:root{--bg:#F8F9FF;--card:#E6EBF5;--ink:#151B27;--muted:#3C4558;--line:#B5BFD2;--accent:#1F5BD6;--ok:#C3EFCD;--okink:#00210B;--next:#D6E2FF;--nextink:#001849;--code:#D7DEEC}
@media (prefers-color-scheme:dark){:root{--bg:#0B0F17;--card:#1A2130;--ink:#E1E6F2;--muted:#BAC4D8;--line:#3D475C;--accent:#ADC6FF;--ok:#005226;--okink:#9CF6B4;--next:#1B4AAE;--nextink:#DCE6FF;--code:#262F42}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:860px;margin:0 auto;padding:24px 16px 64px}h1{font-size:28px;margin:0 0 4px}h2{font-size:22px;margin:40px 0 12px}
.sub{color:var(--muted)}section.card{background:var(--card);border-radius:12px;padding:16px;margin:12px 0;scroll-margin-top:16px}
section.card:target{outline:2px solid var(--accent)}h3{margin:6px 0 4px;font-size:19px}.chip{display:inline-block;font-size:12px;font-weight:600;padding:2px 8px;border-radius:6px;background:var(--code);color:var(--muted)}
.chip.now{background:var(--ok);color:var(--okink)}.chip.next{background:var(--next);color:var(--nextink)}
.label{color:var(--accent);font-size:13px;font-weight:600;margin-top:10px}.use{font-style:italic;color:var(--muted)}
code,pre{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}code{background:var(--code);padding:1px 4px;border-radius:4px}
pre{background:var(--code);padding:10px;border-radius:8px;overflow-x:auto;white-space:pre-wrap;word-break:break-word}
.ep{border-top:1px solid var(--line);margin-top:12px;padding-top:10px;scroll-margin-top:16px}.ep:target{outline:2px solid var(--accent);border-radius:8px}
table{border-collapse:collapse;width:100%;font-size:14px}td,th{border-bottom:1px solid var(--line);padding:6px 4px;text-align:left;vertical-align:top}
a{color:var(--accent)}nav a{margin-right:12px}ul{margin:4px 0 4px 20px;padding:0}
</style></head><body><main>""")
        append("<h1>Bridgit API</h1><p class=\"sub\">Bridgit: Distributed sensorial applications, built by your favorite agent. · Version ${h(appVersion)}, API revision ${ApiChanges.CURRENT}. ")
        append("What an AI control agent can ask an Android phone to do, and the exact calls. Base URL <code>http://&lt;phone&gt;:8734/v1</code> on the owner's Tailscale network; ")
        append("every call needs <code>Authorization: Bearer &lt;key&gt;</code> except where noted. A running phone also serves this live at <code>GET /v1/guide</code> and <code>GET /v1/openapi.json</code>.</p>")
        append("<nav><a href=\"#abilities\">Supported Control Agent Requests</a><a href=\"#technical\">How it works</a><a href=\"#${slug(APPLET_TITLE)}\">Build an applet</a><a href=\"#reference\">All endpoints</a><a href=\"#errors\">Errors</a><a href=\"#revisions\">Revisions</a></nav>")
        for ((id, title, technical) in listOf(Triple("abilities", "Supported Control Agent Requests:", false), Triple("technical", "How Bridgit works", true))) {
            append("<h2 id=\"$id\">${h(title)}</h2>")
            for (c in cards.filter { it.technical == technical }) card(c)
        }
        appletGuide()
        append("<h2 id=\"reference\">All endpoints</h2>")
        for (e in Endpoints.all) endpoint(e)
        append("<section class=\"card\" id=\"complete-history\"><h2>Activity history and device diagnostics</h2>")
        LogGuide.markdown.lineSequence().drop(1).filter { it.isNotBlank() }.forEach { append("<p>${md(it)}</p>") }
        append("</section>")
        append("<h2 id=\"errors\">Errors</h2><p>Errors are <code>{\"error\",\"message\",\"request_id\"}</code> with these codes.</p><table><tr><th>HTTP</th><th>Code</th><th>Meaning</th><th>The agent should</th></tr>")
        for (e in ErrorCatalog.all.filter { it.built }) append("<tr><td>${e.http}</td><td><code>${e.code}</code></td><td>${md(e.meaning)}</td><td>${md(e.agentShould)}</td></tr>")
        append("</table><h2 id=\"revisions\">API revisions</h2>")
        for (r in ApiChanges.revisions.reversed()) {
            append("<p><b>Revision ${r.revision}</b> (app ${h(r.appVersion)}, ${h(r.date)})</p><ul>")
            r.items.forEach { append("<li>${md(it.what)}${it.endpoint?.let { e -> " <code>${h(e)}</code>" } ?: ""}</li>") }
            append("</ul>")
        }
        append("</main></body></html>")
    }

    /** Compatibility anchor on the full API reference; Open/Share use the compact page. */
    private fun StringBuilder.appletGuide() {
        append("<h2 id=\"${slug(APPLET_TITLE)}\">Build an applet</h2>")
        append("<p><a href=\"$APPLET_URL\">Open the compact applet builder instructions</a> without loading the full API reference.</p>")
        append(AppletBuildGuide.HTML)
    }

    private fun StringBuilder.card(c: Card) {
        val cls = when { c.stage.startsWith("Available") -> "now"; c.stage.startsWith("Next") -> "next"; else -> "" }
        append("<section class=\"card\" id=\"${slug(c.title)}\"><span class=\"chip $cls\">${h(c.stage)}</span><h3>${h(c.title)}</h3><p>${h(c.what)}</p>")
        if (c.options.isNotEmpty()) { append("<div class=\"label\">Options</div><ul>"); c.options.forEach { append("<li>${h(it)}</li>") }; append("</ul>") }
        append("<div class=\"label\">For example</div><p class=\"use\">${h(c.useCase)}</p>")
        val eps = c.endpoints.mapNotNull { find(it) }
        if (eps.isNotEmpty()) {
            append("<div class=\"label\">API</div>")
            eps.forEach { endpoint(it, short = true) }
        } else if (!c.stage.startsWith("Available")) {
            append("<div class=\"label\">API</div><p class=\"sub\">Not built yet. Spec ${h(c.ref)} in <code>docs/bridgit-spec-v2.md</code> describes the planned calls.</p>")
        }
        append("</section>")
    }

    private fun StringBuilder.endpoint(e: EndpointSpec, short: Boolean = false) {
        val anchor = if (short) "" else " id=\"${epAnchor(e)}\""
        append("<div class=\"ep\"$anchor><b><code>${e.method} ${h(e.fullPath)}</code></b>")
        if (!e.auth) append(" <span class=\"chip\">no key needed</span>")
        if (e.idempotent) append(" <span class=\"chip\">Idempotency-Key</span>")
        if (short) append(" <a href=\"#${epAnchor(e)}\">details</a>")
        append("<p>${md(e.summary)}.</p>")
        if (!short) {
            if (e.query.isNotEmpty()) { append("<table><tr><th>Query</th><th>Type</th><th>What</th></tr>"); e.query.forEach { p -> append("<tr><td><code>${p.name}</code></td><td>${p.type}</td><td>${md(p.description)}${p.enum?.let { " (${it.joinToString(", ")})" } ?: ""}</td></tr>") }; append("</table>") }
            if (e.body.isNotEmpty()) { append("<table><tr><th>Body</th><th>Type</th><th>What</th></tr>"); e.body.forEach { p -> append("<tr><td><code>${p.name}</code>${if (p.required) " *" else ""}</td><td>${p.type}</td><td>${md(p.description)}</td></tr>") }; append("</table>") }
            append("<p><b>Returns:</b> ${md(e.returns)}</p>")
            if (e.errors.isNotEmpty()) append("<p><b>Errors:</b> ${e.errors.joinToString(", ") { "<code>$it</code>" }}</p>")
        }
        append("<pre>${h(e.example)}</pre></div>")
    }
}
