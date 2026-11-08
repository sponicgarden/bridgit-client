package cc.bridgit.app.portal

import cc.bridgit.app.api.ApiError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PortalUpdatesTest {
    private fun bad(body: String) {
        try { PortalUpdatesLogic.parseReport(JSONObject(body), 1L); fail("accepted $body") } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
    }

    @Test fun newerComparesBuildNumbers() {
        assertTrue(PortalUpdatesLogic.newer("2611072030", "2611071200"))
        assertFalse(PortalUpdatesLogic.newer("2611071200", "2611071200"))
        assertFalse(PortalUpdatesLogic.newer(null, "2611071200"))
        assertFalse(PortalUpdatesLogic.newer("2611072030", "x"))
    }

    @Test fun mergeKeepsPendingPromptAndLatestSwitch() {
        val a = PortalUpdatesLogic.merge(null, null, true, 10)
        val b = PortalUpdatesLogic.merge(a, false, false, 20)
        assertEquals("pur_20", b.id)
        assertEquals(false, b.prompts)
        assertTrue(b.promptNow)
        val c = PortalUpdatesLogic.merge(b, null, false, 30)
        assertEquals(false, c.prompts)
    }

    @Test fun requestRoundTrips() {
        val r = PortalUpdatesLogic.merge(null, true, false, 5)
        assertEquals(r, PortalUpdatesLogic.parseRequest(r.json().toString()))
        val n = PortalUpdatesLogic.merge(null, null, true, 6)
        assertNull(PortalUpdatesLogic.parseRequest(n.json().toString())!!.prompts)
    }

    @Test fun reportIsValidatedAndStored() {
        val r = PortalUpdatesLogic.parseReport(JSONObject("""{"prompts_enabled":true,"supported":true,"installed_build":"2611071200","latest_build":"2611072030","last_result":"prompted","ack":"pur_99"}"""), 7)
        assertTrue(r.updateReady)
        assertEquals(r, PortalUpdatesLogic.parseStoredReport(PortalUpdatesLogic.storeReport(r).toString()))
        bad("""{"supported":true}""")
        bad("""{"prompts_enabled":true,"supported":true,"installed_build":"1.0"}""")
        bad("""{"prompts_enabled":true,"supported":true,"last_result":"installed"}""")
        bad("""{"prompts_enabled":true,"supported":true,"ack":"x"}""")
        bad("""{"prompts_enabled":true,"supported":true,"token":"secret"}""")
    }

    @Test fun portalPairingsMayReportUpdates() {
        assertTrue("POST /v1/portal/updates" in PortalData.ROUTES)
        assertTrue(PortalEndpoints.UPDATES in PortalEndpoints.ALL)
    }
}
