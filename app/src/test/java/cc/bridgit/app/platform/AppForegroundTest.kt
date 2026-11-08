package cc.bridgit.app.platform

import cc.bridgit.app.api.ApiError
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppForegroundTest {
    @Test fun reasonMustBeBoundedPrintableText() {
        assertEquals("Morning screenshot recovery", AppForeground.validate(JSONObject().put("reason", "Morning screenshot recovery")))
        for (value in listOf<Any>("", " ", "x".repeat(257), "line\nforged audit", 12, JSONObject.NULL)) {
            try { AppForeground.validate(JSONObject().put("reason", value)); fail("accepted invalid reason") } catch (_: ApiError) { }
        }
        try { AppForeground.validate(JSONObject().put("reason", "ok").put("package", "other.app")); fail("accepted arbitrary target") } catch (_: ApiError) { }
    }
    @Test fun cooldownIncludesClockRollbackAndExactBoundary() {
        assertEquals(0L, AppForeground.remaining(100, null))
        assertEquals(1_000L, AppForeground.remaining(100, 100))
        assertEquals(1_000L, AppForeground.remaining(99, 100))
        assertEquals(1L, AppForeground.remaining(1_099, 100))
        assertEquals(0L, AppForeground.remaining(1_100, 100))
    }
    @Test fun targetsHaveClosedShapeAndDoNotAcceptUrls() {
        for (target in listOf(JSONObject().put("type","external").put("id","x"), JSONObject().put("type","screen"), JSONObject().put("type","screen").put("id","home").put("url","https://example.com"))) {
            try { AppForeground.validate(JSONObject().put("reason","Switch").put("target",target)); fail("invalid target accepted") } catch (_: ApiError) { }
        }
        AppForeground.validate(JSONObject().put("reason","Switch").put("target",JSONObject().put("type","portal").put("id","pkg.example")))
    }
    @Test fun historicalCardSelectionPreservesStatusAndOwnerBoundary() {
        for (status in cc.bridgit.app.interact.IStatus.values()) {
            val card=cc.bridgit.app.interact.Interaction(1,"caller","Caller",cc.bridgit.app.interact.IKind.HTML,"Card",null,null,emptyList(),"<p>Saved</p>","normal",false,status,JSONObject().put("saved",true),1,2,1,2,3)
            assertSame(card,AppForeground.cardFor("caller",card))
            assertEquals(status,card.status)
            assertTrue(card.answer!!.getBoolean("saved"))
            try { AppForeground.cardFor("other",card); fail("cross-agent card accepted") } catch (_: ApiError) { }
        }
    }
    @Test fun dedicatedGrantAndCanonicalToolAreDiscoverable() {
        assertEquals("app.foreground", Commands.scope("app.foreground"))
        assertTrue("app.foreground" in Commands.mutations)
        assertTrue(Capabilities.all.any { it.id == "app.foreground" })
        assertTrue(ToolSchemas.forTool("app.foreground").toString().contains("reason"))
    }

    @Test fun diagnosticsTargetsUseTheNewNames() {
        assertEquals(cc.bridgit.app.R.id.home, AppForeground.SCREENS["device_diagnostics"])
        assertEquals(cc.bridgit.app.R.id.networkDevices, AppForeground.SCREENS["network_diagnostics"])
        // Existing ids keep working for agents that already use them.
        assertEquals(cc.bridgit.app.R.id.home, AppForeground.SCREENS["home"])
        assertEquals(cc.bridgit.app.R.id.networkDevices, AppForeground.SCREENS["network_devices"])
        assertTrue(AppForeground.SCREEN_NOTES.keys.all { it in AppForeground.SCREENS })
        assertTrue(AppForeground.SCREEN_NOTES.getValue("device_diagnostics").contains("Device Diagnostics"))
        assertTrue(AppForeground.SCREEN_NOTES.getValue("network_diagnostics").contains("/v1/lan/discover"))
        AppForeground.validate(JSONObject().put("reason","Owner asked").put("target",JSONObject().put("type","screen").put("id","device_diagnostics")))
    }
}
