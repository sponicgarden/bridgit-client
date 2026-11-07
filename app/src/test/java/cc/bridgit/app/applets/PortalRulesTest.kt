package cc.bridgit.app.applets

import cc.bridgit.app.interact.CardRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PortalRulesTest {
    private fun bad(f: () -> Unit) { try { f(); fail("expected Bad") } catch (_: PortalRules.Bad) {} }

    @Test fun functionNamesAndSpecs() {
        val f = PortalRules.function("show_day", """{"description":"Show one day","params":{"date":"YYYY-MM-DD"}}""")
        assertEquals("show_day", f.name)
        assertEquals("Show one day", f.description)
        assertEquals("YYYY-MM-DD", f.params!!.getString("date"))
        assertEquals("", PortalRules.function("refresh", null).description)
        assertNull(PortalRules.function("refresh", "{}").params)
        bad { PortalRules.function("Show", "{}") }
        bad { PortalRules.function("1x", "{}") }
        bad { PortalRules.function("a".repeat(41), "{}") }
        bad { PortalRules.function("ok", "[1]") }
        bad { PortalRules.function("ok", """{"params":"text"}""") }
        bad { PortalRules.function("ok", """{"description":"${"x".repeat(17 * 1024)}"}""") }
        assertEquals(200, PortalRules.function("ok", """{"description":"${"x".repeat(300)}"}""").description.length)
    }

    @Test fun exposeReplacesAndCaps() {
        var fs = emptyList<PortalRules.Function>()
        for (i in 0 until PortalRules.MAX_FUNCTIONS) fs = PortalRules.withFunction(fs, PortalRules.function("f$i", null))
        fs = PortalRules.withFunction(fs, PortalRules.function("f3", """{"description":"again"}"""))
        assertEquals(PortalRules.MAX_FUNCTIONS, fs.size)
        assertEquals("again", fs[3].description)
        bad { PortalRules.withFunction(fs, PortalRules.function("extra", null)) }
    }

    @Test fun stateMustBeSmallJson() {
        assertTrue(PortalRules.state("""{"view":"day"}""") is JSONObject)
        assertTrue(PortalRules.state("[1,2]") is JSONArray)
        assertNull(PortalRules.state("\"text\""))
        assertNull(PortalRules.state("{bad"))
        assertNull(PortalRules.state(null))
        assertNull(PortalRules.state("""{"x":"${"y".repeat(17 * 1024)}"}"""))
    }

    @Test fun agentCalls() {
        val fs = listOf(PortalRules.function("show_day", null))
        val (fn, args, ms) = PortalRules.call(JSONObject("""{"function":"show_day","args":{"date":"2026-11-05"}}"""), fs)
        assertEquals("show_day", fn)
        assertEquals("2026-11-05", (args as JSONObject).getString("date"))
        assertEquals(10_000L, ms)
        assertEquals(30_000L, PortalRules.call(JSONObject("""{"function":"show_day","timeout_s":30}"""), fs).third)
        bad { PortalRules.call(JSONObject("{}"), fs) }
        bad { PortalRules.call(JSONObject("""{"function":"show_day","args":"x"}"""), fs) }
        bad { PortalRules.call(JSONObject("""{"function":"show_day","timeout_s":31}"""), fs) }
        try { PortalRules.call(JSONObject("""{"function":"nope"}"""), fs); fail() } catch (e: PortalRules.NotExposed) { assertEquals(listOf("show_day"), e.known) }
    }

    @Test fun pageReplies() {
        assertEquals(5, PortalRules.reply("""{"ok":true,"result":5}""")!!.getInt("result"))
        val no = PortalRules.reply("""{"ok":false,"error":"no such day"}""")!!
        assertFalse(no.getBoolean("ok")); assertEquals("no such day", no.getString("error"))
        assertTrue(PortalRules.reply("""{"ok":true}""")!!.isNull("result"))
        assertNull(PortalRules.reply("nope"))
    }

    @Test fun openWait() {
        assertEquals(5000L, PortalRules.openWaitMs(JSONObject()))
        assertEquals(0L, PortalRules.openWaitMs(JSONObject("""{"wait_s":0}""")))
        bad { PortalRules.openWaitMs(JSONObject("""{"wait_s":16}""")) }
    }

    @Test fun portalShimOnlyInPortals() {
        val card = CardRules.prepare("<p>hi</p>")
        assertTrue(card.contains(PortalRules.STUB)); assertFalse(card.contains("BridgitPortalNative"))
        val portal = CardRules.prepare("<p>hi</p>", portal = true)
        assertTrue(portal.contains(PortalRules.SHIM)); assertFalse(portal.contains(PortalRules.STUB))
        // The forced CSP still comes first.
        assertTrue(portal.indexOf("Content-Security-Policy") < portal.indexOf("BridgitPortalNative"))
    }
}
