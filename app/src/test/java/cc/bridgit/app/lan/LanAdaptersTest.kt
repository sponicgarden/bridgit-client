package cc.bridgit.app.lan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LanAdaptersTest {
    private fun device(adapter: String, identity: String = "") = LanDevice("net:test", "Desk", "192.168.1.20", adapter, "agent-a", "secret-user", "secret-password", true, 123, identity)
    @Test fun shellyVerifiesIdentityAndChannelBeforeIssuingOnlyFixedRelayAction() {
        val paths = mutableListOf<String>()
        val adapter = ShellyAdapter { path, body, auth ->
            assertTrue(auth); assertNull(body); paths += path
            when(path) {
                "/shelly" -> JSONObject("""{"type":"SHSW-1","mac":"AABBCCDDEEFF"}""")
                "/status" -> JSONObject("""{"relays":[{"ison":false}],"password":"secret-password"}""")
                "/relay/0?turn=on" -> JSONObject().put("ison", true)
                else -> error("Unexpected path: $path")
            }
        }
        val out = adapter.execute(device("shelly_gen1", "aabbccddeeff"), "relay_on", 0)
        assertEquals(listOf("/shelly", "/status", "/relay/0?turn=on"), paths)
        assertTrue(out.getBoolean("on")); assertFalse(out.toString().contains("secret-"))
        paths.clear()
        assertThrows(IllegalArgumentException::class.java) { adapter.execute(device("shelly_gen1", "001122334455"), "relay_off", 0) }
        assertEquals(listOf("/shelly"), paths)
        paths.clear()
        assertThrows(IllegalArgumentException::class.java) { adapter.execute(device("shelly_gen1"), "relay_on", 1) }
        assertEquals(listOf("/shelly", "/status"), paths)
    }
    @Test fun unsupportedOrUnconfirmedDeviceNeverReportsControlSuccess() {
        val adapter = ShellyAdapter { path, _, _ -> when(path) {
            "/shelly" -> JSONObject("""{"type":"SHSW-1","mac":"AABBCCDDEEFF"}""")
            "/status" -> JSONObject("""{"relays":[{"ison":false}]}""")
            else -> JSONObject().put("ison", false)
        } }
        assertThrows(IllegalArgumentException::class.java) { adapter.execute(device("shelly_gen1"), "relay_on", 0) }
        val gen2 = ShellyAdapter { _, _, _ -> JSONObject("""{"type":"SHSW-1","mac":"AABBCCDDEEFF","gen":2}""") }
        assertThrows(IllegalArgumentException::class.java) { gen2.execute(device("shelly_gen1"), "status", 0) }
    }
    @Test fun routerLogsInUsesReadOnlyDhcpAclAndDestroysSessionWithoutReturningIt() {
        val calls = mutableListOf<String>(); val session = "a".repeat(32)
        val adapter = OpenWrtAdapter { path, request, auth ->
            assertEquals("/ubus", path); assertFalse(auth)
            val p = request!!.getJSONArray("params"); val call = "${p.getString(1)}.${p.getString(2)}"; calls += call
            val out = when(call) {
                "session.login" -> { assertEquals("secret-password", p.getJSONObject(3).getString("password")); JSONObject().put("ubus_rpc_session", session) }
                "dhcp.ipv4leases" -> {
                    assertEquals(session, p.getString(0))
                    JSONObject().put("dhcp_leases", JSONArray().put(JSONObject().put("ipaddr", "192.168.1.25").put("hostname", "Lamp").put("macaddr", "aa:bb:cc:dd:ee:ff").put("password", "secret-password")))
                }
                "session.destroy" -> JSONObject()
                else -> error("Arbitrary router operation: $call")
            }
            JSONObject().put("result", JSONArray().put(0).put(out))
        }
        val out = adapter.execute(device("openwrt_ubus"), "dhcp_leases", 0)
        assertEquals(listOf("session.login", "dhcp.ipv4leases", "session.destroy"), calls)
        assertEquals("not_checked", out.getJSONArray("leases").getJSONObject(0).getString("availability"))
        assertFalse(out.toString().contains(session)); assertFalse(out.toString().contains("secret-"))
    }
    @Test fun routerAclFailureStillClosesItsSession() {
        val calls = mutableListOf<String>()
        val adapter = OpenWrtAdapter { _, request, _ ->
            val p = request!!.getJSONArray("params"); val op = p.getString(2); calls += op
            JSONObject().put("result", if (op == "login") JSONArray().put(0).put(JSONObject().put("ubus_rpc_session", "b".repeat(32))) else JSONArray().put(if (op == "board") 6 else 0))
        }
        assertThrows(IllegalArgumentException::class.java) { adapter.execute(device("openwrt_ubus"), "status", 0) }
        assertEquals(listOf("login", "board", "destroy"), calls)
    }
    @Test fun removingEnrollmentBetweenVerificationAndControlPreventsMutation() {
        var enrolled = true
        val paths = mutableListOf<String>()
        val wire = LanTransport { path, _, _ ->
            paths += path
            if (path == "/shelly") JSONObject("""{"type":"SHSW-1","mac":"AABBCCDDEEFF"}""")
            else { enrolled = false; JSONObject("""{"relays":[{"ison":false}]}""") }
        }
        val adapter = ShellyAdapter(LanAdapters.guarded({ check(enrolled) { "Enrollment revoked" } }, wire))
        assertThrows(IllegalStateException::class.java) { adapter.execute(device("shelly_gen1"), "relay_on", 0) }
        assertEquals(listOf("/shelly", "/status"), paths)
    }
    @Test fun inventoryDoesNotLeakCredentialsOrClaimOnline() {
        val d = device("shelly_gen1")
        assertFalse(d.publicJson().toString().contains("secret-")); assertFalse(d.toString().contains("secret-"))
        assertEquals("not_checked", d.publicJson().getString("availability"))
        assertEquals("secret-password", LanDevice.read(d.stored()).password)
        assertEquals(listOf("status"), LanRules.actions("tapo_onvif"))
    }
}
