package cc.bridgit.app.dog

import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.vision.ChangeGate
import cc.bridgit.app.vision.ChangeSettings
import cc.bridgit.app.vision.FrameDiff
import kotlinx.coroutines.CancellationException

/** Applet-owned choices; the judgment service itself has no exclusive model selection. */
object DogModels {
    fun restore(saved: Set<String>?, legacy: String?): List<String> =
        if (saved != null) DogJev.MODELS.filter { it in saved }
        else listOf(legacy?.takeIf { it in DogJev.MODELS } ?: "clef")

    fun toggle(models: List<String>, model: String, enabled: Boolean): List<String> {
        require(model in DogJev.MODELS)
        return DogJev.MODELS.filter { if (it == model) enabled else it in models }
    }
}

/** Checks one photo with each selected model, caching independently per camera and model. */
class DogChecks(private val settings: ChangeSettings) {
    data class Result(val readings: List<Reading>, val failures: Map<String, Exception>, val missed: Int) {
        // Clef first when selected and successful; exactly one reading feeds the activity totals.
        val primary: Reading? get() = readings.firstOrNull()
        val reused: Boolean get() = readings.isNotEmpty() && failures.isEmpty() && readings.all { it.reused }
        /** A photo is free only when every selected model reused its answer. */
        val tracked: Reading? get() = primary?.copy(reused = reused, unchangedSince = if (failures.isEmpty()) readings.mapNotNull { it.unchangedSince }.maxOrNull() else null)
        val terminal: Boolean get() = readings.isEmpty() && failures.isNotEmpty() &&
            failures.values.all { it is Jev.Failure && !it.retryable }
    }

    companion object {
        /** Camera scans: a full scan asks every model fresh; a rescan lets the change gate reuse unchanged pictures. */
        fun scanForce(full: Boolean): Boolean = full
    }

    private val gates = HashMap<Pair<String, String>, ChangeGate<Reading>>()
    /** When each camera's current run of too-dark photos began, so the gallery groups a dark night as one moment. */
    private val darkSince = HashMap<String, Long>()
    fun clear() { gates.clear(); last.clear(); darkSince.clear() }

    suspend fun check(camera: String, grid: IntArray?, at: Long, models: List<String>, force: Boolean = false,
                      verifySelection: () -> Unit = {}, ask: suspend (String) -> Reading): Result {
        verifySelection()
        val readings = ArrayList<Reading>()
        val failures = LinkedHashMap<String, Exception>()
        var missed = 0
        val selected = DogJev.MODELS.filter { it in models }
        if (grid != null && FrameDiff.dark(grid) && selected.isNotEmpty()) {
            // Nothing is visible: no model is asked (it would only guess), and no earlier answer is carried on.
            // Like a failure, a dark stretch breaks continuity, so the first lit photo after it is asked fresh.
            for (model in selected) gates.remove(camera to model)
            val since = darkSince.getOrPut(camera) { at }
            return Result(listOf(Reading(at, false, 0.0, null, 0.0, null, 0.0, selected.first(), camera = camera,
                unchangedSince = since, dark = true)), failures, 0)
        }
        darkSince.remove(camera)
        for (model in selected) {
            verifySelection()
            val gate = gates.getOrPut(camera to model) { ChangeGate(settings) }
            val cached = gate.reusable(grid, at, model)
            if (!force && cached != null) {
                readings.add(cached.copy(at = at, reused = true))
                continue
            }
            try {
                val reading = ask(model).copy(unchangedSince = at)
                val before = last[camera to model]
                val timed = !force && (gate.changeShare(grid, at) ?: 1.0) < settings.minPatch
                if (DogJev.missedChange(timed, before, reading)) missed++
                gate.answered(grid, at, model, reading, DogJev::sameAnswer)
                last[camera to model] = reading
                readings.add(reading)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A failed check cannot certify continuity through this frame.
                gates.remove(camera to model)
                failures[model] = e
            }
        }
        verifySelection()
        return Result(readings, failures, missed)
    }

    private val last = HashMap<Pair<String, String>, Reading>()
}
