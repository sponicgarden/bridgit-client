package cc.bridgit.app.interact

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Wave 2 card pieces (docs/applet-wave2.md B-E): checks, grouping and totals, boxes, marks, gallery captions. */
class TablePiecesTest {
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }

    private val items = mapOf("label" to "text", "category" to "choice", "new_usd" to "number", "quantity" to "integer",
        "insurable" to "boolean", "box" to "text", "photo" to "photo", "taken_at" to "time", "status" to "choice")
    private val scope = CardPieces.Scope(watch = { false }, tables = mapOf("items" to items.keys, "photos" to setOf("photo", "status", "taken_at", "count")),
        packaged = true,
        columnTypes = mapOf("items" to items, "photos" to mapOf("photo" to "photo", "status" to "choice", "taken_at" to "time", "count" to "integer")))

    private val cols = a(o("key" to "label", "label" to "Item"), o("key" to "new_usd", "label" to "New", "format" to "money"), o("key" to "quantity", "label" to "Qty"))
    private fun table(vararg extra: Pair<String, Any?>) = o("type" to "table", "rows" to o("\$source" to "inv"), "columns" to cols, *extra)
    private fun card(vararg pieces: JSONObject) = o("title" to "Inventory", "sources" to o("inv" to o("table" to "items", "limit" to 500), "shots" to o("table" to "photos")),
        "pieces" to JSONArray(pieces.toList()))
    private fun ok(vararg pieces: JSONObject) = CardPieces.check(card(*pieces), scope)
    private fun refused(piece: JSONObject, part: String) {
        try { ok(piece); fail("Expected refusal: $part") }
        catch (e: CardPieces.Bad) { assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }
    private fun camera(c: JSONObject, label: String = "Take photos") = o("type" to "buttons", "items" to a(o("label" to label, "do" to o("camera" to c))))

    // ---- checks -----------------------------------------------------------------------------------------------

    @Test fun acceptsTheInventoryTable() {
        ok(table("group_by" to "category", "totals" to a("new_usd", "quantity"), "marks" to o("insurable" to o("true" to "Insurable", "false" to "Not insurable")),
            "photo" to o("column" to "photo", "box" to "box"), "owner_edit" to true, "limit" to 500))
        ok(table("marks" to o("insurable" to true), "limit" to 50))
    }

    @Test fun refusesUnknownColumnsAndWrongTypes() {
        refused(table("group_by" to "room"), "room isn't a column of items")
        refused(table("totals" to a("label")), "label is a text column")
        refused(table("totals" to a("used_usd")), "used_usd isn't a column")
        refused(table("totals" to a("new_usd", "insurable")), "insurable is a boolean column")
        refused(o("type" to "table", "rows" to o("\$source" to "inv"), "columns" to a(o("key" to "label", "label" to "Item")), "totals" to a("new_usd")), "must also be one of the piece's columns")
        refused(table("totals" to a()), "1-6 columns")
        refused(table("marks" to o("label" to true)), "a mark shows a true/false column")
        refused(table("marks" to o("insurable" to "yes")), "must be true or")
        refused(table("photo" to o("column" to "label")), "not a photo column")
        refused(table("photo" to o("column" to "photo", "box" to "new_usd")), "a box is kept in a text column")
        refused(table("photo" to o("column" to "photo", "size" to 3)), "size isn't allowed")
        refused(table("owner_edit" to "yes"), "owner_edit must be true or false")
        refused(table("limit" to 51), "up to 500 with group_by")
        refused(table("group_by" to "category", "limit" to 501), "limit must be 1-500")
        // owner_edit needs an on-phone table, not any list.
        refused(o("type" to "table", "rows" to a(o("label" to "x")), "columns" to cols, "owner_edit" to true), "on-phone table")
    }

    @Test fun tableSourcesMayReadUpTo500Rows() {
        ok(table())
        try { CardPieces.check(card(table()).put("sources", o("inv" to o("table" to "items", "limit" to 501))), scope); fail() }
        catch (e: CardPieces.Bad) { assertTrue(e.message!!.contains("limit must be 1-500")) }
    }

    @Test fun cameraButtonChecksAndPermission() {
        val c = ok(camera(o("table" to "photos", "photo" to "photo", "row" to o("status" to "new"), "size" to "large", "lens" to "back")))
        assertTrue(c.camera); assertEquals(setOf("photos"), c.records)
        assertTrue(ok(camera(o("table" to "photos"))).camera) // photo defaults to "photo"
        assertFalse(ok(table()).camera)
        refused(camera(o("table" to "receipts")), "on-phone table")
        refused(camera(o("table" to "photos", "photo" to "status")), "not a photo column")
        refused(camera(o("table" to "items", "photo" to "nope")), "nope isn't a column of items")
        refused(camera(o("table" to "photos", "row" to o("room" to "x"))), "room isn't a column of photos")
        refused(camera(o("table" to "photos", "row" to o("photo" to "x"))), "filled with each photo")
        refused(camera(o("table" to "photos", "size" to "huge")), "large or medium")
        refused(camera(o("table" to "photos", "lens" to "side")), "back or front")
        refused(camera(o("table" to "photos", "flash" to true)), "flash isn't allowed")
        refused(o("type" to "buttons", "items" to a(o("label" to "Shoot", "into" to "x", "do" to o("camera" to o("table" to "photos"))))), "into goes with a call button")
        // An agent's own card has no on-phone tables.
        try { CardPieces.check(card(camera(o("table" to "photos"))), CardPieces.Scope(watch = { false })); fail() } catch (e: CardPieces.Bad) { assertTrue(e.message!!.contains("on-phone table") || e.message!!.contains("sources")) }
    }

    @Test fun installReviewSaysTheCameraTakesPhotos() {
        val words = CardPieces.describe(card(camera(o("table" to "photos"))), "Inventory")
        assertTrue(words, words.contains("\"Take photos\" (takes photos when you tap Take photos)"))
    }

    @Test fun galleryChecks() {
        val g = o("type" to "gallery", "photos" to o("\$source" to "shots"), "photo" to "photo", "caption" to "status",
            "words" to o("new" to "New", "reading" to "Reading…", "done" to "Done"), "caption_count" to "count")
        ok(g)
        refused(JSONObject(g.toString()).put("photo", "status"), "not a photo column")
        refused(JSONObject(g.toString()).put("caption", "room"), "room isn't a column of photos")
        refused(JSONObject(g.toString()).put("caption_count", "room"), "room isn't a column of photos")
        refused(JSONObject(g.toString()).put("words", o()), "1-32 values")
    }

    // ---- grouping and totals ----------------------------------------------------------------------------------

    @Test fun groupsSortByNameWithOtherLastAndAddUp() {
        val rows = listOf(o("category" to "Kitchen", "new_usd" to 120.5, "quantity" to 2), o("category" to "bedroom", "new_usd" to "$1,000", "quantity" to 1),
            o("category" to JSONObject.NULL, "new_usd" to 10, "quantity" to JSONObject.NULL), o("category" to "Kitchen", "new_usd" to JSONObject.NULL, "quantity" to 3),
            o("category" to "  ", "new_usd" to 5))
        val g = TablePieces.group(rows, "category", listOf("new_usd", "quantity"))
        assertEquals(listOf("bedroom", "Kitchen", null), g.groups.map { it.name })
        assertEquals(2, g.groups[1].rows.size); assertEquals(2, g.groups[2].rows.size)
        assertEquals(120.5, g.groups[1].totals["new_usd"]!!, 1e-9); assertEquals(5.0, g.groups[1].totals["quantity"]!!, 1e-9)
        assertEquals(1000.0, g.groups[0].totals["new_usd"]!!, 1e-9)
        assertEquals(15.0, g.groups[2].totals["new_usd"]!!, 1e-9); assertNull(g.groups[2].totals["quantity"])
        assertEquals(1135.5, g.totals["new_usd"]!!, 1e-9); assertEquals(6.0, g.totals["quantity"]!!, 1e-9)
        // No group_by: one group, the same totals.
        val one = TablePieces.group(rows, null, listOf("new_usd"))
        assertEquals(1, one.groups.size); assertEquals(1135.5, one.totals["new_usd"]!!, 1e-9)
        assertNull(TablePieces.group(emptyList(), null, listOf("new_usd")).totals["new_usd"])
    }

    // ---- boxes, marks, captions -------------------------------------------------------------------------------

    @Test fun parsesBoxes() {
        assertEquals(TablePieces.Box(0.1, 0.2, 0.5, 0.6), TablePieces.box("0.1,0.2,0.5,0.6"))
        assertEquals(TablePieces.Box(0.1, 0.2, 0.5, 0.6), TablePieces.box(" 0.5, 0.6 ,0.1,0.2"))
        assertEquals(TablePieces.Box(0.0, 0.0, 1.0, 1.0), TablePieces.box(o("x0" to -0.01, "y0" to 0, "x1" to 1.02, "y1" to 1)))
        assertEquals(TablePieces.Box(0.1, 0.2, 0.5, 0.6), TablePieces.box("{\"x0\":0.1,\"y0\":0.2,\"x1\":0.5,\"y1\":0.6}"))
        assertEquals(TablePieces.Box(0.1, 0.2, 0.5, 0.6), TablePieces.box(a(0.1, 0.2, 0.5, 0.6)))
        assertNull(TablePieces.box("0.1,0.2,0.5")); assertNull(TablePieces.box("a,b,c,d")); assertNull(TablePieces.box("10,20,50,60"))
        assertNull(TablePieces.box("0.2,0.2,0.2,0.5")); assertNull(TablePieces.box(null)); assertNull(TablePieces.box("{bad"))
        assertNull(TablePieces.box(o("x0" to 0.1, "y0" to 0.2, "x1" to 0.5)))
    }

    @Test fun marksAndCaptions() {
        val w = o("true" to "Insurable", "false" to "Not insurable")
        assertEquals("Insurable", TablePieces.mark(true, w, "insurable")); assertEquals("Not insurable", TablePieces.mark("false", w, "insurable"))
        assertEquals("Insurable", TablePieces.mark(true, true, "insurable")); assertNull(TablePieces.mark(false, true, "insurable"))
        assertNull(TablePieces.mark(null, w, "insurable")); assertNull(TablePieces.mark("maybe", w, "insurable"))
        assertEquals("3 items", TablePieces.countWords(3)); assertEquals("1 item", TablePieces.countWords(1)); assertEquals("0 items", TablePieces.countWords(0))
        assertNull(TablePieces.countWords(null)); assertNull(TablePieces.countWords(1.5))
        val g = o("caption" to "status", "words" to o("reading" to "Reading…"), "caption_count" to "count")
        assertEquals("Reading… · 3 items", TablePieces.caption(g, o("status" to "reading", "count" to 3)))
        assertEquals("done", TablePieces.caption(g, o("status" to "done")))
        assertEquals("open", TablePieces.caption(o("caption" to "status"), o("status" to "open")))
        assertNull(TablePieces.caption(o(), o("status" to "open")))
    }
}
