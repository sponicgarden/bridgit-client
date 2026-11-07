package cc.bridgit.app.core

import cc.bridgit.app.portal.PortalData
import cc.bridgit.app.relay.RelayDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ways the device lock and new-place tracking can fail:
 * 1. A stolen key works from a second Tailscale device.
 * 2. The same device is refused because the two server paths spell its address differently.
 * 3. Existing agents are left unlocked after the upgrade (or locked to a relay label).
 * 4. Rotating a key leaves it locked to the old machine, so the new setup can't be used.
 * 5. The lock or the known networks are lost on restart.
 * 6. The first relay network raises an alert, or a new one doesn't.
 */
class DeviceLockTest {
    private class MemStorage(var json: String? = null) : AgentStorage {
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    @Test fun firstDeviceBindsAndOthersAreRefused() {
        val s = MemStorage()
        val r = AgentRegistry(s)
        val (a, _) = r.add("Claude", Connection.DIRECT)
        assertTrue(r.checkDevice(a.id, "100.101.1.2"))
        assertTrue(r.checkDevice(a.id, "100.101.1.2"))
        assertFalse(r.checkDevice(a.id, "100.101.9.9"))
        assertEquals(listOf("100.101.1.2"), AgentRegistry(s).get(a.id)!!.boundIps)
        assertFalse(AgentRegistry(s).checkDevice(a.id, "100.101.9.9"))
    }

    @Test fun allowAddsADeviceAndUnlockAndRotateClear() {
        val r = AgentRegistry(MemStorage())
        val (a, _) = r.add("Codex", Connection.DIRECT)
        r.checkDevice(a.id, "100.101.1.2")
        r.allowDevice(a.id, "fd7a:115c:a1e0::1")
        assertTrue(r.checkDevice(a.id, "fd7a:115c:a1e0::1"))
        (1..6).forEach { r.allowDevice(a.id, "100.64.0.$it") }
        assertEquals(AgentRegistry.MAX_BOUND, r.get(a.id)!!.boundIps.size)
        r.unlockDevice(a.id)
        assertTrue(r.checkDevice(a.id, "100.101.7.7"))
        r.rotate(a.id)
        assertTrue(r.get(a.id)!!.boundIps.isEmpty())
    }

    @Test fun addressesAreSpelledOneWay() {
        assertEquals("100.101.1.2", Net.normalise("/100.101.1.2"))
        assertEquals(Net.normalise("fd7a:115c:a1e0:0:0:0:0:1"), Net.normalise("[FD7A:115C:A1E0::1%tailscale0]"))
    }

    @Test fun agentsFromBeforeTheLockAreLockedToTheirLastTailscaleAddress() {
        val (a, _) = AgentRegistry(MemStorage()).add("Old", Connection.DIRECT)
        val tail = a.copy(lastSourceIp = "100.70.1.1").toJson().apply { remove("bound_ips") }
        assertEquals(listOf("100.70.1.1"), Agent.fromJson(tail).boundIps)
        val relay = a.copy(lastSourceIp = "relay:connector").toJson().apply { remove("bound_ips") }
        assertTrue(Agent.fromJson(relay).boundIps.isEmpty())
        val never = a.toJson().apply { remove("bound_ips") }
        assertTrue(Agent.fromJson(never).boundIps.isEmpty())
    }

    @Test fun onlyASecondNetworkIsNews() {
        val s = MemStorage()
        val r = AgentRegistry(s)
        val (a, _) = r.add("Relayed", Connection.RELAY)
        assertFalse(r.notePlace(a.id, "AS13335 US"))
        assertFalse(r.notePlace(a.id, "AS13335 US"))
        assertTrue(r.notePlace(a.id, "AS7922 US"))
        assertFalse(AgentRegistry(s).notePlace(a.id, "AS7922 US"))
    }

    @Test fun relayCallerBecomesAPlace() {
        assertNull(RelayDispatcher.placeOf(null))
        val p = RelayDispatcher.placeOf(JSONObject().put("ip", "1.2.3.4").put("asn", 13335).put("country", "US").put("as_org", "Cloudflare"))!!
        assertEquals("AS13335 US", p.first)
        assertEquals("AS13335 Cloudflare, US", p.second)
        assertEquals("1.2.3.4", RelayDispatcher.placeOf(JSONObject().put("ip", "1.2.3.4"))!!.first)
        assertNull(RelayDispatcher.placeOf(JSONObject()))
    }

    @Test fun singleUseLinksSayClaim() {
        val single = PortalData.parsePairLink(PortalData.pairLink("https://r.example", "device-1234", "rly_x", "A11", claim = true))!!
        assertEquals("1", single["claim"])
        assertNull(PortalData.parsePairLink(PortalData.pairLink("https://r.example", "device-1234", "rly_x", "A11"))!!["claim"])
    }
}
