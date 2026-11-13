package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.McpTools
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.spending.Caller
import cc.bridgit.app.core.spending.FakeClock
import cc.bridgit.app.core.spending.Gates
import cc.bridgit.app.core.spending.Increments
import cc.bridgit.app.core.spending.Money
import cc.bridgit.app.core.spending.Namespace
import cc.bridgit.app.core.spending.SoftwareJournalKeys
import cc.bridgit.app.core.spending.SpendRefusal
import cc.bridgit.app.core.spending.SpendRunner
import cc.bridgit.app.core.spending.Spending
import cc.bridgit.app.core.spending.SpendingDb
import cc.bridgit.app.core.spending.sandbox.SandboxAdapter.Detail
import cc.bridgit.app.platform.Capabilities
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Instant
import java.util.UUID

/**
 * The spending configuration API (spending authority G11) and the wired core, sandbox namespace, through the
 * same dispatch an agent's call takes. Spends are run by the core's own runner, as the debug trigger does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.LEGACY)
class ConfigurationSpendingTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val file = "spending-api-test.db"

    /** Wednesday 11 November 2026, 10:00 UTC. */
    private val clock = FakeClock(wall = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli(), elapsed = 50_000, boots = 3)
    private val keys = SoftwareJournalKeys()
    private val install = SoftwareInstall()
    private var online = true
    private lateinit var caller: Agent
    private lateinit var api: ApiServer
    private var requests = 0

    @Before fun setup() {
        TestAndroidKeystore.install()
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        caller = controller("ConfigOwner", "owner_features", "budgets")
        Capabilities.setEnabled(app, "owner.configuration", true)
        app.deleteDatabase(file)
        app.spending.dbFile = file
        app.spending.journalKeys = keys
        app.spending.install = install
        app.spending.clock = clock
        app.spending.online = { online }
        api = ApiServer(Robolectric.buildService(BridgeService::class.java).create().get(), emptyList(), 0).also { it.prepareRelay() }
    }

    @After fun cleanup() { app.spending.close(); TestAndroidKeystore.remove() }

    private fun controller(name: String, vararg caps: String): Agent {
        val a = app.agents.add(name, Connection.DIRECT).first
        app.agents.update(a.id) { it.copy(caps = caps.toSet()) }
        app.platform.grant(a.id, "owner.configuration", Long.MAX_VALUE)
        return app.agents.get(a.id)!!
    }

    private fun call(spec: EndpointSpec, body: JSONObject = JSONObject(), by: Agent = caller, query: Map<String, String> = emptyMap(), id: String? = null) = runBlocking {
        val queries = if (spec.method == "GET" || spec.method == "DELETE") mapOf("namespace" to "sandbox") + query else query
        api.dispatch(spec, app.agents.get(by.id)!!, "100.64.0.2", queries, id?.let { mapOf("id" to it) } ?: emptyMap(), body, UUID.randomUUID().toString())
    }

    private fun ok(spec: EndpointSpec, body: JSONObject = JSONObject(), by: Agent = caller, query: Map<String, String> = emptyMap(), id: String? = null): JSONObject {
        val out = call(spec, body, by, query, id)
        assertEquals(String(out.bytes), 200, out.status)
        return JSONObject(String(out.bytes))
    }

    private fun refused(code: String, spec: EndpointSpec, body: JSONObject = JSONObject(), by: Agent = caller, query: Map<String, String> = emptyMap(), id: String? = null): JSONObject {
        val out = call(spec, body, by, query, id)
        val json = JSONObject(String(out.bytes))
        assertEquals(String(out.bytes), code, json.optString("error"))
        assertEquals(ErrorCatalog.http(code), out.status)
        return json
    }

    private fun o(text: String) = JSONObject(text)
    private fun usd(atoms: Long) = """{"unit":"USD-6","atoms":$atoms}"""
    private fun enable() { ok(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"spending.core_enabled":true}}""")) }
    private val space get() = app.spending.sandbox()

    /** The core on, one sandbox source and a $1 a day budget on the sandbox instrument. */
    private fun ready(amount: Long = 1_000_000, gate: String = "") {
        enable()
        ok(ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox","id":"sbx"}"""))
        ok(ConfigurationSpending.BUDGET_CREATE, o("""{"namespace":"sandbox","id":"day","scope":{"kind":"instrument","id":"sandbox"},"period":"day","amount":${usd(amount)}$gate}"""))
    }

    private fun spend(cost: Long, source: String = "sbx", by: Agent? = caller, request: String = "req-${++requests}", route: String? = null, namespace: String? = null, detail: Map<String, String> = emptyMap()): SpendRunner.Outcome =
        space.runner.run(space.installId, SpendRunner.Request(request, source, Money("USD-6", cost), if (by == null) "owner" else "agent", by?.id ?: "owner", route = route, claimedNamespace = namespace, detail = detail))

    /** The spend's refusal as the API error it is on the wire. */
    private fun refusal(o: SpendRunner.Outcome): JSONObject {
        val json = SpendWire.outcome(o, Namespace.SANDBOX)
        assertEquals(json.toString(), "refused", json.getString("result"))
        return json.getJSONObject("refusal").put("http", json.getInt("http"))
    }

    private fun budget(id: String = "day"): JSONObject {
        val all = ok(ConfigurationSpending.BUDGETS).getJSONArray("budgets")
        return (0 until all.length()).map(all::getJSONObject).first { it.getString("id") == id }
    }

    private fun mine(id: String = "day") = budget(id).getJSONObject("this_device")
    private fun atoms(o: JSONObject, key: String) = o.getJSONObject(key).getLong("atoms")
    private fun logged(text: String): Boolean {
        app.log.flush()
        return app.log.query(cc.bridgit.app.log.LogQuery(kinds = listOf("spending"))).any { it.message.orEmpty().contains(text) }
    }

    // ---- the switch ----

    @Test fun offByDefaultNothingIsOpenedReadsSayOffAndWritesAreRefused() {
        assertFalse(app.settings.spendingCoreEnabled)
        val root = ok(ConfigurationSpending.GET)
        assertFalse(root.getBoolean("enabled"))
        assertEquals("spending.core_enabled", root.getString("setting"))
        for (read in listOf(ConfigurationSpending.SOURCES, ConfigurationSpending.RULES, ConfigurationSpending.BUDGETS, ConfigurationSpending.ALLOWANCES, ConfigurationSpending.JOURNAL,
            ConfigurationSpending.SUMMARY, ConfigurationSpending.LIMITS)) assertEquals("spending.core_enabled", refused("capability_disabled", read).getString("setting"))
        refused("capability_disabled", ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":true}"""))
        refused("capability_disabled", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox"}"""))
        refused("capability_disabled", ConfigurationSpending.SOURCE_UNLINK, id = "sbx")
        refused("capability_disabled", ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","zone":"UTC"}"""))
        refused("capability_disabled", ConfigurationSpending.BUDGET_CREATE, o("""{"namespace":"sandbox","id":"day","scope":{"kind":"account"},"period":"day","amount":${usd(1)}}"""))
        refused("capability_disabled", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(1)}}"""), id = "day")
        refused("capability_disabled", ConfigurationSpending.BUDGET_DELETE, id = "day")
        refused("capability_disabled", ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(1)}}"""), id = "day")
        refused("capability_disabled", ConfigurationSpending.BUDGET_GATE, o("""{"namespace":"sandbox","agents_may_change":false}"""), id = "day")
        refused("capability_disabled", ConfigurationSpending.JOURNAL_ACK, o("""{"namespace":"sandbox","acknowledge_history":true}"""))
        // Nothing was opened or made: no journal file, no journal key, no host file, no source store.
        assertFalse(app.spending.isOpen)
        assertFalse(app.getDatabasePath(file).exists())
        assertFalse(app.getDatabasePath(SpendingDb.fileName(Namespace.SANDBOX)).exists())
        assertNull(keys.epoch(Namespace.SANDBOX))
        assertFalse(java.io.File(app.filesDir, "spending-sandbox-host.json").exists())
    }

    @Test fun theSwitchIsAnOwnerSettingAndOffAgainChangesNothingAndKeepsTheJournal() {
        val index = ok(ConfigurationEndpoints.INDEX)
        assertEquals("/v1/owner/configuration/spending", index.getString("spending_url"))
        val row = (0 until index.getJSONArray("settings").length()).map { index.getJSONArray("settings").getJSONObject(it) }.first { it.getString("key") == Spending.SETTING }
        assertEquals(false, row.get("value"))
        assertTrue(row.getBoolean("writable"))
        assertEquals(cc.bridgit.app.core.SettingLock.OWNER, cc.bridgit.app.capability.SettingsController(app).def(Spending.SETTING)!!.lock)
        refused("invalid_parameter", ConfigurationEndpoints.SETTINGS, o("""{"changes":{"spending.core_enabled":"yes"}}"""))
        ready()
        assertTrue(app.settings.spendingCoreEnabled)
        assertTrue(spend(1_000) is SpendRunner.Outcome.Settled)
        val epoch = ok(ConfigurationSpending.GET).getJSONObject("journal").getString("epoch")

        ok(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"spending.core_enabled":false}}"""))
        assertFalse(app.spending.isOpen)
        refused("capability_disabled", ConfigurationSpending.BUDGETS)
        refused("capability_disabled", ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(1)}}"""), id = "day")
        // On again: the same journal, the same count. Off kept everything and changed nothing.
        enable()
        assertEquals(epoch, ok(ConfigurationSpending.GET).getJSONObject("journal").getString("epoch"))
        assertEquals(1_000L, atoms(mine(), "used"))
        // Without the configuration grant nothing is read or changed.
        app.platform.revoke(caller.id, "owner.configuration")
        assertEquals(403, call(ConfigurationSpending.BUDGETS).status)
        assertEquals(403, call(ConfigurationEndpoints.SETTINGS, o("""{"changes":{"spending.core_enabled":false}}""")).status)
        assertTrue(app.settings.spendingCoreEnabled)
    }

    @Test fun theEndpointsAreRegisteredForHttpMcpAndTheGeneratedDocs() {
        for (spec in ConfigurationSpending.ALL) {
            assertTrue(spec.path, spec in ConfigurationEndpoints.ALL)
            assertTrue(spec.path, spec in cc.bridgit.app.api.Endpoints.all)
            assertEquals("owner.configuration", spec.controlScope)
            assertTrue(spec.path, McpTools.name(spec).startsWith("owner_configuration_spending"))
            if (spec.method != "GET") assertTrue(spec.path, spec.idempotencyRequired)
        }
        assertEquals(ConfigurationSpending.ALL.size, ConfigurationSpending.ALL.map { McpTools.name(it) }.toSet().size)
        // Nothing here offers taking an ended share: its evidence is not checked by any code yet.
        assertTrue(ConfigurationSpending.ALL.none { it.summary.contains("ended share") || it.path.contains("claim") || it.path.contains("transfer") })
    }

    // ---- namespace ----

    @Test fun theLiveNamespaceIsRefusedAndNothingOfItIsMade() {
        ready()
        assertTrue(spend(1_000) is SpendRunner.Outcome.Settled)
        // With no namespace a read is for live: not available, and it shows nothing of the sandbox.
        val root = JSONObject(String(runBlocking { api.dispatch(ConfigurationSpending.GET, caller, "100.64.0.2", emptyMap(), emptyMap(), JSONObject(), null) }.bytes))
        assertEquals("live", root.getString("namespace"))
        assertFalse(root.getBoolean("available"))
        assertEquals("not_built", root.getString("reason"))
        assertFalse(root.has("journal"))
        for (read in listOf(ConfigurationSpending.SOURCES, ConfigurationSpending.RULES, ConfigurationSpending.BUDGETS, ConfigurationSpending.ALLOWANCES, ConfigurationSpending.JOURNAL,
            ConfigurationSpending.SUMMARY, ConfigurationSpending.LIMITS)) {
            for (q in listOf(emptyMap(), mapOf("namespace" to "live"))) {
                val out = runBlocking { api.dispatch(read, caller, "100.64.0.2", q, emptyMap(), JSONObject(), null) }
                val json = JSONObject(String(out.bytes))
                assertEquals(read.path, "platform_unsupported", json.optString("error"))
                assertEquals("live", json.getString("namespace"))
                assertEquals("not_built", json.getString("reason"))
            }
        }
        val create = """"id":"x","scope":{"kind":"account"},"period":"day","amount":${usd(5)}"""
        refused("platform_unsupported", ConfigurationSpending.BUDGET_CREATE, o("{$create}"))
        refused("platform_unsupported", ConfigurationSpending.BUDGET_CREATE, o("""{"namespace":"live",$create}"""))
        refused("platform_unsupported", ConfigurationSpending.SET, o("""{"paused":true}"""))
        refused("platform_unsupported", ConfigurationSpending.RULES_SET, o("""{"zone":"UTC"}"""))
        refused("platform_unsupported", ConfigurationSpending.BUDGET_TOPUP, o("""{"amount":${usd(5)}}"""), id = "day")
        refused("platform_unsupported", ConfigurationSpending.BUDGET_GATE, o("""{"agents_may_change":false}"""), id = "day")
        refused("platform_unsupported", ConfigurationSpending.BUDGET_UPDATE, o("""{"amount":${usd(5)}}"""), id = "day")
        refused("platform_unsupported", ConfigurationSpending.JOURNAL_ACK, o("""{"acknowledge_history":true}"""))
        assertEquals(422, call(ConfigurationSpending.BUDGETS, query = mapOf("namespace" to "test")).status)
        // A live instrument can't be linked here, and a sandbox budget or cap can't name one.
        assertEquals("live", refused("platform_unsupported", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"openrouter"}""")).getString("namespace"))
        refused("namespace_mismatch", ConfigurationSpending.BUDGET_CREATE, o("""{"namespace":"sandbox","id":"x","scope":{"kind":"instrument","id":"openrouter"},"period":"day","amount":${usd(5)}}"""))
        refused("namespace_mismatch", ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","caps":{"instruments":{"openrouter":${usd(5)}}}}"""))
        // A spend block that claims live on a sandbox source is refused by the core.
        val r = refusal(spend(1_000, namespace = "live"))
        assertEquals("namespace_mismatch", r.getString("error"))
        // No live journal file and no live key exist.
        assertFalse(app.getDatabasePath(SpendingDb.fileName(Namespace.LIVE)).exists())
        assertNull(keys.epoch(Namespace.LIVE))
        assertEquals(1_000L, atoms(mine(), "used"))
    }

    // ---- sources, rules, budgets: validation as the store's own rules ----

    @Test fun sourcesAreLinkedAndUnlinkedAndTheKeyIsNeverReturned() {
        enable()
        assertEquals(0, ok(ConfigurationSpending.SOURCES).getJSONArray("sources").length())
        refused("invalid_parameter", ConfigurationSpending.SOURCE_LINK, JSONObject())
        refused("invalid_parameter", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox","id":"Has Space"}"""))
        refused("invalid_parameter", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox","key":"abc"}"""))
        val linked = ok(ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox"}"""))
        assertEquals("sandbox-1", linked.getString("id"))
        assertEquals("sandbox", linked.getString("namespace"))
        assertEquals("metered", linked.getString("class"))
        assertEquals("owner", linked.getString("funder"))
        assertEquals("device", linked.getString("custody"))
        assertEquals("core_only", linked.getString("cap"))
        assertEquals("in_process", linked.getString("host"))
        assertEquals("provider_signed", linked.getString("time_source"))
        assertTrue(linked.getBoolean("configured"))
        refused("conflict", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox","id":"sandbox-1"}"""))
        val key = space.source("sandbox-1")!!.key
        val list = ok(ConfigurationSpending.SOURCES)
        assertEquals(1, list.getJSONArray("sources").length())
        assertFalse(list.toString().contains(key))
        assertFalse(linked.toString().contains(key))
        // A provider-side limit shows as cap: provider, with what is left under it.
        space.host.limit(key, 5_000)
        val capped = ok(ConfigurationSpending.SOURCES).getJSONArray("sources").getJSONObject(0)
        assertEquals("provider", capped.getString("cap"))
        assertEquals(5_000L, atoms(capped, "available"))
        refused("not_found", ConfigurationSpending.SOURCE_UNLINK, id = "other")
        assertTrue(ok(ConfigurationSpending.SOURCE_UNLINK, id = "sandbox-1").getBoolean("unlinked"))
        assertEquals(0, ok(ConfigurationSpending.SOURCES).getJSONArray("sources").length())
    }

    @Test fun moneyIsExactAndBodiesAreValidatedBeforeAnythingChanges() {
        enable()
        fun create(rest: String) = o("""{"namespace":"sandbox","id":"day","period":"day",$rest}""")
        val scope = """"scope":{"kind":"instrument","id":"sandbox"}"""
        for (bad in listOf(
            """$scope,"amount":1.5""", """$scope,"amount":{"unit":"USD-6","atoms":1.5}""", """$scope,"amount":{"unit":"USD-6","amount":1.5}""",
            """$scope,"amount":{"unit":"USD-6","amount":"0.0000001"}""", """$scope,"amount":{"unit":"USD-6","amount":"-1"}""", """$scope,"amount":{"unit":"USD-6","atoms":-1}""",
            """$scope,"amount":{"unit":"DOGE","atoms":1}""", """$scope,"amount":{"unit":"USD-6","atoms":1,"amount":"1"}""", """$scope,"amount":{"unit":"USD-6"}""", scope,
            """"amount":${usd(5)}""", """"scope":{"kind":"planet"},"amount":${usd(5)}""", """"scope":{"kind":"applet"},"amount":${usd(5)}""",
            """"scope":{"kind":"device","id":"another-install"},"amount":${usd(5)}""", """$scope,"amount":${usd(5)},"gate":{"ceiling":${usd(4)}}""",
            """$scope,"amount":${usd(5)},"gate":{"agents_may_change":"no"}""", """$scope,"amount":${usd(5)},"portion":${usd(5)}""",
        )) refused("invalid_parameter", ConfigurationSpending.BUDGET_CREATE, create(bad))
        refused("invalid_parameter", ConfigurationSpending.BUDGET_CREATE, o("""{"namespace":"sandbox","id":"Day One","period":"day",$scope,"amount":${usd(5)}}"""))
        refused("invalid_parameter", ConfigurationSpending.BUDGET_CREATE, o("""{"namespace":"sandbox","id":"day","period":"year",$scope,"amount":${usd(5)}}"""))
        assertEquals(0, ok(ConfigurationSpending.RULES).getInt("generation"))
        assertEquals(0, ok(ConfigurationSpending.BUDGETS).getJSONArray("budgets").length())

        val made = ok(ConfigurationSpending.BUDGET_CREATE, create("""$scope,"amount":{"unit":"USD-6","amount":"1.50"}""")).getJSONObject("budget")
        assertEquals(1_500_000L, atoms(made, "amount"))
        assertEquals("1.500000", made.getJSONObject("amount").getString("amount"))
        assertEquals(1_500_000L, atoms(made.getJSONObject("this_device"), "portion"))
        assertEquals("standing", made.getJSONObject("this_device").getString("kind"))
        assertTrue(made.getJSONObject("this_device").getBoolean("holds_reclaim"))
        assertTrue(made.getJSONObject("gate").isNull("gate_strength"))
        refused("conflict", ConfigurationSpending.BUDGET_CREATE, create("""$scope,"amount":${usd(5)}"""))
        refused("not_found", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(5)}}"""), id = "week")
        refused("not_found", ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(5)}}"""), id = "week")
        refused("not_found", ConfigurationSpending.BUDGET_GATE, o("""{"namespace":"sandbox","agents_may_change":false}"""), id = "week")
        refused("not_found", ConfigurationSpending.BUDGET_DELETE, id = "week")
        refused("invalid_parameter", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox"}"""), id = "day")
        refused("invalid_parameter", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":{"unit":"EUR-2","atoms":5}}"""), id = "day")
        refused("invalid_parameter", ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(0)}}"""), id = "day")
        refused("invalid_parameter", ConfigurationSpending.BUDGET_GATE, o("""{"namespace":"sandbox"}"""), id = "day")
        refused("invalid_parameter", ConfigurationSpending.SET, o("""{"namespace":"sandbox"}"""))
        refused("invalid_parameter", ConfigurationSpending.SET, o("""{"namespace":"sandbox","classes":{"gold":false}}"""))
        refused("invalid_parameter", ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":"yes"}"""))
        refused("invalid_parameter", ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","zone":"Mars/Olympus"}"""))
        refused("invalid_parameter", ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","caps":{"routes":{}}}"""))
        refused("invalid_parameter", ConfigurationSpending.JOURNAL_ACK, o("""{"namespace":"sandbox","acknowledge_history":false}"""))
        assertEquals(422, call(ConfigurationSpending.JOURNAL, query = mapOf("limit" to "0")).status)
        assertEquals(422, call(ConfigurationSpending.JOURNAL, query = mapOf("epoch" to "nope")).status)
        // A change names the generation it replaces.
        refused("conflict", ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":true,"generation":7}"""))
        assertTrue(ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":true,"generation":1}""")).getBoolean("changed"))
        assertFalse("the same again changes nothing and makes no new generation", ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":true}""")).getBoolean("changed"))
        assertEquals(2, ok(ConfigurationSpending.RULES).getInt("generation"))
    }

    @Test fun everyRuleChangeIsSignedWithTheInstallKeyAndNamesTheCallingAgent() {
        ready()
        val rules = ok(ConfigurationSpending.RULES)
        assertEquals(1, rules.getInt("generation"))
        assertEquals("sandbox", rules.getString("namespace"))
        assertEquals(install.id(), rules.getJSONObject("signed_by").getString("id"))
        assertEquals(install.id(), rules.getString("executor"))
        assertEquals("agent", rules.getJSONObject("actor").getString("kind"))
        assertEquals(caller.id, rules.getJSONObject("actor").getString("id"))
        assertEquals("mcp", rules.getJSONObject("actor").getString("door"))
        assertEquals(java.util.TimeZone.getDefault().id, rules.getString("zone"))
        // The signature is the install key's (P-256) over the document: the device takes its own document back, and no other signer's.
        val doc = space.core.document()!!
        assertEquals(cc.bridgit.app.core.spending.RuleDocuments.Adopt.Known, space.core.adopt(doc.json))
        val forged = JSONObject(doc.json).put("generation", 2).put("parents", org.json.JSONArray(listOf(doc.digest))).toString()
        assertTrue(space.core.adopt(forged) is cc.bridgit.app.core.spending.RuleDocuments.Adopt.Refused)
        assertEquals(1, ok(ConfigurationSpending.RULES).getInt("generation"))
    }

    // ---- a spend, end to end ----

    @Test fun aSandboxSpendIsQuotedHeldRunAndSettledAndTheReadsShowIt() {
        ready()
        val done = spend(250_000) as SpendRunner.Outcome.Settled
        assertEquals(Money("USD-6", 250_000), done.cost)
        assertFalse(done.overshoot)
        val wire = SpendWire.outcome(done, Namespace.SANDBOX)
        assertEquals("settled", wire.getString("result"))
        assertEquals(250_000L, atoms(wire, "cost"))
        assertEquals("settled", wire.getJSONObject("entry").getString("state"))

        val b = budget()
        val mine = b.getJSONObject("this_device")
        assertEquals(1_000_000L, atoms(mine, "portion"))
        assertEquals(250_000L, atoms(mine, "used"))
        assertEquals(750_000L, atoms(mine, "remaining"))
        assertEquals("day:2026-11-11", mine.getString("period_id"))
        assertEquals("current", mine.getString("standing"))
        val midnight = Instant.parse("2026-11-12T00:00:00Z").toEpochMilli()
        if (java.util.TimeZone.getDefault().id == "UTC") assertEquals(midnight, mine.getLong("resets_at"))

        val journal = ok(ConfigurationSpending.JOURNAL)
        val entries = (0 until journal.getJSONArray("entries").length()).map { journal.getJSONArray("entries").getJSONObject(it) }
        assertEquals(listOf("epoch", "rules", "period", "allowance", "spend", "spend", "anchor"), entries.map { it.getString("kind") })
        val hold = entries.first { it.getString("kind") == "spend" }
        assertEquals("held", hold.getString("state"))
        assertEquals("sandbox", hold.getString("namespace"))
        assertEquals("sbx", hold.getString("source"))
        assertEquals(caller.id, hold.getJSONObject("principal").getString("id"))
        assertEquals(done.spendId, hold.getString("spend_id"))
        assertFalse(journal.getBoolean("more"))
        val page = ok(ConfigurationSpending.JOURNAL, query = mapOf("after" to "4", "limit" to "2"))
        assertEquals(2, page.getJSONArray("entries").length())
        assertEquals(6L, page.getLong("next_after"))
        assertTrue(page.getBoolean("more"))

        val allowances = ok(ConfigurationSpending.ALLOWANCES)
        val held = allowances.getJSONObject("held").getJSONObject("sbx")
        assertEquals("rules", held.getString("issuer"))
        assertFalse(held.getBoolean("expired"))
        assertTrue(allowances.getJSONObject("journal").getBoolean("whole"))
        assertTrue(allowances.getJSONObject("journal").getJSONObject("time").getBoolean("anchored"))
        assertEquals(clock.wall, allowances.getJSONObject("journal").getJSONObject("time").getLong("last_anchor_at"))
        val share = allowances.getJSONArray("budgets").getJSONObject(0)
        assertEquals(0, share.getJSONArray("in_transit").length())
        assertTrue(share.isNull("pending_cloud_room"))
        assertEquals(1_000_000L, atoms(share.getJSONObject("portions").getJSONObject("devices").getJSONObject(install.id()!!), "portion"))

        val summary = ok(ConfigurationSpending.SUMMARY)
        assertEquals(250_000L, atoms(summary.getJSONArray("budgets").getJSONObject(0), "used"))
        val settled = summary.getJSONObject("sources").getJSONObject("sbx").getJSONObject("settled")
        assertEquals(1, settled.getInt("count"))
        assertEquals(250_000L, settled.getJSONArray("counted").getJSONObject(0).getLong("atoms"))
        assertEquals(0L, summary.getLong("age_ms"))
        assertEquals(0, ok(ConfigurationSpending.LIMITS).getJSONArray("bridgit_funded").length())

        // The same request again holds nothing more and runs nothing.
        val again = spend(250_000, request = "req-1") as SpendRunner.Outcome.Known
        assertEquals("settled", again.entry!!.state!!.id)
        assertEquals(1, space.host.billedCalls)
        assertEquals(250_000L, atoms(mine(), "used"))
    }

    @Test fun aLostAnswerKeepsItsHoldUntilReconcileAndALateChargeAndABillPastTheQuoteAreCounted() {
        ready()
        val lost = spend(300_000, detail = mapOf(Detail.LOSE_ANSWER to "true")) as SpendRunner.Outcome.Unknown
        assertEquals("no_answer", lost.reason)
        assertEquals(300_000L, atoms(mine(), "used"))
        // Asked again, nothing runs: the outcome is settled by a reconcile, never by a second call.
        assertTrue(spend(300_000, request = "req-1") is SpendRunner.Outcome.Known)
        assertEquals(1, space.host.billedCalls)
        // The journal survives the app stopping, and the provider remembers what it billed.
        app.spending.close()
        val read = space.runner.reconcile("sbx")
        assertTrue(read.read)
        assertEquals(listOf(lost.spendId), read.settled)
        assertEquals(300_000L, atoms(mine(), "used"))

        val late = spend(100_000, detail = mapOf(Detail.CHARGE_LATE to "true")) as SpendRunner.Outcome.Settled
        assertEquals(0L, late.cost.atoms)
        assertEquals(300_000L, atoms(mine(), "used"))
        assertEquals(listOf(late.spendId), space.runner.reconcile("sbx").raised)
        assertEquals(400_000L, atoms(mine(), "used"))

        // A bill past the quote settles at the real cost; the period's count passes its portion and refuses everything further (G5.4).
        val over = spend(550_000, detail = mapOf(Detail.BILL_PAST_QUOTE_ATOMS to "200000")) as SpendRunner.Outcome.Settled
        assertTrue(over.overshoot)
        assertEquals(750_000L, over.cost.atoms)
        assertEquals(1_150_000L, atoms(mine(), "used"))
        assertEquals(-150_000L, atoms(mine(), "remaining"))
        val r = refusal(spend(1))
        assertEquals("spend_over_budget", r.getString("error"))
        assertEquals(6, r.getInt("rule"))
        assertTrue(logged("spend.overshoot"))
    }

    @Test fun aCallTheProviderRefusesIsGivenBackWhole() {
        ready()
        space.host.limit(space.source("sbx")!!.key, 1_000)
        val back = spend(5_000) as SpendRunner.Outcome.GivenBack
        assertEquals("limit_reached", back.reason)
        assertEquals(0L, atoms(mine(), "used"))
        assertEquals(0, space.host.billedCalls)
    }

    // ---- the rules, in order ----

    @Test fun eachRuleRefusalNamesItsRuleAndWhenItEnds() {
        ready()
        val resets = mine().also { assertEquals("current", it.getString("standing")) }.getLong("resets_at")
        fun expect(o: SpendRunner.Outcome, code: String, rule: Int?, reason: String?, resetsAt: Long? = null) {
            val r = refusal(o)
            assertEquals(r.toString(), code, r.getString("error"))
            assertEquals(ErrorCatalog.http(code), r.getInt("http"))
            if (rule == null) assertTrue(r.isNull("rule")) else assertEquals(rule, r.getInt("rule"))
            if (reason == null) assertTrue(r.isNull("reason")) else assertEquals(reason, r.getString("reason"))
            if (resetsAt == null) assertTrue(r.toString(), r.isNull("resets_at")) else assertEquals(resetsAt, r.getLong("resets_at"))
            assertEquals("sandbox", r.getString("namespace"))
        }
        // Rule 1: the device's pause of outside calls, the namespace's pause, the class and the source.
        app.settings.externalCallsPaused = true
        expect(spend(1_000), "spending_off", 1, "paused")
        app.settings.externalCallsPaused = false
        ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":true}"""))
        assertTrue(ok(ConfigurationSpending.GET).getJSONObject("switches").getBoolean("paused"))
        expect(spend(1_000), "spending_off", 1, "paused")
        ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":false,"classes":{"metered":false}}"""))
        expect(spend(1_000), "spending_off", 1, "switched_off")
        ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","classes":{"metered":true},"sources":{"sbx":false}}"""))
        expect(spend(1_000), "spending_off", 1, "switched_off")
        assertFalse(ok(ConfigurationSpending.SOURCES).getJSONArray("sources").getJSONObject(0).getBoolean("on"))
        ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","sources":{"sbx":true}}"""))

        // Rule 2: an agent the device does not let act. It comes before rule 3, so an unlinked source does not hide it.
        val paused = controller("Paused", "owner_features")
        app.agents.update(paused.id) { it.copy(paused = true) }
        expect(spend(1_000, by = paused), "spend_not_allowed", 2, "may_not_act")
        expect(spend(1_000, source = "nowhere", by = paused), "spend_not_allowed", 2, "may_not_act")

        // Rule 3: the source is not linked; the route is not on its list.
        expect(spend(1_000, source = "nowhere"), "spend_source_not_allowed", 3, "source_not_linked")
        expect(spend(1_000, route = "gpt-9"), "spend_source_not_allowed", 3, "route_not_allowed")

        // Rule 4: the per-spend cap, the source's before the instrument's.
        ok(ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","caps":{"instruments":{"sandbox":${usd(500_000)}}}}"""))
        expect(spend(500_001), "spend_over_cap", 4, "per_spend_cap")
        ok(ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","caps":{"sources":{"sbx":${usd(100_000)}}}}"""))
        expect(spend(100_001), "spend_over_cap", 4, "per_spend_cap")
        assertEquals(100_000L, atoms(ok(ConfigurationSpending.RULES).getJSONObject("caps").getJSONObject("sources"), "sbx"))
        ok(ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","caps":{"sources":{"sbx":null},"instruments":{"sandbox":null}}}"""))

        // Rule 5 has no source on the device-held path (none is Bridgit-funded): its answer is checked as it goes on the wire.
        val five = SpendWire.refusal(SpendRefusal(null, SpendRefusal.Reason.BRIDGIT_CAP, 5, resets), Namespace.SANDBOX)
        assertEquals("spend_bridgit_cap", five.code)
        assertEquals(5, five.extra["rule"])
        assertEquals(resets, five.extra["resets_at"])

        // Rule 6: the budget's portion for the period, with when the period ends.
        expect(spend(1_000_001), "spend_over_budget", 6, "budget_portion_used", resets)
        assertTrue(spend(999_000) is SpendRunner.Outcome.Settled)
        expect(spend(1_001), "spend_over_budget", 6, "budget_portion_used", resets)

        // Rule 7: calls per hour, counted from the journal. The contract has no value yet; a test sets one.
        app.spending.callsPerHourForTests = 1
        expect(spend(1), "rate_limited", 7, "spend_rate")
        app.spending.callsPerHourForTests = null

        // Rule 8: no budget gives this device a share for the spend.
        ok(ConfigurationSpending.BUDGET_DELETE, id = "day")
        expect(spend(1), "allowance_exhausted", 8, "no_allowance")
        // Nothing that was refused reached the provider or the journal's count.
        assertEquals(1, space.host.billedCalls)
    }

    @Test fun offlineRefusesPaidWorkAtOnceWhileReadsAndReductionsStillAnswer() {
        ready()
        assertTrue(spend(100_000) is SpendRunner.Outcome.Settled)
        clock.advance(90_000)
        online = false
        val before = ok(ConfigurationSpending.JOURNAL).getJSONObject("head").getLong("seq")
        val r = refusal(spend(1_000))
        assertEquals("offline", r.getString("error"))
        assertEquals(503, r.getInt("http"))
        assertTrue("not a budget refusal: no rule decided", r.isNull("rule"))
        assertTrue(r.isNull("resets_at"))
        assertEquals(1, space.host.billedCalls)
        // Reads answer from the device, with their age.
        assertFalse(ok(ConfigurationSpending.GET).getBoolean("online"))
        assertEquals(before, ok(ConfigurationSpending.JOURNAL).getJSONObject("head").getLong("seq"))
        assertEquals(100_000L, atoms(mine(), "used"))
        val summary = ok(ConfigurationSpending.SUMMARY)
        assertFalse(summary.getBoolean("online"))
        assertTrue(summary.getLong("age_ms") >= 0)
        assertTrue(summary.has("last_entry_at") && summary.has("read_at"))
        assertEquals(1, ok(ConfigurationSpending.SOURCES).getJSONArray("sources").length())
        assertEquals(1, ok(ConfigurationSpending.RULES).getInt("generation"))
        assertEquals(0, ok(ConfigurationSpending.LIMITS).getJSONArray("bridgit_funded").length())
        // A reduction applies on this device at once; so does an authorised raise, as an increment (G7.2).
        assertEquals(500_000L, atoms(ok(ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(500_000)}}"""), id = "day").getJSONObject("budget"), "amount"))
        assertEquals(600_000L, atoms(ok(ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(100_000)}}"""), id = "day").getJSONObject("budget").getJSONObject("this_device"), "portion"))
        assertTrue(ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":true}""")).getBoolean("changed"))
        online = true
        ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":false}"""))
        assertTrue(spend(1_000) is SpendRunner.Outcome.Settled)
    }

    // ---- budgets: reduce, raise, top up, gates ----

    @Test fun aLowerAmountIsAReductionAndAHigherOneOrATopUpIsAnIncrementAppliedOnce() {
        ready()
        val lowered = ok(ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(400_000)},"rebalance":false}"""), id = "day").getJSONObject("budget")
        assertEquals(400_000L, atoms(lowered, "amount"))
        assertEquals(400_000L, atoms(lowered.getJSONObject("this_device"), "portion"))
        assertFalse(lowered.getBoolean("rebalance"))
        assertEquals(2, ok(ConfigurationSpending.RULES).getInt("generation"))
        assertEquals("spend_over_budget", refusal(spend(400_001)).getString("error"))

        // A raise is an increment: the document's generation does not move, and the amount stands from now on.
        val raised = ok(ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(700_000)}}"""), id = "day")
        assertEquals("standing", raised.getJSONObject("increment").getString("lasts"))
        assertEquals(300_000L, atoms(raised.getJSONObject("increment"), "amount"))
        assertFalse(raised.getJSONObject("increment").getBoolean("draws_headroom"))
        assertEquals(700_000L, atoms(raised.getJSONObject("budget"), "amount"))
        assertEquals(400_000L, atoms(raised.getJSONObject("budget"), "base"))
        assertEquals(2, raised.getInt("generation"))
        refused("invalid_parameter", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(900_000)},"rebalance":true}"""), id = "day")
        // A reduction lowers the document's amount; what standing raises added is not undone by it.
        val floor = refused("invalid_parameter", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(200_000)}}"""), id = "day")
        assertTrue(floor.getString("message"), floor.getString("message").contains("0.300000"))
        assertEquals(300_000L, atoms(ok(ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(300_000)}}"""), id = "day").getJSONObject("budget"), "amount"))
        ok(ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(700_000)}}"""), id = "day")

        // A top-up lasts this period only, and one idempotency key makes one increment, across a restart too.
        val body = o("""{"namespace":"sandbox","amount":${usd(50_000)}}""")
        val agent = Caller("agent", caller.id, "http")
        val first = ConfigurationSpending.topUp(app, agent, true, "day", body, "${caller.id}:key-1").getJSONObject("increment")
        assertEquals("period", first.getString("lasts"))
        assertEquals("day:2026-11-11", first.getString("period"))
        assertFalse(first.getBoolean("replayed"))
        app.spending.close()
        val again = ConfigurationSpending.topUp(app, agent, true, "day", body, "${caller.id}:key-1").getJSONObject("increment")
        assertEquals(first.getString("id"), again.getString("id"))
        assertTrue(again.getBoolean("replayed"))
        // The key stands for that raise only: for another amount it gets neither the old answer nor a new raise.
        val reused = runCatching { ConfigurationSpending.topUp(app, agent, true, "day", o("""{"namespace":"sandbox","amount":${usd(60_000)}}"""), "${caller.id}:key-1") }.exceptionOrNull() as ApiError
        assertEquals("invalid_parameter", reused.code)
        assertEquals("request_key_reused", reused.extra["reason"])
        val b = budget()
        assertEquals(700_000L, atoms(b, "amount"))
        assertEquals(750_000L, atoms(b, "total"))
        assertEquals(750_000L, atoms(b.getJSONObject("this_device"), "portion"))
        assertEquals(3, b.getJSONArray("increments").length())
        assertNotEquals(first.getString("id"), ConfigurationSpending.topUp(app, agent, true, "day", body, "${caller.id}:key-2").getJSONObject("increment").getString("id"))
        assertEquals(800_000L, atoms(mine(), "portion"))
        assertTrue(spend(800_000) is SpendRunner.Outcome.Settled)

        // The next day the top-ups are gone and the standing amount is what opens.
        clock.advance(24L * 60 * 60 * 1000)
        assertTrue(spend(1) is SpendRunner.Outcome.Settled)
        val next = budget()
        assertEquals("day:2026-11-12", next.getJSONObject("this_device").getString("period_id"))
        assertEquals(700_000L, atoms(next, "total"))
        assertEquals(700_000L, atoms(next.getJSONObject("this_device"), "portion"))
        assertEquals(1L, atoms(next.getJSONObject("this_device"), "used"))

        assertTrue(ok(ConfigurationSpending.BUDGET_DELETE, id = "day").getBoolean("removed"))
        assertEquals(0, ok(ConfigurationSpending.BUDGETS).getJSONArray("budgets").length())
    }

    @Test fun budgetsNeedTheBudgetsCapabilityButReducingAndSwitchesDoNot() {
        ready()
        val plain = controller("Plain", "owner_features")
        val create = o("""{"namespace":"sandbox","id":"week","scope":{"kind":"account"},"period":"week","amount":${usd(5)}}""")
        assertEquals("budgets", refused("agent_not_allowed", ConfigurationSpending.BUDGET_CREATE, create, by = plain).getString("capability"))
        refused("agent_not_allowed", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(2_000_000)}}"""), by = plain, id = "day")
        refused("agent_not_allowed", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(500_000)}}"""), by = plain, id = "day")
        refused("agent_not_allowed", ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(5)}}"""), by = plain, id = "day")
        refused("agent_not_allowed", ConfigurationSpending.BUDGET_GATE, o("""{"namespace":"sandbox","agents_may_change":false}"""), by = plain, id = "day")
        refused("agent_not_allowed", ConfigurationSpending.BUDGET_DELETE, by = plain, id = "day")
        // Setting a per-spend cap only lowers; removing it again raises, which is the capability's.
        ok(ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","caps":{"instruments":{"sandbox":${usd(10)}}}}"""), by = plain)
        refused("agent_not_allowed", ConfigurationSpending.RULES_SET, o("""{"namespace":"sandbox","caps":{"instruments":{"sandbox":null}}}"""), by = plain)
        ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":true}"""), by = plain)
        ok(ConfigurationSpending.SET, o("""{"namespace":"sandbox","paused":false}"""), by = plain)
        assertEquals(1_000_000L, atoms(budget(), "amount"))
        assertEquals(plain.id, ok(ConfigurationSpending.RULES).getJSONObject("actor").getString("id"))
    }

    @Test fun anAgentsGateIsItsOwnRestraintAndTheOwnersGateIsLiftedOnlyByTheOwner() {
        ready(gate = ""","gate":{"ceiling":${usd(1_500_000)}}""")
        val made = budget()
        assertEquals("agent", made.getJSONObject("gate").getString("gate_strength"))
        assertEquals("agent", made.getJSONObject("gate").getJSONObject("set_by").getString("kind"))
        assertEquals(1_500_000L, atoms(made.getJSONObject("gate"), "ceiling"))
        // With one device the whole room under the ceiling is its headroom balance; an agent's raise is drawn from it.
        assertEquals(500_000L, atoms(made.getJSONObject("this_device"), "headroom"))
        val up = ok(ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(200_000)}}"""), id = "day")
        assertTrue(up.getJSONObject("increment").getBoolean("draws_headroom"))
        assertEquals(300_000L, atoms(up.getJSONObject("budget").getJSONObject("this_device"), "headroom"))
        val over = refused("over_ceiling", ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(300_001)}}"""), id = "day")
        assertEquals("day", over.getString("budget"))
        refused("over_ceiling", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(1_400_000)}}"""), id = "day")
        refused("invalid_parameter", ConfigurationSpending.BUDGET_GATE, o("""{"namespace":"sandbox","ceiling":${usd(900_000)}}"""), id = "day")
        // The agent's own gate: it tightens it and lifts it again.
        ok(ConfigurationSpending.BUDGET_GATE, o("""{"namespace":"sandbox","agents_may_change":false}"""), id = "day")
        refused("agents_may_not_change", ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(1)}}"""), id = "day")
        ok(ConfigurationSpending.BUDGET_GATE, o("""{"namespace":"sandbox","agents_may_change":true,"ceiling":null}"""), id = "day")
        assertTrue(budget().getJSONObject("gate").isNull("gate_strength"))
        assertTrue(budget().getJSONObject("gate").isNull("set_by"))
        ok(ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(400_000)}}"""), id = "day")

        // The owner sets a gate (no screen does yet; the debug hook and this test stand in for the owner).
        val owner = Caller("owner", "owner", "app")
        ConfigurationSpending.setGate(app, owner, true, "day", o("""{"namespace":"sandbox","agents_may_change":false}"""))
        val gated = budget().getJSONObject("gate")
        assertEquals("grants", gated.getString("gate_strength"))
        assertEquals("owner", gated.getJSONObject("set_by").getString("kind"))
        refused("agents_may_not_change", ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(1)}}"""), id = "day")
        refused("agents_may_not_change", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","rebalance":false}"""), id = "day")
        val lift = refused("no_approver", ConfigurationSpending.BUDGET_GATE, o("""{"namespace":"sandbox","agents_may_change":true}"""), id = "day")
        assertEquals("owner_lifts_gate", lift.getJSONObject("remaining_human_step").getString("step"))
        assertEquals("grants", lift.getString("gate_strength"))
        // Nothing goes around it: removing the budget is refused the same way. Reducing it is not.
        refused("no_approver", ConfigurationSpending.BUDGET_DELETE, id = "day")
        refused("agents_may_not_change", ConfigurationSpending.BUDGET_UPDATE, o("""{"namespace":"sandbox","amount":${usd(900_000)}}"""), id = "day")
        assertFalse(budget().getJSONObject("gate").getBoolean("agents_may_change"))
        // The owner lifts it; then the agent may change the budget again.
        ConfigurationSpending.setGate(app, owner, true, "day", o("""{"namespace":"sandbox","agents_may_change":true}"""))
        ok(ConfigurationSpending.BUDGET_TOPUP, o("""{"namespace":"sandbox","amount":${usd(1)}}"""), id = "day")

        // An owner's raise is not bound by the agent ceiling, but must fit the room no balance holds.
        ConfigurationSpending.setGate(app, owner, true, "day", o("""{"namespace":"sandbox","ceiling":${usd(1_000_100)}}"""))
        val refusedRaise = space.core.increments.raise("day", Money("USD-6", 5_000_000), "standing", owner, true) as Increments.Made.Refused
        assertEquals(Gates.Why.RAISE_THE_CEILING_FIRST, (refusedRaise.verdict as Gates.Verdict.Refused).why)
        val wire = SpendWire.gate(refusedRaise.verdict, "day")
        assertEquals("raise_the_ceiling_first", wire.code)
        assertEquals(409, wire.http)
    }

    // ---- restarts, reboots and a journal that is not whole ----

    @Test fun afterARebootTheOldCountStandsUntilTheProvidersTimeAnchorsTheJournal() {
        ready()
        assertTrue(spend(100_000) is SpendRunner.Outcome.Settled)
        // The device reboots with its clock set a day ahead. The provider's own time has not moved.
        app.spending.close()
        clock.boots++; clock.elapsed = 1_000
        val real = clock.wall
        clock.wall += 24L * 60 * 60 * 1000
        val host = space.host
        host.clock(space.source("sbx")!!.key, real - clock.wall)
        val time = ok(ConfigurationSpending.GET).getJSONObject("journal").getJSONObject("time")
        assertFalse(time.getBoolean("usable"))
        assertEquals("unanchored", mine().getString("standing"))
        // An anchor the wall clock contradicts starts nothing: yesterday's count still stands, and no new period opened.
        assertTrue(spend(100_000) is SpendRunner.Outcome.Settled)
        assertEquals("day:2026-11-11", mine().getString("period_id"))
        assertEquals(200_000L, atoms(mine(), "used"))
        val r = refusal(spend(900_000))
        assertEquals("allowance_exhausted", r.getString("error"))
        assertEquals(8, r.getInt("rule"))
        assertEquals("time_unanchored", r.getString("reason"))
        assertTrue("no time to promise a reset by", r.isNull("resets_at"))
        // The clock is put right; the provider's time agrees with it and anchors the journal. The same period goes on.
        clock.wall = real + 60_000
        host.clock(space.source("sbx")!!.key, 0)
        assertTrue(spend(100_000) is SpendRunner.Outcome.Settled)
        assertTrue(ok(ConfigurationSpending.GET).getJSONObject("journal").getJSONObject("time").getBoolean("anchored"))
        assertEquals("current", mine().getString("standing"))
        assertEquals(300_000L, atoms(mine(), "used"))
        assertEquals("spend_over_budget", refusal(spend(900_000)).getString("error"))
    }

    @Test fun aJournalThatIsNotWholeStartsANewEpochWithEveryPeriodFullyUsed() {
        ready()
        assertTrue(spend(100_000) is SpendRunner.Outcome.Settled)
        val epoch = ok(ConfigurationSpending.GET).getJSONObject("journal").getString("epoch")
        // The key goes, as clearing the key store would do it, while the app runs: the next call finds the journal not whole.
        keys.forget(Namespace.SANDBOX)
        val broken = refused("conflict", ConfigurationSpending.BUDGETS)
        assertEquals("journal_unverified", broken.getString("reason"))
        assertFalse(app.spending.isOpen)
        val journal = ok(ConfigurationSpending.GET).getJSONObject("journal")
        assertNotEquals(epoch, journal.getString("epoch"))
        assertEquals("reset", journal.getString("origin"))
        assertEquals("key_missing", journal.getString("reset_reason"))
        assertTrue(journal.getBoolean("whole"))
        // The rule document is taken up again from the device's own rows; the period counts as fully used.
        assertEquals(1, ok(ConfigurationSpending.RULES).getInt("generation"))
        assertTrue(mine().isNull("used"))
        val r = refusal(spend(1_000))
        assertEquals("allowance_exhausted", r.getString("error"))
        assertEquals(8, r.getInt("rule"))
        assertEquals(2, ok(ConfigurationSpending.JOURNAL).getJSONArray("epochs").length())
        assertTrue(logged("spend.journal_reset"))
        refused("conflict", ConfigurationSpending.JOURNAL_ACK, o("""{"namespace":"sandbox","acknowledge_history":true}"""))
    }

    @Test fun anUnregisteredInstallHasNoKeyToSignWithSoTheCoreDoesNotOpen() {
        install.registered = false
        enable()
        assertTrue(ok(ConfigurationSpending.GET, query = mapOf("namespace" to "live")).getBoolean("enabled"))
        refused("team_unavailable", ConfigurationSpending.BUDGETS)
        refused("team_unavailable", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox"}"""))
        assertFalse(app.spending.isOpen)
        assertFalse(app.getDatabasePath(file).exists())
        install.registered = true
        assertEquals(0, ok(ConfigurationSpending.BUDGETS).getJSONArray("budgets").length())
    }

    // ---- review findings (Astra, 12 Nov 2026) ----

    @Test fun aSourceIdIsOneCredentialForGoodSoANewLinkNeverReconcilesItsPredecessorsSpends() {
        ready()
        val lost = spend(300_000, detail = mapOf(Detail.LOSE_ANSWER to "true")) as SpendRunner.Outcome.Unknown
        assertEquals(300_000L, atoms(mine(), "used"))
        ok(ConfigurationSpending.SOURCE_UNLINK, id = "sbx")
        // The id stays with its spends: a new key under it would be asked about them, and its empty record would free the hold.
        assertEquals("id_retired", refused("conflict", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox","id":"sbx"}""")).getString("reason"))
        app.spending.close()
        refused("conflict", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox","id":"sbx"}"""))
        // Even with the tombstone gone, the journal still names the id.
        app.getSharedPreferences("spending_sandbox", android.content.Context.MODE_PRIVATE).edit().remove("retired:sbx").commit()
        refused("conflict", ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox","id":"sbx"}"""))
        assertFalse(space.runner.reconcile("sbx").read)
        // A new link is a new id; reconciling it leaves its predecessor's hold whole.
        val other = ok(ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox"}""")).getString("id")
        assertNotEquals("sbx", other)
        val read = space.runner.reconcile(other)
        assertTrue(read.read)
        assertTrue(read.givenBack.isEmpty() && read.settled.isEmpty())
        assertEquals("unknown", space.journal.latest(lost.spendId)!!.state!!.id)
        assertEquals(300_000L, atoms(mine(), "used"))
        // A made-up id skips a retired one as well.
        ok(ConfigurationSpending.SOURCE_UNLINK, id = other)
        assertNotEquals(other, ok(ConfigurationSpending.SOURCE_LINK, o("""{"instrument":"sandbox"}""")).getString("id"))
    }

    @Test fun aReconcileRightAfterAHoldCommitsLeavesItAloneUntilItsCallHasAnswered() {
        ready()
        var between: SpendRunner.Reconciled? = null
        var stateThen: String? = null
        val runner = space.runner
        // Exactly the gap the review names: the hold is in the journal and the provider has not been called.
        runner.afterHold = {
            runner.afterHold = null
            between = runner.reconcile("sbx")
            stateThen = space.journal.entries().last { it.kind == "spend" }.state!!.id
        }
        val done = spend(400_000) as SpendRunner.Outcome.Settled
        assertTrue(between!!.read)
        assertTrue(between!!.givenBack.isEmpty() && between!!.settled.isEmpty() && between!!.left.isEmpty())
        assertEquals("the hold is still a hold, not an unknown outcome to be given back", "held", stateThen)
        assertEquals(400_000L, done.cost.atoms)
        assertEquals(listOf("held", "settled"), space.journal.entries().filter { it.spendId == done.spendId }.map { it.state!!.id })
        assertEquals(400_000L, atoms(mine(), "used"))
        // The room was never returned: another spend cannot take what the first one holds.
        assertEquals("spend_over_budget", refusal(spend(700_000)).getString("error"))
        // A hold with no run behind it (the app died before the answer) is still found and kept as unknown.
        runner.afterHold = { throw IllegalStateException("the app stops here") }
        assertTrue(runCatching { spend(100_000) }.isFailure)
        runner.afterHold = null
        val after = runner.reconcile("sbx")
        assertEquals(1, after.givenBack.size)
        assertEquals(400_000L, atoms(mine(), "used"))
    }

    @Test fun aRetriedBudgetPatchIsAnsweredFromWhatItsKeyDidAndNeverWorkedOutAgain() {
        ready()
        val agent = Caller("agent", caller.id, "http")
        fun patch(atoms: Long, key: String) = ConfigurationSpending.updateBudget(app, agent, true, "day", o("""{"namespace":"sandbox","amount":${usd(atoms)}}"""), "${caller.id}:$key")
        val first = patch(1_500_000, "k-raise")
        assertEquals(500_000L, atoms(first.getJSONObject("increment"), "amount"))
        assertFalse(first.has("replayed"))
        patch(2_000_000, "k-more")
        // After a restart the same key and target must not turn into a reduction to 1.5.
        app.spending.close()
        val again = patch(1_500_000, "k-raise")
        assertTrue(again.getBoolean("replayed"))
        assertEquals(first.getJSONObject("increment").getString("id"), again.getJSONObject("increment").getString("id"))
        assertTrue(again.getJSONObject("increment").getBoolean("replayed"))
        assertEquals(2_000_000L, atoms(again.getJSONObject("budget"), "amount"))
        assertEquals(1_000_000L, atoms(budget(), "base"))
        assertEquals(1, ok(ConfigurationSpending.RULES).getInt("generation"))
        assertEquals(2, budget().getJSONArray("increments").length())
        // The same for a reduction: its retry does not lower a budget that was raised since.
        val lowered = patch(1_200_000, "k-lower")
        assertEquals(1_200_000L, atoms(lowered.getJSONObject("budget"), "amount"))
        patch(1_800_000, "k-up")
        app.spending.close()
        val replay = patch(1_200_000, "k-lower")
        assertTrue(replay.getBoolean("replayed"))
        assertFalse(replay.has("increment"))
        assertEquals(1_800_000L, atoms(budget(), "amount"))
        assertEquals(2, ok(ConfigurationSpending.RULES).getInt("generation"))
        // A key stands for one request.
        val reused = runCatching { patch(900_000, "k-lower") }.exceptionOrNull() as ApiError
        assertEquals("invalid_parameter", reused.code)
        assertEquals("request_key_reused", reused.extra["reason"])
        assertEquals(1_800_000L, atoms(budget(), "amount"))
    }

    @Test fun aRefusalOfAGateIsAnApiErrorWithItsField() {
        val e: ApiError = SpendWire.gate(Gates.Verdict.Refused(Gates.Why.BUDGETS_CAPABILITY_REQUIRED, "budgets/day"), "day")
        assertEquals("agent_not_allowed", e.code)
        assertEquals("budgets/day", e.extra["field"])
        assertEquals("owner_authorization_required", SpendWire.gate(Gates.Verdict.AwaitingOwner("Approve the change on an approver device")).code)
    }
}
