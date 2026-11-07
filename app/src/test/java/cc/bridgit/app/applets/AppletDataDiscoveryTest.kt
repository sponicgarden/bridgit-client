package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.McpTools
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletDataDiscoveryTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val store get() = AppletTableStore.of(app)
    private val data get() = AppletDataDiscovery(app)
    private lateinit var controller: Agent
    private lateinit var record: JSONObject
    private lateinit var principal: String
    private lateinit var table: AppletTables.Table
    private val service by lazy { Robolectric.buildService(BridgeService::class.java).create() }
    private val api by lazy { ApiServer(service.get(), emptyList(), 0).also { it.prepareRelay() } }

    @Before fun setup() = runBlocking {
        app.settings.listenerEnabled = true
        app.registry.switchable().forEach { app.settings.setOn(it.switchKey!!, true) }
        for (s in listOf("workflows", "interactions")) cc.bridgit.app.platform.Capabilities.setEnabled(app, s, true)
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        controller = app.agents.add("Owner control", Connection.DIRECT).first
        val definition = JSONObject(File("../site-content/public/recipes/voice-notebook/applet.json").readText())
        definition.getJSONObject("processing").put("tables", JSONArray().put(JSONObject()
            .put("id", "photos").put("label", "Photos").put("max_rows", 2000).put("columns", JSONObject()
                .put("photo", JSONObject().put("type", "photo"))
                .put("payload", JSONObject().put("type", "json").put("description", "A result object with numeric score and optional move labels/counts."))
                .put("status", JSONObject().put("type", "choice").put("options", JSONArray(listOf("new", "done", "failed")))))))
        definition.put("permissions", JSONObject().put("required", JSONArray(listOf("interactions"))).put("automatic_grants", false))
        definition.put("ui", JSONObject().put("card", JSONObject().put("title", "Inventory test")
            .put("pieces", JSONArray().put(JSONObject().put("type", "header").put("title", "Inventory")))))
        // Owner installed it; there is no requesting/installer agent and no sharing flag.
        val pending = app.applets.propose(AppletPackage.check(definition), null, JSONObject().put("kind", "test"), null, notify = false)
        record = app.applets.approve(pending.getString("id"), null)
        principal = record.getString("agent_id")
        table = AppletDataDiscovery.tables(record).single()
    }

    @After fun cleanup() = runBlocking {
        app.applets.all().forEach { app.applets.remove(it.getString("id")) }
        service.destroy()
        Unit
    }

    private suspend fun call(spec: EndpointSpec, who: Agent = controller, paths: Map<String, String> = emptyMap(),
                             query: Map<String, String> = emptyMap(), body: JSONObject = JSONObject()) =
        api.dispatch(spec, who, "100.64.0.2", query, mapOf("id" to record.getString("id")) + paths, body, null)

    private fun add(ref: String? = null, status: String = "done") = store.add(principal, table,
        JSONObject().put("status", status).apply { if (ref != null) put("photo", ref) }, JSONObject().put("type", "card"))

    @Test fun ownerControlDiscoversReadsAndEditsWithoutInstallerOrSharingApproval() = runBlocking {
        val image = byteArrayOf(1, 2, 3, 4)
        val ref = store.keepPhoto(principal, "camera", image)
        val row = add(ref)
        val pendingBefore = app.applets.pending().size
        val listing = call(AppletEndpoints.LIST)
        assertEquals(String(listing.bytes), 200, listing.status)
        val found = JSONObject(String(listing.bytes)).getJSONArray("installed").getJSONObject(0)
        assertEquals("${AppletDataDiscovery.base(record)}/data", found.getString("data_url"))
        assertEquals(200, call(AppletEndpoints.GET).status)
        assertEquals(200, call(AppletEndpoints.DEVELOPMENT).status)
        val catalog = JSONObject(String(call(AppletEndpoints.DATA).bytes))
        assertEquals(AppletDataDiscovery.CONTRACT, catalog.getString("contract"))
        assertEquals("owner_control", catalog.getString("access"))
        assertTrue(catalog.getBoolean("complete"))
        val t = catalog.getJSONArray("tables").getJSONObject(0)
        assertEquals("photo", t.getJSONObject("columns").getJSONObject("photo").getString("type"))
        assertEquals(1, t.getJSONObject("choice_counts").getJSONObject("status").getJSONObject("values").getInt("done"))
        assertEquals(1, t.getInt("count"))
        val rows = call(AppletEndpoints.ROWS, paths = mapOf("table" to "photos"))
        assertEquals(200, rows.status)
        val edit = call(AppletEndpoints.EDIT_ROW, paths = mapOf("table" to "photos", "row" to row.getString("id")),
            body = JSONObject().put("set", JSONObject().put("status", "failed")))
        assertEquals(String(edit.bytes), 200, edit.status)
        val photo = call(AppletEndpoints.PHOTO, paths = mapOf("photo" to ref.removePrefix("kept:")))
        assertEquals(200, photo.status); assertArrayEquals(image, photo.bytes)
        val chunk = call(AppletEndpoints.PHOTO, paths = mapOf("photo" to ref.removePrefix("kept:")), query = mapOf("offset" to "1", "length" to "2"))
        assertEquals(200, chunk.status); assertArrayEquals(byteArrayOf(2, 3), chunk.bytes)
        assertEquals(pendingBefore, app.applets.pending().size)
        assertEquals(1, store.count(principal, table.id))
        assertFalse(app.platform.batches.running(principal) != null)
    }

    @Test fun pagesAndExportIncludeAllRowsBeyondStorePageLimitAndFreeFormRecords() = runBlocking {
        repeat(22) { store.addMany(principal, table, List(50) { JSONObject().put("status", "done") }, JSONObject().put("type", "card")) }
        add(status = "new")
        val ids = mutableListOf<String>(); var cursor: String? = null
        do {
            val response = call(AppletEndpoints.ROWS, paths = mapOf("table" to "photos"), query = mapOf("limit" to "200") + (cursor?.let { mapOf("since" to it) } ?: emptyMap()))
            assertEquals(200, response.status)
            val page = JSONObject(String(response.bytes)); val rows = page.getJSONArray("items")
            for (i in 0 until rows.length()) ids += rows.getJSONObject(i).getString("id")
            cursor = page.optString("next").takeIf { !page.isNull("next") }
        } while (cursor != null)
        assertEquals(1101, ids.size); assertEquals(ids.size, ids.toSet().size)
        repeat(4) { n ->
            app.platform.store.save(principal, AppletData.KIND, JSONObject().put("id", AppletData.id(principal, "item.$n"))
                .put("key", "item.$n").put("value", JSONObject().put("text", "x".repeat(22000))).put("updated_at", n))
        }
        var after: String? = null; var count = 0
        do {
            val page = data.records(controller, record, after)
            count += page.getJSONArray("records").length()
            after = page.optString("next").takeIf { !page.isNull("next") }
        } while (after != null)
        assertEquals(4, count)
        val exported = JSONObject(String(call(AppletEndpoints.EXPORT).bytes))
        val t = exported.getJSONArray("tables").getJSONObject(0)
        assertEquals(1101, t.getJSONArray("rows").length())
        assertEquals(4, exported.getJSONObject("records").getJSONArray("rows").length())
        assertEquals(1, t.getJSONObject("choice_counts").getJSONObject("status").getJSONObject("values").getInt("new"))
    }

    @Test fun archiveKeepsOriginalsAndReportsMissingPhotosWithoutRecapture() = runBlocking {
        val bytes = byteArrayOf(8, 7, 6)
        val ref = store.keepPhoto(principal, "camera", bytes)
        add(ref)
        val missing = store.keepPhoto(principal, "camera", byteArrayOf(0))
        add(missing); store.photoFile(principal, missing)!!.delete()
        val zip = call(AppletEndpoints.EXPORT, query = mapOf("format" to "zip"))
        assertEquals(String(zip.bytes), 200, zip.status)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip.bytes)).use { input ->
            var entry = input.nextEntry
            while (entry != null) { entries[entry.name] = input.readBytes(); entry = input.nextEntry }
        }
        assertArrayEquals(bytes, entries["photos/${ref.removePrefix("kept:")}.jpg"])
        val manifest = JSONObject(String(entries.getValue("manifest.json")))
        assertFalse(manifest.getBoolean("media_complete"))
        assertEquals(2, manifest.getJSONArray("photos").length())
        assertEquals(404, call(AppletEndpoints.PHOTO, paths = mapOf("photo" to "missing")).status)
        assertEquals(404, call(AppletEndpoints.PHOTO, paths = mapOf("photo" to "../other")).status)
        assertEquals(422, call(AppletEndpoints.EXPORT, query = mapOf("format" to "bad")).status)
    }

    @Test fun oversizedArchiveRefusesAndLargeOriginalCanBeReadInBoundedChunks() = runBlocking {
        val ref = store.keepPhoto(principal, "camera", byteArrayOf(1))
        add(ref)
        java.io.RandomAccessFile(store.photoFile(principal, ref)!!, "rw").use {
            it.setLength(AppletDataDiscovery.MAX_EXPORT_BYTES.toLong() + 1)
        }
        val path = mapOf("photo" to ref.removePrefix("kept:"))
        assertEquals(413, call(AppletEndpoints.PHOTO, paths = path).status)
        val chunk = call(AppletEndpoints.PHOTO, paths = path, query = mapOf("offset" to "0", "length" to "1"))
        assertEquals(200, chunk.status); assertArrayEquals(byteArrayOf(1), chunk.bytes)
        assertEquals(413, call(AppletEndpoints.EXPORT, query = mapOf("format" to "zip")).status)
        assertEquals(422, call(AppletEndpoints.PHOTO, paths = path, query = mapOf("length" to "999999999")).status)
    }

    @Test fun appletCardsDiscoverAndWriteOnlyTheirOwnDeclaredJsonTable() = runBlocking {
        app.agents.update(principal) { it.copy(caps = setOf("applet_data")) }
        val applet = app.agents.get(principal)!!
        val schema = call(AppletData.TABLES, applet)
        assertEquals(String(schema.bytes), 200, schema.status)
        val col = JSONObject(String(schema.bytes)).getJSONArray("tables").getJSONObject(0).getJSONObject("columns").getJSONObject("payload")
        assertTrue(col.getString("description").contains("score"))
        val saved = call(AppletData.TABLE_ADD, applet, paths = mapOf("table" to "photos"), body = JSONObject().put("row", JSONObject().put("payload", JSONObject().put("score", 7))))
        assertEquals(String(saved.bytes), 200, saved.status)
        assertEquals(7, JSONObject(String(saved.bytes)).getJSONObject("payload").getInt("score"))
        assertEquals(1, JSONObject(String(call(AppletData.TABLE_ROWS, applet, paths = mapOf("table" to "photos")).bytes)).getJSONArray("items").length())
        assertEquals(404, call(AppletData.TABLE_ADD, applet, paths = mapOf("table" to "undeclared"), body = JSONObject().put("row", JSONObject())).status)
        assertEquals(422, call(AppletData.TABLE_ADD, applet, paths = mapOf("table" to "photos"), body = JSONObject().put("row", JSONObject().put("unknown", 1))).status)
        assertEquals(422, call(AppletData.TABLES, controller).status)
        val rowId = JSONObject(String(saved.bytes)).getString("id")
        assertEquals(200, call(AppletData.TABLE_EDIT, applet, paths = mapOf("table" to "photos", "row" to rowId), body = JSONObject().put("set", JSONObject().put("payload", JSONObject.NULL))).status)
        val deleted = call(AppletData.TABLE_DELETE, applet, paths = mapOf("table" to "photos", "row" to rowId))
        assertEquals(200, deleted.status); assertTrue(JSONObject(String(deleted.bytes)).getBoolean("existed"))
        assertEquals(0, store.count(principal, "photos"))
    }

    @Test fun multilingualSchemasAreDiscoverableWithinTheCardReplyBudget() = runBlocking {
        val updated = JSONObject(record.toString())
        val tables = JSONArray()
        repeat(3) { t -> tables.put(JSONObject().put("id", "table$t").put("columns", JSONObject().apply {
            repeat(16) { c -> put("column$c", JSONObject().put("type", "json").put("description", "界".repeat(1024))) }
        })) }
        updated.getJSONObject("definition").getJSONObject("processing").put("tables", tables)
        InstalledAppletStore(app).use { it.save(updated, advanceRevision = false) }
        app.agents.update(principal) { it.copy(caps = setOf("applet_data")) }
        val applet = app.agents.get(principal)!!
        var after: String? = null; val seen = mutableSetOf<String>()
        do {
            val page = call(AppletData.TABLES, applet, query = after?.let { mapOf("after" to it) } ?: emptyMap())
            assertEquals(200, page.status); assertTrue(page.bytes.size < 40 * 1024)
            val json = JSONObject(String(page.bytes)); val listed = json.getJSONArray("tables")
            for (i in 0 until listed.length()) {
                val t = listed.getJSONObject(i)
                t.getJSONObject("columns").keys().forEach { c -> assertTrue(seen.add("${t.getString("id")}.$c")) }
            }
            after = json.optString("next").takeIf { !json.isNull("next") }
        } while (after != null)
        assertEquals(48, seen.size)
    }

    @Test fun legacyPortalDeclarationMigrationIsAdditivePersistentAndIdempotent() {
        val old = JSONObject(record.toString())
        old.getJSONObject("definition").getJSONObject("ui").put("portal", JSONObject().put("records", JSONObject().put("kind", "workout").put("title", "Workouts")))
        InstalledAppletStore(app).use { it.save(old, advanceRevision = false) }
        val legacyRow = add(status = "new")
        app.applets.migrateLegacyPortalStorage()
        val migrated = app.applets.get(record.getString("id"))!!
        assertEquals(old.optLong("revision") + 1, migrated.getLong("revision"))
        val definition = migrated.getJSONObject("definition")
        AppletPackage.check(definition)
        val target = definition.getJSONObject("ui").getJSONObject("portal").getJSONObject("records")
        assertEquals("json", AppletDataDiscovery.tables(migrated).single { it.id == target.getString("table") }.columns.getValue(target.getString("column")).type)
        assertEquals(legacyRow.getString("id"), store.rows(principal, "photos", 10).single().getString("id"))
        app.applets.migrateLegacyPortalStorage()
        assertEquals(migrated.getLong("revision"), app.applets.get(record.getString("id"))!!.getLong("revision"))
    }

    @Test fun appletCannotWriteUndeclaredBlobsButLegacyDataRemainsReadable() = runBlocking {
        app.agents.update(principal) { it.copy(caps = setOf("applet_data")) }
        val applet = app.agents.get(principal)!!
        val rejected = call(AppletData.PUT, applet, paths = mapOf("key" to "new.blob"), body = JSONObject().put("value", JSONObject().put("x", 1)))
        assertEquals(String(rejected.bytes), 422, rejected.status)
        assertEquals(0, app.platform.store.usage(principal, AppletData.KIND).first)
        app.platform.store.save(principal, AppletData.KIND, JSONObject().put("id", AppletData.id(principal, "legacy"))
            .put("key", "legacy").put("value", JSONObject().put("x", 2)))
        assertEquals(1, data.records(controller, record).getJSONArray("records").length())
    }

    @Test fun nativeAppletReturnsDiscoveryLinksInsteadOfFailingOnMissingPrincipal() {
        val native = JSONObject(record.toString()).put("native", "dog").apply { remove("agent_id") }
        val catalog = data.catalog(controller, native)
        assertFalse(catalog.getBoolean("complete"))
        assertEquals("/v1/applets/dog-monitor/history", catalog.getJSONObject("native_sources").getString("history_url"))
        assertTrue(catalog.isNull("export_url"))
    }

    @Test fun capabilityScopesAndAppletPrincipalsStayRestricted() = runBlocking {
        app.agents.update(controller.id) { it.copy(caps = emptySet()) }
        val restricted = app.agents.get(controller.id)!!
        assertEquals(403, call(AppletEndpoints.DATA, restricted).status)
        app.agents.update(controller.id) { it.copy(caps = null) }
        val ownApplet = app.agents.get(principal)!!
        assertFalse(AppletDataDiscovery.controls(ownApplet))
        assertFalse(AppletDataDiscovery.visible(ownApplet, record))
        assertEquals(403, call(AppletEndpoints.DATA, ownApplet.copy(caps = setOf("applet_install"))).status)
        val otherPrincipal = ownApplet.copy(id = "other-applet", caps = setOf("applet_install"))
        assertFalse(AppletDataDiscovery.canRead(otherPrincipal, record, table))
        val requested = JSONObject(record.toString()).put("requested_by", otherPrincipal.id)
        assertFalse(AppletDataDiscovery.canRead(otherPrincipal, requested, table))
        assertTrue(AppletDataDiscovery.canRead(otherPrincipal, requested, table.copy(shared = true)))
        assertEquals("applets_data_get", McpTools.name(AppletEndpoints.DATA))
        assertEquals("applets_data_export", McpTools.name(AppletEndpoints.EXPORT))
        assertEquals("applets_records_list", McpTools.name(AppletEndpoints.RECORDS))
    }
}
