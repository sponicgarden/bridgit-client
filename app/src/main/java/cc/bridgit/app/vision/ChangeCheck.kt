package cc.bridgit.app.vision

import org.json.JSONObject

/**
 * The free change check on the phone (owner, 2 Nov 2026), shared by the Pet monitor and camera watches.
 * Before a photo goes to a paid model (Clef or Jev), it becomes a tiny grayscale grid and is compared with
 * the last photo a model was asked about. If nothing solid changed, that answer is reused and nothing is paid.
 * Pure (no Android), unit-tested; [Grids] turns JPEG bytes into a grid.
 */

/**
 * How picky the change check is. A cell "changed" when its brightness moved more than [cellChange] (out of
 * 255); the picture changed when one connected patch of changed cells covers at least [minPatch] of it. An
 * unchanged picture is still asked about again after [maxReuseMs]. [learnNoise]: see [NoiseMask].
 */
data class ChangeSettings(
    val enabled: Boolean = true,
    val sensitivity: String = DEFAULT_SENSITIVITY,
    val learnNoise: Boolean = true,
    val maxReuseMs: Long = FrameDiff.MAX_REUSE_MS,
) {
    val cellChange: Int get() = PRESETS.getValue(sensitivity).first
    val minPatch: Double get() = PRESETS.getValue(sensitivity).second

    fun json(): JSONObject = JSONObject().put("change_check", enabled).put("sensitivity", sensitivity)
        .put("learn_noise", learnNoise).put("max_reuse_s", maxReuseMs / 1000)

    companion object {
        const val DEFAULT_SENSITIVITY = "normal"
        /** Sensitivity presets: cell change (of 255) and smallest patch (share of the picture). */
        val PRESETS: Map<String, Pair<Int, Double>> = linkedMapOf(
            "low" to (35 to 0.05),
            "normal" to (FrameDiff.CELL_CHANGE to FrameDiff.MIN_PATCH),
            "high" to (15 to 0.01),
        )
        const val MIN_REUSE_S = 30
        const val MAX_REUSE_S = 3600

        /** The presets and limits, for /v1/capabilities. */
        fun describe(): JSONObject = JSONObject()
            .put("sensitivity", JSONObject().apply {
                for ((k, v) in PRESETS) put(k, JSONObject().put("cell_change_of_255", v.first).put("min_patch_share", v.second))
            })
            .put("default", ChangeSettings().json())
            .put("max_reuse_s_range", org.json.JSONArray().put(MIN_REUSE_S).put(MAX_REUSE_S))
            .put("grid", "${FrameDiff.SIZE}x${FrameDiff.SIZE} grayscale, average brightness taken out")
            .put("noise_mask", JSONObject().put("mute_minutes", NoiseMask.TTL_MS / 60_000).put("never_learns_over_share", NoiseMask.MAX_LEARN))
    }
}

/**
 * Compares two photo grids ([SIZE] x [SIZE] brightness values). Only a solid patch of change counts: leaves
 * or a curtain flickering in scattered spots, sensor noise, or the camera adjusting its exposure (each grid's
 * average brightness is taken out first) don't, while a dog walking in or a person at the door does.
 */
object FrameDiff {
    const val SIZE = 32
    /** A cell changed when its brightness moved more than this (out of 255, about 10%). */
    const val CELL_CHANGE = 25
    /** The picture changed when one connected patch of changed cells covers this much of it (2.5%, ~26 of 1,024 cells). */
    const val MIN_PATCH = 0.025
    const val MAX_REUSE_MS = 5 * 60_000L
    /** Too dark to see anything: the average grid brightness is below this (of 255)... */
    const val DARK_MEAN = 20
    /** ...and no cell is brighter than this, so no lamp or lit window shows a scene. */
    const val DARK_PEAK = 40

    /**
     * A near-black photo (lights off, camera covered): nothing can be seen, so no answer about it means anything.
     * Average brightness is taken out before comparing, so two black photos always look "unchanged".
     */
    fun dark(grid: IntArray): Boolean =
        grid.isNotEmpty() && grid.average() < DARK_MEAN && grid.max() < DARK_PEAK

    /** Average difference, 0 (same) to 1, with each grid's average brightness taken out. */
    fun difference(a: IntArray, b: IntArray): Double {
        require(a.size == b.size && a.isNotEmpty()) { "grids must be the same size" }
        val ma = a.average(); val mb = b.average()
        var sum = 0.0
        for (i in a.indices) sum += kotlin.math.abs((a[i] - ma) - (b[i] - mb))
        return sum / (a.size * 255.0)
    }

    /** Which cells changed (brightness moved more than [cellChange], average brightness taken out). */
    fun changedCells(a: IntArray, b: IntArray, cellChange: Int = CELL_CHANGE): BooleanArray {
        require(a.size == b.size && a.isNotEmpty()) { "grids must be the same size" }
        val ma = a.average(); val mb = b.average()
        return BooleanArray(a.size) { kotlin.math.abs((a[it] - ma) - (b[it] - mb)) > cellChange }
    }

    /** The biggest connected patch of changed cells between two grids, as a share of the picture. */
    fun largestPatch(a: IntArray, b: IntArray, size: Int = SIZE, muted: BooleanArray? = null, cellChange: Int = CELL_CHANGE): Double =
        largestPatch(changedCells(a, b, cellChange), size, muted)

    /** The biggest connected patch (side by side, not diagonal) of [changedAll] cells, leaving out [muted] ones. */
    fun largestPatch(changedAll: BooleanArray, size: Int = SIZE, muted: BooleanArray? = null): Double {
        require(changedAll.size == size * size) { "grid must be $size x $size" }
        val changed = if (muted == null) changedAll else BooleanArray(changedAll.size) { changedAll[it] && !muted[it] }
        val seen = BooleanArray(changed.size)
        val stack = IntArray(changed.size)
        var best = 0
        for (start in changed.indices) {
            if (!changed[start] || seen[start]) continue
            var top = 0; var n = 0
            stack[top++] = start; seen[start] = true
            while (top > 0) {
                val c = stack[--top]; n++
                val x = c % size; val y = c / size
                fun visit(q: Int) { if (changed[q] && !seen[q]) { seen[q] = true; stack[top++] = q } }
                if (x > 0) visit(c - 1); if (x < size - 1) visit(c + 1)
                if (y > 0) visit(c - size); if (y < size - 1) visit(c + size)
            }
            if (n > best) best = n
        }
        return best.toDouble() / changed.size
    }

    /** Reuse the last answer? [sinceAsked]: time since that photo was asked about; [muted]: cells [NoiseMask] ignores. */
    fun reuse(grid: IntArray, lastGrid: IntArray?, sinceAsked: Long, muted: BooleanArray? = null,
              settings: ChangeSettings = ChangeSettings()): Boolean =
        settings.enabled && lastGrid != null && lastGrid.size == grid.size && sinceAsked in 0 until settings.maxReuseMs &&
            largestPatch(grid, lastGrid, muted = muted, cellChange = settings.cellChange) < settings.minPatch
}

/**
 * Learns where the picture changes without anything happening: when a change cost a paid call but the answer
 * came back the same, the cells that changed are ignored for [TTL_MS], so a swaying tree or a TV costs one
 * extra call, not one every photo. A change over more than [MAX_LEARN] of the picture is never learned
 * (lights, a moved phone: a new scene, not noise).
 */
class NoiseMask(size: Int = FrameDiff.SIZE) {
    companion object {
        const val TTL_MS = 10 * 60_000L
        const val MAX_LEARN = 0.3
    }

    private val mutedUntil = LongArray(size * size)

    /** A paid call for [changed] cells gave the same answer as before: ignore them for a while. Returns true if learned. */
    @Synchronized fun learn(changed: BooleanArray, now: Long): Boolean {
        require(changed.size == mutedUntil.size)
        if (changed.count { it }.toDouble() / changed.size > MAX_LEARN) return false
        for (i in changed.indices) if (changed[i]) mutedUntil[i] = now + TTL_MS
        return true
    }

    @Synchronized fun muted(now: Long): BooleanArray = BooleanArray(mutedUntil.size) { mutedUntil[it] > now }

    @Synchronized fun clear() = mutedUntil.fill(0)
}

/**
 * One loop's change check: remembers the last photo a model was asked about (its grid, when, which model
 * [key], and the answer [T]) and decides for each new photo whether that answer can be reused for free.
 * After a paid answer, [answered] records it and, when it matched the previous answer, teaches the
 * [NoiseMask] to ignore the cells that changed (if [ChangeSettings.learnNoise]).
 */
class ChangeGate<T>(@Volatile var settings: ChangeSettings = ChangeSettings()) {
    class Ref<T>(val grid: IntArray, val at: Long, val key: String, val answer: T)

    private var last: Ref<T>? = null
    private val noise = NoiseMask()

    /** The answer to reuse for [grid] taken at [now], or null when a model has to be asked. */
    @Synchronized fun reusable(grid: IntArray?, now: Long, key: String): T? {
        val l = last ?: return null
        if (grid == null || l.key != key) return null
        return if (FrameDiff.reuse(grid, l.grid, now - l.at, if (settings.learnNoise) noise.muted(now) else null, settings)) l.answer else null
    }

    /** The share of the picture in the largest changed patch since the last photo asked about, or null. */
    @Synchronized fun changeShare(grid: IntArray?, now: Long): Double? {
        val l = last ?: return null
        if (grid == null || grid.size != l.grid.size) return null
        return FrameDiff.largestPatch(grid, l.grid, muted = if (settings.learnNoise) noise.muted(now) else null, cellChange = settings.cellChange)
    }

    /** A model answered [answer] about [grid]; [same] says whether two answers mean the same. Returns true if noise was learned. */
    @Synchronized fun answered(grid: IntArray?, now: Long, key: String, answer: T, same: (T, T) -> Boolean): Boolean {
        val l = last
        var learned = false
        if (settings.learnNoise && grid != null && l != null && l.key == key && grid.size == l.grid.size &&
            now - l.at < settings.maxReuseMs && same(l.answer, answer))
            learned = noise.learn(FrameDiff.changedCells(grid, l.grid, settings.cellChange), now)
        if (grid != null) last = Ref(grid, now, key, answer)
        return learned
    }

    @Synchronized fun clear() { last = null; noise.clear() }
}
