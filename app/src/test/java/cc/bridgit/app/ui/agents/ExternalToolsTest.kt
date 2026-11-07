package cc.bridgit.app.ui.agents

import cc.bridgit.app.outbound.OutboundEntry
import cc.bridgit.app.ui.agents.ExternalTools.Facts
import cc.bridgit.app.ui.agents.ExternalTools.Id
import cc.bridgit.app.ui.agents.ExternalTools.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ways the External tools list can mislead: a call filed under the wrong tool, a refused call shown as a call, the wrong state. */
class ExternalToolsTest {
    private fun e(service: String, ts: Long, status: Int = 200, error: String? = null) = OutboundEntry(ts = ts, host = "h", service = service,
        method = "POST", path = "/", purpose = "", agentId = null, agent = "phone", categories = emptyList(), bytesSent = 0,
        bytesReceived = 0, status = status, error = error, durationMs = 1, transport = "https")

    @Test fun servicesMapToTools() {
        assertEquals(Id.GLM, ExternalTools.of("GLM 5.3 Flash (OpenRouter via Bridgit)"))
        assertEquals(Id.JEV, ExternalTools.of("TypeSafe Jev (via Bridgit team backend)"))
        assertEquals(Id.CLEF, ExternalTools.of("Cloudflare Clef (via Bridgit team backend)"))
        assertEquals(Id.REPORTS, ExternalTools.of("Bridgit team backend"))
        assertEquals(Id.RELAY, ExternalTools.of("Bridgit relay"))
        assertEquals(Id.WATCH, ExternalTools.of("Bridgit remote view"))
        assertEquals(Id.UPDATES, ExternalTools.of("Bridgit updates"))
        assertNull(ExternalTools.of("MET Norway weather"))
    }

    @Test fun newestRealCallWins() {
        val calls = ExternalTools.lastCalls(listOf(
            e("TypeSafe Jev (via Bridgit team backend)", 10),
            e("TypeSafe Jev (via Bridgit team backend)", 30, status = 500),
            e("TypeSafe Jev (via Bridgit team backend)", 50, status = 0, error = "paused: External calls are paused"),
            e("Bridgit updates", 5),
        ))
        assertEquals(ExternalTools.LastCall(30, false), calls[Id.JEV])
        assertEquals(ExternalTools.LastCall(5, true), calls[Id.UPDATES])
        assertNull(calls[Id.CLEF])
    }

    @Test fun states() {
        assertEquals(State.PAUSED, ExternalTools.state(Id.JEV, Facts(true), paused = true))
        assertEquals(State.READY, ExternalTools.state(Id.JEV, Facts(true), paused = false))
        assertEquals(State.MISSING, ExternalTools.state(Id.JEV, Facts(false), paused = false))
        assertEquals(State.READY, ExternalTools.state(Id.CLEF, Facts(false, ownKeys = listOf("OpenRouter")), paused = false))
        assertEquals(State.OFF, ExternalTools.state(Id.RELAY, Facts(true, on = false), paused = false))
        assertEquals(State.ON, ExternalTools.state(Id.UPDATES, Facts(true), paused = false))
    }
}
