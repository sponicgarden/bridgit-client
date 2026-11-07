package cc.bridgit.app.platform

import android.content.Intent
import android.os.BatteryManager
import cc.bridgit.app.*
import cc.bridgit.app.power.BatteryRules
import cc.bridgit.app.power.PowerState
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Power, battery and sun triggers and the flow's cooldown_s / max_runs_per_day (docs/applet-reach.md G and D3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PowerTriggerTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    @Before fun on() { app.settings.listenerEnabled = true }
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private fun rejects(part: String, block: () -> Unit) {
        try { block(); fail("Expected invalid_workflow") } catch (e: ApiError) { assertTrue(e.message, e.code in setOf("invalid_workflow", "invalid_parameter")); assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }
    private fun flow(trigger: JSONObject, vararg extra: Pair<String, Any?>) =
        o("name" to "Power cut", "trigger" to trigger, "steps" to a(o("type" to "event", "text" to a("Off for ", o("\$trigger" to "off_minutes"), " min")))).apply { extra.forEach { (k, v) -> put(k, v) } }
    private fun plugged(on: Boolean) {
        @Suppress("DEPRECATION") app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, if (on) BatteryManager.BATTERY_PLUGGED_AC else 0))
    }
    private fun active(def: JSONObject): Pair<String, String> {
        val agent = app.policy.addAgent("PowerTester" + (System.nanoTime() % 100000)).first.id
        app.platform.workflows.validate(def).strings("required_scopes").forEach { Capabilities.setEnabled(app, it, true); app.platform.grant(agent, it, System.currentTimeMillis() + 3_600_000) }
        val id = app.platform.workflows.propose(agent, def).getString("id"); app.platform.workflows.activate(id)
        return agent to id
    }
    private fun runs(agent: String, id: String) = app.platform.workflows.runs(agent, id, 50).getJSONArray("runs")
    /** Ticks, then waits for the runs the tick started to finish. */
    private fun tick(agent: String, id: String): JSONArray = runBlocking {
        app.platform.workflows.tick()
        withTimeout(10_000) { while (app.platform.workflows.busy(id)) delay(20) }
        delay(50); withTimeout(10_000) { while (app.platform.workflows.busy(id)) delay(20) }
        runs(agent, id)
    }
    private fun record(id: String) = app.platform.store.find(id, kind = "workflow")!!
    private fun backdate(agent: String, id: String, vararg p: Pair<String, Any>) = synchronized(app.platform.store) { app.platform.store.save(agent, "workflow", record(id).apply { p.forEach { (k, v) -> put(k, v) } }) }

    @Test fun triggersValidateAndReadAsSentences() {
        val wf = app.platform.workflows
        val v = wf.validate(flow(o("type" to "power", "on" to "unplugged", "for_s" to 600), "cooldown_s" to 3600, "max_runs_per_day" to 10))
        assertTrue(v.getString("summary"), v.getString("summary").startsWith("When the phone has been off charge for 10 minutes (a power cut), at most once an hour, at most 10 times a day: "))
        assertFalse(v.getJSONArray("required_scopes").toString().contains("location"))
        assertTrue(wf.validate(flow(o("type" to "sun", "on" to "sunset", "offset_min" to -30))).getJSONArray("required_scopes").toString().contains("location"))
        assertFalse(wf.validate(flow(o("type" to "sun", "on" to "sunrise", "latitude" to 52.2, "longitude" to 21.0))).getJSONArray("required_scopes").toString().contains("location"))
        assertTrue(wf.validate(flow(o("type" to "battery", "on" to "low"))).getString("summary").startsWith("When the battery gets low (15%): "))
        assertEquals(listOf("low", "critical", "okay", "full", "saver_on", "saver_off", "hot", "cooled"), WorkflowDefinition.BATTERY_ONS)
        rejects("trigger.on must be unplugged, plugged, restored") { wf.validate(flow(o("type" to "power", "on" to "off"))) }
        rejects("for_s") { wf.validate(flow(o("type" to "power", "on" to "unplugged", "for_s" to 4000))) }
        rejects("trigger.on must be low") { wf.validate(flow(o("type" to "battery", "on" to "charging_started"))) }
        rejects("both latitude and longitude") { wf.validate(flow(o("type" to "sun", "on" to "sunset", "latitude" to 1.0))) }
        rejects("offset_min") { wf.validate(flow(o("type" to "sun", "on" to "sunset", "offset_min" to 500))) }
        rejects("cooldown_s") { wf.validate(flow(o("type" to "button"), "cooldown_s" to -1)) }
        rejects("max_runs_per_day") { wf.validate(flow(o("type" to "button"), "max_runs_per_day" to 0)) }
        rejects("isn't a workflow field") { wf.validate(flow(o("type" to "button"), "cooldown" to 5)) }
        // The old charging trigger still validates and reads the same.
        assertTrue(wf.validate(flow(o("type" to "charging"))).getString("summary").startsWith("When the phone starts charging: "))
    }

    @Test fun aFlowActivatedWhileChargingDoesNotFireUntilTheNextPlugIn() {
        plugged(true)
        for (t in listOf(o("type" to "charging"), o("type" to "power", "on" to "plugged"))) {
            val (agent, id) = active(flow(t))
            assertEquals(0, tick(agent, id).length())
            assertEquals(0, tick(agent, id).length())
            plugged(false); tick(agent, id); plugged(true)
            val r = tick(agent, id)
            assertEquals(1, r.length())
            assertEquals("plugged", r.getJSONObject(0).getJSONObject("trigger").getString("on"))
            assertEquals(1, tick(agent, id).length())
            app.platform.workflows.pause(agent, id)
        }
    }

    @Test fun aPowerCutFiresOnceAfterForSAndThenSaysWhenPowerIsBack() {
        plugged(true)
        val (agent, cut) = active(flow(o("type" to "power", "on" to "unplugged", "for_s" to 600)))
        val (agentB, back) = active(flow(o("type" to "power", "on" to "restored", "for_s" to 600)).also { it.put("name", "Power back") })
        assertEquals(0, tick(agent, cut).length())
        plugged(false); tick(agent, cut)
        assertEquals("still under 10 minutes", 0, tick(agent, cut).length())
        // Pretend the unplug happened 11 minutes ago (and both flows were armed before it).
        val now = System.currentTimeMillis()
        app.getSharedPreferences("power_state", 0).edit().putLong("since", now - 11 * 60_000L).putLong("before", now - 3_600_000L).commit()
        backdate(agent, cut, "power_armed_at" to now - 2 * 3_600_000L); backdate(agentB, back, "power_armed_at" to now - 2 * 3_600_000L)
        val r = tick(agent, cut)
        assertEquals(1, r.length())
        val trig = r.getJSONObject(0).getJSONObject("trigger")
        assertEquals("unplugged", trig.getString("on")); assertEquals(11, trig.getInt("off_minutes")); assertTrue(trig.has("off_at"))
        assertEquals(r.toString(), "completed", r.getJSONObject(0).getString("status"))
        assertEquals("once per outage", 1, tick(agent, cut).length())
        plugged(true)
        val b = tick(agentB, back)
        assertEquals(1, b.length())
        val bt = b.getJSONObject(0).getJSONObject("trigger")
        assertEquals("restored", bt.getString("on")); assertEquals(11, bt.getInt("off_minutes")); assertTrue(bt.has("on_at"))
        assertEquals(1, tick(agentB, back).length())
        assertEquals(1, runs(agent, cut).length())
    }

    @Test fun aRebootDuringAnOutageReportsItOnceThePowerIsBack() {
        // Saved before the reboot: off charge since 40 minutes ago. The first reading afterwards is plugged.
        plugged(false)
        val (agent, back) = active(flow(o("type" to "power", "on" to "restored", "for_s" to 600)))
        val now = System.currentTimeMillis()
        backdate(agent, back, "power_armed_at" to now - 2 * 3_600_000L)
        app.getSharedPreferences("power_state", 0).edit().putBoolean("plugged", false).putLong("since", now - 40 * 60_000L).remove("before").commit()
        plugged(true)
        val r = tick(agent, back)
        assertEquals(1, r.length()); assertEquals(40, r.getJSONObject(0).getJSONObject("trigger").getInt("off_minutes"))
        assertEquals(true, PowerState.load(app)!!.plugged)
        assertEquals(1, tick(agent, back).length())
    }

    @Test fun cooldownAndDailyCapSkipStartsWithoutRefreshingTheCooldown() = runBlocking {
        val (agent, id) = active(flow(o("type" to "called"), "cooldown_s" to 3600))
        app.platform.workflows.runNow(agent, id, null)
        val lastRun = record(id).getLong("last_run")
        try { app.platform.workflows.runNow(agent, id, null); fail("should be skipped") } catch (e: ApiError) { assertEquals("workflow_skipped", e.code); assertTrue(e.message!!.contains("cooldown_s")) }
        assertEquals(1, record(id).getInt("skipped_runs")); assertEquals(lastRun, record(id).getLong("last_run"))
        // A rule or schedule start reports it as skipped, not as a broken flow.
        assertEquals("skipped", app.platform.workflows.start(agent, id, o("caller" to "rule"), null).getString("status"))
        assertEquals(2, record(id).getInt("skipped_runs"))
        // The owner's own Run tap isn't gated.
        assertEquals("completed", app.platform.workflows.runNow(agent, id, null, ownerTap = true).getString("status"))
        // Cooldown over: it runs again.
        backdate(agent, id, "last_run" to System.currentTimeMillis() - 3_601_000L)
        assertEquals("completed", app.platform.workflows.runNow(agent, id, null).getString("status"))

        val (a2, capped) = active(flow(o("type" to "called"), "max_runs_per_day" to 2))
        repeat(2) { app.platform.workflows.runNow(a2, capped, null) }
        try { app.platform.workflows.runNow(a2, capped, null); fail("over the cap") } catch (e: ApiError) { assertEquals("workflow_skipped", e.code); assertTrue(e.message!!.contains("max_runs_per_day")) }
        // A new day starts the count again.
        backdate(a2, capped, "run_day" to java.time.LocalDate.now().minusDays(1).toString())
        assertEquals("completed", app.platform.workflows.runNow(a2, capped, null).getString("status"))
        assertEquals(1, record(capped).getInt("day_runs"))
    }

    @Test fun theOldAppletEventCooldownIsTheFlowsCooldown() {
        val t = o("type" to "applet.event", "applet_id" to "a", "source_id" to "s", "transport" to "webhook", "cooldown_seconds" to 120)
        val def = o("name" to "x", "trigger" to t, "steps" to a(o("type" to "event", "text" to "x")))
        assertEquals(120, WorkflowDefinition.cooldownOf(def))
        assertTrue(FlowSteps.summary(def), FlowSteps.summary(def).contains(", at most once every 2 minutes per device and kind of event: "))
        assertEquals(60, WorkflowDefinition.cooldownOf(JSONObject(def.toString()).put("cooldown_s", 60)))
        assertEquals(0, WorkflowDefinition.cooldownOf(o("name" to "x", "trigger" to o("type" to "button"))))
        rejects("disagree") { app.platform.workflows.validate(JSONObject(def.toString()).put("cooldown_s", 60)) }
        app.platform.workflows.validate(JSONObject(def.toString()).put("cooldown_s", 120))
    }

    @Test fun batteryChangesStartBatteryFlowsWithTheReading() = runBlocking {
        val (agent, id) = active(flow(o("type" to "battery", "on" to "low")))
        app.platform.workflows.onBattery(listOf("battery.full"), BatteryRules.Reading(100, true, true, false, 30.0))
        delay(200); assertEquals(0, runs(agent, id).length())
        app.platform.workflows.onBattery(listOf("battery.low"), BatteryRules.Reading(14, false, false, false, 30.5))
        withTimeout(10_000) { while (runs(agent, id).length() == 0 || app.platform.workflows.busy(id)) delay(20) }
        val t = runs(agent, id).getJSONObject(0).getJSONObject("trigger")
        assertEquals("low", t.getString("on")); assertEquals(14, t.getInt("percent")); assertFalse(t.getBoolean("charging"))
        // The tick never starts a battery flow.
        assertEquals(1, tick(agent, id).length())
    }

    @Test fun aSunFlowWithItsOwnPositionWaitsForTheNextSunset() {
        val (agent, id) = active(flow(o("type" to "sun", "on" to "sunset", "latitude" to 52.23, "longitude" to 21.01)))
        assertEquals(0, tick(agent, id).length())
        val next = record(id).getLong("sun_next_at"); assertTrue(next > System.currentTimeMillis())
        assertEquals(0, tick(agent, id).length())
        // The saved time has come: it runs once and works out the next one.
        backdate(agent, id, "sun_next_at" to System.currentTimeMillis() - 1000)
        val r = tick(agent, id)
        assertEquals(1, r.length()); assertEquals("sunset", r.getJSONObject(0).getJSONObject("trigger").getString("on"))
        assertTrue(record(id).getLong("sun_next_at") > System.currentTimeMillis())
        // Missed by more than 6 hours (the phone was off): skipped, not run late.
        backdate(agent, id, "sun_next_at" to System.currentTimeMillis() - 7 * 3_600_000L)
        assertEquals(1, tick(agent, id).length())
    }
}
