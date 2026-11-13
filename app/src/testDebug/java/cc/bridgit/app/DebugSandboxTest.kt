package cc.bridgit.app

import android.content.Intent
import cc.bridgit.app.core.spending.Caller
import cc.bridgit.app.core.spending.FakeClock
import cc.bridgit.app.core.spending.SoftwareJournalKeys
import cc.bridgit.app.ownerapi.ConfigurationSpending
import cc.bridgit.app.ownerapi.SoftwareInstall
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The debug build's sandbox trigger (SA3.1): the test controls a device run uses over ADB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.LEGACY)
class DebugSandboxTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val clock = FakeClock()
    private val agent = Caller("agent", "a1", "http")

    @Before fun setup() {
        app.deleteDatabase("spending-debug-test.db")
        app.spending.dbFile = "spending-debug-test.db"
        app.spending.journalKeys = SoftwareJournalKeys()
        app.spending.install = SoftwareInstall()
        app.spending.clock = clock
        app.spending.online = { true }
    }

    @After fun cleanup() { app.spending.close() }

    private fun ready() {
        app.spending.setEnabled(true, "test")
        ConfigurationSpending.link(app, JSONObject("""{"instrument":"sandbox","id":"sbx"}"""))
        ConfigurationSpending.createBudget(app, agent, true, JSONObject("""{"namespace":"sandbox","id":"day","scope":{"kind":"instrument","id":"sandbox"},"period":"day","amount":{"unit":"USD-6","atoms":10000}}"""))
    }

    private fun run(extra: String, text: String): JSONObject = DebugSandbox.handle(app, Intent().putExtra(extra, text)).getJSONObject(extra)

    @Test fun specsAreKeyValueLists() {
        assertEquals(mapOf("cost" to "1000", "request" to "r 1"), DebugSandbox.spec(" cost=1000 , request = r 1,"))
        assertEquals(emptyMap<String, String>(), DebugSandbox.spec(""))
        assertThrows(IllegalArgumentException::class.java) { DebugSandbox.spec("cost") }
        assertThrows(IllegalArgumentException::class.java) { DebugSandbox.spec("=5") }
    }

    @Test fun whileTheCoreIsOffTheTriggerDoesNothing() {
        assertEquals("capability_disabled", run("sandbox_spend", "cost=1000").getString("error"))
        assertFalse(app.spending.isOpen)
    }

    @Test fun aSpendWithEachTestControl() {
        ready()
        val plain = run("sandbox_spend", "cost=1000,request=r1")
        assertEquals("settled", plain.getString("result"))
        assertEquals(1_000L, plain.getJSONObject("cost").getLong("atoms"))
        assertEquals("sbx", plain.getString("source"))
        assertEquals("known", run("sandbox_spend", "cost=1000,request=r1").getString("result"))

        val lost = run("sandbox_spend", "cost=2000,request=r2,lose_answer=true")
        assertEquals("unknown", lost.getString("result"))
        val read = run("sandbox_reconcile", "sbx")
        assertTrue(read.getBoolean("read"))
        assertEquals(lost.getString("spend_id"), read.getJSONArray("settled").getString(0))

        val late = run("sandbox_spend", "cost=500,request=r3,charge_late=true")
        assertEquals(0L, late.getJSONObject("cost").getLong("atoms"))
        assertEquals(1, run("sandbox_reconcile", "").getJSONArray("raised").length())

        // The provider's own controls: a cap it enforces, and its clock.
        assertEquals(3_600L, run("sandbox_host", "source=sbx,limit=3600").getLong("limit"))
        assertEquals("given_back", run("sandbox_spend", "cost=200,request=r4").getString("result"))
        run("sandbox_host", "limit=none,clock_ms=3600000")
        assertEquals("ignored:clock_disagrees", run("sandbox_anchor", "sbx").getString("anchor"))
        assertEquals(0L, run("sandbox_host", "clock_ms=0").getLong("clock_ms"))
        assertEquals("accepted", run("sandbox_anchor", "sbx").getString("anchor"))

        val over = run("sandbox_spend", "cost=1000,request=r5,bill_past_quote=9000")
        assertTrue(over.getBoolean("overshoot"))
        val full = run("sandbox_spend", "cost=1,request=r6")
        assertEquals("refused", full.getString("result"))
        assertEquals("spend_over_budget", full.getJSONObject("refusal").getString("error"))
        assertEquals(6, full.getJSONObject("refusal").getInt("rule"))
        assertTrue(full.getJSONObject("refusal").has("resets_at"))
    }

    @Test fun theNetworkTheRateLimitAndTheOwnersGateAreSetByTheTrigger() {
        ready()
        assertFalse(run("sandbox_online", "false").getBoolean("online"))
        val offline = run("sandbox_spend", "cost=1,request=o1")
        assertEquals("offline", offline.getJSONObject("refusal").getString("error"))
        assertEquals(503, offline.getInt("http"))
        assertTrue(run("sandbox_online", "true").getBoolean("online"))

        assertEquals(1, run("sandbox_calls_per_hour", "1").getInt("calls_per_hour"))
        assertEquals("settled", run("sandbox_spend", "cost=1,request=c1").getString("result"))
        assertEquals("rate_limited", run("sandbox_spend", "cost=1,request=c2").getJSONObject("refusal").getString("error"))
        assertTrue(run("sandbox_calls_per_hour", "none").isNull("calls_per_hour"))

        val gated = run("sandbox_gate", "budget=day,agents_may_change=false,ceiling=20000").getJSONObject("budget").getJSONObject("gate")
        assertEquals("owner", gated.getJSONObject("set_by").getString("kind"))
        assertEquals("grants", gated.getString("gate_strength"))
        assertEquals(20_000L, gated.getJSONObject("ceiling").getLong("atoms"))

        // An unlinked source and a malformed control are answered, not thrown.
        assertEquals("spend_source_not_allowed", run("sandbox_spend", "cost=1,source=nowhere").getJSONObject("refusal").getString("error"))
        assertEquals("invalid_parameter", run("sandbox_spend", "request=x").getString("error"))
        assertEquals("invalid_parameter", run("sandbox_spend", "cost=1,colour=red").getString("error"))
        assertEquals("invalid_parameter", run("sandbox_spend", "cost=-5").getString("error"))
        assertEquals("invalid_parameter", run("sandbox_host", "source=nowhere").getString("error"))
    }
}
