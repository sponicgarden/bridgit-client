package cc.bridgit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ways key and agent logic can fail:
 * 1. Generated keys have the wrong length, alphabet or prefix, so scanners or the format check miss them.
 * 2. A 1.2 key (64 hex) or a near-miss string is accepted.
 * 3. The plaintext key ends up in the persisted JSON.
 * 4. Rotation leaves the old key working, or the new key not working.
 * 5. A rotated/removed key gets a plain 401 instead of its reason, or the reason outlives 7 days.
 * 6. Names: duplicates differing only by case, bad characters, more than 16 agents.
 * 7. Persisted state doesn't survive a reload (tombstones lost, so reasons vanish after a restart).
 * 8. scrub() misses a key inside other text.
 */
class KeysAndAgentsTest {
    private class MemStorage(var json: String? = null) : AgentStorage {
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private var now = 1_000_000L
    private fun registry(s: MemStorage = MemStorage()) = AgentRegistry(s, clock = { now })

    @Test fun firstUseCueOnlyOncePerKeyAndSurvivesRestart() {
        val storage = MemStorage()
        val r = registry(storage)
        val (agent, _) = r.add("Cue test", Connection.DIRECT)
        val seen = mutableListOf<String>()
        r.onFirstUse = { seen += it.id }
        r.recordUse("missing", "100.1.1.1")
        assertTrue(seen.isEmpty())
        r.recordUse(agent.id, "100.1.1.1")
        r.recordUse(agent.id, "100.1.1.1")
        assertEquals(listOf(agent.id), seen)
        val reloaded = registry(storage)
        reloaded.onFirstUse = { seen += it.id }
        reloaded.recordUse(agent.id, "100.1.1.1")
        assertEquals(1, seen.size)
        r.rotate(agent.id)
        r.recordUse(agent.id, "100.1.1.1")
        r.recordUse(agent.id, "100.1.1.1")
        assertEquals(2, seen.size)
    }

    @Test fun keyFormat() {
        repeat(200) {
            val k = Keys.generate()
            assertEquals(47, k.length)
            assertTrue(k, Keys.isWellFormed(k))
            assertTrue(Keys.PATTERN.matches(k))
        }
        assertNotEquals(Keys.generate(), Keys.generate())
    }

    @Test fun malformedKeysRejected() {
        val hex64 = "a".repeat(64)
        for (bad in listOf(null, "", hex64, "brg_", "brg_" + "A".repeat(42), "brg_" + "A".repeat(44), "brg_" + "A".repeat(42) + "=",
            "BRG_" + "A".repeat(43), " brg_" + "A".repeat(43), "brg_" + "A".repeat(42) + "+"))
            assertFalse("$bad", Keys.isWellFormed(bad))
        assertEquals(AuthResult.Invalid, registry().authenticate(hex64))
    }

    @Test fun hashMatchesOnlyTheRightKey() {
        val salt = Keys.newSalt()
        val k = Keys.generate()
        val h = Keys.hash(salt, k)
        assertTrue(Keys.matches(salt, h, k))
        assertFalse(Keys.matches(salt, h, Keys.generate()))
        assertFalse(Keys.matches(Keys.newSalt(), h, k))
    }

    @Test fun addAuthenticateAndNoPlaintextStored() {
        val s = MemStorage()
        val r = registry(s)
        val (a, key) = r.add("Grok", Connection.USERSPACE)
        assertEquals(a.id, (r.authenticate(key) as AuthResult.Ok).agent.id)
        assertEquals(key.take(8), a.keyPrefix)
        assertFalse("key persisted", s.json!!.contains(key))
        assertFalse("key persisted", s.json!!.contains(key.drop(4)))
        assertEquals(Connection.USERSPACE, registry(s).get(a.id)!!.connection)
    }

    @Test fun rotateKillsOldKeyWithReasonForSevenDays() {
        val s = MemStorage()
        val r = registry(s)
        val (a, old) = r.add("Muse", Connection.DIRECT)
        val (_, new) = r.rotate(a.id)
        assertEquals(AuthResult.Revoked("key_rotated", "Muse"), r.authenticate(old))
        assertTrue(r.authenticate(new) is AuthResult.Ok)
        // survives a restart
        assertEquals(AuthResult.Revoked("key_rotated", "Muse"), registry(s).authenticate(old))
        now += AgentRegistry.TOMBSTONE_MS + 1
        assertEquals(AuthResult.Invalid, registry(s).authenticate(old))
    }

    @Test fun removeGivesAgentRemoved() {
        val r = registry()
        val (a, key) = r.add("Codex", Connection.DIRECT)
        r.remove(a.id)
        assertNull(r.get(a.id))
        assertEquals(AuthResult.Revoked("agent_removed", "Codex"), r.authenticate(key))
    }

    @Test fun names() {
        val r = registry()
        r.add("Claude", Connection.DIRECT)
        assertNotNull(r.checkName("claude"))
        assertNotNull(r.checkName(""))
        assertNotNull(r.checkName(" lead"))
        assertNotNull(r.checkName("a".repeat(25)))
        assertNotNull(r.checkName("bad/name"))
        assertNull(r.checkName("Grok-2 beta_1"))
        assertNull("renaming to own name is fine", r.checkName("Claude", exceptId = r.byName("claude")!!.id))
        repeat(15) { r.add("A$it", Connection.DIRECT) }
        assertEquals(16, r.count)
        assertNotNull(r.checkName("Seventeen"))
    }

    @Test fun recordUseCountsAndPersistsEventually() {
        val s = MemStorage()
        val r = registry(s)
        val (a, _) = r.add("Grok", Connection.DIRECT)
        r.recordUse(a.id, "100.88.1.2")
        r.recordUse(a.id, "100.88.1.2")
        assertEquals(2, r.get(a.id)!!.requestCount)
        r.flush()
        assertEquals("100.88.1.2", registry(s).get(a.id)!!.lastSourceIp)
    }

    @Test fun scrub() {
        val k = Keys.generate()
        val text = "header Authorization: Bearer $k and again $k."
        val out = Keys.scrub(text)
        assertFalse(out.contains(k))
        assertTrue(out.contains("brg_[redacted]"))
    }

    @Test fun tailnetAddresses() {
        for (ok in listOf("100.64.0.1", "100.101.102.103", "100.127.255.255", "/100.88.1.2", "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12::5%tun0"))
            assertTrue(ok, Net.isTailnet(ok))
        for (bad in listOf("100.63.255.255", "100.128.0.1", "192.168.1.32", "127.0.0.1", "10.0.0.1", "fd7a:115c:a1e1::1",
            "::1", "face", "pixel.ts.net", "", null))
            assertFalse("$bad", Net.isTailnet(bad))
    }

    /** AGT-15: the single Developer switch split in two. An agent that had it keeps both; new ones get neither. */
    @Test fun developerSplitsIntoFixerModeAndSuperPowers() {
        val reg = registry()
        val (a, _) = reg.add("claude test 11-02-26", Connection.DIRECT)
        assertFalse(a.fixer); assertFalse(a.superPowers)
        val old = a.toJson().apply { remove("fixer_mode"); remove("super_powers"); put("developer", true) }
        val migrated = Agent.fromJson(old)
        assertTrue(migrated.has(Power.FIXER)); assertTrue(migrated.has(Power.SUPER))
        val plain = Agent.fromJson(a.toJson().apply { remove("fixer_mode"); remove("super_powers") })
        assertFalse(plain.fixer); assertFalse(plain.superPowers)
        // Once saved with the new keys, the old one no longer decides.
        val one = Agent.fromJson(a.copy(fixer = true).toJson().put("developer", true))
        assertTrue(one.fixer); assertFalse(one.superPowers)
        assertFalse(a.copy(fixer = true).toJson().has("developer"))
    }

    @Test fun eachPrivilegedEndpointAsksForTheRightPower() {
        val needs = cc.bridgit.app.api.Endpoints.all.filter { it.privileged }.associate { "${it.method} ${it.path}" to it.power }
        assertEquals(Power.FIXER, needs["POST /issues/{id}/status"])
        assertEquals(Power.FIXER, needs["POST /feedback/{id}/status"]) // the pre-rename alias
        // Reading your own issues and following up need nothing.
        assertNull(cc.bridgit.app.api.Endpoints.ISSUES_LIST.power)
        assertNull(cc.bridgit.app.api.Endpoints.ISSUES_FOLLOWUP.power)
        assertEquals(Power.SUPER, cc.bridgit.app.adb.AdbModule.SHELL.power)
        assertEquals(Power.SUPER, cc.bridgit.app.adb.AdbModule.GRANT.power)
        assertEquals(setOf("fixer_mode_off", "super_powers_off"), Power.values().map { it.reason }.toSet())
    }

    /** Agents switch models: each X-Bridgit-Model header updates the card; repeats and bad values change nothing. */
    @Test fun modelHeaderFollowsSwitches() {
        val r = registry()
        val (a, _) = r.add("probe", Connection.DIRECT)
        assertNotNull(r.reportModel(a.id, "claude-opus-5-5", "Claude Code"))
        assertEquals("claude-opus-5-5 · Claude Code", r.get(a.id)!!.modelLabel)
        assertNull(r.reportModel(a.id, "claude-opus-5-5", "Claude Code")) // same again: no audit line
        assertNotNull(r.reportModel(a.id, "claude-sonnet-5-5", null))   // switch, harness kept
        assertEquals("claude-sonnet-5-5 · Claude Code", r.get(a.id)!!.modelLabel)
        assertNull(r.reportModel(a.id, "", null))
        assertNull(r.reportModel(a.id, "x".repeat(61), null))
        assertNull(r.reportModel(a.id, "bad\nmodel", null))
        assertNull(r.reportModel("nope", "claude-opus-5-5", null))
        assertEquals("claude-sonnet-5-5", r.get(a.id)!!.model)
    }
}
