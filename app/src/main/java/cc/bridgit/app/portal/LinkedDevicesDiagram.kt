package cc.bridgit.app.portal

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import cc.bridgit.app.platform.DeviceType
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.graphics.PointF
import cc.bridgit.app.portal.LinkedDevicesLayout.Diagram
import cc.bridgit.app.portal.LinkedDevicesLayout.Node
import cc.bridgit.app.portal.LinkedDevicesLayout.Role
import kotlin.math.min

/**
 * The Linked devices map, drawn in the Home scene's look (scene.html): the same dark field with a faint dot grid,
 * hexagon hubs, rounded tiles, dotted elbow lines and the scene's colours (hubs gold, portals violet, satellites blue,
 * this phone green), with the row names written up the left edge like Home's group names. It holds still.
 * A0243: each link is two arrows, one per direction, showing whether both devices opted in to it (LinkLanes).
 */
class LinkedDevicesDiagram @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onNode: ((Node) -> Unit)? = null
    private var diagram: Diagram? = null
    private val placed = mutableMapOf<String, RectF>()

    fun show(d: Diagram) {
        diagram = d
        contentDescription = describe(d)
        isClickable = true
        requestLayout(); invalidate()
    }

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    // scene.html's :root colours, dark and light.
    private val void get() = if (dark) 0xFF070B14.toInt() else 0xFFF8F9FF.toInt()
    private val panel get() = if (dark) 0xFF0D1424.toInt() else 0xFFE6EAF3.toInt()
    private val line get() = if (dark) 0xFF1C2842.toInt() else 0xFFC2C9D8.toInt()
    private val ink get() = if (dark) 0xFFDCE4F5.toInt() else 0xFF181C25.toInt()
    private val muted get() = if (dark) 0xFF9AA7C0.toInt() else 0xFF465268.toInt()
    private val off get() = if (dark) 0xFF3A4560.toInt() else 0xFF788396.toInt()
    private val ok get() = if (dark) 0xFF4FE0A0.toInt() else 0xFF006C46.toInt()
    private fun roleColour(r: Role) = LinkedDevicesGlyph.roleColour(dark, r)
    private val icons = mutableMapOf<String, android.graphics.drawable.Drawable?>()
    /** A network device's picture, by its kind (the Network devices screen's pictures). */
    private fun icon(kind: String) = icons.getOrPut(kind) {
        val res = when (kind) {
            "CAMERA" -> cc.bridgit.app.R.drawable.ic_camera
            "TV", "CAST" -> cc.bridgit.app.R.drawable.ic_network_tv
            "COMPUTER" -> cc.bridgit.app.R.drawable.ic_computer
            "MOBILE" -> cc.bridgit.app.R.drawable.agentact_ic_phone
            "SPEAKER", "AUDIO" -> cc.bridgit.app.R.drawable.agentact_ic_speaker
            "PRINTER" -> cc.bridgit.app.R.drawable.ic_network_printer
            "ROUTER" -> cc.bridgit.app.R.drawable.ic_network_wifi
            "SMART" -> cc.bridgit.app.R.drawable.feedback_ic_lightbulb
            else -> cc.bridgit.app.R.drawable.ic_dns
        }
        androidx.appcompat.content.res.AppCompatResources.getDrawable(context, res)?.mutate()
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * dp; strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(0.1f, 5f * dp), 0f)
    }
    private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * sp; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sp; textAlign = Paint.Align.CENTER }
    private val rowLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sp; letterSpacing = 0.12f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }

    /** A0243: each row is tall enough for a link's two arrows to run between a tile's name and the next row. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = diagram?.rows?.size ?: 2
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rows * ROW_DP * dp).toInt())
    }

    private val lanes by lazy { LinkLanes(dark, dp) }

    override fun onDraw(c: Canvas) {
        val d = diagram ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val r = 16f * dp
        fill.color = void; c.drawRoundRect(0f, 0f, w, h, r, r, fill)
        // The faint dot grid behind Home's map.
        fill.color = line
        var gy = 12f * dp
        while (gy < h) { var gx = 12f * dp; while (gx < w) { c.drawCircle(gx, gy, 0.9f * dp, fill); gx += 18f * dp }; gy += 18f * dp }

        val left = 30f * dp // room for the row names
        val rows = d.rows
        // A tile sits near the top of its row, its name below it, and the rest of the row is where the arrows run.
        val rowsY = rows.indices.map { (it * ROW_DP + TILE_Y_DP) * dp }
        val names = d.lanes.map { it.first.name }
        placed.clear()
        // Row names up the left edge, as Home's "Intelligence" and "Information".
        rows.forEachIndexed { i, row ->
            rowLabel.color = if (row.isEmpty()) off else muted
            c.save(); c.rotate(-90f, 14f * dp, rowsY[i]); c.drawText(names[i], 14f * dp, rowsY[i] + 4f * dp, rowLabel); c.restore()
        }
        // Where each node goes: spread evenly across its row.
        val slots = mutableMapOf<String, Float>()
        rows.forEachIndexed { i, row ->
            val slot = (w - left - 8f * dp) / row.size.coerceAtLeast(1)
            row.forEachIndexed { j, n ->
                val big = when (n.role) { Role.HUB -> 30f; Role.NETWORK -> 20f; else -> 23f }
                val half = min(big * dp, slot * 0.32f)
                val cx = left + slot * (j + 0.5f)
                placed[n.key] = RectF(cx - half, rowsY[i] - half, cx + half, rowsY[i] + half)
                slots[n.key] = slot
            }
        }
        val byKey = d.all.associateBy { it.key }
        // Network devices and links whose directions aren't known: one dotted elbow line, as before.
        for (e in d.edges.filter { it.flow == null }) {
            val a = placed[e.from] ?: continue; val b = placed[e.to] ?: continue
            val from = byKey[e.from] ?: continue
            val goingDown = a.centerY() < b.centerY()
            val y0 = if (goingDown) a.bottom else a.top
            val y1 = if (goingDown) b.top else b.bottom
            // Network devices join on a bus just above their row, so the line across stays clear of the satellites.
            val mid = if (from.role == Role.NETWORK) y0 - 12f * dp else (y0 + y1) / 2
            val p = Path().apply { moveTo(a.centerX(), y0); lineTo(a.centerX(), mid); lineTo(b.centerX(), mid); lineTo(b.centerX(), y1) }
            dots.color = roleColour(from.role); dots.alpha = if (from.role == Role.NETWORK) 120 else if (from.online) 220 else 110
            c.drawPath(p, dots)
        }
        drawLinks(c, d, byKey, slots)
        for (n in d.all) drawNode(c, n, placed[n.key] ?: continue, slots[n.key] ?: continue)
    }

    /**
     * A0243: every link as two arrows side by side, one per direction (who uses whom), each in its double opt-in state
     * (LinkLanes). An arrow leaves a tile from under its name or from its top edge, so names and heads never overlap;
     * the links meeting one side of a tile spread across it, ordered by where their other end is, so they don't cross.
     */
    private fun drawLinks(c: Canvas, d: Diagram, byKey: Map<String, Node>, slots: Map<String, Float>) {
        val links = d.edges.filter { it.flow != null && placed[it.from] != null && placed[it.to] != null }
        if (links.isEmpty()) return
        // Which side of each tile a link meets it on: below (its name's side) or above.
        fun below(at: String, other: String) = placed.getValue(at).centerY() < placed.getValue(other).centerY()
        val ends = mutableMapOf<Pair<String, Boolean>, MutableList<Pair<Int, String>>>()
        links.forEachIndexed { i, e ->
            ends.getOrPut(e.from to below(e.from, e.to)) { mutableListOf() } += i to e.to
            ends.getOrPut(e.to to below(e.to, e.from)) { mutableListOf() } += i to e.from
        }
        val gap = LANE_GAP_DP * dp
        fun anchor(i: Int, at: String, other: String): PointF {
            val box = placed.getValue(at); val n = byKey.getValue(at); val down = below(at, other)
            val list = ends.getValue(at to down).sortedBy { placed.getValue(it.second).centerX() }
            val k = list.size; val idx = list.indexOfFirst { it.first == i }
            val step = min(gap * 2.6f, (slots[at] ?: box.width()) * 0.8f / k)
            val x = box.centerX() + (idx - (k - 1) / 2f) * step
            val y = if (down) labelBottom(n, box) + 4f * dp
                else box.top - (if (n.role == Role.HUB) box.height() * 0.06f else 0f) - 5f * dp
            return PointF(x, y)
        }
        links.forEachIndexed { i, e ->
            val f = e.flow ?: return@forEachIndexed
            val a = anchor(i, e.from, e.to); val b = anchor(i, e.to, e.from)
            // Forward (from uses to) on the right of from→to; back (to uses from) beside it, drawn to→from.
            lanes.draw(c, LinkLanes.lane(a, b, gap / 2), f.forward, badgeAt = 0.4f)
            lanes.draw(c, LinkLanes.lane(a, b, -gap / 2).asReversed(), f.back, badgeAt = 0.4f)
        }
    }

    /** Where a tile's name block ends (label() draws it). */
    private fun labelBottom(n: Node, box: RectF) = box.bottom + (if (n.role == Role.HUB) 22f else 18f) * dp + 15f * sp + 4f * dp

    private fun drawNode(c: Canvas, n: Node, box: RectF, slot: Float) {
        val colour = if (n.thisPhone) ok else roleColour(n.role)
        val cx = box.centerX(); val cy = box.centerY(); val half = box.width() / 2
        val shape = when (n.role) {
            Role.HUB -> hexagon(cx, cy, half * 1.12f)
            Role.NETWORK -> Path().apply { addCircle(cx, cy, half, Path.Direction.CW) }
            else -> Path().apply { addRoundRect(box, 9f * dp, 9f * dp, Path.Direction.CW) }
        }
        fill.color = panel; c.drawPath(shape, fill)
        stroke.color = colour; stroke.alpha = if (n.online || n.role == Role.NETWORK) 255 else 120
        stroke.strokeWidth = (if (n.thisPhone) 3f else 2f) * dp
        c.drawPath(shape, stroke)
        if (n.thisPhone) { // a soft second ring, like the selected node on Home
            stroke.strokeWidth = 1f * dp; stroke.alpha = 90
            val ring = if (n.role == Role.HUB) hexagon(cx, cy, half * 1.12f + 5f * dp)
                else Path().apply { addRoundRect(RectF(box).apply { inset(-5f * dp, -5f * dp) }, 13f * dp, 13f * dp, Path.Direction.CW) }
            c.drawPath(ring, stroke)
        }
        if (n.role == Role.NETWORK) { drawNetwork(c, n, box, slot); return }
        drawType(c, n.type, cx, cy, half, if (n.online) 255 else 140)
        // Online dot, top right.
        fill.color = if (n.online) ok else off
        c.drawCircle(box.right - 1f * dp, box.top + 1f * dp, 4.5f * dp, fill)
        stroke.color = void; stroke.alpha = 255; stroke.strokeWidth = 2f * dp
        c.drawCircle(box.right - 1f * dp, box.top + 1f * dp, 4.5f * dp, stroke)

        // Name under the tile, then "This tablet" in green on this device, else role and type: "offline · Hub · Tablet".
        if (n.thisPhone) label(c, n, box, slot, "This " + (n.type?.noun ?: "device"))
        else {
            val role = if (n.kind == LinkedDevicesLayout.Kind.VIEWER) "View only" else roleName(n.role)
            val head = (if (n.online) "" else (n.status ?: "offline") + " · ") + role
            val full = n.type?.let { "$head · ${it.title}" } ?: head
            // A narrow slot drops the type first, whole, so the status and role stay readable (Sol 6.1).
            label(c, n, box, slot, if (small.measureText(full) <= slot * 0.94f) full else head)
        }
    }

    /**
     * What kind of device it is, in the middle of its tile (Sol 6.1 spec): one family of 1.6dp ink outlines sized as
     * width x height of the tile's half-size. Phone 0.55 x 0.95, tablet 0.78 x 0.95, computer (laptop) and TV
     * 1.15 x 0.85, watch 0.65 x 1.05, camera 1.05 x 0.75; a device that didn't say gets a plain 0.75 x 0.85 outline.
     */
    private fun drawType(c: Canvas, type: DeviceType?, cx: Float, cy: Float, half: Float, alpha: Int) {
        val join = stroke.strokeJoin; val cap = stroke.strokeCap
        stroke.strokeWidth = 1.6f * dp; stroke.color = ink; stroke.alpha = alpha
        stroke.strokeJoin = Paint.Join.ROUND; stroke.strokeCap = Paint.Cap.ROUND
        fill.color = ink; fill.alpha = alpha
        val r = 3f * dp
        fun rect(w: Float, h: Float, y: Float = cy, rr: Float = r) = c.drawRoundRect(cx - w / 2, y - h / 2, cx + w / 2, y + h / 2, rr, rr, stroke)
        fun speaker(w: Float, top: Float) = c.drawRoundRect(cx - w / 2, top + 2.5f * dp, cx + w / 2, top + 4.3f * dp, 1f * dp, 1f * dp, fill)
        when (type) {
            DeviceType.PHONE -> { val w = half * 0.55f; val h = half * 0.95f; rect(w, h); speaker(w * 0.44f, cy - h / 2) }
            DeviceType.TABLET -> { val w = half * 0.78f; val h = half * 0.95f; rect(w, h); speaker(w * 0.22f, cy - h / 2) }
            DeviceType.COMPUTER -> {
                val w = half * 1.15f; val h = half * 0.85f; val top = cy - h / 2
                val sw = w * 0.82f; val sh = h * 0.78f
                c.drawRoundRect(cx - sw / 2, top, cx + sw / 2, top + sh, r, r, stroke)
                c.drawLine(cx - w / 2, cy + h / 2, cx + w / 2, cy + h / 2, stroke)
            }
            DeviceType.TV -> {
                val w = half * 1.15f; val h = half * 0.85f; val top = cy - h / 2; val sh = h * 0.74f
                c.drawRoundRect(cx - w / 2, top, cx + w / 2, top + sh, r, r, stroke)
                c.drawLine(cx, top + sh, cx, cy + h / 2, stroke)
                c.drawLine(cx - w * 0.18f, cy + h / 2, cx + w * 0.18f, cy + h / 2, stroke)
            }
            DeviceType.WATCH -> {
                val face = half * 0.65f; val h = half * 1.05f; val strap = face * 0.5f
                c.drawLine(cx - strap / 2, cy - h / 2, cx + strap / 2, cy - h / 2, stroke)
                c.drawLine(cx - strap / 2, cy - h / 2, cx - strap / 2, cy - face / 2, stroke)
                c.drawLine(cx + strap / 2, cy - h / 2, cx + strap / 2, cy - face / 2, stroke)
                c.drawLine(cx - strap / 2, cy + h / 2, cx + strap / 2, cy + h / 2, stroke)
                c.drawLine(cx - strap / 2, cy + h / 2, cx - strap / 2, cy + face / 2, stroke)
                c.drawLine(cx + strap / 2, cy + h / 2, cx + strap / 2, cy + face / 2, stroke)
                rect(face, face, rr = face * 0.3f)
            }
            DeviceType.CAMERA -> {
                val w = half * 1.05f; val h = half * 0.75f; val bump = h * 0.2f
                val bodyTop = cy - h / 2 + bump
                c.drawRoundRect(cx - w / 2, bodyTop, cx + w / 2, cy + h / 2, r, r, stroke)
                c.drawRoundRect(cx - w * 0.16f, cy - h / 2, cx + w * 0.16f, bodyTop + 1f * dp, 1.5f * dp, 1.5f * dp, stroke)
                c.drawCircle(cx, (bodyTop + cy + h / 2) / 2, (cy + h / 2 - bodyTop) * 0.3f, stroke)
            }
            null -> rect(half * 0.75f, half * 0.85f)
        }
        fill.alpha = 255; stroke.alpha = 255; stroke.strokeJoin = join; stroke.strokeCap = cap
    }

    /** A device on this phone's network: a circle with its picture, or "…" for the "+N more" tile. */
    private fun drawNetwork(c: Canvas, n: Node, box: RectF, slot: Float) {
        val cx = box.centerX(); val cy = box.centerY(); val half = box.width() / 2
        val a = 255
        if (n.added) { // a soft second ring: added to Bridgit
            stroke.color = roleColour(Role.NETWORK); stroke.alpha = 110; stroke.strokeWidth = 1f * dp
            c.drawCircle(cx, cy, half + 4.5f * dp, stroke)
        }
        if (n.kind == LinkedDevicesLayout.Kind.NETWORK_MORE) {
            fill.color = ink; fill.alpha = 200
            for (k in -1..1) c.drawCircle(cx + k * half * 0.38f, cy, 2.2f * dp, fill)
            fill.alpha = 255
        } else icon(n.icon)?.let { ic ->
            val s = (half * 0.62f).toInt()
            ic.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            ic.setTint(ink); ic.alpha = a; ic.draw(c)
        }
        if (n.kind == LinkedDevicesLayout.Kind.NETWORK && n.online) {
            // A green dot while the last scan found it in the last two minutes; no dot otherwise (nothing is scanned
            // from here, so an older find is not "offline"). A ring marks one added to Bridgit.
            fill.color = ok
            c.drawCircle(box.right - 3f * dp, box.top + 3f * dp, 4f * dp, fill)
            stroke.color = void; stroke.alpha = 255; stroke.strokeWidth = 2f * dp
            c.drawCircle(box.right - 3f * dp, box.top + 3f * dp, 4f * dp, stroke)
        }
        label(c, n, box, slot, if (n.added) "Added · ${n.label}" else n.label)
    }

    private fun label(c: Canvas, n: Node, box: RectF, slot: Float, subtitle: String) {
        val cx = box.centerX()
        val maxW = slot * 0.94f
        val top = box.bottom + (if (n.role == Role.HUB) 22f else 18f) * dp
        val title = TextUtils.ellipsize(n.name, name, maxW, TextUtils.TruncateAt.END).toString()
        val role = TextUtils.ellipsize(subtitle, small, maxW, TextUtils.TruncateAt.END).toString()
        // The label sits on the field's colour, so a line passing behind it stops at its edge (as on Home).
        val half2 = maxOf(name.measureText(title), small.measureText(role)) / 2 + 4f * dp
        fill.color = void
        c.drawRoundRect(cx - half2, top - name.textSize, cx + half2, top + 15f * sp + 4f * dp, 6f * dp, 6f * dp, fill)
        name.color = ink
        c.drawText(title, cx, top, name)
        small.color = if (n.thisPhone) ok else muted
        c.drawText(role, cx, top + 15f * sp, small)
    }

    private fun roleName(r: Role) = when (r) { Role.HUB -> "Hub"; Role.PORTAL -> "Portal"; Role.SATELLITE -> "Satellite"; Role.NETWORK -> "Network" }

    private fun hexagon(cx: Float, cy: Float, r: Float) = LinkedDevicesGlyph.hexagon(cx, cy, r)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action != MotionEvent.ACTION_UP) return super.onTouchEvent(e)
        val d = diagram ?: return false
        val slop = 14f * dp
        // Of the tiles whose tap area holds the touch, the one whose centre is nearest (areas overlap on a full row).
        val hit = d.all.filter { n -> placed[n.key]?.let { RectF(it).apply { inset(-slop, -slop); bottom += 30f * dp }.contains(e.x, e.y) } == true }
            .minByOrNull { n -> placed.getValue(n.key).let { (it.centerX() - e.x) * (it.centerX() - e.x) + (it.centerY() - e.y) * (it.centerY() - e.y) } }
        if (hit != null) { touchHit = true; playSoundEffect(android.view.SoundEffectConstants.CLICK); onNode?.invoke(hit); performClick(); touchHit = false }
        return true
    }

    /** A click with no touch on a tile (TalkBack's double tap): [onPickFromList] offers the devices as a list. */
    var onPickFromList: (() -> Unit)? = null
    private var touchHit = false

    override fun performClick(): Boolean {
        val handled = super.performClick()
        if (!touchHit) onPickFromList?.invoke()
        return handled
    }

    companion object {
        /** One row of the map: its tiles, their names and the room for the arrows down to the next row. */
        const val ROW_DP = 156f
        /** Where a row's tiles sit, from the row's top. */
        const val TILE_Y_DP = 46f
        /** Between a link's two arrows. */
        const val LANE_GAP_DP = 10f
    }

    /** Said by TalkBack: the map in words. */
    private fun describe(d: Diagram): String {
        // "this tablet, A11 Blue Tab"; another device: "Office, tablet, offline" (its type only when it said one).
        fun list(ns: List<Node>) = ns.joinToString(", ") { n ->
            if (n.thisPhone) "this ${n.type?.noun ?: "device"}, ${n.name}"
            else listOfNotNull(n.name, n.type?.noun, if (n.online) null else n.status ?: "offline").joinToString(", ")
        }
        return buildList {
            add("Map of linked devices.")
            if (d.hubs.isNotEmpty()) add("Hubs: ${list(d.hubs)}.")
            if (d.portals.isNotEmpty()) add("Portals viewing a hub: ${list(d.portals)}.")
            if (d.satellites.isNotEmpty()) add("Satellites of a hub: ${list(d.satellites)}.")
            // A0243: each link's two directions, e.g. "Office uses this tablet: on. This tablet uses Office: waiting for Office."
            val byKey = d.all.associateBy { it.key }
            fun who(n: Node) = if (n.thisPhone) "this ${n.type?.noun ?: "device"}" else n.name
            fun said(user: Node, target: Node, s: cc.bridgit.app.link.LinkModel.State) = "${who(user).replaceFirstChar { it.uppercase() }} uses ${who(target)}: " + when (s) {
                cc.bridgit.app.link.LinkModel.State.ON -> "on"
                cc.bridgit.app.link.LinkModel.State.PAUSED -> "paused"
                cc.bridgit.app.link.LinkModel.State.WAITING_FOR_TARGET -> "waiting for ${who(target)}"
                cc.bridgit.app.link.LinkModel.State.WAITING_FOR_USER -> "waiting for ${who(user)}"
                cc.bridgit.app.link.LinkModel.State.OFF -> "off"
            } + "."
            for (e in d.edges) { val f = e.flow ?: continue; val a = byKey[e.from] ?: continue; val b = byKey[e.to] ?: continue
                add(said(a, b, f.forward)); add(said(b, a, f.back)) }
            if (d.network.isNotEmpty()) add("On this ${d.all.firstOrNull { it.thisPhone }?.type?.noun ?: "device"}'s network: ${d.network.joinToString(", ") { if (it.label.isEmpty()) it.name else it.name + ", " + it.label }}.")
            if (d.alone) add("Nothing is linked yet.")
        }.joinToString(" ")
    }
}
