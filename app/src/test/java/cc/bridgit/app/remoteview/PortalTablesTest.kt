package cc.bridgit.app.remoteview

import cc.bridgit.app.applets.AppletPackage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortalTablesTest {
    private fun inventory() = JSONObject(File("../applets/insurance-inventory/applet.json").readText())
    private fun row(id: String, vararg kv: Pair<String, Any?>) = JSONObject().put("id", id).put("at", 1L).put("by", JSONObject()).apply { kv.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

    @Test fun inventoryPortalIsItsInterface() {
        val d = AppletPackage.check(inventory()).definition
        assertEquals(listOf("photos", "items"), PortalTables.tablesOf(d))
        assertEquals(listOf("camera", "create", "csv"), PortalTables.actionsOf(d))
        assertTrue(PortalRecords.always(d)); assertTrue(PortalTables.portalIsInterface(d))
        assertEquals(setOf("camera", "create", "csv"), PortalTables.buttonIds(d.getJSONObject("ui").getJSONObject("card")))
    }

    @Test fun badDeclarationsAreRefused() {
        for ((portal, part) in listOf(
            JSONObject("""{"tables":["rooms"]}""") to "isn't one of the applet's tables",
            JSONObject("""{"tables":[]}""") to "1-3",
            JSONObject("""{"tables":["items"],"actions":["dance"]}""") to "isn't the id of a button",
            JSONObject("""{"actions":["camera"]}""") to "needs ui.portal.tables",
            JSONObject("""{"include":"sometimes"}""") to "optional",
            JSONObject("""{"tables":["items"],"records":{"kind":"x","title":"X"}}""") to "not both")) {
            val d = inventory(); d.getJSONObject("ui").put("portal", portal)
            try { AppletPackage.check(d); fail(part) } catch (e: AppletPackage.Bad) { assertTrue("${e.message} / $part", e.message!!.contains(part)) }
        }
        assertNull(PortalTables.check(JSONObject(), emptySet(), emptySet()))
    }

    @Test fun diffSendsNewChangedAndGoneRows() {
        val a = row("a", "label" to "Lamp"); val b = row("b", "label" to "Chair")
        val first = PortalTables.diff(listOf(a, b), emptyMap())
        assertEquals(listOf("a", "b"), first.upserts.map { it.getString("id") })
        assertEquals("Lamp", first.upserts[0].getJSONObject("values").getString("label"))
        assertTrue(!first.upserts[0].getJSONObject("values").has("by"))
        val b2 = row("b", "label" to "Armchair"); val c = row("c", "label" to "Rug")
        val next = PortalTables.diff(listOf(b2, c), first.sent)
        assertEquals(listOf("b", "c"), next.upserts.map { it.getString("id") }); assertEquals(listOf("a"), next.deletes)
        assertEquals(PortalTables.diff(listOf(b2, c), next.sent).upserts.size, 0)
    }

    @Test fun diffPagesAndWaitsForPhotos() {
        val rows = (1..5).map { row("r$it", "photo" to "p$it") }
        val d = PortalTables.diff(rows, emptyMap(), max = 2) { it.optString("photo") != "p1" }
        assertEquals(listOf("r2", "r3"), d.upserts.map { it.getString("id") }); assertTrue(d.more)
        // A sent row whose photo isn't on the portal again is kept, never sent as gone.
        val sent = PortalTables.diff(rows, emptyMap()).sent
        assertEquals(emptyList<String>(), PortalTables.diff(rows, sent) { false }.deletes)
        assertEquals(listOf("p1", "p2"), PortalTables.photoRefs(listOf(rows[0], rows[1], row("x", "photo" to null)), listOf("photo")))
    }

    @Test fun portalSetsOnlyPlainColumns() {
        val set = PortalTables.cleanSet(JSONObject("""{"label":"Desk","photo":"p9","id":"z","by":{},"new_usd":40}"""), listOf("label", "photo", "new_usd"), listOf("photo"))
        assertEquals(setOf("label", "new_usd"), set.keys().asSequence().toSet())
        assertEquals("Desk", set.getString("label"))
    }
}
