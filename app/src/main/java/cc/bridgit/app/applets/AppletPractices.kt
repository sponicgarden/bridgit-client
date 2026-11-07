package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/** Pure structural assessment. Reported evidence never becomes a behavioral test pass. */
object AppletPractices {
    const val CONTRACT = "bridgit-practices/1"
    const val VERSION = "1.0.0"
    const val GUIDE = "https://bridgit.cc/learn/builder/applet-practices.md"
    private fun bad(message: String): Nothing = throw AppletPackage.Bad(message)
    private fun text(v: Any?, max: Int = 2000) = v is String && v.isNotBlank() && v.length <= max
    private fun keys(o: JSONObject?, allowed: Set<String>) = o != null && o.keys().asSequence().all { it in allowed }
    private fun entries(a: JSONArray?) = a?.let { (0 until it.length()).map(it::get) }.orEmpty()
    private fun rules() = entries(AppletPracticeCatalog.get().getJSONArray("practices")).map { it as JSONObject }
    fun pointer(d: JSONObject, path: String): Any {
        if (!path.startsWith('/') || path.length > 300 || Regex("~(?![01])").containsMatchIn(path) || path.startsWith("/ui/practices")) bad("Exception target must be a package JSON pointer outside ui.practices.")
        var value: Any = d
        for (key in path.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }) {
            value = when (val parent = value) {
                is JSONObject -> if (parent.has(key)) parent.get(key) else bad("Exception target does not exist: $path")
                is JSONArray -> key.toIntOrNull()?.takeIf { it >= 0 && it < parent.length() && it.toString() == key }?.let(parent::get) ?: bad("Exception target does not exist: $path")
                else -> bad("Exception target does not exist: $path")
            }
        }
        return value
    }
    fun check(d: JSONObject, previous: JSONObject? = null) {
        val ui = d.optJSONObject("ui") ?: return
        if (!ui.has("practices")) return // Historical releases stay readable.
        val q = ui.optJSONObject("practices")
        if (!keys(q, setOf("contract", "catalog", "review", "exceptions")) || q!!.optString("contract") != CONTRACT || q.optString("catalog") != VERSION) bad("Unsupported ui.practices contract/catalog. Read $GUIDE")
        val review = q.optJSONObject("review")
        val catalogRules=rules()
        val ids = catalogRules.map { it.getString("id") }.toSet()
        val references = review?.optJSONArray("practice_ids")?.let(::entries)
        if (!keys(review, setOf("decision", "reason", "practice_ids", "proposal")) || review!!.optString("decision") !in setOf("no-new-practice", "add", "change") || !text(review.opt("reason")) || references == null || references.size !in 1..64 || references.toSet().size != references.size || references.any { it !is String || it !in ids } || review.optString("decision") != "no-new-practice" && !text(review.opt("proposal"))) bad("ui.practices.review needs a decision, reason, known practice_ids and a proposal for add/change.")
        if (q.has("exceptions") && q.optJSONArray("exceptions") == null) bad("At most 32 practice exceptions.")
        val exceptions = entries(q.optJSONArray("exceptions"))
        if (exceptions.size > 32) bad("At most 32 practice exceptions.")
        val seen = HashSet<String>()
        for (raw in exceptions) {
            val e = raw as? JSONObject
            val rule = catalogRules.find { it.optString("id") == e?.optString("practice") }
            val acceptance = e?.optJSONArray("acceptance")?.let(::entries)
            if (!keys(e, setOf("id", "practice", "target", "reason", "alternative", "acceptance", "review_on")) || e == null || !text(e.opt("id"), 80) || !seen.add(e.optString("id")) || rule?.optBoolean("exceptions_allowed") != true || !text(e.opt("reason")) || !text(e.opt("alternative")) || !text(e.opt("review_on"), 200) || acceptance == null || acceptance.size !in 1..20 || acceptance.any { !text(it, 1000) }) bad("Invalid, duplicate or non-waivable practice exception.")
            val target = pointer(d, e.optString("target"))
            val old = entries(previous?.optJSONObject("ui")?.optJSONObject("practices")?.optJSONArray("exceptions")).filterIsInstance<JSONObject>().find { it.optString("id") == e.optString("id") }
            if (old != null && (AppletPackage.canonical(old) != AppletPackage.canonical(e) || AppletPackage.canonical(pointer(previous!!, old.optString("target"))) != AppletPackage.canonical(target))) bad("Changed exception ${e.optString("id")} needs a new id and review.")
        }
    }
    private fun hasOffAttribute(html: String, name: String): Boolean {
        val pattern=Regex("""(?:\s|^)$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",RegexOption.IGNORE_CASE)
        val cleaned=html.replace(Regex("""<!--[\s\S]*?-->"""),"")
        return pattern.findAll(cleaned).any { match ->
            val raw=match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
            val decoded=raw.replace(Regex("""&#(?:x([0-9a-f]+)|([0-9]+));?""",RegexOption.IGNORE_CASE)) { m ->
                val hex=m.groups[1]?.value
                val code=(hex ?: m.groupValues[2]).toIntOrNull(if(hex!=null)16 else 10)
                if(code!=null && code in 1..0x10ffff) String(Character.toChars(code)) else "�"
            }.replace("&Tab;","\t").replace("&NewLine;","\n").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&")
            decoded=="off"
        }
    }
    fun optOuts(d: JSONObject): List<Pair<String,String>> {
        val out=mutableListOf<Pair<String,String>>()
        fun walk(value: Any, path: String) {
            when(value) {
                is JSONObject -> {
                    if(value.optString("type")=="table" && Regex("^/ui/card/pieces/[0-9]+$").matches(path) && value.opt("sort")==false) out += "TABLE-001" to path
                    if(path.startsWith("/assets/") && value.optString("media_type")=="text/html") {
                        val text=value.optString("text")
                        val name=path.removePrefix("/assets/").replace("~1","/").replace("~0","~")
                        if(d.optJSONObject("ui")?.optJSONObject("card")?.optString("asset")==name && !Regex("data-bridgit-ui\\b").containsMatchIn(text)) out += "UI-001" to path
                        if(hasOffAttribute(text,"data-bridgit-sort")) out += "TABLE-001" to path
                        if(hasOffAttribute(text,"data-bridgit-viewer")) out += "MEDIA-001" to path
                    }
                    for(k in value.keys()) if(!(path=="/ui"&&k=="practices")) walk(value.get(k),path+"/"+k.replace("~","~0").replace("/","~1"))
                }
                is JSONArray -> for(i in 0 until value.length()) walk(value.get(i),"$path/$i")
            }
        }
        walk(d,"");return out
    }
    fun assess(d: JSONObject, previous: JSONObject? = null): JSONObject {
        check(d, previous)
        val q = d.optJSONObject("ui")?.optJSONObject("practices")
        val card = d.optJSONObject("ui")?.optJSONObject("card")
        val html = d.optJSONObject("assets")?.let { a -> a.keys().asSequence().mapNotNull { a.optJSONObject(it)?.takeIf { v -> v.optString("media_type") == "text/html" }?.optString("text") }.joinToString("\n") }.orEmpty()
        val pieces = card?.optJSONArray("pieces")?.toString().orEmpty()
        val processing = d.optJSONObject("processing")
        val body = processing?.toString().orEmpty()
        val surfaces = mapOf("all" to true, "ui" to (card != null || d.optJSONObject("ui")?.has("portal") == true),
            "tables" to (pieces.contains("\"type\":\"table\"") || Regex("<table\\b", RegexOption.IGNORE_CASE).containsMatchIn(html) || (d.optJSONObject("ui")?.optJSONObject("portal")?.optJSONArray("tables")?.length() ?: 0) > 0),
            "media" to (Regex("\"type\":\"(?:gallery|photo|video)\"").containsMatchIn(pieces) || Regex("<(?:img|video)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html) || body.contains("\"type\":\"photo\"")),
            "capture" to Regex("camera|capture|photo|video").containsMatchIn(body + pieces + html),
            "async" to (processing?.has("workflow") == true || (processing?.optJSONArray("flows")?.length() ?: 0) > 0 || (processing?.optJSONArray("watches")?.length() ?: 0) > 0 || html.contains("Bridgit.act")),
            "data" to ((processing?.optJSONArray("tables")?.length() ?: 0) > 0),
            "editable" to ((d.optJSONObject("config_schema")?.optJSONObject("properties")?.length() ?: 0) > 0 || (processing?.optJSONArray("tables")?.length() ?: 0) > 0), "upgrade" to true)
        val results = JSONArray()
        val optouts=optOuts(d);val oldOptouts=previous?.let(::optOuts).orEmpty()
        for (rule in rules()) {
            val id = rule.getString("id"); val scope = rule.getString("applicability")
            val applicable = surfaces[scope] == true
            val exceptions = entries(q?.optJSONArray("exceptions")).filterIsInstance<JSONObject>().filter { it.optString("practice") == id }
            var status = if (!applicable) "not-applicable" else if (rule.optString("enforcement") == "review") "unverified" else "runtime-default"
            if (id == "GOV-001") status = if (q != null) "structural-pass" else "fail"
            if (id == "GOV-002") status = "structural-pass"
            val targets=optouts.filter { it.first==id }.map { it.second }
            val unexcepted=targets.filter { target -> exceptions.none { it.optString("target")==target } }
            if(applicable && targets.isNotEmpty()) status=if(unexcepted.isEmpty()) "proposed-exception" else if(previous!=null && unexcepted.all { target ->
                oldOptouts.any { it.first==id && it.second==target } && AppletPackage.canonical(pointer(previous,target))==AppletPackage.canonical(pointer(d,target))
            }) "legacy-debt" else "fail"
            results.put(JSONObject().put("practice", id).put("revision", rule.getInt("revision")).put("applicable", applicable).put("status", status).put("checks", rule.getJSONArray("checks")).put("targets",JSONArray(targets)).put("exceptions", JSONArray(exceptions.map { it.getString("id") })))
        }
        return JSONObject().put("contract", CONTRACT).put("catalog", VERSION).put("catalog_source", "bridgit-builder-practices/1").put("guide", GUIDE)
            .put("candidate_sha256", AppletPackage.digest(d)).put("catalog_sha256", AppletPackage.digest(AppletPracticeCatalog.get()))
            .put("behavior_verified",false).put("valid", entries(results).filterIsInstance<JSONObject>().none { it.optString("status") == "fail" })
            .put("verification", "Structural results and runtime defaults are not behavioral or device test passes.").put("results", results)
    }
    fun requireChange(d: JSONObject, previous: JSONObject? = null): JSONObject = assess(d, previous).also { report ->
        if (!report.getBoolean("valid")) bad("Applet practice review required: " + entries(report.getJSONArray("results")).filterIsInstance<JSONObject>().filter { it.optString("status") == "fail" }.joinToString { it.getString("practice") } + ". $GUIDE")
    }
}
