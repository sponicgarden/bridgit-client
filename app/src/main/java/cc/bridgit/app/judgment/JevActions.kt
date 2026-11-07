package cc.bridgit.app.judgment

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.interact.InteractionRules
import cc.bridgit.app.log.LogFacts
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the conditional actions the team backend sent back with a Jev answer ("bridgit-actions/1").
 * The backend already evaluated the conditions; this only does what the phone knows how to do, from
 * a closed list of plain-text actions. Anything else (an unknown type, a link, a field too long) is
 * ignored and logged, never run. There is no default action: no answer, no actions.
 */
object JevActions {
    const val SCHEMA = "bridgit-actions/1"

    /** Action types and their text fields: name to (max length, required). Same as bridgit-cloud/feedback/src/actions.mjs. */
    val TYPES: Map<String, Map<String, Pair<Int, Boolean>>> = mapOf(
        "notice" to mapOf("title" to (80 to true), "text" to (2000 to false)),
        "log" to mapOf("message" to (500 to true)),
        "speak" to mapOf("text" to (300 to true)),
    )

    private val LINK = Regex("[a-z][a-z0-9+.-]*://|javascript:|data:", RegexOption.IGNORE_CASE)

    /** One action the phone will run, or why it won't. */
    data class Step(val id: String, val type: String, val fields: Map<String, String>, val reason: String, val refused: String?)

    /** Checks each action against the closed list. Pure, unit-tested. */
    fun plan(schema: String, actions: JSONArray): List<Step> = (0 until actions.length()).map { i ->
        val a = actions.optJSONObject(i)
        val id = a?.optString("id").orEmpty().ifBlank { "#$i" }
        val type = a?.optString("type").orEmpty()
        val reason = a?.optString("reason").orEmpty()
        fun refuse(why: String) = Step(id, type, emptyMap(), reason, why)
        when {
            a == null -> refuse("not an object")
            schema != SCHEMA -> refuse("unknown schema ${schema.ifBlank { "(none)" }}")
            type !in TYPES -> refuse("unknown action type ${type.ifBlank { "(none)" }}")
            else -> {
                val spec = TYPES.getValue(type)
                val extra = a.keys().asSequence().filter { it !in spec && it !in setOf("id", "type", "reason") }.toList()
                val fields = spec.mapNotNull { (name, rule) -> a.optString(name, "").takeIf { a.has(name) && !a.isNull(name) && it.isNotEmpty() }?.let { name to it } }.toMap()
                val missing = spec.filter { it.value.second && it.key !in fields }.keys
                val tooLong = fields.filter { (k, v) -> v.length > spec.getValue(k).first }.keys
                when {
                    extra.isNotEmpty() -> refuse("unexpected field ${extra.first()}")
                    missing.isNotEmpty() -> refuse("missing ${missing.first()}")
                    tooLong.isNotEmpty() -> refuse("${tooLong.first()} too long")
                    fields.values.any { LINK.containsMatchIn(it) } -> refuse("holds a link or script")
                    else -> Step(id, type, fields, reason, null)
                }
            }
        }
    }

    /**
     * Runs the planned actions as [agentId] / [agentName] (the agent that asked, or the owner for the
     * phone's own calls) and returns one result per action: {id, type, reason, ran, error?}.
     */
    fun run(app: BridgeApp, agentId: String, agentName: String, schema: String, actions: JSONArray): JSONArray {
        val out = JSONArray()
        for (s in plan(schema, actions)) {
            val r = JSONObject().put("id", s.id).put("type", s.type).put("reason", s.reason)
            if (s.refused != null) {
                app.log.system("diagnostic", "judgment", "Ignored Jev action ${s.id} for $agentName: ${s.refused}", level = "warn")
                out.put(r.put("ran", false).put("error", "ignored: ${s.refused}"))
                continue
            }
            val error = runCatching { runOne(app, agentId, agentName, s) }.exceptionOrNull()
            if (error == null) out.put(r.put("ran", true))
            else {
                app.log.system("diagnostic", "judgment", "Jev action ${s.id} for $agentName didn't run: ${error.message}", level = "warn")
                out.put(r.put("ran", false).put("error", error.message ?: error.javaClass.simpleName))
            }
        }
        return out
    }

    private fun allows(app: BridgeApp, agentId: String, capability: String): Boolean {
        val module = app.registry.byId(capability)
        if (module != null && !module.enabled) return false
        return agentId == "owner" || app.agents.get(agentId)?.allows(capability) == true
    }

    private fun runOne(app: BridgeApp, agentId: String, agentName: String, s: Step) {
        when (s.type) {
            "notice" -> {
                check(allows(app, agentId, "interactions")) { "cards are off or not allowed for $agentName" }
                val d = InteractionRules.parse(JSONObject().put("kind", "notice").put("title", s.fields.getValue("title"))
                    .apply { s.fields["text"]?.let { put("text", it) } })
                val card = app.interactions.create(agentId, agentName, d, null)
                app.log.system("audit", "interaction", LogFacts.jevNoticeMessage(agentName, s.fields.getValue("title")),
                    data = LogFacts.jevNotice(agentId, s.id, s.reason, card.id))
            }
            "speak" -> {
                check(allows(app, agentId, "speaker")) { "the speaker is off or not allowed for $agentName" }
                app.speaker.speakText(agentId, agentName, s.fields.getValue("text"), allowInQuiet = false)
            }
            "log" -> app.log.system("audit", "judgment", "$agentName (Jev: ${s.reason}): ${s.fields.getValue("message")}")
        }
    }
}
