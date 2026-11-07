package cc.bridgit.app.ui.agents

import cc.bridgit.app.outbound.OutboundEntry

/**
 * The Agents tab's "External tools": every service off the owner's network the phone calls on its own
 * behalf, with its state and last call. The outbound log's service names say which tool a call belongs to.
 */
object ExternalTools {
    enum class Id { JEV, CLEF, GLM, LOCATE, APPRAISE, CHEF, SPEECH, REPORTS, RELAY, WATCH, UPDATES }
    enum class State { READY, ON, OFF, PAUSED, MISSING }

    /** What the phone knows about one tool. [ownKeys]: labels of the owner's own keys that can stand in. */
    data class Facts(val available: Boolean, val on: Boolean = true, val ownKeys: List<String> = emptyList())

    data class LastCall(val at: Long, val ok: Boolean)

    /** Which tool an outbound log entry belongs to, by its service name; null for other destinations. */
    fun of(service: String): Id? = when {
        service.contains("speech", true) -> Id.SPEECH
        (service.contains("kitchen", true) || service.contains("Chef", true)) -> Id.CHEF
        service.contains("appraise", true) -> Id.APPRAISE
        service.contains("locate", true) -> Id.LOCATE
        service.contains("GLM") -> Id.GLM
        service.contains("Jev") -> Id.JEV
        service.contains("Clef") -> Id.CLEF
        service == "Bridgit team backend" -> Id.REPORTS
        service == "Bridgit relay" -> Id.RELAY
        service == "Bridgit remote view" -> Id.WATCH
        service == "Bridgit updates" -> Id.UPDATES
        else -> null
    }

    /** Newest call per tool, from entries in any order. Calls refused while paused don't count as calls. */
    fun lastCalls(entries: List<OutboundEntry>): Map<Id, LastCall> {
        val out = HashMap<Id, LastCall>()
        for (e in entries) {
            if (e.error?.startsWith("paused") == true) continue
            val id = of(e.service) ?: continue
            val prev = out[id]
            if (prev == null || e.ts > prev.at) out[id] = LastCall(e.ts, e.ok)
        }
        return out
    }

    /** Paused wins; then a tool this build can't use; then the tool's own switch. Decision models are "Ready". */
    fun state(id: Id, f: Facts, paused: Boolean): State = when {
        paused -> State.PAUSED
        !f.available && f.ownKeys.isEmpty() -> State.MISSING
        !f.on -> State.OFF
        id == Id.JEV || id == Id.CLEF || id == Id.GLM || id == Id.LOCATE || id == Id.APPRAISE || id == Id.CHEF || id == Id.SPEECH -> State.READY
        else -> State.ON
    }
}
