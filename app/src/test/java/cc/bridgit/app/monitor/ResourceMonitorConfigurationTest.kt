package cc.bridgit.app.monitor

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.capability.SettingsController
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.ownerapi.ConfigurationEndpoints
import cc.bridgit.app.ownerapi.ConfigurationSettings
import cc.bridgit.app.ownerapi.TestAndroidKeystore
import cc.bridgit.app.platform.Capabilities
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

/**
 * A0189/A0190: device.resource_monitor and device.resource_monitor_interval through the owner configuration API and
 * POST /v1/settings (authorized round trip, invalid values, unauthorized callers, revocation, Idempotency-Key retries),
 * and GET /v1/device/resources reading the samples.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.LEGACY)
class ResourceMonitorConfigurationTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var caller: Agent
    private var tick = 0L

    @Before fun setup() {
        TestAndroidKeystore.install()
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        caller = app.agents.add("MonitorOwner", Connection.DIRECT).first
        app.agents.update(caller.id) { it.copy(caps = null) }
        caller = app.agents.get(caller.id)!!
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.platform.grant(caller.id, "owner.configuration", Long.MAX_VALUE)
        ResourceMonitor.resetForTest(app)
        ResourceMonitor.reader = { fakeReading() }
    }
    @After fun cleanup() {
        app.settings.resourceMonitor = false
        ResourceMonitor.resetForTest(app)
        TestAndroidKeystore.remove()
    }

    private fun fakeReading(): Reading { tick++
        return Reading(at = System.currentTimeMillis() + tick * 60_000, pid = 7, cpuMs = tick * 600, rssKb = 150 * 1024, peakRssKb = 160 * 1024, threads = 40,
            contextSwitches = tick * 100, rxBytes = tick * 2000, txBytes = tick * 500, diskReadBytes = 0, diskWriteBytes = tick * 4096, javaHeapKb = 1, nativeHeapKb = 1,
            storageBytes = 80L * 1024 * 1024, batteryPercent = 70, charging = true, batteryTempC = 30.0, thermal = "none", cores = 8, costMicros = 500) }

    private fun o(text: String) = JSONObject(text)
    private fun api(): ApiServer = ApiServer(Robolectric.buildService(BridgeService::class.java).create().get(), emptyList(), 0).also { it.prepareRelay() }
    private fun call(api: ApiServer, spec: EndpointSpec, body: JSONObject = JSONObject(), key: String? = UUID.randomUUID().toString(),
                     query: Map<String, String> = emptyMap(), who: Agent = caller) = runBlocking { api.dispatch(spec, who, "100.64.0.2", query, emptyMap(), body, key) }
    private fun json(o: ApiServer.Outcome) = JSONObject(String(o.bytes))
    private fun rejected(code: String, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code) }
    }
    /** Audit entries naming the settings; the log writes on its own thread, so wait briefly for [expect]. */
    private fun audits(expect: Int = 0): Int {
        val until = System.currentTimeMillis() + 3000
        while (true) {
            val n = app.log.query(LogQuery(channels = listOf("audit"))).count { it.toString().contains("device.resource_monitor") }
            if (n >= expect || System.currentTimeMillis() > until) return n
            Thread.sleep(20)
        }
    }

    @Test fun offByDefaultWithAOneMinuteIntervalAndListedAsEditable() {
        assertFalse(app.settings.resourceMonitor)
        assertEquals("1m", app.settings.resourceMonitorInterval)
        assertFalse(ResourceMonitor.running())
        val defs = SettingsController(app).defs().associateBy { it.key }
        assertEquals("boolean", defs.getValue("device.resource_monitor").type)
        assertEquals(listOf("1s", "10s", "1m", "1h"), defs.getValue("device.resource_monitor_interval").enum)
        val listed = ConfigurationSettings(app, caller).status().getJSONArray("settings")
        val keys = (0 until listed.length()).map { listed.getJSONObject(it) }.filter { it.getString("key").startsWith("device.resource_monitor") }
        assertEquals(2, keys.size)
        assertTrue(keys.all { it.getBoolean("writable") && it.getString("write_url") == "/v1/owner/configuration/settings" })
        val st = ResourceMonitor.status(app)
        assertFalse(st.getBoolean("enabled")); assertEquals("1m", st.getString("default_interval")); assertEquals(60, st.getInt("interval_s"))
        assertTrue(st.isNull("since"))
    }

    @Test fun ownerConfigurationRoundTripStartsAndStopsTheMonitor() = runBlocking {
        val service = ConfigurationSettings(app, caller)
        service.update(o("""{"changes":{"device.resource_monitor":true,"device.resource_monitor_interval":"10s"}}"""))
        assertTrue(app.settings.resourceMonitor)
        assertEquals("10s", app.settings.resourceMonitorInterval)
        assertTrue(ResourceMonitor.running())
        val on = ResourceMonitor.status(app)
        assertTrue(on.getBoolean("enabled")); assertEquals(10, on.getInt("interval_s")); assertNotNull(on.optString("since").takeIf { it.isNotEmpty() && it != "null" })
        assertEquals(1, audits(1))
        assertEquals(true, SettingsController(app).read("device.resource_monitor", caller))
        assertEquals("10s", SettingsController(app).read("device.resource_monitor_interval", caller))
        service.update(o("""{"changes":{"device.resource_monitor":false}}"""))
        assertFalse(ResourceMonitor.running())
        val off = ResourceMonitor.status(app)
        assertFalse(off.getBoolean("enabled")); assertTrue(off.isNull("since")); assertFalse(off.isNull("stopped_at"))
        assertEquals("10s", app.settings.resourceMonitorInterval) // the interval is kept for next time
    }

    @Test fun invalidValuesAreRefusedAndChangeNothing() {
        for (body in listOf("""{"device.resource_monitor_interval":"5m"}""", """{"device.resource_monitor_interval":10}""", """{"device.resource_monitor":"yes"}""",
                """{"device.resource_monitor":true,"device.resource_monitor_interval":"2h"}""", """{"device.resource_monitor_extra":true}"""))
            rejected("invalid_parameter") { ConfigurationSettings(app, caller).update(JSONObject().put("changes", o(body))) }
        assertFalse(app.settings.resourceMonitor)
        assertEquals("1m", app.settings.resourceMonitorInterval)
        assertFalse(ResourceMonitor.running())
        try { app.settings.resourceMonitorInterval = "2m"; fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, audits(1))
    }

    @Test fun unauthorizedCallersAndRevokedGrantsAreRefused() {
        val applet = app.agents.add("Applet", Connection.APPLET).first
        rejected("forbidden") { ConfigurationSettings(app, applet).update(o("""{"changes":{"device.resource_monitor":true}}""")) }
        val other = app.agents.add("Other agent", Connection.DIRECT).first // paired, never granted owner.configuration
        try { runBlocking { ConfigurationSettings(app, other).update(o("""{"changes":{"device.resource_monitor":true}}""")) }; fail() }
        catch (e: ApiError) { assertTrue(e.code, e.code in setOf("agent_not_allowed", "forbidden", "unauthorized")) }
        app.platform.revoke(caller.id, "owner.configuration")
        try { runBlocking { ConfigurationSettings(app, caller).update(o("""{"changes":{"device.resource_monitor":true}}""")) }; fail() }
        catch (e: ApiError) { assertTrue(e.code, e.code in setOf("agent_not_allowed", "forbidden", "unauthorized")) }
        assertFalse(app.settings.resourceMonitor)
        // POST /v1/settings needs the App settings switch and Agent Super Powers.
        val api = api()
        assertTrue(call(api, Endpoints.SETTINGS_SET, o("""{"changes":{"device.resource_monitor":true}}"""), who = other).status >= 400)
        app.settings.setOn("settings_api", true)
        assertEquals(403, call(api, Endpoints.SETTINGS_SET, o("""{"changes":{"device.resource_monitor":true}}"""), who = app.agents.get(other.id)!!).status)
        assertFalse(app.settings.resourceMonitor)
        app.agents.update(other.id) { it.copy(caps = null, superPowers = true) }
        val ok = call(api, Endpoints.SETTINGS_SET, o("""{"changes":{"device.resource_monitor":true,"device.resource_monitor_interval":"1h"}}"""), who = app.agents.get(other.id)!!)
        assertEquals(String(ok.bytes), 200, ok.status)
        assertTrue(app.settings.resourceMonitor); assertEquals("1h", app.settings.resourceMonitorInterval)
        val bad = call(api, Endpoints.SETTINGS_SET, o("""{"changes":{"device.resource_monitor_interval":"1d"}}"""), who = app.agents.get(other.id)!!)
        assertEquals(422, bad.status); assertEquals("1h", app.settings.resourceMonitorInterval)
    }

    @Test fun aRetryWithTheSameIdempotencyKeyIsNotAppliedTwice() {
        val api = api()
        val key = UUID.randomUUID().toString()
        val body = o("""{"changes":{"device.resource_monitor":true}}""")
        val first = call(api, ConfigurationEndpoints.SETTINGS, body, key)
        assertEquals(String(first.bytes), 200, first.status)
        assertTrue(app.settings.resourceMonitor)
        assertEquals(1, audits(1))
        app.settings.resourceMonitor = false // the owner turns it off on the phone in between
        val retry = call(api, ConfigurationEndpoints.SETTINGS, body, key)
        assertEquals(200, retry.status)
        val replay = json(retry)
        assertEquals(json(first).getString("request_id"), replay.getString("idempotent_replay_of"))
        assertEquals(json(first).getJSONArray("changed").toString(), replay.getJSONArray("changed").toString())
        assertFalse(app.settings.resourceMonitor) // the replay returns the stored answer and changes nothing
        assertEquals(1, audits(2))
        assertEquals(422, call(api, ConfigurationEndpoints.SETTINGS, body, key = null).status) // writes need a key
    }

    /** The real reader never throws, even where the phone (here Robolectric) hides /proc files or storage stats. */
    @Test fun theRealReaderSamplesWithoutThrowing() {
        ResourceMonitor.resetForTest(app) // restores the real reader
        val first = ResourceMonitor.sampleOnce(app)
        val second = ResourceMonitor.sampleOnce(app)
        assertTrue(first.has("monitor_cost_ms") && first.getDouble("monitor_cost_ms") >= 0)
        assertEquals(cc.bridgit.app.BuildConfig.VERSION_NAME, second.getString("build"))
        for (k in listOf("cpu_percent", "rss_mb", "network_bytes", "disk_bytes", "storage_mb", "battery", "thermal", "context_switches")) assertTrue(k, second.has(k))
        assertEquals(2, ResourceMonitor.log.sampleCount())
    }

    @Test fun samplesAreReadableThroughTheApiAndHealth() {
        repeat(3) { ResourceMonitor.sampleOnce(app) }
        assertTrue(File(app.filesDir, "resource-monitor.json").exists())
        val api = api()
        val samples = call(api, Endpoints.DEVICE_RESOURCES, query = mapOf("view" to "samples", "limit" to "2"), key = null)
        assertEquals(String(samples.bytes), 200, samples.status)
        val r = json(samples)
        assertEquals("bridgit_only", r.getString("scope"))
        assertEquals("android", r.getString("platform"))
        assertEquals(2, r.getJSONArray("samples").length())
        val s = r.getJSONArray("samples").getJSONObject(1)
        assertEquals(1.0, s.getDouble("cpu_percent"), 0.01) // 600 ms per 60 s
        assertEquals(150.0, s.getDouble("rss_mb"), 0.01)
        assertEquals(cc.bridgit.app.BuildConfig.VERSION_NAME, s.getString("build"))
        assertTrue(r.getJSONObject("thresholds").has("cpu_percent"))
        assertTrue(r.getJSONObject("measures").getString("battery").contains("no per-app energy"))
        assertEquals(3, r.getJSONObject("retention").getInt("samples_kept"))
        val hourly = json(call(api, Endpoints.DEVICE_RESOURCES, query = mapOf("view" to "hourly"), key = null))
        assertTrue(hourly.getJSONArray("hours").length() >= 1)
        assertEquals("ok", hourly.getJSONArray("hours").getJSONObject(0).getString("level"))
        val summary = json(call(api, Endpoints.DEVICE_RESOURCES, key = null))
        assertEquals("summary", summary.getString("view")); assertFalse(summary.isNull("current_hour")); assertFalse(summary.has("samples"))
        for (q in listOf(mapOf("view" to "weekly"), mapOf("limit" to "0"), mapOf("limit" to "5000"), mapOf("since" to "yesterday")))
            assertEquals(q.toString(), 422, call(api, Endpoints.DEVICE_RESOURCES, query = q, key = null).status)
        val since = json(call(api, Endpoints.DEVICE_RESOURCES, query = mapOf("view" to "samples", "since" to "2999-01-01T00:00:00Z"), key = null))
        assertEquals(0, since.getJSONArray("samples").length())
        val health = ResourceMonitor.healthJson(app)
        assertFalse(health.getBoolean("enabled")); assertEquals("/v1/device/resources", health.getString("details"))
        assertEquals(150.0, health.getDouble("rss_mb"), 0.01)
    }
}
