package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.interact.CardPieces
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.platform.FlowBatches
import cc.bridgit.app.platform.FlowSteps
import cc.bridgit.app.platform.WorkflowEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Applet wave 2, the flow engine (docs/applet-wave2.md): the appraise step, record with for_each, the update step,
 * card-button batches (order, restart, cut-off element, cancel, max, budget stop) and the phase 3 fixes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletWave2Test {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private val link = o("kind" to "link", "url" to "https://bridgit.cc/recipes/voice-notebook/applet.json")
    private val realAppraiser = WorkflowEngine.appraiser

    @Before fun phoneReady() {
        app.settings.listenerEnabled = true
        for (m in app.registry.switchable()) app.settings.setOn(m.switchKey!!, true)
        for (s in listOf("workflows", "interactions", "decisions")) cc.bridgit.app.platform.Capabilities.setEnabled(app, s, true)
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
    }

    @After fun cleanUp() = runBlocking {
        WorkflowEngine.appraiser = realAppraiser
        for (r in app.applets.all()) app.applets.remove(r.getString("id"))
        app.applets.pending().forEach { app.applets.pendingDrop(it.getString("id")) }
    }

    // ---- a small inventory applet -------------------------------------------------------------------------------

    private fun ref(kind: String, v: String) = o(kind to v)
    private val batchDo get() = o("flow" to o("\$flow" to "read"), "for_each" to ref("\$source", "photos"), "only_if" to o("item" to "status", "is" to "new"),
        "with" to o("row" to ref("\$item", "id"), "photo" to ref("\$item", "photo")), "max" to 200, "progress" to "Reading")

    private fun inventory(edit: (JSONObject) -> Unit = {}): AppletPackage.Package {
        val d = JSONObject(File("../site-content/public/recipes/voice-notebook/applet.json").readText())
        d.put("id", "inventory-test").put("name", "Inventory test")
        d.put("processing", o(
            "tables" to a(
                o("id" to "photos", "columns" to o("photo" to o("type" to "photo"), "status" to o("type" to "choice", "options" to a("new", "reading", "done")), "items" to o("type" to "integer"))),
                o("id" to "items", "columns" to o("label" to o("type" to "text"), "new_usd" to o("type" to "number"), "box" to o("type" to "text"), "photo" to o("type" to "photo"))),
                o("id" to "log", "columns" to o("n" to o("type" to "number")))),
            "flows" to a(
                o("id" to "read", "name" to "Read a photo", "max_paid_per_day" to 100, "trigger" to o("type" to "called", "with" to o("row" to "text", "photo" to "text")), "steps" to a(
                    o("id" to "look", "type" to "appraise", "photo" to ref("\$trigger", "with.photo"), "max_items" to 30),
                    o("id" to "rec", "type" to "record", "table" to "items", "for_each" to ref("\$step", "look.items"),
                        "row" to o("label" to ref("\$item", "label"), "new_usd" to ref("\$item", "new_usd"), "box" to ref("\$item", "box"), "photo" to ref("\$trigger", "with.photo"))),
                    o("id" to "mark", "type" to "update", "table" to "photos", "row" to ref("\$trigger", "with.row"), "set" to o("status" to "DONE", "items" to ref("\$step", "rec.count"))))),
                o("id" to "log", "name" to "Log", "trigger" to o("type" to "called", "with" to o("n" to "number")), "steps" to a(
                    o("type" to "record", "table" to "log", "row" to o("n" to ref("\$trigger", "with.n"))))),
                o("id" to "slow", "name" to "Slow log", "trigger" to o("type" to "called", "with" to o("n" to "number")), "steps" to a(
                    o("type" to "wait", "seconds" to 1), o("type" to "record", "table" to "log", "row" to o("n" to ref("\$trigger", "with.n"))))))))
        d.put("permissions", o("required" to a("interactions", "workflows", "decisions"), "optional" to a(), "remote" to a(), "automatic_grants" to false))
        d.getJSONObject("ui").put("card", o("title" to "Inventory test", "sources" to o("photos" to o("table" to "photos"), "progress" to o("batch" to "read_all")),
            "pieces" to a(o("type" to "header", "title" to "Inventory"),
                o("type" to "buttons", "items" to a(o("id" to "read_all", "label" to "Create inventory", "style" to "primary", "do" to batchDo))),
                o("type" to "metrics", "items" to a(o("label" to "Read", "value" to ref("\$source", "progress.done")))))))
        edit(d)
        return AppletPackage.check(d)
    }

    private fun install(p: AppletPackage.Package = inventory()): JSONObject = runBlocking {
        app.applets.approve(app.applets.propose(p, null, link, null, notify = false).getString("id"), null)
    }

    private fun table(agent: String, id: String) = AppletTables.parse(app.applets.forAgent(agent)!!.getJSONObject("definition").getJSONObject("processing")).single { it.id == id }
    private fun item(label: String, usd: Any?, x0: Double = 0.1) = o("label" to label, "new_usd" to usd, "box" to o("x0" to x0, "y0" to 0.2, "x1" to 0.5, "y1" to 0.123456))
    private fun appraised(vararg items: JSONObject) = Jev.Appraised(null, items.toList(), "test-model", 0.01, "team")
    private val store get() = AppletTableStore.of(app)
    private val by = o("type" to "card")

    private fun photoRow(agent: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): JSONObject {
        val ref = store.keepPhoto(agent, "camera", bytes)
        return store.add(agent, table(agent, "photos"), o("photo" to ref, "status" to "new"), by)
    }

    private fun waitFor(ms: Long = 15_000, what: () -> Boolean) = runBlocking { withTimeout(ms) { while (!what()) delay(50) } }
    private fun batch(agent: String) = app.platform.store.list(agent, FlowBatches.KIND, 10).maxByOrNull { it.optLong("started_at") }!!
    private fun finished(agent: String) = waitFor { batch(agent).optString("status") in FlowBatches.FINISHED }
    private fun wf(r: JSONObject, flow: String) = r.getJSONObject("flows").getJSONObject(flow).getString("workflow_id")

    // ---- appraise + record for_each + update ------------------------------------------------------------------

    @Test fun publishedForkRunsItsSelectedAppraisalAndSavesTheReturnedRows() = runBlocking {
        val pkg = inventory { d ->
            val step = AppletPackage.flowBodies(d.getJSONObject("processing")).first().getJSONArray("steps").getJSONObject(0)
            step.put("type","service").put("service","appraise.signed-edition-budget.v2").put("operation","appraise")
            d.getJSONObject("permissions").put("remote",a("Send the selected inventory photo for appraisal."))
            d.put("data_policy",o("device_only" to o("remote_models" to true)))
        }
        val owner = "a".repeat(64)
        val src = o("kind" to "template","template" to "private-family","revision" to 4,"library_account" to owner)
        val r = app.applets.approve(app.applets.propose(pkg,null,src,null,notify=false).getString("id"),null)
        val agent = r.getString("agent_id")
        val row = photoRow(agent)
        app.budgetSync.transport = { _,_,body ->
            assertEquals(owner,body!!.getString("library_owner"))
            200 to o("account" to "linked","applets" to a(o("applet" to pkg.id,"limits" to a(o("usd" to 1)))))
        }
        try {
            WorkflowEngine.appraiser = { _,_,max,pin,verify ->
                verify()
                assertEquals(30,max)
                assertEquals("private-family",pin!!.getString("key"))
                assertEquals(4,pin.getInt("revision"))
                assertEquals("read",pin.getString("action"))
                appraised(item("Desk",50))
            }
            val run = app.platform.workflows.runNow(agent,wf(r,"read"),o("row" to row.getString("id"),"photo" to row.getString("photo")))
            assertEquals(run.toString(),"completed",run.getString("status"))
            assertEquals("Desk",store.rows(agent,"items",10).single().getString("label"))
        } finally { app.budgetSync.transport = null }
    }

    @Test fun aBatchReadsEachNewPhotoIntoItemRows() {
        val seen = mutableListOf<Int>()
        WorkflowEngine.appraiser = { _, bytes, max, _, _ -> seen += bytes.size; assertEquals(30, max); appraised(item("Sofa", "$1,200"), item("Lamp", 40, 0.6)) }
        val r = install(); val agent = r.getString("agent_id")
        val rows = listOf(photoRow(agent, byteArrayOf(1)), photoRow(agent, byteArrayOf(1, 2)))
        // A photo already done is skipped by only_if.
        store.add(agent, table(agent, "photos"), o("photo" to rows[0].getString("photo"), "status" to "done"), by)
        val elements = CardPieces.batchElements(batchDo, JSONArray(store.rows(agent, "photos", 100, null, newestFirst = false)), CardPieces.Values(emptyMap()))
        assertEquals(2, elements.size)
        assertEquals(rows[0].getString("id"), elements[0].first); assertEquals(rows[0].getString("photo"), elements[0].second.getString("photo"))
        val started = app.platform.batches.start(agent, "read_all", "Create inventory", "read", elements)
        assertEquals(started.toString(), "started", started.getString("status"))
        finished(agent)
        val s = app.platform.batches.summary(agent, "read_all")
        assertEquals(s.toString(), "done", s.getString("status")); assertEquals(2, s.getInt("done")); assertEquals(0, s.getInt("failed")); assertEquals(2, s.getInt("total"))
        assertEquals(listOf(1, 2), seen) // in order, the kept photo's bytes
        val items = store.rows(agent, "items", 100, null, newestFirst = false)
        assertEquals(listOf("Sofa", "Lamp", "Sofa", "Lamp"), items.map { it.getString("label") })
        assertEquals(1200.0, items[0].getDouble("new_usd"), 0.0)
        assertEquals("0.1000,0.2000,0.5000,0.1235", items[0].getString("box"))
        assertEquals(rows[0].getString("photo"), items[0].getString("photo"))
        for (row in rows) store.get(agent, "photos", row.getString("id"))!!.let { assertEquals("done", it.getString("status")); assertEquals(2, it.getInt("items")) }
        // Each run is a normal run with trigger.caller batch and trigger.batch {id, index, total}.
        val runs = app.platform.workflows.runs(agent, wf(r, "read"), 10).getJSONArray("runs")
        assertEquals(2, runs.length())
        val t = runs.getJSONObject(0).getJSONObject("trigger")
        assertEquals("batch", t.getString("caller")); assertEquals(2, t.getJSONObject("batch").getInt("total")); assertEquals(s.getString("id"), t.getJSONObject("batch").getString("id"))
        val look = runs.getJSONObject(0).getJSONArray("steps").getJSONObject(0)
        assertEquals("ok", look.getString("status")); assertEquals(2, look.getJSONObject("result").getJSONArray("items").length())
        assertEquals(0.1, look.getJSONObject("result").getJSONArray("items").getJSONObject(0).getJSONObject("box").getDouble("x0"), 0.0) // boxes stay 0-1
        // A second batch finds nothing new.
        val again = CardPieces.batchElements(batchDo, JSONArray(store.rows(agent, "photos", 100, null, newestFirst = false)), CardPieces.Values(emptyMap()))
        assertTrue(again.isEmpty())
        assertEquals("empty", app.platform.batches.start(agent, "read_all", "Create inventory", "read", again).getString("status"))
    }

    @Test fun recordForEachWritesAllRowsOrNone() = runBlocking {
        val r = install(); val agent = r.getString("agent_id"); val photo = photoRow(agent)
        val with = o("row" to photo.getString("id"), "photo" to photo.getString("photo"))
        // One bad element: nothing is written, and the photo row isn't marked.
        WorkflowEngine.appraiser = { _, _, _, _, _ -> appraised(item("Sofa", 10), item("Chair", "lots")) }
        val bad = app.platform.workflows.runNow(agent, wf(r, "read"), with)
        assertEquals(bad.toString(), "failed", bad.getString("status")); assertEquals("invalid_row", bad.getString("error"))
        assertTrue(bad.getJSONArray("steps").getJSONObject(1).getString("message").contains("Row 2"))
        assertEquals(0, store.count(agent, "items"))
        assertEquals("new", store.get(agent, "photos", photo.getString("id"))!!.getString("status"))
        // More than 50: refused.
        WorkflowEngine.appraiser = { _, _, _, _, _ -> appraised(*(1..51).map { item("Thing $it", it) }.toTypedArray()) }
        assertEquals("invalid_row", app.platform.workflows.runNow(agent, wf(r, "read"), with).getString("error"))
        assertEquals(0, store.count(agent, "items"))
        // Fifty rows are one write: the 60-a-minute limit still has room for the next run.
        WorkflowEngine.appraiser = { _, _, _, _, _ -> appraised(*(1..50).map { item("Thing $it", it) }.toTypedArray()) }
        val ok = app.platform.workflows.runNow(agent, wf(r, "read"), with)
        assertEquals(ok.toString(), "completed", ok.getString("status"))
        assertEquals(50, ok.getJSONArray("steps").getJSONObject(1).getJSONObject("result").getInt("count"))
        assertEquals(50, store.count(agent, "items"))
        assertEquals(50, store.get(agent, "photos", photo.getString("id"))!!.getInt("items"))
    }

    @Test fun updateNamesAMissingRowAndAModelFailure() = runBlocking {
        val r = install(); val agent = r.getString("agent_id"); val photo = photoRow(agent)
        WorkflowEngine.appraiser = { _, _, _, _, _ -> appraised() }
        val gone = app.platform.workflows.runNow(agent, wf(r, "read"), o("row" to "nothere", "photo" to photo.getString("photo")))
        assertEquals("row_not_found", gone.getString("error"))
        // No kept photo: the appraise step fails before any paid call.
        var called = false
        WorkflowEngine.appraiser = { _, _, _, _, _ -> called = true; appraised() }
        assertEquals("photo_not_found", app.platform.workflows.runNow(agent, wf(r, "read"), o("row" to photo.getString("id"), "photo" to "kept:nothere")).getString("error"))
        assertFalse(called)
        WorkflowEngine.appraiser = { _, _, _, _, _ -> throw Jev.Failure("budget_reached", "This applet's limit is used up") }
        assertEquals("budget_reached", app.platform.workflows.runNow(agent, wf(r, "read"), o("row" to photo.getString("id"), "photo" to photo.getString("photo"))).getString("error"))
        // Failed runs never block the workflow.
        assertEquals("active", app.platform.store.find(wf(r, "read"))!!.getString("status"))
    }

    @Test fun storeUpdateMergesCoercesAndDropsAnUnusedPhoto() {
        val ag = "test-" + java.util.UUID.randomUUID().toString().take(8)
        val t = AppletTables.parse(o("tables" to a(o("id" to "photos", "columns" to o("photo" to o("type" to "photo"), "status" to o("type" to "choice", "options" to a("new", "done")), "items" to o("type" to "integer")))))).single()
        val ref = store.keepPhoto(ag, "camera", byteArrayOf(9))
        val row = store.add(ag, t, o("photo" to ref, "status" to "new"), by)
        val id = row.getString("id")
        val up = store.update(ag, t, id, o("status" to " DONE ", "items" to "3"), o("type" to "flow"))
        assertEquals(id, up.getString("id")); assertEquals("done", up.getString("status")); assertEquals(3L, up.getLong("items")); assertEquals(ref, up.getString("photo"))
        assertEquals(1, store.count(ag, "photos"))
        try { store.update(ag, t, id, o("items" to "many"), by); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        try { store.update(ag, t, id, o("nope" to 1), by); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        assertEquals("done", store.get(ag, "photos", id)!!.getString("status")) // a refused update changes nothing
        try { store.update(ag, t, "zzzz", o("status" to "new"), by); fail() } catch (e: AppletTables.Refused) { assertEquals("row_not_found", e.code) }
        // Clearing the photo deletes the kept photo no other row uses.
        assertNotNull(store.photoFile(ag, ref))
        store.update(ag, t, id, o("photo" to JSONObject.NULL), by)
        assertNull(store.photoFile(ag, ref))
        assertTrue(store.get(ag, "photos", id)!!.isNull("photo"))
    }

    @Test fun addManyChecksEveryRowFirstAndCountsOnce() {
        val ag = "test-" + java.util.UUID.randomUUID().toString().take(8)
        val t = AppletTables.parse(o("tables" to a(o("id" to "log", "columns" to o("n" to o("type" to "integer")), "max_rows" to 60)))).single()
        try { store.addMany(ag, t, listOf(o("n" to 1), o("n" to "x")), by); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        assertEquals(0, store.count(ag, "log"))
        repeat(AppletTables.MAX_WRITES_PER_MINUTE - 1) { store.addMany(ag, t, listOf(o("n" to it)), by) }
        // One more write of 50 rows fits (one write); max_rows 60 drops the oldest.
        assertEquals(50, store.addMany(ag, t, (1..50).map { o("n" to 1000 + it) }, by).size)
        assertEquals(60, store.count(ag, "log"))
        assertEquals(1050L, store.rows(ag, "log", 1).single().getLong("n"))
        try { store.addMany(ag, t, listOf(o("n" to 1)), by); fail() } catch (e: AppletTables.Refused) { assertEquals("rate_limited", e.code) }
        try { store.addMany(ag, t, (1..51).map { o("n" to it) }, by); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
    }

    @Test fun anAgentsEditKeepsTheFreedPhotoForTheSweepAndCountsTowardTheLimit() {
        val ag = "test-" + java.util.UUID.randomUUID().toString().take(8)
        val t = AppletTables.parse(o("tables" to a(o("id" to "photos", "columns" to o("photo" to o("type" to "photo"), "items" to o("type" to "integer")))))).single()
        val agentBy = o("type" to "agent", "agent_id" to "other")
        val ref = store.keepPhoto(ag, "camera", byteArrayOf(9))
        val id = store.add(ag, t, o("photo" to ref), by).getString("id")
        // An agent clears the photo: the row changes, the kept photo stays until the 7-day sweep.
        store.update(ag, t, id, o("photo" to JSONObject.NULL), agentBy)
        assertTrue(store.get(ag, "photos", id)!!.isNull("photo"))
        assertNotNull(store.photoFile(ag, ref))
        store.clean(ag, listOf(t), now = System.currentTimeMillis() + 60_000)
        assertNotNull(store.photoFile(ag, ref))
        store.clean(ag, listOf(t), now = System.currentTimeMillis() + AppletTables.ORPHAN_PHOTO_MS + 60_000)
        assertNull(store.photoFile(ag, ref))
        // Agent edits are writes: the 61st in a minute is refused, while the owner's own edits never are.
        // (the add above and the first edit were two of them)
        repeat(AppletTables.MAX_WRITES_PER_MINUTE - 2) { store.update(ag, t, id, o("items" to it), agentBy) }
        try { store.update(ag, t, id, o("items" to 99), agentBy); fail() } catch (e: AppletTables.Refused) { assertEquals("rate_limited", e.code) }
        assertEquals(57L, store.get(ag, "photos", id)!!.getLong("items"))
        store.update(ag, t, id, o("items" to 100), o("owner" to true))
        assertEquals(100L, store.get(ag, "photos", id)!!.getLong("items"))
    }

    @Test fun oneWriteOfMoreRowsThanTheTableKeepsIsRefused() {
        val ag = "test-" + java.util.UUID.randomUUID().toString().take(8)
        val t = AppletTables.parse(o("tables" to a(o("id" to "log", "columns" to o("n" to o("type" to "integer")), "max_rows" to 10)))).single()
        store.addMany(ag, t, (1..10).map { o("n" to it) }, by)
        try { store.addMany(ag, t, (1..11).map { o("n" to 100 + it) }, by); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code); assertTrue(e.message!!.contains("at most 10")) }
        assertEquals((1..10).toList(), store.rows(ag, "log", 100, null, newestFirst = false).map { it.getInt("n") })
        // Up to max_rows still fits, evicting the oldest.
        store.addMany(ag, t, (1..3).map { o("n" to 100 + it) }, by)
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10, 101, 102, 103), store.rows(ag, "log", 100, null, newestFirst = false).map { it.getInt("n") })
    }

    // ---- batches ----------------------------------------------------------------------------------------------

    private fun numbers(vararg n: Int) = n.map { "k$it" to o("n" to it) }
    private fun logged(agent: String) = store.rows(agent, "log", 100, null, newestFirst = false).map { it.getInt("n") }

    @Test fun aBatchRunsInOrderOneAtATimeAndASecondStartWaits() {
        val r = install(); val agent = r.getString("agent_id")
        assertEquals("started", app.platform.batches.start(agent, "b", "Log all", "slow", numbers(1, 2, 3)).getString("status"))
        // One batch per applet: another start while it runs does nothing.
        assertEquals("busy", app.platform.batches.start(agent, "b", "Log all", "log", numbers(9)).getString("status"))
        waitFor { app.platform.batches.summary(agent, "b").opt("current") == 2 }
        finished(agent)
        assertEquals(listOf(1, 2, 3), logged(agent))
        val s = app.platform.batches.summary(agent, "Log all") // by label too
        assertEquals("done", s.getString("status")); assertEquals(3, s.getInt("done")); assertTrue(s.isNull("current"))
        assertEquals("none", app.platform.batches.summary(agent, "other").getString("status"))
        // with values are checked against the flow before anything runs.
        assertEquals("bad_with", app.platform.batches.start(agent, "b", "Log all", "log", listOf("x" to o("n" to "one"))).getString("status"))
        assertEquals("flow_not_found", app.platform.batches.start(agent, "b", "Log all", "nope", numbers(1)).getString("status"))
    }

    @Test fun cancelStopsAfterTheCurrentRun() {
        val r = install(); val agent = r.getString("agent_id")
        app.platform.batches.start(agent, "b", "Log all", "slow", numbers(1, 2, 3, 4, 5))
        // The batch cursor points to the first item even before its workflow starts.
        waitFor { app.platform.workflows.busy(wf(r, "slow")) }
        assertTrue(app.platform.batches.cancel(agent))
        assertTrue(app.platform.batches.summary(agent, "b").getBoolean("cancelling"))
        finished(agent)
        val b = batch(agent)
        assertEquals("cancelled", b.getString("status"))
        assertEquals(listOf(1), logged(agent)) // the run going on finished; nothing after it
        val els = b.getJSONArray("elements")
        assertEquals("done", els.getJSONObject(0).getString("status"))
        for (i in 1 until 5) assertEquals("not_run", els.getJSONObject(i).getString("status"))
        assertFalse(app.platform.batches.cancel(agent))
    }

    /** The test inventory with [flow] in place of "slow" (a package has at most 4 flows). */
    private fun withFlow(flow: JSONObject) = inventory { d ->
        val flows = d.getJSONObject("processing").getJSONArray("flows")
        for (i in 0 until flows.length()) if (flows.getJSONObject(i).getString("id") == "slow") flows.put(i, flow)
    }

    /** A share step opens the share sheet for the owner: never in a batch, never by an agent's workflow.run. */
    @Test fun aSharingFlowNeverRunsAsABatchOrForAnAgent() = runBlocking {
        val r = install(withFlow(o("id" to "export", "name" to "Share the log", "trigger" to o("type" to "button"), "steps" to a(
            o("type" to "share", "table" to "log", "as" to "csv")))))
        val agent = r.getString("agent_id"); val wfId = wf(r, "export")
        val started = app.platform.batches.start(agent, "b", "Share all", "export", numbers(1, 2))
        assertEquals(started.toString(), "flow_not_active", started.getString("status"))
        assertTrue(started.getString("message").contains("share step"))
        assertTrue(app.platform.store.list(agent, FlowBatches.KIND, 10).isEmpty())
        try { app.platform.workflows.runNow(agent, wfId, null); fail() } catch (e: cc.bridgit.app.ApiError) { assertEquals("workflow_not_active", e.code); assertTrue(e.message!!.contains("share step")) }
        assertEquals(0, app.platform.workflows.runs(agent, wfId, 10).getJSONArray("runs").length())
        // A running batch of it (saved before this check, or its flow changed since) is stopped after a restart, not resumed.
        val id = "batch-share" + java.util.UUID.randomUUID().toString().take(6)
        app.platform.store.save(agent, FlowBatches.KIND, o("id" to id, "button" to "b", "label" to "Share all", "flow" to "export", "workflow_id" to wfId, "status" to "running",
            "cursor" to 0, "total" to 2, "done" to 0, "failed" to 0, "unknown" to 0, "elements" to a(o("key" to "k1", "with" to o(), "status" to "pending"), o("key" to "k2", "with" to o(), "status" to "pending")),
            "started_at" to System.currentTimeMillis()))
        app.platform.batches.resume()
        val b = app.platform.store.find(id, agent, FlowBatches.KIND)!!
        assertEquals("stopped", b.getString("status")); assertEquals("share_needs_owner", b.getString("stop_reason"))
        assertEquals("not_run", b.getJSONArray("elements").getJSONObject(0).getString("status"))
        assertEquals(0, app.platform.workflows.runs(agent, wfId, 10).getJSONArray("runs").length())
    }

    /** Cancel while the batch waits for its flow (busy with another start) ends it at once, with nothing run. */
    @Test fun cancelWhileWaitingForABusyFlowEndsTheBatch() = runBlocking {
        val r = install(withFlow(o("id" to "slower", "name" to "Slower log", "trigger" to o("type" to "called", "with" to o("n" to "number")), "steps" to a(
            o("type" to "wait", "seconds" to 3), o("type" to "record", "table" to "log", "row" to o("n" to ref("\$trigger", "with.n")))))))
        val agent = r.getString("agent_id"); val wfId = wf(r, "slower")
        val direct = launch(kotlinx.coroutines.Dispatchers.IO) { app.platform.workflows.runNow(agent, wfId, o("n" to 7)) }
        withTimeout(5_000) { while (!app.platform.workflows.busy(wfId)) delay(20) }
        assertEquals("started", app.platform.batches.start(agent, "b", "Log all", "slower", numbers(1, 2)).getString("status"))
        delay(300)
        assertTrue(app.platform.batches.cancel(agent))
        withTimeout(2_500) { while (batch(agent).optString("status") !in FlowBatches.FINISHED) delay(50) } // before the 3 s run ends
        val b = batch(agent)
        assertEquals("cancelled", b.getString("status")); assertEquals(0, b.getInt("done")); assertEquals(0, b.getInt("failed"))
        for (i in 0 until 2) assertEquals("not_run", b.getJSONArray("elements").getJSONObject(i).getString("status"))
        direct.join()
        assertEquals(listOf(7), logged(agent))
        // The batch's waiting was never counted as dropped runs.
        assertEquals(0, app.platform.store.find(wfId)!!.optInt("dropped_runs"))
    }

    @Test fun maxAndOnlyIfPickTheElements() {
        val list = JSONArray((1..300).map { o("id" to "r$it", "status" to if (it % 2 == 0) "done" else "new", "photo" to "kept:p$it") })
        val d = JSONObject(batchDo.toString()).put("max", 3)
        val e = CardPieces.batchElements(d, list, CardPieces.Values(emptyMap()))
        assertEquals(listOf("r1", "r3", "r5"), e.map { it.first })
        assertEquals(o("row" to "r3", "photo" to "kept:p3").toString(), e[1].second.toString())
        assertEquals(50, CardPieces.batchElements(JSONObject(batchDo.toString()).apply { remove("max"); remove("only_if") }, list, CardPieces.Values(emptyMap())).size)
        assertEquals(200, CardPieces.batchElements(JSONObject(batchDo.toString()).apply { remove("only_if") }, list, CardPieces.Values(emptyMap())).size)
        // A plain list without ids: the key is the place in the list; with may also read the card.
        val plain = CardPieces.batchElements(o("for_each" to ref("\$source", "x.items"), "with" to o("n" to ref("\$item", "n"), "note" to ref("\$source", "x.note"))),
            a(o("n" to 5), o("n" to 6)), CardPieces.Values(mapOf("x" to o("note" to "hi"))))
        assertEquals(listOf("0", "1"), plain.map { it.first }); assertEquals("hi", plain[1].second.getString("note"))
        val r = install(); val agent = r.getString("agent_id")
        assertEquals("bad_with", app.platform.batches.start(agent, "b", "Log", "log", (1..201).map { "k$it" to o("n" to it) }).getString("status"))
    }

    /** After process death: the element that was running is marked unknown_outcome and skipped; the rest run; the workflow isn't blocked. */
    @Test fun aRestartResumesAtTheCursorAndSkipsTheCutOffElement() {
        val r = install(); val agent = r.getString("agent_id"); val wfId = wf(r, "log")
        val id = "batch-restart" + java.util.UUID.randomUUID().toString().take(6)
        val els = a(o("key" to "k1", "with" to o("n" to 1), "status" to "done"), o("key" to "k2", "with" to o("n" to 2), "status" to "running"),
            o("key" to "k3", "with" to o("n" to 3), "status" to "pending"), o("key" to "k4", "with" to o("n" to 4), "status" to "pending"))
        app.platform.store.save(agent, FlowBatches.KIND, o("id" to id, "button" to "b", "label" to "Log all", "flow" to "log", "workflow_id" to wfId, "status" to "running",
            "cursor" to 1, "total" to 4, "done" to 1, "failed" to 0, "unknown" to 0, "elements" to els, "started_at" to System.currentTimeMillis()))
        // The run of element 2 was half-way when the phone stopped.
        app.platform.store.save(agent, "execution", o("workflow_id" to wfId, "status" to "running", "step" to 0, "steps" to a(),
            "trigger" to o("caller" to "batch", "batch" to o("id" to id, "index" to 1, "total" to 4)), "started_at" to System.currentTimeMillis()))
        app.platform.reconcileInterrupted()
        assertEquals("active", app.platform.store.find(wfId)!!.getString("status")) // phase 3 fix: not blocked
        app.platform.batches.resume()
        finished(agent)
        assertEquals(listOf(3, 4), logged(agent)) // element 2 isn't run again
        val s = app.platform.batches.summary(agent, "b")
        assertEquals("done", s.getString("status")); assertEquals(3, s.getInt("done")); assertEquals(1, s.getInt("unknown"))
        assertEquals("k2", s.getJSONArray("unknown_items").getJSONObject(0).getString("key"))
        assertEquals(2, s.getJSONArray("unknown_items").getJSONObject(0).getInt("index"))
        val runs = app.platform.workflows.runs(agent, wfId, 10).getJSONArray("runs")
        assertEquals(setOf("unknown_outcome", "completed"), (0 until runs.length()).map { runs.getJSONObject(it).getString("status") }.toSet())
    }

    @Test fun aBudgetStopEndsTheBatchAndSaysWhy() {
        WorkflowEngine.appraiser = { _, _, _, _, _ -> throw Jev.Failure("budget_reached", "Used up") }
        val r = install(); val agent = r.getString("agent_id")
        repeat(3) { photoRow(agent) }
        val elements = CardPieces.batchElements(batchDo, JSONArray(store.rows(agent, "photos", 100, null, newestFirst = false)), CardPieces.Values(emptyMap()))
        app.platform.batches.start(agent, "read_all", "Create inventory", "read", elements)
        finished(agent)
        val s = app.platform.batches.summary(agent, "read_all")
        assertEquals("stopped", s.getString("status")); assertEquals("budget_reached", s.getString("stop_reason"))
        assertEquals(1, s.getInt("failed")); assertEquals("budget_reached", s.getJSONArray("failed_items").getJSONObject(0).getString("error"))
        assertEquals("not_run", batch(agent).getJSONArray("elements").getJSONObject(2).getString("status"))
    }

    // ---- the card button and source ---------------------------------------------------------------------------

    private fun bad(part: String, edit: (JSONObject) -> Unit) {
        try { inventory(edit); fail("expected refusal: $part") } catch (e: AppletPackage.Bad) { assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }
    private fun button(d: JSONObject) = d.getJSONObject("ui").getJSONObject("card").getJSONArray("pieces").getJSONObject(1).getJSONArray("items").getJSONObject(0)
    /** The card without its batch source and the metric that reads it. */
    private fun noBatch(d: JSONObject): JSONObject {
        d.getJSONObject("ui").getJSONObject("card").getJSONObject("sources").remove("progress")
        d.getJSONObject("ui").getJSONObject("card").getJSONArray("pieces").remove(2)
        return button(d)
    }

    @Test fun batchButtonsAreChecked() {
        val p = inventory()
        val words = CardPieces.describe(p.definition.getJSONObject("ui").getJSONObject("card"), "Inventory test")
        assertTrue(words, words.contains("\"Create inventory\" (runs the flow \"read\" once for each entry in \"photos\", at most 200)"))
        bad("doesn't take") { button(it).getJSONObject("do").getJSONObject("with").put("nope", 1) }
        bad("goes with for_each") { noBatch(it).getJSONObject("do").apply { remove("for_each"); remove("only_if"); remove("max"); remove("progress") } }
        bad("isn't a flow") { button(it).getJSONObject("do").put("flow", o("\$flow" to "missing")) }
        bad("max must be 1-200") { button(it).getJSONObject("do").put("max", 500) }
        bad("only_if.item") { button(it).getJSONObject("do").put("only_if", o("item" to "a b", "is" to "x")) }
        bad("names no source") { button(it).getJSONObject("do").put("for_each", ref("\$source", "nothing")) }
        bad("batch must name a button") { it.getJSONObject("ui").getJSONObject("card").getJSONObject("sources").put("progress", o("batch" to "other")) }
        // A plain flow button with fixed values is fine; $item isn't.
        inventory { noBatch(it).put("do", o("flow" to o("\$flow" to "log"), "with" to o("n" to 3))) }
        bad("goes with for_each") { noBatch(it).put("do", o("flow" to o("\$flow" to "log"), "with" to o("n" to ref("\$item", "n")))) }
        // Not on an agent's own card.
        try { CardPieces.check(o("title" to "x", "pieces" to a(o("type" to "buttons", "items" to a(o("label" to "Go", "do" to o("flow" to "log")))))), CardPieces.Scope(watch = { false })); fail() }
        catch (e: CardPieces.Bad) { assertTrue(e.message!!.contains("installed applet")) }
        // The install compiles {"$flow"} to the flow's name and the card still checks.
        val r = install(p)
        assertNotNull(r.optLong("card_id", -1).takeIf { it > 0 })
    }

    // ---- phase 3 fixes ----------------------------------------------------------------------------------------

    @Test fun aCalledFlowsRunLimitCountsItsWaitsWithWithReferences() {
        val def = o("name" to "x", "trigger" to o("type" to "called", "with" to o("note" to "text")), "steps" to a(
            o("type" to "wait", "seconds" to 200), o("type" to "notice", "title" to a(ref("\$trigger", "with.note")))))
        assertEquals(FlowSteps.BASE_RUN_SECONDS + 200, FlowSteps.runLimitSeconds(def))
        // Before the fix the check failed on with.note and the run limit fell back to 120 s.
        try { FlowSteps.check(def.getJSONArray("steps")); fail() } catch (e: cc.bridgit.app.ApiError) { assertTrue(e.message!!.contains("isn't declared")) }
    }

    @Test fun aCrashedRunIsUnknownButDoesNotBlockTheWorkflow() = runBlocking {
        val r = install(); val agent = r.getString("agent_id"); val wfId = wf(r, "slow")
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.platform.workflows.runNow(agent, wfId, o("n" to 1)) } }
        withTimeout(5_000) { while (app.platform.store.list(agent, "execution", 10).none { it.optString("workflow_id") == wfId }) delay(20) }
        delay(200); job.cancel(); job.join()
        val run = app.platform.workflows.runs(agent, wfId, 1).getJSONArray("runs").getJSONObject(0)
        assertEquals("unknown_outcome", run.getString("status"))
        assertEquals("active", app.platform.store.find(wfId)!!.getString("status"))
        assertEquals("completed", app.platform.workflows.runNow(agent, wfId, o("n" to 2)).getString("status"))
        assertEquals(listOf(2), logged(agent))
    }
}
