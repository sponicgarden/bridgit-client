package cc.bridgit.app.core

import cc.bridgit.app.outbound.Outbound
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ways pausing can fail:
 * 1. A paused agent comes back unpaused after a restart (the flag isn't persisted), or old records fail to load.
 * 2. Pausing changes the key, so the agent gets "wrong key" instead of "paused".
 * 3. Paused external calls still leave the phone through SafeHttp or an explicit hook.
 * 4. Calls to the owner's own tailnet machines are refused too (callbacks break).
 */
class PauseTest {
    private class MemStorage(var json: String? = null) : AgentStorage {
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    @After fun reset() { Outbound.paused = { false }; Outbound.detach(); SafeHttp.resolverForTests = null }

    @Test fun pausedAgentSurvivesReloadAndKeepsItsKey() {
        val s = MemStorage()
        val r = AgentRegistry(s)
        val (a, key) = r.add("Claude", Connection.DIRECT)
        assertFalse(a.paused)
        r.update(a.id) { it.copy(paused = true) }
        val again = AgentRegistry(s)
        assertTrue(again.get(a.id)!!.paused)
        val auth = again.authenticate(key)
        assertTrue(auth is AuthResult.Ok && auth.agent.paused)
    }

    @Test fun recordsWithoutTheFlagLoadUnpaused() {
        val (a, _) = AgentRegistry(MemStorage()).add("Codex", Connection.DIRECT)
        val old = a.copy(paused = true).toJson().apply { remove("paused") }
        assertFalse(old.has("paused"))
        assertFalse(Agent.fromJson(old).paused)
        assertTrue(Agent.fromJson(a.copy(paused = true).toJson()).paused)
    }

    @Test fun pausedExternalCallsNeverLeave() {
        val recorded = mutableListOf<cc.bridgit.app.outbound.OutboundEntry>()
        Outbound.attach { recorded += it }
        Outbound.paused = { true }
        var resolved = false
        SafeHttp.resolverForTests = { resolved = true; emptyList() }
        try {
            SafeHttp.get("https://example.com/x", SafeHttp.Policy.PUBLIC, 2_000, 1_000)
            fail("should be refused")
        } catch (e: SafeHttp.Failure) {
            assertEquals(SafeHttp.Reason.PAUSED, e.reason)
        }
        assertFalse("no DNS lookup while paused", resolved)
        var ran = false
        try { Outbound.call("https://example.com/y") { ran = true }; fail("should be refused") } catch (_: Outbound.Paused) {}
        assertFalse(ran)
        assertTrue(recorded.isNotEmpty() && recorded.all { it.status == 0 && it.error!!.startsWith("paused") })
    }

    @Test fun tailnetCallsStillGoWhilePaused() {
        Outbound.paused = { true }
        SafeHttp.resolverForTests = { emptyList() }
        try {
            SafeHttp.get("http://agent.tail1234.ts.net:9000/cb", SafeHttp.Policy.TAILNET, 2_000, 1_000, extraPort = 9000)
            fail("expected a DNS failure")
        } catch (e: SafeHttp.Failure) {
            assertEquals(SafeHttp.Reason.DNS, e.reason)
        }
    }
}
