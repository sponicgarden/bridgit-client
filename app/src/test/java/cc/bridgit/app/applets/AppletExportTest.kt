package cc.bridgit.app.applets

import android.content.Intent
import android.net.Uri
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.interact.CardPieces
import cc.bridgit.app.platform.FlowSteps
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset

/** The share step (docs/applet-dev-experience.md O1): checking, CSV text, groups and totals, limits, the share sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletExportTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    @Before fun enableOwnerListener() { app.settings.listenerEnabled = true }

    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private val utc = ZoneOffset.UTC

    private val items = AppletTables.Table("items", "Items", linkedMapOf(
        "label" to AppletTables.Column("label", "text", "Item"),
        "category" to AppletTables.Column("category", "choice", "Category"),
        "new_usd" to AppletTables.Column("new_usd", "number", "New"),
        "used_usd" to AppletTables.Column("used_usd", "number", "Used"),
        "count" to AppletTables.Column("count", "integer", "Count"),
        "insurable" to AppletTables.Column("insurable", "boolean", "Insurable"),
        "photo" to AppletTables.Column("photo", "photo", "Photo"),
        "boxes" to AppletTables.Column("boxes", "boxes", "Boxes"),
    ))
    private fun row(n: Int, vararg p: Pair<String, Any?>) = o("id" to "r$n", "at" to 1_700_000_000_000L + n, "by" to JSONObject.NULL, *p)
    private fun share(vararg p: Pair<String, Any?>) = o("type" to "share", "table" to "items", "as" to "csv", *p)
    private fun dir() = File(app.cacheDir, AppletExport.DIR).also { it.deleteRecursively() }
    // ---- CSV --------------------------------------------------------------------------------------------------

    @Test fun csvQuotesFormatsGuardsAndStartsWithABom() {
        val rows = listOf(
            row(1, "label" to "Sofa, \"big\"", "category" to "Living room", "new_usd" to 1200.5, "used_usd" to "$300", "count" to 2, "insurable" to true),
            row(2, "label" to "Café ☕\nline two", "new_usd" to JSONObject.NULL, "insurable" to false),
            row(3, "label" to "=SUM(A1:A9)", "category" to "@cmd", "new_usd" to 5),
            row(4, "label" to "-5", "category" to "+1 spare", "count" to -3),
        )
        val step = share("as" to "csv", "columns" to a("label", "category", "new_usd", o("key" to "used_usd", "label" to "Used, today"), "count", "insurable"))
        val bytes = AppletExport.csv(AppletExport.columns(items, step), rows, utc)
        assertEquals(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3))
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        assertEquals(
            "Item,Category,New,\"Used, today\",Count,Insurable\r\n" +
                "\"Sofa, \"\"big\"\"\",Living room,1200.50,300.00,2,Yes\r\n" +
                "\"Café ☕\nline two\",,,,,No\r\n" +
                "'=SUM(A1:A9),'@cmd,5.00,,,\r\n" +
                "-5,'+1 spare,,,-3,\r\n",
            text)
    }

    @Test fun defaultColumnsArePlainOnesWithUsdAsMoney() {
        val cols = AppletExport.columns(items, share())
        assertEquals(listOf("label", "category", "new_usd", "used_usd", "count", "insurable"), cols.map { it.key })
        assertEquals("money", cols.first { it.key == "new_usd" }.format)
        assertEquals("number", cols.first { it.key == "count" }.format)
        assertEquals("yes_no", cols.first { it.key == "insurable" }.format)
        val listed = AppletExport.columns(items, share("columns" to a(o("key" to "count", "format" to "money", "currency" to "EUR"), "at")))
        assertEquals("€3.50", AppletExport.text(listed[0], 3.5, csv = false))
        assertEquals("Saved", listed[1].label)
        assertEquals("2023-11-14T22:13:20", AppletExport.text(listed[1], 1_700_000_000_000L, csv = true, zone = utc))
    }

    @Test fun moneyAndNumbersReadWell() {
        assertEquals("$1,234.50", AppletExport.money(1234.5))
        assertEquals("-$0.99", AppletExport.money(-0.99))
        assertEquals("CHF 10.00", AppletExport.money(10.0, "CHF"))
        assertEquals(1200.5, AppletExport.numberOf("$1,200.50")!!, 0.0)
        assertEquals(null, AppletExport.numberOf(true))
    }

    // ---- filter, groups and totals ------------------------------------------------------------------------------

    @Test fun whereFiltersRows() {
        val r = row(1, "insurable" to true, "new_usd" to 50, "category" to "Kitchen")
        assertTrue(AppletExport.keep(r, o("column" to "insurable", "is" to true)))
        assertTrue(!AppletExport.keep(r, o("column" to "insurable", "is" to false)))
        assertTrue(AppletExport.keep(r, o("column" to "category", "in" to a("kitchen", "Bedroom"))))
        assertTrue(AppletExport.keep(r, o("column" to "new_usd", "at_least" to 50)))
        assertTrue(!AppletExport.keep(r, o("column" to "new_usd", "below" to 50)))
        assertTrue(!AppletExport.keep(r, o("column" to "photo", "exists" to true)))
        assertTrue(!AppletExport.keep(row(2), o("column" to "category", "is_not" to "Kitchen")))
    }

    @Test fun groupsSortWithOtherLastAndSumTotals() {
        val rows = listOf(
            row(1, "category" to "Kitchen", "new_usd" to 100, "used_usd" to 40),
            row(2, "category" to "bedroom", "new_usd" to "$1,000.25", "used_usd" to 500),
            row(3, "new_usd" to 10, "used_usd" to "n/a"),
            row(4, "category" to "Kitchen", "new_usd" to 0.75),
        )
        val cols = AppletExport.columns(items, share())
        val g = AppletExport.group(rows, cols.first { it.key == "category" }, listOf("new_usd", "used_usd"))
        assertEquals(listOf("bedroom", "Kitchen", AppletExport.OTHER), g.groups.map { it.name })
        assertEquals(listOf("r1", "r4"), g.groups[1].rows.map { it.getString("id") })
        assertEquals(100.75, g.groups[1].sums["new_usd"]!!, 1e-9)
        assertEquals(40.0, g.groups[1].sums["used_usd"]!!, 1e-9)
        assertEquals(0.0, g.groups[2].sums["used_usd"]!!, 1e-9)
        assertEquals(1111.0, g.sums["new_usd"]!!, 1e-9)
        assertEquals(540.0, g.sums["used_usd"]!!, 1e-9)

    }

    // ---- limits -------------------------------------------------------------------------------------------------

    private fun tooLarge(block: () -> Unit) {
        try { block(); fail("accepted") } catch (e: ApiError) { assertEquals("export_too_large", e.code); assertEquals(413, e.http) }
    }

    @Test fun limitsRowsAndRejectsLegacyPdfBeforeCreatingFiles() {
        tooLarge { AppletExport.build(dir(), items, share(), (0..AppletExport.MAX_ROWS).map { row(it) }) }
        val target = dir()
        val legacy = share("as" to "pdf", "photos" to "thumbs")
        val original = legacy.toString()
        AppletExport.check(legacy, "legacy share") { throw IllegalArgumentException(it) }
        assertEquals(original, legacy.toString())
        assertTrue(AppletExport.words(legacy).contains("unavailable"))
        try { AppletExport.build(target, items, legacy, emptyList()); fail("PDF accepted") }
        catch (e: ApiError) { assertEquals(422, e.http); assertEquals("invalid_export", e.code) }
        assertTrue(!target.exists())
        assertEquals(201, AppletExport.build(target, items, share(), (1..201).map { row(it, "photo" to "kept:p$it") }).rows)
    }

    @Test fun oldExportsAreCleanedUp() {
        val d = dir().also { it.mkdirs() }
        val old = File(d, "old.csv").apply { writeText("x"); setLastModified(System.currentTimeMillis() - AppletExport.KEEP_MS - 1000) }
        val fresh = File(d, "fresh.csv").apply { writeText("x") }
        // Exports live in their own folders now: an old folder goes with its file.
        val oldFolder = File(d, "abc").apply { mkdirs(); File(this, "Items.csv").writeText("x"); setLastModified(System.currentTimeMillis() - AppletExport.KEEP_MS - 1000) }
        AppletExport.clean(d)
        assertTrue(!old.exists()); assertTrue(fresh.exists()); assertTrue(!oldFolder.exists())
    }

    @Test fun twoExportsWithTheSameNameNeverOverwriteEachOther() {
        val d = dir()
        val first = AppletExport.build(d, items, share("as" to "csv", "title" to "Home inventory"), listOf(row(1, "label" to "Lamp")), zone = utc)
        val second = AppletExport.build(d, items, share("as" to "csv", "title" to "Home inventory"), listOf(row(2, "label" to "Sofa")), zone = utc)
        assertEquals(first.file.name, second.file.name)
        assertTrue(first.file.absolutePath != second.file.absolutePath)
        assertTrue(first.file.readText().contains("Lamp")); assertTrue(second.file.readText().contains("Sofa"))
        // cacheDir/applet-share/<own folder>/<name>; theShareIntentCarriesOnlyTheFile checks the FileProvider reaches it
        // (FileProvider caches its roots per process, so only one test here may ask it for a URI).
        assertEquals(d.canonicalFile, first.file.parentFile!!.parentFile!!.canonicalFile)
    }

    // ---- checking -----------------------------------------------------------------------------------------------

    private fun stepBad(step: JSONObject, part: String, tables: Map<String, Set<String>>? = null) {
        try { AppletExport.check(step, "share", tables) { throw IllegalArgumentException(it) }; fail("accepted $step") }
        catch (e: IllegalArgumentException) { assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }

    @Test fun checkRefusesBadFields() {
        val cols = mapOf("items" to setOf("label", "category", "new_usd", "insurable"))
        AppletExport.check(share("title" to "Home inventory", "columns" to a("label", o("key" to "new_usd", "label" to "New", "format" to "money", "currency" to "USD")),
            "group_by" to "category", "totals" to a("new_usd"), "photos" to "thumbs", "where" to o("column" to "insurable", "is" to true)), "share", cols) { fail(it); throw IllegalStateException() }
        stepBad(share("as" to "xls"), "pdf or csv")
        stepBad(share("table" to "Items!"), "table must be")
        stepBad(share("format" to "pdf"), "isn't a field of share")
        stepBad(share("columns" to a(o("key" to "label", "format" to "currency"))), "format must be one of")
        stepBad(share("columns" to a(o("key" to "new_usd", "currency" to "usd"))), "3-letter")
        stepBad(share("where" to o("column" to "insurable", "is" to true, "below" to 3)), "exactly one test")
        stepBad(share("photos" to "full"), "thumbs or none")
        stepBad(share("table" to "rooms"), "isn't in processing.tables", cols)
        stepBad(share("group_by" to "room"), "has no column room", cols)
    }

    private fun flow(step: JSONObject, trigger: JSONObject) = o("name" to "Report", "trigger" to trigger, "steps" to a(step))
    private fun refusedFlow(def: JSONObject, part: String) {
        try { app.platform.workflows.validate(def); fail("accepted") }
        catch (e: ApiError) { assertEquals("invalid_workflow", e.code); assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }

    @Test fun aFlowMayShareOnlyFromAButton() {
        val v = app.platform.workflows.validate(flow(share("title" to "Home inventory"), o("type" to "button")))
        assertEquals(setOf("workflows"), (0 until v.getJSONArray("required_scopes").length()).map { v.getJSONArray("required_scopes").getString(it) }.toSet())
        assertEquals(FlowSteps.BASE_RUN_SECONDS + AppletExport.RUN_SECONDS, v.getJSONObject("limits").getInt("max_run_seconds"))
        assertTrue(v.getString("summary"), v.getString("summary").contains("share \"Home inventory\" as a CSV file (you choose where it goes)"))
        refusedFlow(flow(share(), o("type" to "interval", "seconds" to 900)), "button trigger")
        refusedFlow(flow(share(), o("type" to "called")), "button trigger")
        refusedFlow(flow(share(), o("type" to "charging")), "button trigger")
        refusedFlow(flow(share("as" to "doc"), o("type" to "button")), "pdf or csv")
        try { FlowSteps.checkInline(share()); fail("accepted inline share") }
        catch (e: ApiError) { assertTrue(e.message, e.message!!.contains("owner present")) }
    }

    @Test fun rulesAndSchedulesCantStartASharingFlow() {
        val agent = app.policy.addAgent("ShareTester").first.id
        app.platform.store.save(agent, "workflow", o("id" to "wf_share", "status" to "active", "definition" to flow(share(), o("type" to "button"))))
        app.platform.store.save(agent, "workflow", o("id" to "wf_plain", "status" to "active", "definition" to flow(o("type" to "event", "text" to "hi"), o("type" to "button"))))
        assertTrue(app.platform.workflows.startable(agent, "wf_share")!!.contains("share step"))
        assertEquals(null, app.platform.workflows.startable(agent, "wf_plain"))
        val started = kotlinx.coroutines.runBlocking { app.platform.workflows.start(agent, "wf_share", o("caller" to "schedule"), null) }
        assertEquals("flow_not_active", started.getString("status"))
        assertTrue(started.getString("message").contains("share step"))
    }

    private fun card(d: JSONObject) = o("title" to "Inventory", "pieces" to a(o("type" to "buttons", "items" to a(o("label" to "Share", "do" to d)))))
    private val cardScope = CardPieces.Scope(watch = { false }, tables = mapOf("items" to setOf("label", "category", "new_usd")), packaged = true)
    private fun cardBad(c: JSONObject, part: String, s: CardPieces.Scope = cardScope) {
        try { CardPieces.check(c, s); fail("accepted") } catch (e: CardPieces.Bad) { assertTrue("${e.message} should say $part", e.message!!.contains(part)) }
    }

    @Test fun aCardButtonMayShare() {
        val ok = card(o("step" to share("title" to "Home inventory", "group_by" to "category", "totals" to a("new_usd"))))
        CardPieces.check(ok, cardScope)
        assertTrue(CardPieces.describe(ok, "Inventory"), CardPieces.describe(ok, "Inventory").contains("\"Share\" (makes a CSV file of \"Home inventory\" for you to share when you tap it)"))
        cardBad(card(o("step" to share("table" to "rooms"))), "isn't in processing.tables")
        cardBad(card(o("step" to share("totals" to a("used_usd")))), "has no column used_usd")
        cardBad(card(o("step" to o("type" to "notice", "title" to "Hi"))), "share step only")
        cardBad(card(o("step" to share())), "installed applet's card", CardPieces.Scope(watch = { false }, tables = cardScope.tables))
        cardBad(card(o("step" to share(), "refresh" to true)), "do")
    }

    // ---- the share sheet -----------------------------------------------------------------------------------------

    @Test fun theShareIntentCarriesOnlyTheFile() {
        val built = AppletExport.build(dir(), items, share("as" to "csv", "title" to "Home inventory"), listOf(row(1, "label" to "Lamp")), zone = utc)
        assertEquals("text/csv", built.mime)
        val chooser = AppletExport.intent(app, built)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        assertEquals("Home inventory", send.getStringExtra(Intent.EXTRA_SUBJECT))
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.fileprovider", uri.authority)
        assertTrue(uri.toString(), uri.path!!.startsWith("/applet-share/") && uri.path!!.endsWith(built.file.name))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        assertEquals(o("file" to built.file.name, "as" to "csv", "rows" to 1, "pages" to 1, "photos" to 0, "bytes" to built.file.length()).toString(), built.json().toString())
    }
}
