package cc.bridgit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0120: API access is on by default, survives a restart, and records from before the switch load with it on. */
class ApiAccessFlagTest {
    private class MemStorage(var json: String? = null) : AgentStorage {
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    @Test fun newAgentsHaveApiAccessAndTurningItOffKeepsTheKeyAndSurvivesReload() {
        val s = MemStorage()
        val r = AgentRegistry(s)
        val (a, key) = r.add("Claude", Connection.DIRECT)
        assertTrue(a.apiAccess)
        assertFalse(a.suspended)
        r.update(a.id) { it.copy(apiAccess = false) }
        val again = AgentRegistry(s)
        assertFalse(again.get(a.id)!!.apiAccess)
        assertTrue(again.get(a.id)!!.suspended)
        val auth = again.authenticate(key)
        assertTrue("still registered and paired", auth is AuthResult.Ok && !auth.agent.apiAccess)
    }

    @Test fun recordsWithoutTheFlagLoadWithApiAccessOn() {
        val (a, _) = AgentRegistry(MemStorage()).add("Codex", Connection.DIRECT)
        val old = a.copy(apiAccess = false).toJson().apply { remove("api_access") }
        assertTrue(Agent.fromJson(old).apiAccess)
        assertFalse(Agent.fromJson(a.copy(apiAccess = false).toJson()).apiAccess)
        assertEquals(true, a.toJson().getBoolean("api_access"))
    }
}
