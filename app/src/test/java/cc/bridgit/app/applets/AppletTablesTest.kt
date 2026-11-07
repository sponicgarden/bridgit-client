package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** On-phone tables (docs/applet-cards.md): declaring, checking a row, update words, and the row/photo store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletTablesTest {
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private fun receipts(edit: JSONObject.() -> Unit = {}) = o("id" to "receipts", "label" to "Receipts",
        "columns" to o("text" to o("type" to "text", "label" to "Receipt text"), "total" to o("type" to "number", "label" to "Total"),
            "items" to o("type" to "integer"), "paid" to o("type" to "boolean"), "when" to o("type" to "time"), "day" to o("type" to "date"),
            "currency" to o("type" to "choice", "options" to a("EUR", "USD", "GBP")), "photo" to o("type" to "photo")),
        "keep_days" to 365, "max_rows" to 2000, "share_with_installer" to false).apply(edit)
    private fun tables(vararg t: JSONObject) = AppletTables.parse(o("tables" to a(*t)))
    private fun parseFails(t: JSONObject, part: String) = parseFailsAll(o("tables" to a(t)), part)
    private fun parseFailsAll(processing: JSONObject, part: String) {
        try { AppletTables.parse(processing); fail("Expected refusal: $part") }
        catch (e: AppletPackage.Bad) { assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }
    private fun refused(t: AppletTables.Table, row: JSONObject, code: String, part: String = "") {
        try { AppletTables.coerce(t, row); fail("Expected $code") }
        catch (e: AppletTables.Refused) { assertEquals(code, e.code); assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }

    // ---- declaring --------------------------------------------------------------------------------------------

    @Test fun parsesTheSpecExampleWithDefaults() {
        val t = tables(receipts()).single()
        assertEquals("receipts", t.id); assertEquals("Receipts", t.label)
        assertEquals(365, t.keepDays); assertEquals(2000, t.maxRows); assertFalse(t.shared)
        assertEquals(listOf("EUR", "USD", "GBP"), t.columns["currency"]!!.options)
        assertEquals("Receipt text", t.columns["text"]!!.label)
        assertEquals("items", t.columns["items"]!!.label)
        val d = tables(o("id" to "notes", "columns" to o("text" to o("type" to "text")), "share_with_installer" to true)).single()
        assertEquals(AppletTables.DEFAULT_KEEP_DAYS, d.keepDays); assertEquals(AppletTables.DEFAULT_MAX_ROWS, d.maxRows); assertTrue(d.shared)
        assertEquals("notes", d.label)
        assertTrue(AppletTables.parse(JSONObject()).isEmpty())
        assertTrue(AppletTables.parse(o("tables" to a())).isEmpty())
    }

    @Test fun declarationRulesAreEnforced() {
        val col = o("text" to o("type" to "text"))
        parseFailsAll(o("tables" to a(*Array(4) { o("id" to "t$it", "columns" to col) })), "at most 3 tables")
        parseFailsAll(o("tables" to "receipts"), "must be a list")
        parseFailsAll(o("tables" to a(o("id" to "t", "columns" to col), o("id" to "t", "columns" to col))), "declared twice")
        parseFails(o("id" to "Receipts", "columns" to col), "lowercase")
        parseFails(o("id" to "9a", "columns" to col), "lowercase")
        parseFails(o("id" to "a".repeat(33), "columns" to col), "lowercase")
        parseFails(o("id" to "t", "columns" to col, "colour" to "red"), "colour isn't a table field")
        parseFails(o("id" to "t", "columns" to o()), "1-16 columns")
        parseFails(o("id" to "t", "columns" to JSONObject().apply { repeat(17) { put("c$it", o("type" to "text")) } }), "1-16 columns")
        parseFails(o("id" to "t"), "needs columns")
        parseFails(o("id" to "t", "columns" to o("Total" to o("type" to "number"))), "column id Total")
        parseFails(o("id" to "t", "columns" to o("at" to o("type" to "number"))), "can't be a column id")
        parseFails(o("id" to "t", "columns" to o("x" to o("type" to "money"))), "type must be one of")
        parseFails(o("id" to "t", "columns" to o("x" to o("type" to "text", "max" to 5))), "max isn't a column field")
        parseFails(o("id" to "t", "columns" to o("x" to o("type" to "choice"))), "needs options")
        parseFails(o("id" to "t", "columns" to o("x" to o("type" to "choice", "options" to a("one")))), "2-32 options")
        parseFails(o("id" to "t", "columns" to o("x" to o("type" to "choice", "options" to JSONArray((1..33).map { "o$it" })))), "2-32 options")
        parseFails(o("id" to "t", "columns" to o("x" to o("type" to "choice", "options" to a("a", "a")))), "options must differ")
        parseFails(o("id" to "t", "columns" to o("x" to o("type" to "choice", "options" to a("a", 2)))), "each option")
        parseFails(o("id" to "t", "columns" to o("x" to o("type" to "text", "options" to a("a", "b")))), "only a choice column")
        parseFails(o("id" to "t", "columns" to col, "keep_days" to 0), "keep_days must be a whole number from 1 to 3650")
        parseFails(o("id" to "t", "columns" to col, "keep_days" to 3651), "keep_days")
        parseFails(o("id" to "t", "columns" to col, "keep_days" to 1.5), "keep_days")
        parseFails(o("id" to "t", "columns" to col, "max_rows" to 0), "max_rows must be a whole number from 1 to 10000")
        parseFails(o("id" to "t", "columns" to col, "max_rows" to 10_001), "max_rows")
        parseFails(o("id" to "t", "columns" to col, "share_with_installer" to "yes"), "share_with_installer")
        parseFails(o("id" to "t", "columns" to col, "label" to ""), "label")
        // Bounds themselves are fine.
        val edge = tables(o("id" to "t", "columns" to col, "keep_days" to 3650, "max_rows" to 10_000)).single()
        assertEquals(3650, edge.keepDays); assertEquals(10_000, edge.maxRows)
    }

    @Test fun jsonColumnsRequireDescriptionsAndPreserveStructuredValues() {
        parseFails(o("id" to "results", "columns" to o("payload" to o("type" to "json"))), "description")
        parseFails(o("id" to "results", "columns" to o("payload" to o("type" to "json", "description" to " "))), "description")
        val table = tables(o("id" to "results", "columns" to o("payload" to o("type" to "json", "description" to "A result with score:number and tags:array of strings.")))).single()
        val blob = o("score" to 3, "tags" to a("a", "b"))
        val value = AppletTables.coerce(table, o("payload" to blob)).getJSONObject("payload")
        assertEquals(blob.toString(), value.toString())
        blob.put("score", 99); assertEquals(3, value.getInt("score"))
        assertTrue(AppletTables.coerce(table, o("payload" to a(o("score" to 1)))).get("payload") is JSONArray)
        try { AppletTables.coerce(table, o("payload" to "{}")); fail("Encoded JSON text must be rejected") } catch (_: AppletTables.Refused) {}
        try { AppletTables.coerce(table, o("payload" to o("text" to "x".repeat(5000)))); fail("Row byte limit must apply to JSON") } catch (_: AppletTables.Refused) {}
    }

    // ---- one row ----------------------------------------------------------------------------------------------

    @Test fun coerceChecksEachType() {
        val t = tables(receipts()).single()
        val ok = AppletTables.coerce(t, o("text" to "Coffee", "total" to "3.50", "items" to 2.0, "paid" to "true", "when" to "14:30",
            "day" to "2026-11-05", "currency" to "EUR", "photo" to "kept:abc123"))
        assertEquals("Coffee", ok.getString("text")); assertEquals(3.5, ok.getDouble("total"), 0.0)
        assertEquals(2L, ok.getLong("items")); assertEquals(true, ok.getBoolean("paid"))
        assertEquals("kept:abc123", ok.getString("photo"))
        // Missing columns and explicit nulls are empty.
        val empty = AppletTables.coerce(t, o("text" to JSONObject.NULL))
        assertTrue(empty.isNull("text")); assertTrue(empty.isNull("total")); assertEquals(t.columns.size, empty.length())
        assertEquals(7, AppletTables.coerce(t, o("total" to 7)).getInt("total"))
        assertEquals("2026-11-05T14:30:00Z", AppletTables.coerce(t, o("when" to "2026-11-05T14:30:00Z")).getString("when"))

        refused(t, o("colour" to "red"), "invalid_row", "no column colour")
        refused(t, o("text" to "x".repeat(1025)), "invalid_row", "at most 1024")
        refused(t, o("text" to o("a" to 1)), "invalid_row", "must be text")
        refused(t, o("total" to "twelve"), "invalid_row", "Total must be a number")
        refused(t, o("items" to 2.5), "invalid_row", "whole number")
        refused(t, o("paid" to "maybe"), "invalid_row", "true or false")
        refused(t, o("when" to "half past two"), "invalid_row", "ISO time")
        refused(t, o("day" to "05/11/2026"), "invalid_row", "YYYY-MM-DD")
        refused(t, o("day" to "2026-02-30"), "invalid_row", "YYYY-MM-DD")
        refused(t, o("currency" to "JPY"), "invalid_row", "one of EUR, USD, GBP")
        // Written the way people write them: a choice in any case is stored as the option; money with signs and commas.
        assertEquals("EUR", AppletTables.coerce(t, o("currency" to " eur ")).getString("currency"))
        assertEquals("USD", AppletTables.coerce(t, o("currency" to "Usd")).getString("currency"))
        assertEquals(1200.0, AppletTables.coerce(t, o("total" to "$1,200")).getDouble("total"), 0.0)
        assertEquals(1200.5, AppletTables.coerce(t, o("total" to "1,200.50")).getDouble("total"), 0.0)
        assertEquals(1234567.0, AppletTables.coerce(t, o("total" to "€ 1,234,567")).getDouble("total"), 0.0)
        assertEquals(-3.25, AppletTables.coerce(t, o("total" to "-£3.25")).getDouble("total"), 0.0)
        assertEquals(1200L, AppletTables.coerce(t, o("items" to "1,200")).getLong("items"))
        refused(t, o("total" to "1,5"), "invalid_row", "Total must be a number")
        refused(t, o("total" to "$"), "invalid_row", "Total must be a number")
        refused(t, o("items" to "1,200.5"), "invalid_row", "whole number")
        refused(t, o("photo" to "photo:shot"), "invalid_row", "keep: true")
    }

    @Test fun aRowIsAtMost4Kb() {
        val cols = JSONObject().apply { repeat(5) { put("t$it", o("type" to "text")) } }
        val t = tables(o("id" to "big", "columns" to cols)).single()
        AppletTables.coerce(t, o("t0" to "x".repeat(1000), "t1" to "x".repeat(1000), "t2" to "x".repeat(1000)))
        refused(t, o("t0" to "x".repeat(1000), "t1" to "x".repeat(1000), "t2" to "x".repeat(1000), "t3" to "x".repeat(1000), "t4" to "x".repeat(200)), "row_too_large", "at most 4096")
    }

    // ---- updates ----------------------------------------------------------------------------------------------

    @Test fun changesNameWhatAnUpdateDeletes() {
        val old = tables(receipts(), o("id" to "notes", "label" to "Notes", "columns" to o("text" to o("type" to "text"))))
        val counts = { table: String, column: String? -> when (table to column) { "receipts" to null -> 120; "receipts" to "total" -> 120; "receipts" to "items" -> 1; "notes" to null -> 40; else -> 0 } }
        // Nothing lost: added table and column, longer keep_days and more max_rows.
        val grown = tables(receipts { getJSONObject("columns").put("vat", o("type" to "number")); put("keep_days", 400); put("max_rows", 5000) },
            o("id" to "notes", "label" to "Notes", "columns" to o("text" to o("type" to "text"))), o("id" to "extra", "columns" to o("a" to o("type" to "text"))))
        assertEquals(emptyList<String>(), AppletTables.changes(old, grown, counts))
        val shrunk = tables(receipts { getJSONObject("columns").remove("total"); getJSONObject("columns").put("items", o("type" to "text")) })
        assertEquals(listOf(
            "This update deletes the column Total from 120 rows.",
            "This update changes the type of the column items, which deletes it from 1 row.",
            "This update deletes the table Notes and its 40 rows.",
        ), AppletTables.changes(old, shrunk, counts))
        // A removed column nobody filled in loses nothing.
        val notes = o("id" to "notes", "label" to "Notes", "columns" to o("text" to o("type" to "text")))
        assertEquals(emptyList<String>(), AppletTables.changes(old, tables(receipts { getJSONObject("columns").remove("paid") }, notes), counts))
    }

    @Test fun lowerLimitsAndNewSharingWaitForTheOwner() {
        val old = tables(receipts())
        val counts = { table: String, column: String? -> if (table == "receipts" && column == null) 120 else 0 }
        val older = { _: String, _: Long -> 30 }
        assertEquals(listOf("This update keeps at most 100 rows in Receipts, which deletes the oldest 20 rows."),
            AppletTables.changes(old, tables(receipts { put("max_rows", 100) }), counts, older))
        assertEquals(listOf("This update keeps Receipts rows for 7 days, which deletes 30 rows older than that."),
            AppletTables.changes(old, tables(receipts { put("keep_days", 7) }), counts, older))
        // A lower limit that nothing passes loses nothing.
        assertEquals(emptyList<String>(), AppletTables.changes(old, tables(receipts { put("max_rows", 500) }), counts, { _: String, _: Long -> 0 }))
    }

    @Test fun newSharingIsNewAccessForTheOwnerToApprove() {
        val old = tables(receipts())
        val shared = tables(receipts { put("share_with_installer", true) })
        // A table that turns shared, with its rows now.
        assertEquals(listOf("The agent that installed this applet will be able to read the rows of Receipts (120 rows now)."),
            AppletTables.shares(old, shared) { if (it == "receipts") 120 else 0 })
        // A brand-new table that is shared counts too (it used to apply without asking).
        val notes = o("id" to "notes", "label" to "Notes", "columns" to o("text" to o("type" to "text")), "share_with_installer" to true)
        assertEquals(listOf("The agent that installed this applet will be able to read the rows of Notes."),
            AppletTables.shares(old, tables(receipts(), notes)) { 0 })
        // Already shared, sharing taken away, or a new table that isn't shared: no new access.
        assertEquals(emptyList<String>(), AppletTables.shares(shared, shared))
        assertEquals(emptyList<String>(), AppletTables.shares(shared, old))
        assertEquals(emptyList<String>(), AppletTables.shares(old, tables(receipts(), notes.put("share_with_installer", false))))
        // Sharing deletes nothing, so it isn't among the deletes.
        assertEquals(emptyList<String>(), AppletTables.changes(old, shared, { _, _ -> 120 }))
    }

    // ---- the store (Robolectric) ------------------------------------------------------------------------------

    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun agent() = "test-" + UUID.randomUUID().toString().take(8)
    private val flowBy = o("type" to "flow", "run_id" to "run1")

    @Test fun storeAddsReadsAndDropsOldestPastMaxRows() {
        val s = AppletTableStore.of(app); val ag = agent()
        val t = tables(o("id" to "meals", "columns" to o("activity" to o("type" to "text")), "max_rows" to 3)).single()
        var fired = 0; val l = { id: String -> if (id == ag) fired++ }
        s.addListener(l)
        val ids = (1..5).map { s.add(ag, t, o("activity" to "meal $it"), flowBy).getString("id") }
        s.removeListener(l)
        assertEquals(5, fired)
        assertEquals(3, s.count(ag, "meals"))
        assertEquals(listOf("meal 5", "meal 4", "meal 3"), s.rows(ag, "meals", 10).map { it.getString("activity") })
        assertEquals(listOf("meal 3", "meal 4", "meal 5"), s.rows(ag, "meals", 10, newestFirst = false).map { it.getString("activity") })
        assertEquals(listOf("meal 5"), s.rows(ag, "meals", 10, since = ids[3], newestFirst = false).map { it.getString("activity") })
        assertEquals(listOf("meal 3"), s.rows(ag, "meals", 10, since = ids[3], newestFirst = true).map { it.getString("activity") })
        assertNull(s.get(ag, "meals", ids[0]))
        val row = s.get(ag, "meals", ids[4])!!
        assertEquals("flow", row.getJSONObject("by").getString("type")); assertTrue(row.getLong("at") > 0)
        assertTrue(s.delete(ag, "meals", ids[4])); assertFalse(s.delete(ag, "meals", ids[4]))
        assertEquals(2, s.deleteAll(ag, "meals")); assertEquals(0, s.count(ag, "meals"))
        // Other agents never see these rows.
        assertEquals(0, s.count(agent(), "meals"))
    }

    @Test fun storeLimitsWritesAMinute() {
        val s = AppletTableStore.of(app); val ag = agent()
        val t = tables(o("id" to "log", "columns" to o("n" to o("type" to "integer")))).single()
        repeat(AppletTables.MAX_WRITES_PER_MINUTE) { s.add(ag, t, o("n" to it), flowBy) }
        try { s.add(ag, t, o("n" to 99), flowBy); fail() } catch (e: AppletTables.Refused) { assertEquals("rate_limited", e.code) }
    }

    @Test fun keptPhotosFollowTheirRows() {
        val s = AppletTableStore.of(app); val ag = agent()
        val t = tables(o("id" to "shots", "columns" to o("photo" to o("type" to "photo"), "note" to o("type" to "text")))).single()
        val ref = s.keepPhoto(ag, "shot", byteArrayOf(1, 2, 3))
        assertTrue(ref.startsWith("kept:"))
        assertNotNull(s.photoFile(ag, ref)); assertNull(s.photoFile(agent(), ref))
        assertEquals(listOf(ref), s.photos(ag, "shot", 12).map { it.getString("ref") })
        assertTrue(s.photos(ag, "other", 12).isEmpty())
        val row = s.add(ag, t, o("photo" to ref), flowBy)
        // A ref that isn't kept is refused.
        try { s.add(ag, t, o("photo" to "kept:nothere"), flowBy); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        // An old unused photo is cleaned; a used one stays.
        val spare = s.keepPhoto(ag, "shot", byteArrayOf(4))
        s.clean(ag, listOf(t), now = System.currentTimeMillis() + AppletTables.ORPHAN_PHOTO_MS + 60_000)
        assertNull(s.photoFile(ag, spare)); assertNotNull(s.photoFile(ag, ref))
        // Deleting the row deletes the photo only it used.
        s.delete(ag, "shots", row.getString("id"))
        assertNull(s.photoFile(ag, ref))
        // Dropping a photo column removes its photos too.
        val r2 = s.keepPhoto(ag, "shot", byteArrayOf(5)); s.add(ag, t, o("photo" to r2, "note" to "n"), flowBy)
        assertEquals(1, s.countWith(ag, "shots", "photo"))
        assertEquals(1, s.dropColumn(ag, "shots", "photo"))
        assertNull(s.photoFile(ag, r2)); assertEquals(0, s.countWith(ag, "shots", "photo")); assertEquals(1, s.count(ag, "shots"))
        assertTrue(s.hasAny(ag))
        assertTrue(s.dropAll(ag)); assertEquals(0, s.count(ag, "shots")); assertTrue(s.photos(ag, null, 10).isEmpty()); assertFalse(s.hasAny(ag))
    }

    @Test fun aFullTableKeepsThePhotoTheNewRowShares() {
        val s = AppletTableStore.of(app); val ag = agent()
        val t = tables(o("id" to "shots", "columns" to o("photo" to o("type" to "photo")), "max_rows" to 1)).single()
        val ref = s.keepPhoto(ag, "shot", byteArrayOf(1, 2, 3))
        s.add(ag, t, o("photo" to ref), flowBy)
        // The table is full: the first row goes, but the photo it shared with the new row stays.
        val second = s.add(ag, t, o("photo" to ref), flowBy)
        assertEquals(1, s.count(ag, "shots"))
        assertEquals(second.getString("id"), s.rows(ag, "shots", 10).single().getString("id"))
        assertNotNull(s.photoFile(ag, ref))
    }

    @Test fun rowIdsKeepIncreasingAfterARestartAndNeverReplaceARow() {
        val ag = agent()
        val t = tables(o("id" to "log", "columns" to o("n" to o("type" to "integer")))).single()
        val s = AppletTableStore(app) // not the shared one: that may still hold an earlier test's app
        s.add(ag, t, o("n" to 1), flowBy)
        // A row stored with an id from the future (the clock was ahead, then stepped back).
        val future = (System.currentTimeMillis() * 1000 + 3_600_000_000L).toString(36).padStart(12, '0')
        app.platform.store.save(ag, AppletTableStore.ROW, o("id" to "row:$ag:log:$future", "row_id" to future, "table" to "log", "at" to 1L,
            "values" to o("n" to 99), "photos" to a()))
        // A fresh store (as after a restart) gives ids after it, and the future row is untouched.
        val restarted = AppletTableStore(app)
        val next = restarted.add(ag, t, o("n" to 2), flowBy).getString("id")
        assertTrue("$next should sort after $future", next > future)
        assertEquals(99, restarted.get(ag, "log", future)!!.getInt("n"))
        assertEquals(listOf(2, 99, 1), restarted.rows(ag, "log", 10).map { it.getInt("n") })
        // Photo keys follow the same rule.
        val photo = restarted.keepPhoto(ag, "shot", byteArrayOf(7)).removePrefix("kept:")
        assertTrue(photo > next)
    }

    @Test fun cleanDropsRowsPastKeepDays() {
        val s = AppletTableStore.of(app); val ag = agent()
        val t = tables(o("id" to "notes", "columns" to o("text" to o("type" to "text")), "keep_days" to 1)).single()
        s.add(ag, t, o("text" to "a"), flowBy)
        s.clean(ag, listOf(t)); assertEquals(1, s.count(ag, "notes"))
        s.clean(ag, listOf(t), now = System.currentTimeMillis() + 2 * 86_400_000L); assertEquals(0, s.count(ag, "notes"))
    }
}
