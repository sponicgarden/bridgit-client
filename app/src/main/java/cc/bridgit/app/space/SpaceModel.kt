package cc.bridgit.app.space

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * The structured space model of one deployment (A0538, phase 1; plan: docs/plans/a0538-space-model.md):
 * the site, its buildings, rooms, grounds and the devices in them, each device bound to a real one.
 *
 * Lengths are metres in the model's own 2D plan (x east, y north, origin at the south-west corner of the
 * lot); floors are stacked by `level` (0 = ground floor). Pure Kotlin + org.json, no Android dependencies.
 * [Codec.parse] checks the shape first, then [validate] checks ids, references and geometry.
 */
data class SpaceModel(
    val version: Int,
    val id: String,
    val name: String,
    val updatedAt: String?,
    val site: Site,
    val structures: List<Structure>,
    val rooms: List<Room>,
    val grounds: List<Ground>,
    val devices: List<SpaceDevice>,
) {
    data class Point(val x: Double, val y: Double)

    data class Site(val widthM: Double, val depthM: Double, val orientationDeg: Double?, val addressLabel: String?)

    data class Structure(
        val id: String,
        val kind: String,
        val name: String,
        val footprint: List<Point>,
        val levels: Int,
        val roof: String,
    )

    data class Room(
        val id: String,
        val structureId: String,
        val level: Int,
        val name: String,
        val kind: String,
        val polygon: List<Point>,
    )

    data class Ground(val id: String, val name: String, val kind: String, val polygon: List<Point>)

    data class SpaceDevice(val id: String, val label: String, val kind: String, val binding: Binding, val place: Place)

    /** Links a model device to a live one: `self` (this phone), `satellite` (a HubLinks link id),
     *  `portal` (a portal agent id) or `lan` (a LAN device key). `ref` is required except for `self`. */
    data class Binding(val type: String, val ref: String?)

    /** Where a device sits. At most one of [roomId] / [groundId]. */
    data class Place(
        val roomId: String?,
        val groundId: String?,
        val x: Double,
        val y: Double,
        val level: Int,
        val facingDeg: Double?,
        val fovDeg: Double?,
    )

    sealed interface Result {
        data class Ok(val model: SpaceModel) : Result
        data class Invalid(val errors: List<SpaceError>) : Result
    }

    object Codec {
        const val VERSION = 1
        const val MAX_ITEMS = 500
        const val MAX_POINTS = 200
        const val MAX_TEXT = 200

        val STRUCTURE_KINDS = setOf("house", "barn", "shed", "garage")
        val ROOFS = setOf("gable", "hip", "flat")
        val ROOM_KINDS = setOf("living", "kitchen", "bedroom", "bath", "office", "garage", "other")
        val GROUND_KINDS = setOf("yard", "garden", "pasture", "driveway", "pool", "deck", "other")
        val DEVICE_KINDS = setOf("phone", "camera", "speaker", "light", "sensor", "tv", "router", "other")
        val BINDING_TYPES = setOf("self", "satellite", "portal", "lan")

        /** Parses and validates; never throws. */
        fun parse(json: JSONObject): Result {
            val r = Reader()
            val model = r.model(json)
            if (r.errors.isNotEmpty() || model == null) return Result.Invalid(r.errors.toList())
            val errors = validate(model)
            return if (errors.isEmpty()) Result.Ok(model) else Result.Invalid(errors)
        }

        fun parse(text: String): Result {
            val json = try { JSONObject(text) } catch (e: Exception) {
                return Result.Invalid(listOf(SpaceError("", "not a JSON object")))
            }
            return parse(json)
        }

        fun toJson(m: SpaceModel): JSONObject = JSONObject().apply {
            put("version", m.version)
            put("id", m.id)
            put("name", m.name)
            m.updatedAt?.let { put("updated_at", it) }
            put("site", JSONObject().apply {
                put("width_m", m.site.widthM)
                put("depth_m", m.site.depthM)
                m.site.orientationDeg?.let { put("orientation_deg", it) }
                m.site.addressLabel?.let { put("address_label", it) }
            })
            put("structures", JSONArray(m.structures.map { s ->
                JSONObject().put("id", s.id).put("kind", s.kind).put("name", s.name)
                    .put("footprint", points(s.footprint)).put("levels", s.levels).put("roof", s.roof)
            }))
            put("rooms", JSONArray(m.rooms.map { r ->
                JSONObject().put("id", r.id).put("structure_id", r.structureId).put("level", r.level)
                    .put("name", r.name).put("kind", r.kind).put("polygon", points(r.polygon))
            }))
            put("grounds", JSONArray(m.grounds.map { g ->
                JSONObject().put("id", g.id).put("name", g.name).put("kind", g.kind).put("polygon", points(g.polygon))
            }))
            put("devices", JSONArray(m.devices.map { d ->
                JSONObject().put("id", d.id).put("label", d.label).put("kind", d.kind)
                    .put("binding", JSONObject().put("type", d.binding.type).apply { d.binding.ref?.let { put("ref", it) } })
                    .put("place", JSONObject().apply {
                        d.place.roomId?.let { put("room_id", it) }
                        d.place.groundId?.let { put("ground_id", it) }
                        put("x", d.place.x)
                        put("y", d.place.y)
                        put("level", d.place.level)
                        d.place.facingDeg?.let { put("facing_deg", it) }
                        d.place.fovDeg?.let { put("fov_deg", it) }
                    })
            }))
        }

        private fun points(ps: List<Point>) = JSONArray(ps.map { JSONArray().put(it.x).put(it.y) })
    }

    companion object {
        private val ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        /** Ids, references and geometry of an already well-shaped model. Empty when valid. */
        fun validate(m: SpaceModel): List<SpaceError> {
            val errors = mutableListOf<SpaceError>()
            fun err(path: String, msg: String) { errors += SpaceError(path, msg) }

            if (m.version != Codec.VERSION) err("version", "unknown version ${m.version}")
            if (!ID.matches(m.id)) err("id", "invalid id")
            if (m.site.widthM <= 0) err("site.width_m", "must be greater than 0")
            if (m.site.depthM <= 0) err("site.depth_m", "must be greater than 0")
            m.site.orientationDeg?.let { if (it < 0 || it >= 360) err("site.orientation_deg", "must be in [0, 360)") }

            val seen = HashMap<String, String>()
            fun id(path: String, value: String) {
                if (!ID.matches(value)) { err("$path.id", "invalid id"); return }
                val first = seen.putIfAbsent(value, path)
                if (first != null) err("$path.id", "duplicate id \"$value\" (first used at $first)")
            }
            m.structures.forEachIndexed { i, s -> id("structures[$i]", s.id) }
            m.rooms.forEachIndexed { i, r -> id("rooms[$i]", r.id) }
            m.grounds.forEachIndexed { i, g -> id("grounds[$i]", g.id) }
            m.devices.forEachIndexed { i, d -> id("devices[$i]", d.id) }

            val siteBox = listOf(Point(0.0, 0.0), Point(m.site.widthM, 0.0), Point(m.site.widthM, m.site.depthM), Point(0.0, m.site.depthM))
            fun inSite(p: Point) = p.x >= -EPS && p.y >= -EPS && p.x <= m.site.widthM + EPS && p.y <= m.site.depthM + EPS

            fun polygon(path: String, poly: List<Point>): Boolean {
                if (poly.size < 3) { err(path, "needs at least 3 points"); return false }
                if (Geometry.selfIntersects(poly)) { err(path, "edges cross each other"); return false }
                if (abs(Geometry.area(poly)) < EPS) { err(path, "has no area"); return false }
                return true
            }

            val structures = HashMap<String, Structure>()
            m.structures.forEachIndexed { i, s ->
                val p = "structures[$i]"
                if (s.kind !in Codec.STRUCTURE_KINDS) err("$p.kind", "unknown kind \"${s.kind}\"")
                if (s.roof !in Codec.ROOFS) err("$p.roof", "unknown roof \"${s.roof}\"")
                if (s.levels < 1 || s.levels > 50) err("$p.levels", "must be between 1 and 50")
                if (polygon("$p.footprint", s.footprint) && !s.footprint.all(::inSite)) err("$p.footprint", "lies outside the site")
                structures.putIfAbsent(s.id, s)
            }

            val rooms = HashMap<String, Room>()
            m.rooms.forEachIndexed { i, r ->
                val p = "rooms[$i]"
                if (r.kind !in Codec.ROOM_KINDS) err("$p.kind", "unknown kind \"${r.kind}\"")
                val ok = polygon("$p.polygon", r.polygon)
                val s = structures[r.structureId]
                if (s == null) err("$p.structure_id", "no structure \"${r.structureId}\"")
                else {
                    if (r.level < 0 || r.level >= s.levels) err("$p.level", "structure \"${s.id}\" has levels 0..${s.levels - 1}")
                    if (ok && s.footprint.size >= 3 && !Geometry.contains(s.footprint, r.polygon)) {
                        err("$p.polygon", "lies outside structure \"${s.id}\"")
                    }
                }
                rooms.putIfAbsent(r.id, r)
            }

            val grounds = HashMap<String, Ground>()
            m.grounds.forEachIndexed { i, g ->
                val p = "grounds[$i]"
                if (g.kind !in Codec.GROUND_KINDS) err("$p.kind", "unknown kind \"${g.kind}\"")
                if (polygon("$p.polygon", g.polygon) && !Geometry.contains(siteBox, g.polygon)) err("$p.polygon", "lies outside the site")
                grounds.putIfAbsent(g.id, g)
            }

            m.devices.forEachIndexed { i, d ->
                val p = "devices[$i]"
                if (d.kind !in Codec.DEVICE_KINDS) err("$p.kind", "unknown kind \"${d.kind}\"")
                when {
                    d.binding.type !in Codec.BINDING_TYPES -> err("$p.binding.type", "unknown binding \"${d.binding.type}\"")
                    d.binding.type != "self" && d.binding.ref.isNullOrBlank() -> err("$p.binding.ref", "required for ${d.binding.type}")
                }
                val pl = d.place
                val at = Point(pl.x, pl.y)
                if (!inSite(at)) err("$p.place", "lies outside the site")
                if (pl.level < 0) err("$p.place.level", "must be 0 or more")
                pl.facingDeg?.let { if (it < 0 || it >= 360) err("$p.place.facing_deg", "must be in [0, 360)") }
                pl.fovDeg?.let { if (it <= 0 || it > 360) err("$p.place.fov_deg", "must be in (0, 360]") }
                if (pl.roomId != null && pl.groundId != null) err("$p.place", "give room_id or ground_id, not both")
                pl.roomId?.let { rid ->
                    val r = rooms[rid]
                    if (r == null) err("$p.place.room_id", "no room \"$rid\"")
                    else {
                        if (r.level != pl.level) err("$p.place.level", "room \"$rid\" is on level ${r.level}")
                        if (r.polygon.size >= 3 && !Geometry.inOrOn(at, r.polygon)) err("$p.place", "lies outside room \"$rid\"")
                    }
                }
                pl.groundId?.let { gid ->
                    val g = grounds[gid]
                    if (g == null) err("$p.place.ground_id", "no ground \"$gid\"")
                    else if (g.polygon.size >= 3 && !Geometry.inOrOn(at, g.polygon)) err("$p.place", "lies outside ground \"$gid\"")
                }
            }
            return errors
        }

        private const val EPS = 1e-9
    }
}

/** One problem in a space model: [path] is JSON-style (`rooms[1].polygon`), empty for the whole document. */
data class SpaceError(val path: String, val message: String)

/** Collects shape errors (missing fields, wrong types, oversize lists) while reading the JSON. */
private class Reader {
    val errors = mutableListOf<SpaceError>()
    private fun err(path: String, msg: String) { errors += SpaceError(path, msg) }

    fun model(o: JSONObject): SpaceModel? {
        val version = int(o, "", "version") ?: return null
        if (version != SpaceModel.Codec.VERSION) {
            err("version", "unknown version $version")
            return null
        }
        val id = text(o, "", "id")
        val name = text(o, "", "name")
        val updated = optText(o, "", "updated_at")
        val site = obj(o, "", "site")?.let { site(it) }
        val structures = list(o, "structures") { p, x -> structure(p, x) }
        val rooms = list(o, "rooms") { p, x -> room(p, x) }
        val grounds = list(o, "grounds") { p, x -> ground(p, x) }
        val devices = list(o, "devices") { p, x -> device(p, x) }
        if (id == null || name == null || site == null || structures == null || rooms == null || grounds == null || devices == null) return null
        return SpaceModel(version, id, name, updated, site, structures, rooms, grounds, devices)
    }

    private fun site(o: JSONObject): SpaceModel.Site? {
        val w = num(o, "site", "width_m")
        val d = num(o, "site", "depth_m")
        val orient = optNum(o, "site", "orientation_deg")
        val addr = optText(o, "site", "address_label")
        return if (w == null || d == null) null else SpaceModel.Site(w, d, orient, addr)
    }

    private fun structure(p: String, o: JSONObject): SpaceModel.Structure? {
        val id = text(o, p, "id"); val kind = text(o, p, "kind"); val name = text(o, p, "name")
        val fp = poly(o, p, "footprint"); val levels = int(o, p, "levels"); val roof = text(o, p, "roof")
        if (id == null || kind == null || name == null || fp == null || levels == null || roof == null) return null
        return SpaceModel.Structure(id, kind, name, fp, levels, roof)
    }

    private fun room(p: String, o: JSONObject): SpaceModel.Room? {
        val id = text(o, p, "id"); val sid = text(o, p, "structure_id"); val level = int(o, p, "level")
        val name = text(o, p, "name"); val kind = text(o, p, "kind"); val poly = poly(o, p, "polygon")
        if (id == null || sid == null || level == null || name == null || kind == null || poly == null) return null
        return SpaceModel.Room(id, sid, level, name, kind, poly)
    }

    private fun ground(p: String, o: JSONObject): SpaceModel.Ground? {
        val id = text(o, p, "id"); val name = text(o, p, "name"); val kind = text(o, p, "kind"); val poly = poly(o, p, "polygon")
        if (id == null || name == null || kind == null || poly == null) return null
        return SpaceModel.Ground(id, name, kind, poly)
    }

    private fun device(p: String, o: JSONObject): SpaceModel.SpaceDevice? {
        val id = text(o, p, "id"); val label = text(o, p, "label"); val kind = text(o, p, "kind")
        val binding = obj(o, p, "binding")?.let { b ->
            val bp = "$p.binding"
            val type = text(b, bp, "type"); val ref = optText(b, bp, "ref")
            type?.let { SpaceModel.Binding(it, ref) }
        }
        val place = obj(o, p, "place")?.let { pl ->
            val pp = "$p.place"
            val room = optText(pl, pp, "room_id"); val ground = optText(pl, pp, "ground_id")
            val x = num(pl, pp, "x"); val y = num(pl, pp, "y"); val level = int(pl, pp, "level")
            val facing = optNum(pl, pp, "facing_deg"); val fov = optNum(pl, pp, "fov_deg")
            if (x == null || y == null || level == null) null else SpaceModel.Place(room, ground, x, y, level, facing, fov)
        }
        if (id == null || label == null || kind == null || binding == null || place == null) return null
        return SpaceModel.SpaceDevice(id, label, kind, binding, place)
    }

    private fun <T> list(o: JSONObject, key: String, item: (String, JSONObject) -> T?): List<T>? {
        val raw = o.opt(key)
        if (raw == null || raw == JSONObject.NULL) return emptyList()
        if (raw !is JSONArray) { err(key, "must be a list"); return null }
        if (raw.length() > SpaceModel.Codec.MAX_ITEMS) { err(key, "more than ${SpaceModel.Codec.MAX_ITEMS} entries"); return null }
        val out = ArrayList<T>(raw.length())
        var ok = true
        for (i in 0 until raw.length()) {
            val p = "$key[$i]"
            val x = raw.opt(i)
            if (x !is JSONObject) { err(p, "must be an object"); ok = false; continue }
            val v = item(p, x)
            if (v == null) ok = false else out += v
        }
        return if (ok) out else null
    }

    private fun path(p: String, key: String) = if (p.isEmpty()) key else "$p.$key"

    private fun obj(o: JSONObject, p: String, key: String): JSONObject? {
        val v = o.opt(key)
        if (v !is JSONObject) { err(path(p, key), if (v == null) "required" else "must be an object"); return null }
        return v
    }

    private fun text(o: JSONObject, p: String, key: String): String? {
        val v = o.opt(key)
        if (v !is String || v.isBlank()) { err(path(p, key), if (v == null) "required" else "must be a non-empty string"); return null }
        if (v.length > SpaceModel.Codec.MAX_TEXT) { err(path(p, key), "longer than ${SpaceModel.Codec.MAX_TEXT} characters"); return null }
        return v
    }

    private fun optText(o: JSONObject, p: String, key: String): String? {
        val v = o.opt(key)
        if (v == null || v == JSONObject.NULL) return null
        return text(o, p, key)
    }

    private fun num(o: JSONObject, p: String, key: String): Double? {
        val v = o.opt(key)
        val d = (v as? Number)?.toDouble()
        if (d == null || d.isNaN() || d.isInfinite()) { err(path(p, key), if (v == null) "required" else "must be a number"); return null }
        return d
    }

    private fun optNum(o: JSONObject, p: String, key: String): Double? {
        val v = o.opt(key)
        if (v == null || v == JSONObject.NULL) return null
        return num(o, p, key)
    }

    private fun int(o: JSONObject, p: String, key: String): Int? {
        val d = num(o, p, key) ?: return null
        if (d != Math.floor(d) || abs(d) > 1_000_000) { err(path(p, key), "must be a whole number"); return null }
        return d.toInt()
    }

    private fun poly(o: JSONObject, p: String, key: String): List<SpaceModel.Point>? {
        val pp = path(p, key)
        val v = o.opt(key)
        if (v !is JSONArray) { err(pp, if (v == null) "required" else "must be a list of [x, y] points"); return null }
        if (v.length() > SpaceModel.Codec.MAX_POINTS) { err(pp, "more than ${SpaceModel.Codec.MAX_POINTS} points"); return null }
        val out = ArrayList<SpaceModel.Point>(v.length())
        for (i in 0 until v.length()) {
            val pt = v.opt(i) as? JSONArray
            val x = (pt?.opt(0) as? Number)?.toDouble()
            val y = (pt?.opt(1) as? Number)?.toDouble()
            if (pt == null || pt.length() != 2 || x == null || y == null || !x.isFinite() || !y.isFinite()) {
                err("$pp[$i]", "must be [x, y]"); return null
            }
            out += SpaceModel.Point(x, y)
        }
        return out
    }
}

/** Plan geometry on simple polygons (closed implicitly, last point joins the first). */
internal object Geometry {
    private const val EPS = 1e-9

    fun area(poly: List<SpaceModel.Point>): Double {
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2
    }

    private fun cross(o: SpaceModel.Point, a: SpaceModel.Point, b: SpaceModel.Point) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun sign(v: Double) = if (v > EPS) 1 else if (v < -EPS) -1 else 0

    private fun onSegment(p: SpaceModel.Point, a: SpaceModel.Point, b: SpaceModel.Point) =
        sign(cross(a, b, p)) == 0 &&
            p.x >= minOf(a.x, b.x) - EPS && p.x <= maxOf(a.x, b.x) + EPS &&
            p.y >= minOf(a.y, b.y) - EPS && p.y <= maxOf(a.y, b.y) + EPS

    /** Segments a-b and c-d touch or cross. */
    fun intersects(a: SpaceModel.Point, b: SpaceModel.Point, c: SpaceModel.Point, d: SpaceModel.Point): Boolean {
        val d1 = sign(cross(c, d, a)); val d2 = sign(cross(c, d, b))
        val d3 = sign(cross(a, b, c)); val d4 = sign(cross(a, b, d))
        if (d1 * d2 < 0 && d3 * d4 < 0) return true
        return (d1 == 0 && onSegment(a, c, d)) || (d2 == 0 && onSegment(b, c, d)) ||
            (d3 == 0 && onSegment(c, a, b)) || (d4 == 0 && onSegment(d, a, b))
    }

    /** Segments cross at a single interior point of both (touching is not crossing). */
    private fun crossesProperly(a: SpaceModel.Point, b: SpaceModel.Point, c: SpaceModel.Point, d: SpaceModel.Point): Boolean {
        val d1 = sign(cross(c, d, a)); val d2 = sign(cross(c, d, b))
        val d3 = sign(cross(a, b, c)); val d4 = sign(cross(a, b, d))
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    /** Any two non-adjacent edges touch, or two adjacent edges fold back onto each other, or an edge has no length. */
    fun selfIntersects(poly: List<SpaceModel.Point>): Boolean {
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            if (abs(a.x - b.x) < EPS && abs(a.y - b.y) < EPS) return true
        }
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            for (j in i + 1 until n) {
                val c = poly[j]; val d = poly[(j + 1) % n]
                val adjacent = j == i + 1 || (i == 0 && j == n - 1)
                if (adjacent) {
                    // Shared vertex is expected; folding back along the same line is not.
                    val shared = if (j == i + 1) b else a
                    val other1 = if (j == i + 1) a else b
                    val other2 = if (j == i + 1) d else c
                    if (sign(cross(shared, other1, other2)) == 0 &&
                        (other1.x - shared.x) * (other2.x - shared.x) + (other1.y - shared.y) * (other2.y - shared.y) > 0
                    ) return true
                } else if (intersects(a, b, c, d)) return true
            }
        }
        return false
    }

    /** [p] lies inside [poly] or on its boundary. */
    fun inOrOn(p: SpaceModel.Point, poly: List<SpaceModel.Point>): Boolean {
        val n = poly.size
        for (i in 0 until n) if (onSegment(p, poly[i], poly[(i + 1) % n])) return true
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Every point of [inner] is inside or on [outer], and no edges cross (so concave outers are handled). */
    fun contains(outer: List<SpaceModel.Point>, inner: List<SpaceModel.Point>): Boolean {
        if (!inner.all { inOrOn(it, outer) }) return false
        for (i in inner.indices) {
            val a = inner[i]; val b = inner[(i + 1) % inner.size]
            if (!inOrOn(SpaceModel.Point((a.x + b.x) / 2, (a.y + b.y) / 2), outer)) return false
            for (j in outer.indices) {
                if (crossesProperly(a, b, outer[j], outer[(j + 1) % outer.size])) return false
            }
        }
        return true
    }
}
