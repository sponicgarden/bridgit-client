package cc.bridgit.app.core.authority

import cc.bridgit.app.api.ApiAccess
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import org.json.JSONArray

/**
 * The grant checks of every door, in code order, in one place (Client Core architecture 4.2 and 4.3, phase 1.6,
 * A0332): the HTTP door and the in-process dispatch it shares with MCP, card actions, schedules and configuration
 * tools ([requireGrant]); owner configuration ([requireConfiguration]); control scopes ([requireScope]); the relay
 * door ([requireRelayAgent]); http_v2 ([requireApiAccess]). Each refusal keeps the code, status and wording it had
 * before the move (contracts/client-core/doors/authorization.json points here). What the checks read about the
 * world comes through [State], so the order and the refusals are pure and the same on every door.
 */
object Grants {
    /** The state the grant steps read; [AndroidGrantState] reads it from the app. */
    interface State {
        /** The agent as it is now (null once removed). */
        fun live(id: String): Agent?
        fun isLinkAgent(id: String): Boolean
        /** Applets, applet agents, link agents and portal viewers: they never configure the owner's phone. */
        fun isConfigurationExcluded(live: Agent): Boolean
        fun listenerEnabled(): Boolean
        fun scopeKnown(scope: String): Boolean
        fun scopeLabel(scope: String): String
        fun hasGrant(agentId: String, scope: String): Boolean
        fun scopeBlockers(scope: String): List<String>
        /** A Super agent that may grant itself owner.configuration through the configuration API (no phone approval). */
        fun superConfiguration(agentId: String): Boolean
        /** The email-alert gate (Alerts.checkAgentCall); [exempt] calls pass while the owner confirms an address. */
        fun emailGate(exempt: Boolean)
    }

    /** What an operation asks of the caller: its capability module, control scope and whether it is the link's own plumbing. */
    data class Target(val capability: String, val controlScope: String?, val linkPlumbing: Boolean, val emailExempt: Boolean)

    /**
     * The HTTP door's "grant" steps and the in-process dispatch's (authorization.json http_v1 and mcp, grant.*):
     * agent still there, key unchanged, API access, pause (both waived for a link agent's own plumbing), capability,
     * control scope, email gate. Returns the live agent.
     */
    fun requireGrant(s: State, agent: Agent, t: Target, allowListenerPaused: Boolean = false): Agent {
        val live = s.live(agent.id) ?: throw ErrorCatalog.error("unauthorized", "Agent removed.")
        if (live.keyHash != agent.keyHash) throw ErrorCatalog.error("unauthorized", "Agent key was rotated during the request.")
        // A paused link agent still makes the link's own calls (events, disconnect), so both sides stay in step.
        val plumbing = t.linkPlumbing && s.isLinkAgent(live.id)
        if (!live.apiAccess && !plumbing) throw ApiAccess.refusal(live)
        if (live.paused && !plumbing)
            throw ErrorCatalog.error("forbidden", "The owner paused ${live.name} on this phone. Wait until they resume you; don't retry before then.",
                mapOf("reason" to "agent_paused"))
        if (!live.allows(t.capability)) throw ErrorCatalog.error("agent_not_allowed", "This agent does not have ${t.capability} access. Ask the owner to update its permissions on the phone.")
        t.controlScope?.let { scope ->
            if (scope == OWNER_CONFIGURATION) requireConfiguration(s, live, allowListenerPaused)
            else requireScope(s, live.id, scope)
        }
        s.emailGate(t.emailExempt)
        return live
    }

    /**
     * One explicitly owner-granted scope, rechecked immediately before every configuration effect (was
     * ownerapi.ConfigurationAccess.require). With [allowListenerPaused] a configuration call already committed to
     * pausing the listener checks the grant and its blockers without the listener step.
     */
    fun requireConfiguration(s: State, caller: Agent, allowListenerPaused: Boolean = false): Agent {
        val live = s.live(caller.id)?.takeUnless { it.paused }
            ?: throw ErrorCatalog.error("unauthorized", "Control agent is no longer active")
        if (live.keyHash != caller.keyHash) throw ErrorCatalog.error("unauthorized", "Control key changed")
        // Link agents never configure: each Bridgit's own agents change its links (LNK-48).
        if (s.isConfigurationExcluded(live)) throw ErrorCatalog.error("forbidden", "Configuration authority belongs to control agents")
        if (!live.allows("owner_features")) throw ErrorCatalog.error("agent_not_allowed", "Owner feature access is required")
        if (!allowListenerPaused) requireScope(s, live.id, OWNER_CONFIGURATION)
        else {
            if (!s.hasGrant(live.id, OWNER_CONFIGURATION)) throw ErrorCatalog.error("agent_not_allowed", "Configuration grant was revoked")
            if (s.scopeBlockers(OWNER_CONFIGURATION).isNotEmpty()) throw ErrorCatalog.error("capability_disabled", "Configuration authority is off")
        }
        return live
    }

    /**
     * A v2 control scope (was AgentPlatform.authorize; authorization.json http_v2 listener, scope_known,
     * scope_granted, scope_blockers): listener on, scope known, granted to [agentId], nothing blocking it.
     */
    fun requireScope(s: State, agentId: String, scope: String) {
        if (!s.listenerEnabled()) throw ApiError(403, "listener_paused", "Resume the listener on the phone")
        if (!s.scopeKnown(scope)) throw ApiError(422, "unknown_scope", "Unknown capability")
        val superConfiguration by lazy { s.superConfiguration(agentId) }
        val accessUrl = "/v1/owner/configuration/access"
        if (!s.hasGrant(agentId, scope)) {
            if (scope == OWNER_CONFIGURATION && superConfiguration) throw ApiError(403, "agent_not_allowed",
                "This Super agent can grant its configuration scope through $accessUrl; no phone approval is needed",
                mapOf("configuration_access_url" to accessUrl, "user_action_required" to false))
            throw ApiError(403, "agent_not_allowed", "The owner or an existing Super agent must grant $scope to this agent")
        }
        val blockers = s.scopeBlockers(scope)
        if (blockers.isNotEmpty()) {
            val label = s.scopeLabel(scope)
            if (superConfiguration && blockers.all { it == "feature_disabled" }) throw ApiError(403, blockers.first(),
                "This Super agent can enable $label through $accessUrl/features; no phone approval is needed",
                mapOf("blockers" to JSONArray(blockers), "configuration_access_url" to accessUrl, "user_action_required" to false))
            throw ApiError(403, blockers.first(), "Open Agent permissions on the phone: $label", mapOf("blockers" to JSONArray(blockers), "user_action_required" to true))
        }
    }

    /**
     * The relay door's agent steps (authorization.json relay_job agent_live, relay_authority, api_access, rate,
     * explicit_grants), in code order. [relayAuthority] is "listener on, relay on"; [generation] reads the job's config
     * generation (only once the agent is known), which must match the agent's key. [acquireRate] takes one call from the per-agent relay allowance.
     */
    fun requireRelayAgent(s: State, id: String, generation: () -> String, relayAuthority: Boolean, linkPlumbing: Boolean, acquireRate: () -> Boolean): Agent {
        val agent = s.live(id) ?: throw ApiError(401, "agent_revoked", "Agent removed")
        if (!relayAuthority || agent.keyHash != generation()) throw ApiError(403, "agent_revoked", "Relay authority changed")
        // A0120: API access off refuses every relay call; a linked Bridgit's own plumbing still runs, as when paused.
        if (!agent.apiAccess && !(linkPlumbing && s.isLinkAgent(id))) throw ApiAccess.refusal(agent)
        if (!acquireRate()) throw ApiError(429, "rate_limited", "At most 300 relay requests per minute")
        if (agent.caps == null) throw ApiError(403, "explicit_grants_required", "Relay agents need explicit grants")
        return agent
    }

    /** http_v2's api_access step: refuses an agent whose API access is off; null (unknown id) is left to the caller. */
    fun requireApiAccess(live: Agent?) = ApiAccess.require(live)

    const val OWNER_CONFIGURATION = "owner.configuration"
}
