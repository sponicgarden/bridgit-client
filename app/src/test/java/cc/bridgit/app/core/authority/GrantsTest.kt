package cc.bridgit.app.core.authority

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.core.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The door grant checks in core.authority.Grants (A0332), against a state with no app behind it: the order of the
 * refusals, the link-plumbing waiver, the relay door's steps and the control-scope answers. The door fixtures
 * (DoorFixturesTest) prove the same refusals through the real entry points.
 */
class GrantsTest {
    private val base = Agent("a1", "Ada", "hash", "salt", "brg_x", 0L, caps = setOf("log", "owner_features"))

    private class Fake(var agent: Agent?) : Grants.State {
        var link = false; var excluded = false; var listener = true; var granted = setOf<String>(); var blockers = listOf<String>()
        var superConfig = false; val emailCalls = mutableListOf<Boolean>()
        override fun live(id: String) = agent?.takeIf { it.id == id }
        override fun isLinkAgent(id: String) = link
        override fun isConfigurationExcluded(live: Agent) = excluded
        override fun listenerEnabled() = listener
        override fun scopeKnown(scope: String) = scope in setOf("owner.configuration", "camera.capture")
        override fun scopeLabel(scope: String) = "Label of $scope"
        override fun hasGrant(agentId: String, scope: String) = scope in granted
        override fun scopeBlockers(scope: String) = blockers
        override fun superConfiguration(agentId: String) = superConfig
        override fun emailGate(exempt: Boolean) { emailCalls += exempt }
    }

    private fun code(block: () -> Unit): String = try { block(); fail("expected a refusal"); "" } catch (e: ApiError) { e.code + (e.extra["reason"]?.let { "/$it" } ?: "") }

    private val log = Grants.Target("log", null, linkPlumbing = false, emailExempt = false)

    @Test fun grantStepsRefuseInCodeOrder() {
        val s = Fake(null)
        assertEquals("unauthorized", code { Grants.requireGrant(s, base, log) })
        s.agent = base.copy(keyHash = "other", apiAccess = false, paused = true)
        assertEquals("unauthorized", code { Grants.requireGrant(s, base, log) })
        s.agent = base.copy(apiAccess = false, paused = true, caps = emptySet())
        assertEquals("forbidden/api_access_off", code { Grants.requireGrant(s, base, log) })
        s.agent = base.copy(paused = true, caps = emptySet())
        assertEquals("forbidden/agent_paused", code { Grants.requireGrant(s, base, log) })
        s.agent = base.copy(caps = emptySet())
        assertEquals("agent_not_allowed", code { Grants.requireGrant(s, base, log) })
        assertTrue("no email gate before a refusal", s.emailCalls.isEmpty())
        s.agent = base
        assertEquals(base, Grants.requireGrant(s, base, log.copy(emailExempt = true)))
        assertEquals(listOf(true), s.emailCalls)
    }

    @Test fun linkPlumbingWaivesApiAccessAndPauseOnlyForALinkAgent() {
        val s = Fake(base.copy(apiAccess = false, paused = true))
        val plumbing = log.copy(linkPlumbing = true)
        assertEquals("forbidden/api_access_off", code { Grants.requireGrant(s, base, plumbing) })
        s.link = true
        Grants.requireGrant(s, base, plumbing)
        assertEquals("forbidden/api_access_off", code { Grants.requireGrant(s, base, log) })
    }

    @Test fun controlScopesAndConfiguration() {
        val s = Fake(base)
        val camera = log.copy(controlScope = "camera.capture")
        assertEquals("agent_not_allowed", code { Grants.requireGrant(s, base, camera) })
        s.granted = setOf("camera.capture"); s.blockers = listOf("feature_disabled")
        assertEquals("feature_disabled", code { Grants.requireGrant(s, base, camera) })
        s.blockers = emptyList(); s.listener = false
        assertEquals("listener_paused", code { Grants.requireGrant(s, base, camera) })
        s.listener = true
        assertEquals("unknown_scope", code { Grants.requireScope(s, base.id, "nope") })

        val config = log.copy(controlScope = Grants.OWNER_CONFIGURATION)
        s.superConfig = true
        try { Grants.requireGrant(s, base, config); fail() } catch (e: ApiError) {
            assertEquals("agent_not_allowed", e.code); assertEquals(false, e.extra["user_action_required"]) }
        s.granted = setOf(Grants.OWNER_CONFIGURATION)
        Grants.requireGrant(s, base, config)
        // A configuration call that already committed to pausing the listener checks the grant without the listener step.
        s.listener = false
        assertEquals("listener_paused", code { Grants.requireConfiguration(s, base) })
        Grants.requireConfiguration(s, base, allowListenerPaused = true)
        s.excluded = true
        assertEquals("forbidden", code { Grants.requireConfiguration(s, base, allowListenerPaused = true) })
        s.excluded = false; s.agent = base.copy(caps = setOf("log"))
        assertEquals("agent_not_allowed", code { Grants.requireConfiguration(s, base, allowListenerPaused = true) })
    }

    @Test fun relayAgentStepsInCodeOrder() {
        var taken = 0
        val rate = { taken++; true }
        val s = Fake(null)
        assertEquals("agent_revoked", code { Grants.requireRelayAgent(s, "a1", { "hash" }, true, false, rate) })
        s.agent = base
        var generationRead = false
        assertEquals("agent_revoked", code { Grants.requireRelayAgent(s, "a1", { generationRead = true; "hash" }, false, false, rate) })
        assertFalse("relay off refuses before the generation is read", generationRead)
        assertEquals("agent_revoked", code { Grants.requireRelayAgent(s, "a1", { "old" }, true, false, rate) })
        s.agent = base.copy(apiAccess = false)
        assertEquals("forbidden/api_access_off", code { Grants.requireRelayAgent(s, "a1", { "hash" }, true, true, rate) })
        assertEquals("no allowance taken before API access", 0, taken)
        s.link = true
        Grants.requireRelayAgent(s, "a1", { "hash" }, true, true, rate)
        assertEquals(1, taken)
        s.agent = base
        assertEquals("rate_limited", code { Grants.requireRelayAgent(s, "a1", { "hash" }, true, false) { false } })
        s.agent = base.copy(caps = null)
        assertEquals("explicit_grants_required", code { Grants.requireRelayAgent(s, "a1", { "hash" }, true, false, rate) })
    }

    @Test fun apiAccessStepLeavesUnknownAgentsToTheCaller() {
        Grants.requireApiAccess(null)
        Grants.requireApiAccess(base)
        assertEquals("forbidden/api_access_off", code { Grants.requireApiAccess(base.copy(apiAccess = false)) })
    }
}
