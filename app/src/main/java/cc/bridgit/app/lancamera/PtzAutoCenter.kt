package cc.bridgit.app.lancamera

import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pet monitor auto-centring (owner, 7 Nov 2026): after a monitor photo in which Jev sees the pet well off the
 * middle of a pan/tilt camera's frame, steer toward it and take the next photo at once, step after step, until it
 * sits inside the dead zone. Then the monitor goes back to its normal interval. Fixed cameras never get here.
 *
 * Steps use [PtzCentering]'s measured Tapo geometry, damped by [DAMPING] so a step covers about three quarters of
 * the offset and never overshoots on its own; an axis whose step made things worse flips direction (upside-down
 * mounts). A run stops when the pet is centred, lost, the camera is at its limit, [MAX_STEPS] corrections were
 * taken, or the hourly allowance of extra photos ([HOURLY_STEPS]) or the applet budget runs out.
 */
object AutoCenter {
    /** Within 15% of the middle on both axes counts as centred: no move, so the camera never jitters. */
    const val DEAD_ZONE = .15
    const val DAMPING = .75
    const val MAX_STEPS = 8
    /** Each correction is one extra paid Jev photo: at most this many in any rolling hour, all cameras together. */
    const val HOURLY_STEPS = 40
    /** After a run that didn't end centred, wait before trying again on that camera (a lost pet: a short wait). */
    const val COOLDOWN_MS = 10 * 60_000L
    const val LOST_COOLDOWN_MS = 60_000L
    const val PET_MIN = PtzCenterSearch.PET_MIN
    val RESULTS = setOf("better", "worse", "centered", "lost", "failed", "budget")
    val OUTCOMES = setOf("centered", "lost", "limit", "cap", "budget", "failed")

    /** The pet is seen and sits outside the dead zone. */
    fun needs(dogP: Double, box: PetBox?): Boolean =
        dogP >= PET_MIN && box != null && (abs(box.cx - .5) > DEAD_ZONE || abs(box.cy - .5) > DEAD_ZONE)

    fun cooldownMs(outcome: String): Long = when (outcome) { "centered" -> 0L; "lost" -> LOST_COOLDOWN_MS; else -> COOLDOWN_MS }
}

/** At most [perHour] correction photos in any rolling hour. */
class StepBudget(private val perHour: Int = AutoCenter.HOURLY_STEPS) {
    private val times = ArrayDeque<Long>()
    private fun prune(now: Long) { while (times.isNotEmpty() && now - times.first() >= 3_600_000L) times.removeFirst() }
    @Synchronized fun take(now: Long): Boolean { prune(now); if (times.size >= perHour) return false; times.addLast(now); return true }
    @Synchronized fun left(now: Long): Int { prune(now); return perHour - times.size }
}

/** When a camera may start its next run. */
class AutoCenterGate {
    private val until = HashMap<String, Long>()
    @Synchronized fun mayStart(camera: String, now: Long) = now >= (until[camera] ?: 0L)
    @Synchronized fun finished(camera: String, outcome: String, now: Long) {
        val wait = AutoCenter.cooldownMs(outcome)
        if (wait == 0L) until.remove(camera) else until[camera] = now + wait
    }
    @Synchronized fun clear() = until.clear()
}

class AutoCenterRun(private val space: PtzSpace, private val driver: Driver, private val budget: StepBudget, private val clock: () -> Long = System::currentTimeMillis) {
    data class Shot(val dogP: Double, val box: PetBox?)
    /** One correction: where the pet was (offset from the middle, -0.5..0.5, + = right/below), the pan/tilt step taken, what the next photo showed. */
    data class Step(val at: Long, val camera: String, val step: Int, val offsetX: Double, val offsetY: Double, val pan: Double, val tilt: Double,
                    val afterX: Double?, val afterY: Double?, val result: String) {
        fun json(): org.json.JSONObject = org.json.JSONObject().put("at", at).put("camera", camera).put("step", step)
            .put("offset_x", r(offsetX)).put("offset_y", r(offsetY)).put("pan", r(pan)).put("tilt", r(tilt))
            .put("after_x", afterX?.let(::r) ?: org.json.JSONObject.NULL).put("after_y", afterY?.let(::r) ?: org.json.JSONObject.NULL).put("result", result)
        fun line() = "step $step: pet at (${pct(offsetX)}, ${pct(offsetY)}) from the middle, pan ${"%+.3f".format(pan)} tilt ${"%+.3f".format(tilt)} → " +
            (if (afterX != null && afterY != null) "(${pct(afterX)}, ${pct(afterY)}) " else "") + result
        private fun r(v: Double) = (v * 1000).roundToInt() / 1000.0
        private fun pct(v: Double) = "%+d%%".format((v * 100).roundToInt())
    }
    data class Outcome(val result: String, val steps: Int, val limited: Boolean)
    /** The applet budget or a spend limit stopped the photo's analysis. */
    class OutOfBudget : Exception()
    interface Driver {
        val camera: String
        suspend fun authorize()
        suspend fun position(): PtzPoint
        suspend fun move(point: PtzPoint)
        suspend fun settle(ms: Long)
        /** A fresh photo and Jev's answer with the pet's box; null when the photo or answer failed. Throws [OutOfBudget]. */
        suspend fun shoot(): Shot?
        suspend fun logged(step: Step)
    }

    private suspend fun go(point: PtzPoint) {
        driver.authorize(); driver.move(point)
        repeat(80) {
            driver.authorize()
            if (space.near(driver.position(), point)) { driver.settle(500); driver.authorize(); if (space.near(driver.position(), point)) return }
            driver.settle(250)
        }
        throw PtzSearch.PositionUnavailable()
    }

    /** [first] is the monitor photo that found the pet off-centre. */
    suspend fun run(first: Shot): Outcome {
        val logic = PtzCentering(space, AutoCenter.DAMPING, AutoCenter.DEAD_ZONE)
        driver.authorize()
        var at = driver.position()
        if (!space.contains(at)) return Outcome("failed", 0, false)
        var shot = first; var steps = 0
        while (true) {
            val box = shot.box ?: return Outcome("lost", steps, logic.limited)
            if (shot.dogP < AutoCenter.PET_MIN) return Outcome("lost", steps, logic.limited)
            if (logic.centered(box)) return Outcome("centered", steps, logic.limited)
            if (steps >= AutoCenter.MAX_STEPS) return Outcome("cap", steps, logic.limited)
            val next = logic.next(at, box) ?: return Outcome("limit", steps, true)
            if (!budget.take(clock())) return Outcome("budget", steps, logic.limited)
            steps++
            val ex = box.cx - .5; val ey = box.cy - .5
            fun step(after: Shot?, result: String) = Step(clock(), driver.camera, steps, ex, ey, next.x - at.x, next.y - at.y,
                after?.box?.let { it.cx - .5 }, after?.box?.let { it.cy - .5 }, result)
            try { go(next) } catch (e: CancellationException) { throw e } catch (_: Exception) {
                driver.logged(step(null, "failed")); return Outcome("failed", steps, logic.limited)
            }
            val again = try { driver.shoot() } catch (e: CancellationException) { throw e } catch (_: OutOfBudget) {
                driver.logged(step(null, "budget")); return Outcome("budget", steps, logic.limited)
            } catch (_: Exception) { null }
            val result = when {
                again == null -> "failed"
                again.dogP < AutoCenter.PET_MIN || again.box == null -> "lost"
                logic.centered(again.box) -> "centered"
                hypot(again.box.cx - .5, again.box.cy - .5) < hypot(ex, ey) -> "better"
                else -> "worse"
            }
            driver.logged(step(again, result))
            at = next
            when (result) { "failed" -> return Outcome("failed", steps, logic.limited); "lost" -> return Outcome("lost", steps, logic.limited) }
            shot = again!!
        }
    }
}
