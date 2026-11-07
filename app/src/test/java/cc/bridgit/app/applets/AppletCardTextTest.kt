package cc.bridgit.app.applets

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The Installed applets card's words (design pass, owner copy 5 Nov 2026): plurals, device wording, no raw ids. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppletCardTextTest {
    private val zone = ZoneId.of("UTC")
    private val t = AppletCardText(RuntimeEnvironment.getApplication(), zone)

    @Test fun pluralsForChecksChangesPlacesAndIntervals() {
        val now = ZonedDateTime.of(2026, 11, 5, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
        val at = now - 30 * 60_000L
        assertTrue(t.checksLine(1, at, now).startsWith("1 check · Last check at "))
        assertTrue(t.checksLine(2, at, now).startsWith("2 checks · Last check at "))
        assertEquals("No checks yet", t.checksLine(0, null, now))
        assertEquals("2 checks", t.checksLine(2, null, now))
        assertTrue(t.lastCheck(now - 86_400_000L, now).startsWith("Last check yesterday at "))
        assertTrue(t.lastCheck(now - 5 * 86_400_000L, now).startsWith("Last check on "))
        assertEquals("1 change", t.tallyLine(null, 1)); assertEquals("2 changes", t.tallyLine(null, 2)); assertEquals("No changes yet", t.tallyLine(null, 0))
        assertEquals("Pot boiling over once today.", t.tallyLine("Pot boiling over once today.", 5))
        assertEquals("1 place", t.places(1)); assertEquals("2 places", t.places(2))
        assertEquals("Every minute", t.every(60)); assertEquals("Every 10 minutes", t.every(600)); assertEquals("Every 30 seconds", t.every(30))
        assertEquals("Every hour", t.every(3600)); assertEquals("Every 2 days", t.every(172_800))
        assertEquals("Allowed: 5 minutes–1 day · About $0.36 a month", t.allowedLine(300, 86_400, JSONObject().put("l2", 0.36), listOf("l2" to "month"), free = false))
        assertEquals("Fixed interval: 30 seconds", t.allowedLine(30, 30, null, listOf("l1" to "day"), free = false))
        assertEquals("About $0.01 a day", t.cost(JSONObject().put("l1", 0.01).put("l2", 0.3), listOf("l2" to "month", "l1" to "day"), free = false))
        assertEquals("Under $0.01 a day", t.cost(JSONObject().put("l1", 0.0034), listOf("l1" to "day"), free = false))
        assertEquals("Free (runs on the device)", t.cost(JSONObject().put("l1", 0), listOf("l1" to "day"), free = true))
    }

    @Test fun limitsReadInPeriodOrderWithoutIds() {
        val pers = t.byPeriod(listOf("month", "day", "hour", "day")) { it }
        assertEquals(listOf("hour", "day", "day", "month"), pers)
        assertEquals(listOf("This hour", "Today · limit 1", "Today · limit 2", "This month"), t.periodHeaders(pers))
        assertEquals(listOf("Hourly limit", "Daily limit 1", "Daily limit 2", "Monthly limit"), t.fieldLabels(pers, ceiling = false))
        assertEquals(listOf("Daily control agent maximum"), t.fieldLabels(listOf("day"), ceiling = true))
        val b = t.limitBlock("Today", JSONObject().put("id", "l1").put("usd", 1).put("per", "day").put("used_usd", 0.25).put("top_up_usd", 0.5).put("forecast_usd", 0.47).put("estimate", true))
        assertEquals("$0.25 of $1.50 used", b.used)
        assertEquals("Includes a $0.50 top-up", b.topUp)
        assertEquals("Forecast: $0.47 · estimate", b.forecast)
        assertEquals(AppletCardText.Tone.NORMAL, b.tone)
        val out = t.limitBlock("Today", JSONObject().put("usd", 1).put("used_usd", 1.4))
        assertEquals(1f, out.fraction); assertEquals(AppletCardText.Tone.ERROR, out.tone); assertEquals("$1.40 of $1.00 used", out.used)
        assertEquals("Forecast unavailable", out.forecast)
        // A zero limit never divides by zero.
        assertEquals(0f, t.limitBlock("Today", JSONObject().put("usd", 0).put("used_usd", 0.2)).fraction)
        assertEquals(AppletCardText.Tone.WARNING, t.limitBlock("Today", JSONObject().put("usd", 1).put("used_usd", 0.5).put("forecast_usd", 1.2)).tone)
    }

    @Test fun ownerCopyUsesDeviceWording() {
        assertEquals("On track", t.status("ok", null).first)
        val (title, detail, _) = t.status("out", null)
        assertEquals("Budget used up", title)
        assertEquals("The applet is paused until the limit resets, or raise the limit.", detail)
        assertTrue(t.status("out", 1_800_000_000_000L).second!!.matches(Regex("The applet is paused\\. It starts again .+, or raise the limit\\.")))
        assertEquals("Budget status unavailable", t.status(null, null).first)
        assertEquals("This device: $0.20 today · $1.10 this month", t.thisDevice(0.2, 1.1))
        assertEquals(listOf("Control agents can't change this budget."), t.agentLines(false, listOf("l1" to "day"), null))
        // No ceiling set: the backend's real default, $100 a limit.
        assertEquals(listOf("Control agents can change this budget.", "Control agent maximum: $5.00 a day · $100.00 a month."),
            t.agentLines(true, listOf("l1" to "day", "l2" to "month"), JSONObject().put("l1", 5)))
        val res = RuntimeEnvironment.getApplication().resources
        val ids = res.getIdentifier("applets_budget_scope", "string", RuntimeEnvironment.getApplication().packageName)
        assertEquals("Includes all devices working for this applet", res.getString(ids))
        for (name in listOf("applets_budget_scope", "applets_editor_intro", "applets_waiting_totals", "applets_portal_off", "applets_portal_waiting",
                "applets_saved_offline", "applets_saved_not_synced", "applets_remove_body", "applets_free", "applets_used_up_until", "applets_portal_show_counts")) {
            val s = res.getString(res.getIdentifier(name, "string", RuntimeEnvironment.getApplication().packageName))
            assertFalse("$name says phone: $s", s.contains("phone", ignoreCase = true))
        }
    }

    @Test fun actionLabelsAndChangeSummariesHideIds() {
        val labels = t.actionLabels(listOf("main"), mapOf("main" to listOf("main")), "Kitchen cleanliness")
        assertEquals("Kitchen cleanliness", labels["main"])
        val two = t.actionLabels(listOf("main", "people"), mapOf("people" to listOf("Who is in the kitchen")), "Kitchen")
        assertEquals("Scheduled action 1", two["main"]); assertEquals("Who is in the kitchen", two["people"])
        // The device test's backend line: the action id becomes its label (whole words only).
        assertEquals("Changed Kitchen cleanliness from every 10 min to every 20 min",
            t.changeSummary("Changed main from every 10 min to every 20 min", mapOf("main" to "Kitchen cleanliness")))
        assertEquals("Raised the daily limit from $1 to $2", t.changeSummary("Raised the daily limit from $1 to $2", mapOf("main" to "Kitchen cleanliness")))
        assertEquals("Mainly changed", t.changeSummary("Mainly changed", mapOf("main" to "X")))
        assertEquals("Set daily limit to $3", t.changeSummary("Set l1 to $3", emptyMap(), mapOf("l1" to "daily limit")))
        assertEquals("Budget changed", t.changeSummary("", emptyMap()))
        assertEquals("You", t.actor("owner", "Owner"))
        assertEquals("Claude", t.actor("agent", "Claude"))
        assertEquals("A control agent", t.actor("agent", null))
        assertEquals("The applet", t.actor("package", null))
    }

    @Test fun amountsAcceptTheDeviceDecimalSeparator() {
        assertEquals(0.5, AppletCardText.parseAmount("0,5", Locale.GERMANY)!!, 1e-9)
        assertEquals(2.25, AppletCardText.parseAmount("$2.25", Locale.US)!!, 1e-9)
        assertNull(AppletCardText.parseAmount("", Locale.US))
        assertNull(AppletCardText.parseAmount("0", Locale.US))
        assertNull(AppletCardText.parseAmount("100.01", Locale.US))
        assertNull(AppletCardText.parseAmount("abc", Locale.US))
        assertEquals("0,5", AppletCardText.amountText(0.5, Locale.GERMANY))
        assertEquals("10", AppletCardText.amountText(10.0, Locale.US))
    }
}
