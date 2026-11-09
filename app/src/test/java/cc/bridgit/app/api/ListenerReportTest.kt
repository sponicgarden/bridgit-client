package cc.bridgit.app.api

import cc.bridgit.app.BridgeApp.ListenerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0092: health's listener block tells the connection ladder where the listener stands. */
class ListenerReportTest {
    private val ts = listOf("100.94.27.79")

    @Test fun states() {
        assertEquals("stopped", ListenerReport.state(ListenerState(running = false, boundTo = ts), ts))
        assertEquals("listening", ListenerReport.state(ListenerState(running = true, boundTo = ts), ts))
        assertEquals("waiting_for_tailscale", ListenerReport.state(ListenerState(running = true), emptyList()))
        assertEquals("waiting_for_tailscale", ListenerReport.state(ListenerState(running = true), ts))
        assertEquals("bind_failed", ListenerReport.state(ListenerState(running = true, bindError = "BindException: in use"), ts))
        assertEquals("rebinding", ListenerReport.state(ListenerState(running = true, boundTo = ts), listOf("100.64.1.2")))
        assertEquals("rebinding", ListenerReport.state(ListenerState(running = true, boundTo = ts), emptyList()))
        assertEquals("listening", ListenerReport.state(ListenerState(running = true, boundTo = listOf("fd7a:115c:a1e0::1", "100.94.27.79")), listOf("100.94.27.79", "fd7a:115c:a1e0::1")))
    }

    @Test fun json() {
        val j = ListenerReport.json(ListenerState(running = true, boundTo = ts, port = 8734, magicDns = "pixel.tail1234.ts.net"), ts, 8734, true)
        assertEquals("listening", j.getString("state"))
        assertEquals("tailscale", j.getString("binds"))
        assertEquals("100.94.27.79", j.getJSONArray("bound_to").getString(0))
        assertEquals(8734, j.getInt("port"))
        assertTrue(j.getBoolean("on_tailnet"))
        assertTrue(j.isNull("bind_error"))
        assertEquals(Learn.CONNECTION_URL, j.getString("ladder"))
        val down = ListenerReport.json(ListenerState(running = true), emptyList(), 8734, false)
        assertFalse(down.getBoolean("on_tailnet")); assertFalse(down.getBoolean("tailscale_installed"))
        assertTrue(down.isNull("magic_dns"))
    }
}
