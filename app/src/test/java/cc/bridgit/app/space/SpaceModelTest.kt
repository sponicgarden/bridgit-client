package cc.bridgit.app.space

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A0538 phase 1: the space model parses, round-trips and reports bad ids, references and geometry by path. */
class SpaceModelTest {
    /** A 20 x 30 m lot: a 10 x 8 m one-storey house split into two rooms, a yard, one phone and one camera. */
    private fun fixture() = JSONObject("""
        {"version":1,"id":"test-house","name":"Test house","updated_at":"2026-11-15T00:00:00Z",
         "site":{"width_m":20,"depth_m":30,"orientation_deg":0,"address_label":"Test lot"},
         "structures":[{"id":"house","kind":"house","name":"House","footprint":[[2,2],[12,2],[12,10],[2,10]],"levels":1,"roof":"gable"}],
         "rooms":[
           {"id":"living","structure_id":"house","level":0,"name":"Living room","kind":"living","polygon":[[2,2],[7,2],[7,10],[2,10]]},
           {"id":"kitchen","structure_id":"house","level":0,"name":"Kitchen","kind":"kitchen","polygon":[[7,2],[12,2],[12,10],[7,10]]}],
         "grounds":[{"id":"yard","name":"Back yard","kind":"yard","polygon":[[0,12],[20,12],[20,30],[0,30]]}],
         "devices":[
           {"id":"hub","label":"Hub phone","kind":"phone","binding":{"type":"self"},"place":{"room_id":"living","x":3,"y":3,"level":0}},
           {"id":"yard-cam","label":"Yard camera","kind":"camera","binding":{"type":"portal","ref":"agent-123"},
            "place":{"ground_id":"yard","x":10,"y":12,"level":0,"facing_deg":0,"fov_deg":90}}]}
    """.trimIndent())

    private fun ok(json: JSONObject): SpaceModel = when (val r = SpaceModel.Codec.parse(json)) {
        is SpaceModel.Result.Ok -> r.model
        is SpaceModel.Result.Invalid -> { fail("expected valid, got ${r.errors}"); throw AssertionError() }
    }

    private fun errors(json: JSONObject): List<SpaceError> = when (val r = SpaceModel.Codec.parse(json)) {
        is SpaceModel.Result.Ok -> { fail("expected errors"); throw AssertionError() }
        is SpaceModel.Result.Invalid -> r.errors
    }

    private fun assertError(json: JSONObject, path: String, contains: String) {
        val es = errors(json)
        assertTrue("expected $path ~ \"$contains\" in $es", es.any { it.path == path && it.message.contains(contains) })
    }

    private fun JSONObject.arr(key: String, i: Int): JSONObject = getJSONArray(key).getJSONObject(i)

    @Test fun `valid model parses and round-trips`() {
        val m = ok(fixture())
        assertEquals("Test house", m.name)
        assertEquals(2, m.rooms.size)
        assertEquals(SpaceModel.Binding("portal", "agent-123"), m.devices[1].binding)
        assertEquals(90.0, m.devices[1].place.fovDeg!!, 0.0)
        val again = ok(SpaceModel.Codec.toJson(m))
        assertEquals(m, again)
        assertEquals(SpaceModel.Codec.toJson(m).toString(), SpaceModel.Codec.toJson(again).toString())
        assertEquals(m, ok(JSONObject(SpaceModel.Codec.toJson(m).toString())))
    }

    @Test fun `parse from text rejects non-json`() {
        val r = SpaceModel.Codec.parse("not json")
        assertTrue(r is SpaceModel.Result.Invalid)
        assertTrue(SpaceModel.Codec.parse(fixture().toString()) is SpaceModel.Result.Ok)
    }

    @Test fun `duplicate id across collections is reported`() {
        val j = fixture()
        j.arr("devices", 1).put("id", "kitchen")
        assertError(j, "devices[1].id", "duplicate id \"kitchen\"")
    }

    @Test fun `invalid id is reported`() {
        val j = fixture()
        j.arr("rooms", 0).put("id", "Living Room!")
        assertError(j, "rooms[0].id", "invalid id")
    }

    @Test fun `dangling room structure reference is reported`() {
        val j = fixture()
        j.arr("rooms", 1).put("structure_id", "barn")
        assertError(j, "rooms[1].structure_id", "no structure \"barn\"")
    }

    @Test fun `dangling device room and ground references are reported`() {
        val j = fixture()
        j.arr("devices", 0).getJSONObject("place").put("room_id", "attic")
        j.arr("devices", 1).getJSONObject("place").put("ground_id", "pond")
        assertError(j, "devices[0].place.room_id", "no room \"attic\"")
        assertError(j, "devices[1].place.ground_id", "no ground \"pond\"")
    }

    @Test fun `polygon with fewer than 3 points is reported`() {
        val j = fixture()
        j.arr("grounds", 0).put("polygon", JSONArray("[[0,12],[20,12]]"))
        assertError(j, "grounds[0].polygon", "at least 3 points")
    }

    @Test fun `self-intersecting polygon is reported`() {
        val j = fixture()
        // A bow tie.
        j.arr("grounds", 0).put("polygon", JSONArray("[[0,12],[20,30],[20,12],[0,30]]"))
        assertError(j, "grounds[0].polygon", "edges cross")
        val k = fixture()
        k.arr("structures", 0).put("footprint", JSONArray("[[2,2],[12,2],[12,2],[2,10]]"))
        assertError(k, "structures[0].footprint", "edges cross")
    }

    @Test fun `polygon with all points on a line is reported`() {
        val j = fixture()
        // Points on one line: the closing edge folds back over the others.
        j.arr("grounds", 0).put("polygon", JSONArray("[[0,12],[10,12],[20,12]]"))
        assertError(j, "grounds[0].polygon", "edges cross")
    }

    @Test fun `room outside its structure is reported`() {
        val j = fixture()
        j.arr("rooms", 1).put("polygon", JSONArray("[[7,2],[14,2],[14,10],[7,10]]"))
        assertError(j, "rooms[1].polygon", "outside structure \"house\"")
    }

    @Test fun `room in the notch of a concave structure is reported`() {
        val j = fixture()
        // L-shaped house: the top-right quarter is missing. A room spanning the notch has all corners on the
        // footprint's boundary or inside it, but its edge leaves the house.
        j.arr("structures", 0).put("footprint", JSONArray("[[2,2],[12,2],[12,6],[7,6],[7,10],[2,10]]"))
        j.arr("rooms", 1).put("polygon", JSONArray("[[7,2],[12,2],[12,6],[7,10]]"))
        assertError(j, "rooms[1].polygon", "outside structure")
    }

    @Test fun `device outside the site is reported`() {
        val j = fixture()
        j.arr("devices", 1).getJSONObject("place").put("ground_id", JSONObject.NULL).put("x", 25)
        assertError(j, "devices[1].place", "outside the site")
    }

    @Test fun `device outside its room is reported`() {
        val j = fixture()
        j.arr("devices", 0).getJSONObject("place").put("x", 9)
        assertError(j, "devices[0].place", "outside room \"living\"")
    }

    @Test fun `device on the wrong level of its room is reported`() {
        val j = fixture()
        j.arr("devices", 0).getJSONObject("place").put("level", 1)
        assertError(j, "devices[0].place.level", "level 0")
    }

    @Test fun `unknown version is reported`() {
        val j = fixture().put("version", 2)
        assertEquals(listOf(SpaceError("version", "unknown version 2")), errors(j))
        assertError(fixture().apply { remove("version") }, "version", "required")
    }

    @Test fun `unknown kinds and bindings are reported`() {
        val j = fixture()
        j.arr("rooms", 0).put("kind", "ballroom")
        j.arr("devices", 1).getJSONObject("binding").put("type", "bluetooth")
        assertError(j, "rooms[0].kind", "unknown kind")
        assertError(j, "devices[1].binding.type", "unknown binding")
    }

    @Test fun `non-self binding needs a ref`() {
        val j = fixture()
        j.arr("devices", 1).getJSONObject("binding").remove("ref")
        assertError(j, "devices[1].binding.ref", "required for portal")
    }

    @Test fun `shape errors name the field`() {
        val j = fixture()
        j.getJSONObject("site").put("width_m", "wide")
        j.arr("structures", 0).put("levels", 1.5)
        j.arr("rooms", 0).put("polygon", JSONArray("[[2,2],[7],[7,10]]"))
        val es = errors(j)
        assertTrue(es.toString(), es.contains(SpaceError("site.width_m", "must be a number")))
        assertTrue(es.toString(), es.contains(SpaceError("structures[0].levels", "must be a whole number")))
        assertTrue(es.toString(), es.contains(SpaceError("rooms[0].polygon[1]", "must be [x, y]")))
    }

    @Test fun `angles and site size are bounded`() {
        val j = fixture()
        j.getJSONObject("site").put("depth_m", 0)
        j.arr("devices", 1).getJSONObject("place").put("fov_deg", 400).put("facing_deg", 360)
        assertError(j, "site.depth_m", "greater than 0")
        assertError(j, "devices[1].place.fov_deg", "(0, 360]")
        assertError(j, "devices[1].place.facing_deg", "[0, 360)")
    }

    @Test fun `oversize lists are refused`() {
        val j = fixture()
        val pts = JSONArray()
        repeat(SpaceModel.Codec.MAX_POINTS + 1) { pts.put(JSONArray().put(it).put(12)) }
        j.arr("grounds", 0).put("polygon", pts)
        assertError(j, "grounds[0].polygon", "more than")
    }

    @Test fun `missing collections mean empty`() {
        val j = JSONObject("""{"version":1,"id":"empty","name":"Empty lot","site":{"width_m":5,"depth_m":5}}""")
        val m = ok(j)
        assertTrue(m.structures.isEmpty() && m.devices.isEmpty())
        assertEquals(m, ok(SpaceModel.Codec.toJson(m)))
    }
}
