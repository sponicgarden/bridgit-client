package cc.bridgit.app.api

import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Power

/** AGT-15: the one place that refuses an agent without Fixer Mode or Super Powers, so every refusal reads the same. */
object PowerGate {
    fun message(p: Power, agentName: String, what: String) =
        "$what needs ${p.label}. Ask the owner to turn on ${p.label} for $agentName (Bridgit → Agents → $agentName)."

    /** Throws forbidden with reason fixer_mode_off or super_powers_off unless [agent] has [p]. */
    fun require(agent: Agent?, p: Power, what: String) {
        if (agent?.has(p) != true) throw ErrorCatalog.error("forbidden", message(p, agent?.name ?: "you", what), mapOf("reason" to p.reason))
    }
}
