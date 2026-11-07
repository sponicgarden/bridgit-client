package cc.bridgit.app.vision

import kotlin.math.abs
import kotlin.math.hypot

/** A mask (one Boolean per pixel, row by row) to an outline polygon. Pure, unit-tested. */
object Outlines {
    private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1) // E, SE, S, SW, W, NW, N, NE (clockwise, y down)
    private val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    /** Only the largest 8-connected blob of [mask]. */
    fun largest(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val label = IntArray(w * h); var next = 0; var best = 0; var bestSize = 0
        val stack = IntArray(w * h)
        for (i in 0 until w * h) if (mask[i] && label[i] == 0) {
            next++; var size = 0; var sp = 0; stack[sp++] = i; label[i] = next
            while (sp > 0) {
                val p = stack[--sp]; size++
                val x = p % w; val y = p / w
                for (d in 0 until 8) {
                    val nx = x + DX[d]; val ny = y + DY[d]
                    if (nx in 0 until w && ny in 0 until h) { val q = ny * w + nx; if (mask[q] && label[q] == 0) { label[q] = next; stack[sp++] = q } }
                }
            }
            if (size > bestSize) { bestSize = size; best = next }
        }
        return BooleanArray(w * h) { best != 0 && label[it] == best }
    }

    /** The outer boundary of the blob in [mask] (Moore-neighbour tracing), as pixel corners in order. */
    fun trace(mask: BooleanArray, w: Int, h: Int): List<IntArray> {
        val start = (0 until w * h).firstOrNull { mask[it] } ?: return emptyList()
        val sx = start % w; val sy = start / w
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && mask[y * w + x]
        val pts = ArrayList<IntArray>()
        var x = sx; var y = sy; var search = 6; var firstDir = -1
        while (true) {
            var d = -1
            for (k in 0 until 8) { val c = (search + k) % 8; if (inside(x + DX[c], y + DY[c])) { d = c; break } }
            if (d < 0) return listOf(intArrayOf(sx, sy))
            if (x == sx && y == sy) { if (firstDir < 0) firstDir = d else if (d == firstDir) break }
            pts += intArrayOf(x, y)
            x += DX[d]; y += DY[d]; search = (d + 6) % 8
            if (pts.size > 4 * w * h) break
        }
        return pts
    }

    /** Douglas-Peucker on an open polyline. */
    fun simplify(pts: List<IntArray>, eps: Double): List<IntArray> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var far = -1; var farD = eps
            val ax = pts[a][0].toDouble(); val ay = pts[a][1].toDouble(); val bx = pts[b][0].toDouble(); val by = pts[b][1].toDouble()
            val len = hypot(bx - ax, by - ay)
            for (i in a + 1 until b) {
                val px = pts[i][0].toDouble(); val py = pts[i][1].toDouble()
                val dist = if (len == 0.0) hypot(px - ax, py - ay) else abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / len
                if (dist > farD) { farD = dist; far = i }
            }
            if (far >= 0) { keep[far] = true; stack.addLast(a to far); stack.addLast(far to b) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * The outline of [mask]'s largest blob as at most [maxPoints] points, each [x, y] a fraction (0-1) of the
     * mask's width and height (pixel centres). Empty when the mask is empty.
     */
    fun polygon(mask: BooleanArray, w: Int, h: Int, maxPoints: Int = 64): List<DoubleArray> {
        val ring = trace(largest(mask, w, h), w, h)
        if (ring.isEmpty()) return emptyList()
        // Close the ring at its farthest point from the start, so the simplification keeps both ends.
        val far = ring.indices.maxByOrNull { hypot((ring[it][0] - ring[0][0]).toDouble(), (ring[it][1] - ring[0][1]).toDouble()) } ?: 0
        var eps = 0.75
        var out: List<IntArray>
        while (true) {
            val one = simplify(ring.subList(0, far + 1), eps)
            val two = simplify(ring.subList(far, ring.size) + listOf(ring[0]), eps)
            out = one + two.subList(1, two.size - 1)
            if (out.size <= maxPoints || eps > maxOf(w, h).toDouble()) break
            eps *= 1.5
        }
        return out.map { doubleArrayOf((it[0] + 0.5) / w, (it[1] + 0.5) / h) }
    }
}
