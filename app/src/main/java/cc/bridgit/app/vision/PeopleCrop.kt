package cc.bridgit.app.vision

import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The "people view" of a screenshot or camera photo (A0141, A0142, A0143): the phone finds the people in the
 * frame and returns one crop around all of them, instead of the whole frame.
 *
 * - The crop holds each person's whole visible body (head, body, arms and legs as far as they show), never just
 *   a face, plus a little padding ([DEFAULT_PADDING] of the people's larger side, on every side).
 * - Several people: one crop around all of them (their union); `people` lists each person's own box.
 * - `aspect`: "best_fit" (default) is whatever the people plus padding give. "W:H" (20:9, 1:1, 4:5) or a number
 *   frames the crop to that shape by growing it inside the frame. When the frame is too small for that shape, it is
 *   shrunk towards the people (padding first) but never cuts into a person; if even that can't reach the shape,
 *   the crop keeps every person and `aspect_met` is false.
 * - Nobody found: `no_person_found`, and nothing is returned or kept (the full frame never leaves silently).
 *
 * Boxes are pixels of the source frame: the screen, or the upright photo as captured. Pure Kotlin, unit-tested.
 */
object PeopleCropRules {
    const val DEFAULT_PADDING = 0.08
    const val MAX_PADDING = 0.5
    const val MIN_ASPECT = 0.1
    const val MAX_ASPECT = 10.0
    const val MAX_PEOPLE = 8

    /** A rectangle in source pixels (doubles while computing). */
    data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val area get() = maxOf(0.0, width) * maxOf(0.0, height)
        fun contains(o: Rect, eps: Double = 1e-6) = left <= o.left + eps && top <= o.top + eps && right >= o.right - eps && bottom >= o.bottom - eps
        fun clampTo(w: Int, h: Int) = Rect(left.coerceIn(0.0, w.toDouble()), top.coerceIn(0.0, h.toDouble()), right.coerceIn(0.0, w.toDouble()), bottom.coerceIn(0.0, h.toDouble()))
        fun union(o: Rect) = Rect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
        fun iou(o: Rect): Double {
            val i = Rect(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom)).takeIf { it.width > 0 && it.height > 0 }?.area ?: 0.0
            val u = area + o.area - i
            return if (u <= 0) 0.0 else i / u
        }
    }

    /** What the agent asked for. [aspect] null = best fit; [aspectText] is how it was asked ("20:9", "best_fit"). */
    data class Request(val people: Boolean, val aspect: Double?, val aspectText: String, val padding: Double)

    /** The integer crop in source pixels and whether the requested shape was reached. */
    data class Crop(val x: Int, val y: Int, val width: Int, val height: Int, val aspectMet: Boolean?) {
        val right get() = x + width
        val bottom get() = y + height
    }

    /** One person found: a box in source pixels and what found it ("pose": body points, "face": a face with its body estimated). */
    data class Person(val box: Rect, val source: String)

    private val RATIO = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*[:x/]\\s*(\\d+(?:\\.\\d+)?)\\s*$")

    /**
     * Reads `view`, `aspect` and `padding` from a request ([get] looks in the body, then the query). Throws
     * invalid_parameter for anything malformed, including aspect/padding without view "people".
     */
    fun request(get: (String) -> Any?): Request {
        val v = get("view")
        val view = when (v) { null, JSONObject.NULL -> "full"; is String -> v.trim().lowercase(); else -> null }
        if (view != "full" && view != "people") throw ErrorCatalog.error("invalid_parameter", "view must be \"full\" (default) or \"people\".")
        val a = get("aspect").takeIf { it != JSONObject.NULL }
        val p = get("padding").takeIf { it != JSONObject.NULL }
        if (view == "full") {
            if (a != null || p != null) throw ErrorCatalog.error("invalid_parameter", "aspect and padding apply to view \"people\" only.")
            return Request(false, null, "best_fit", DEFAULT_PADDING)
        }
        val (ratio, text) = aspect(a)
        val padding = when (p) {
            null -> DEFAULT_PADDING
            is Number -> p.toDouble()
            is String -> p.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() && it in 0.0..MAX_PADDING }
            ?: throw ErrorCatalog.error("invalid_parameter", "padding must be a number from 0 to $MAX_PADDING (a fraction of the people's larger side; default $DEFAULT_PADDING).")
        return Request(true, ratio, text, padding)
    }

    /** `aspect` as (width / height or null for best fit, how to show it). Pure. */
    fun aspect(a: Any?): Pair<Double?, String> {
        val bad = { ErrorCatalog.error("invalid_parameter", "aspect must be \"best_fit\" (default), a ratio like \"20:9\", \"1:1\" or \"4:5\", or a number (width / height) from $MIN_ASPECT to $MAX_ASPECT.") }
        val value: Double = when (a) {
            null -> return null to "best_fit"
            is Number -> a.toDouble()
            is String -> {
                val s = a.trim().lowercase()
                if (s == "best_fit" || s == "best fit" || s == "bestfit" || s == "auto" || s.isEmpty()) return null to "best_fit"
                val m = RATIO.matchEntire(s)
                if (m != null) {
                    val w = m.groupValues[1].toDouble(); val h = m.groupValues[2].toDouble()
                    if (w <= 0 || h <= 0) throw bad()
                    val r = w / h
                    if (!r.isFinite() || r < MIN_ASPECT || r > MAX_ASPECT) throw bad()
                    return r to "${trim(w)}:${trim(h)}"
                }
                s.toDoubleOrNull() ?: throw bad()
            }
            else -> throw bad()
        }
        if (!value.isFinite() || value < MIN_ASPECT || value > MAX_ASPECT) throw bad()
        return value to trim(value)
    }

    private fun trim(d: Double): String = if (d == Math.floor(d) && abs(d) < 1e9) d.toLong().toString() else (Math.round(d * 10000) / 10000.0).toString()

    /**
     * The crop for [people] in a [w] x [h] frame. [padding] is a fraction of the people's larger side, added on every
     * side (as far as the frame allows). With [aspect] (width / height) the crop takes that shape around the people:
     * grown inside the frame; else shrunk (padding first, never into a person); else, if no rectangle of that shape
     * holds every person, the padded people clamped to the frame with aspectMet false. Pure.
     */
    fun cropRect(people: List<Rect>, w: Int, h: Int, padding: Double = DEFAULT_PADDING, aspect: Double? = null): Crop {
        require(people.isNotEmpty() && w > 0 && h > 0)
        val p = people.map { it.clampTo(w, h) }.reduce { acc, r -> acc.union(r) }
        val m = padding * maxOf(p.width, p.height)
        val q = Rect(p.left - m, p.top - m, p.right + m, p.bottom + m).clampTo(w, h)
        if (aspect == null) return toCrop(q, w, h, null)
        // The smallest rectangle of the shape around the padded people.
        val (w0, h0) = if (q.width / q.height < aspect) q.height * aspect to q.height else q.width to q.width / aspect
        if (w0 <= w && h0 <= h) return toCrop(place(w0, h0, q, w, h), w, h, true)
        // Too big for the frame: the largest rectangle of the shape that fits, if it still holds every person.
        val s = minOf(w / w0, h / h0)
        val (w1, h1) = w0 * s to h0 * s
        if (w1 >= p.width - 1e-6 && h1 >= p.height - 1e-6) return toCrop(place(w1, h1, p, w, h), w, h, true)
        return toCrop(place(minOf(w0, w.toDouble()), minOf(h0, h.toDouble()), q, w, h), w, h, false)
    }

    /** A [cw] x [ch] rectangle centred on [around], slid back inside the frame. */
    private fun place(cw: Double, ch: Double, around: Rect, w: Int, h: Int): Rect {
        var l = around.cx - cw / 2; var t = around.cy - ch / 2
        l = l.coerceIn(0.0, maxOf(0.0, w - cw)); t = t.coerceIn(0.0, maxOf(0.0, h - ch))
        return Rect(l, t, l + cw, t + ch)
    }

    private fun toCrop(r: Rect, w: Int, h: Int, met: Boolean?): Crop {
        val cw = r.width.roundToInt().coerceIn(1, w); val ch = r.height.roundToInt().coerceIn(1, h)
        val x = r.left.roundToInt().coerceIn(0, w - cw); val y = r.top.roundToInt().coerceIn(0, h - ch)
        return Crop(x, y, cw, ch, met)
    }

    /** ML Kit pose landmark ids for the face (nose, eyes, ears, mouth) and the shoulders. */
    private val FACE = 0..10
    private const val L_SHOULDER = 11
    private const val R_SHOULDER = 12

    /**
     * A person's whole visible body from pose points ([pts]: id, x, y, in-frame likelihood; pixels of a [w] x [h]
     * frame), or null when too few points are in frame. Body points are joints, so the box is grown a little past
     * them: above the eyes for the top of the head, and beyond the shoulders and limbs for their width. Pure.
     */
    fun bodyBox(pts: List<DoubleArray>, w: Int, h: Int, minLikelihood: Double = 0.5): Rect? {
        val seen = pts.filter { it[3] >= minLikelihood && it[1] in -0.05 * w..1.05 * w && it[2] in -0.05 * h..1.05 * h }
        if (seen.size < 5) return null
        var r = Rect(seen.minOf { it[1] }, seen.minOf { it[2] }, seen.maxOf { it[1] }, seen.maxOf { it[2] })
        val face = seen.filter { it[0].toInt() in FACE }
        val ls = seen.firstOrNull { it[0].toInt() == L_SHOULDER }; val rs = seen.firstOrNull { it[0].toInt() == R_SHOULDER }
        val shoulders = if (ls != null && rs != null) abs(ls[1] - rs[1]) else 0.0
        if (face.isNotEmpty()) {
            val span = maxOf(face.maxOf { it[1] } - face.minOf { it[1] }, face.maxOf { it[2] } - face.minOf { it[2] }, shoulders * 0.35)
            r = r.union(Rect(face.minOf { it[1] } - 0.4 * span, face.minOf { it[2] } - 0.9 * span, face.maxOf { it[1] } + 0.4 * span, face.maxOf { it[2] } + 0.3 * span))
        }
        val side = maxOf(shoulders * 0.18, r.height * 0.03)
        r = Rect(r.left - side, r.top, r.right + side, r.bottom + r.height * 0.02)
        return r.clampTo(w, h).takeIf { it.width >= 2 && it.height >= 2 }
    }

    /** The body below a face nobody's body points covered: head and shoulders down to about the waist. Pure. */
    fun bodyFromFace(face: Rect, w: Int, h: Int): Rect =
        Rect(face.left - 1.0 * face.width, face.top - 0.35 * face.height, face.right + 1.0 * face.width, face.bottom + 2.5 * face.height).clampTo(w, h)

    /** The part of the frame to search for the body under [face] (a second look when the first missed it). Pure. */
    fun searchAround(face: Rect, w: Int, h: Int): Rect =
        Rect(face.cx - 3.5 * face.width, face.top - 1.0 * face.height, face.cx + 3.5 * face.width, face.top + 9.0 * face.height).clampTo(w, h)

    /** Whether [face] is already part of one of [bodies]. Pure. */
    fun covered(face: Rect, bodies: List<Rect>): Boolean = bodies.any { b -> face.cx in b.left..b.right && face.cy in b.top..b.bottom }

    /** A body that duplicates one already found. Pure. */
    fun duplicate(r: Rect, found: List<Rect>): Boolean = found.any { it.iou(r) > 0.4 || (it.contains(r) && r.area > 0.5 * it.area) }

    /** "20:9" when [w] x [h] is within 1 % of the requested [want], else the reduced ratio ("4:3", "1179:524"). Pure. */
    fun aspectText(w: Int, h: Int, want: Double?, wantText: String): String {
        if (want != null && abs(w.toDouble() / h - want) / want <= 0.01) return wantText
        var a = w; var b = h
        while (b != 0) { val t = a % b; a = b; b = t }
        return "${w / a}:${h / a}"
    }

    private fun r4(x: Double) = Math.round(x * 10000) / 10000.0

    /**
     * The `people_view` block of the reply: the crop box in the source frame, the source size, each person's box,
     * and the returned image's own size and shape ([outW] x [outH], after sizing).
     */
    fun json(req: Request, crop: Crop, srcW: Int, srcH: Int, people: List<Person>, outW: Int, outH: Int, detector: String): JSONObject =
        JSONObject().put("view", "people")
            .put("crop", JSONObject().put("x", crop.x).put("y", crop.y).put("width", crop.width).put("height", crop.height))
            .put("source", JSONObject().put("width_px", srcW).put("height_px", srcH))
            .put("width_px", outW).put("height_px", outH)
            .put("aspect_ratio", aspectText(outW, outH, req.aspect, req.aspectText)).put("aspect_ratio_value", r4(outW.toDouble() / outH))
            .put("aspect_requested", req.aspectText).put("aspect_met", crop.aspectMet ?: JSONObject.NULL)
            .put("padding", req.padding)
            .put("people_count", people.size)
            .put("people", JSONArray(people.map { p ->
                JSONObject().put("x", p.box.left.roundToInt()).put("y", p.box.top.roundToInt())
                    .put("width", p.box.width.roundToInt()).put("height", p.box.height.roundToInt()).put("found_by", p.source)
            }))
            .put("detector", detector).put("on_device", true)

    /** Headers for raw=1 replies: the essentials of [json]. */
    fun headers(o: JSONObject): Map<String, String> {
        val c = o.getJSONObject("crop"); val s = o.getJSONObject("source")
        return mapOf(
            "X-Bridgit-View" to "people",
            "X-Bridgit-Crop" to "${c.getInt("x")},${c.getInt("y")},${c.getInt("width")},${c.getInt("height")}",
            "X-Bridgit-Source-Size" to "${s.getInt("width_px")}x${s.getInt("height_px")}",
            "X-Bridgit-Aspect-Ratio" to o.getString("aspect_ratio"),
            "X-Bridgit-People-Count" to o.getInt("people_count").toString(),
        )
    }
}
