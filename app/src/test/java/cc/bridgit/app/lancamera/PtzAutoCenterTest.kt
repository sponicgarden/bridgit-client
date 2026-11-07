package cc.bridgit.app.lancamera

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PtzAutoCenterTest {
    private val space = PtzSpace(-1.0, 1.0, -1.0, 1.0)
    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9

    /** A Tapo camera with a pet at a fixed pan/tilt; [moving] shifts the pet after every photo. */
    private class Camera(var pet: PtzPoint?, start: PtzPoint = PtzPoint(0.0, 0.0), val flipPan: Boolean = false) : AutoCenterRun.Driver {
        override val camera = "lan:test"
        var current = start; var shots = 0; val log = mutableListOf<AutoCenterRun.Step>(); var moves = 0
        var failShot = false; var outOfBudget = false; var moving: PtzPoint? = null
        override suspend fun authorize() {}
        override suspend fun position() = current
        override suspend fun move(point: PtzPoint) { current = point; moves++ }
        override suspend fun settle(ms: Long) {}
        fun view(): AutoCenterRun.Shot {
            val p = pet ?: return AutoCenterRun.Shot(.02, null)
            // +pan shifts the picture right (unless the camera hangs upside down), +tilt shifts it down.
            val sx = if (flipPan) -1 else 1
            val cx = .5 + sx * (current.x - p.x) / TapoPtz.FRAME_PAN; val cy = .5 + (current.y - p.y) / TapoPtz.FRAME_TILT
            if (cx !in 0.05..0.95 || cy !in 0.05..0.95) return AutoCenterRun.Shot(.03, null)
            return AutoCenterRun.Shot(.95, PetBox(cx - .05, cy - .05, cx + .05, cy + .05, .9))
        }
        override suspend fun shoot(): AutoCenterRun.Shot? {
            shots++
            if (outOfBudget) throw AutoCenterRun.OutOfBudget()
            if (failShot) return null
            moving?.let { m -> pet = pet?.let { PtzPoint(it.x + m.x, it.y + m.y) } }
            return view()
        }
        override suspend fun logged(step: AutoCenterRun.Step) { log.add(step) }
    }

    @Test fun offsetBecomesADampedStepTowardThePet() {
        // Pet 30% right and 25% below the middle: pan negative (picture moves left), tilt negative (look down).
        val next = PtzCentering(space, AutoCenter.DAMPING, AutoCenter.DEAD_ZONE).next(PtzPoint(0.0, 0.0), PetBox(.7, .65, .9, .85, .9))!!
        assertTrue(near(next.x, -.3 * TapoPtz.FRAME_PAN * AutoCenter.DAMPING))
        assertTrue(near(next.y, -.25 * TapoPtz.FRAME_TILT * AutoCenter.DAMPING))
        // Damped: the step covers less than the whole offset, so it can't overshoot.
        assertTrue(abs(next.x) < .3 * TapoPtz.FRAME_PAN && abs(next.y) < .25 * TapoPtz.FRAME_TILT)
    }

    @Test fun deadZoneLeavesANearlyCentredPetAlone() {
        assertFalse("14% off on both axes is centred", AutoCenter.needs(.95, PetBox(.54, .54, .74, .74, .9)))
        assertTrue("16% off on one axis needs a step", AutoCenter.needs(.95, PetBox(.56, .4, .76, .6, .9)))
        assertFalse("no pet, no step", AutoCenter.needs(.4, PetBox(.8, .8, .9, .9, .9)))
        assertFalse("no box, no step", AutoCenter.needs(.95, null))
        // Only the axis outside the dead zone moves.
        val next = PtzCentering(space, AutoCenter.DAMPING, AutoCenter.DEAD_ZONE).next(PtzPoint(0.0, 0.0), PetBox(.75, .5, .95, .7, .9))!!
        assertTrue(next.x < 0); assertEquals(0.0, next.y, 0.0)
    }

    @Test fun keepsCorrectingUntilCentredThenStops() = runBlocking {
        val cam = Camera(PtzPoint(-.15, -.3))
        val first = cam.view()
        assertTrue(AutoCenter.needs(first.dogP, first.box))
        val out = AutoCenterRun(space, cam, StepBudget()).run(first)
        assertEquals("centered", out.result)
        assertTrue(out.steps in 1..AutoCenter.MAX_STEPS)
        assertEquals(out.steps, cam.shots); assertEquals(out.steps, cam.log.size)
        assertEquals("centered", cam.log.last().result)
        val last = cam.view().box!!
        assertTrue(abs(last.cx - .5) <= AutoCenter.DEAD_ZONE && abs(last.cy - .5) <= AutoCenter.DEAD_ZONE)
        // Each step brought the pet closer (damping, no overshoot past the middle on pan).
        cam.log.dropLast(1).forEach { assertEquals("better", it.result) }
        cam.log.forEach { assertTrue("never crosses the middle", it.afterX == null || it.afterX!! * it.offsetX >= 0) }
    }

    @Test fun anUpsideDownCameraFlipsPanAndStillCentres() = runBlocking {
        val cam = Camera(PtzPoint(-.1, 0.0), flipPan = true)
        val out = AutoCenterRun(space, cam, StepBudget()).run(cam.view())
        assertEquals("centered", out.result)
        assertEquals("worse", cam.log.first().result)
    }

    @Test fun stopsAtTheStepCap() = runBlocking {
        // A pet that keeps walking away about as fast as the damped camera follows is never centred.
        val slow = Camera(PtzPoint(-.12, 0.0)).apply { moving = PtzPoint(-.08, 0.0) }
        val capped = AutoCenterRun(PtzSpace(-100.0, 100.0, -1.0, 1.0), slow, StepBudget()).run(slow.view())
        assertEquals("cap", capped.result); assertEquals(AutoCenter.MAX_STEPS, capped.steps); assertEquals(AutoCenter.MAX_STEPS, slow.shots)
    }

    @Test fun stopsWhenThePetIsLost() = runBlocking {
        val cam = Camera(PtzPoint(-.15, 0.0))
        val first = cam.view()
        cam.pet = null // it walked off before the next photo
        val out = AutoCenterRun(space, cam, StepBudget()).run(first)
        assertEquals("lost", out.result); assertEquals(1, out.steps); assertEquals(1, cam.shots)
        assertEquals("lost", cam.log.single().result)
        assertNull(cam.log.single().afterX)
    }

    @Test fun stopsAtTheCameraLimit() = runBlocking {
        // Already at the right-hand stop with the pet further right: no move is possible.
        val cam = Camera(PtzPoint(-1.4, 0.0), start = PtzPoint(-1.0, 0.0))
        val first = AutoCenterRun.Shot(.95, PetBox(.8, .4, .95, .6, .9))
        val out = AutoCenterRun(space, cam, StepBudget()).run(first)
        assertEquals("limit", out.result); assertTrue(out.limited); assertEquals(0, cam.moves); assertEquals(0, cam.shots)
    }

    @Test fun stopsWhenTheHourlyAllowanceOrBudgetRunsOut() = runBlocking {
        var now = 0L
        val budget = StepBudget(2)
        val cam = Camera(PtzPoint(-.12, 0.0)).apply { moving = PtzPoint(-.08, 0.0) }
        val out = AutoCenterRun(PtzSpace(-100.0, 100.0, -1.0, 1.0), cam, budget) { now }.run(cam.view())
        assertEquals("budget", out.result); assertEquals(2, cam.shots); assertEquals(0, budget.left(now))
        now = 3_600_000L; assertEquals("an hour later the allowance is back", 2, budget.left(now))
        val broke = Camera(PtzPoint(-.15, 0.0)).apply { outOfBudget = true }
        val spent = AutoCenterRun(space, broke, StepBudget()).run(broke.view())
        assertEquals("budget", spent.result); assertEquals("budget", broke.log.single().result)
    }

    @Test fun aFailedPhotoStopsTheRun() = runBlocking {
        val cam = Camera(PtzPoint(-.15, 0.0)).apply { failShot = true }
        assertEquals("failed", AutoCenterRun(space, cam, StepBudget()).run(cam.view()).result)
        assertEquals("failed", cam.log.single().result)
    }

    @Test fun cooldownsFollowTheOutcome() {
        val g = AutoCenterGate()
        g.finished("a", "cap", 1000); assertFalse(g.mayStart("a", 1000 + AutoCenter.COOLDOWN_MS - 1)); assertTrue(g.mayStart("a", 1000 + AutoCenter.COOLDOWN_MS))
        g.finished("a", "lost", 0); assertTrue(g.mayStart("a", AutoCenter.LOST_COOLDOWN_MS))
        g.finished("a", "centered", 0); assertTrue(g.mayStart("a", 0)); assertTrue(g.mayStart("b", 0))
    }

    @Test fun stepsAreLoggedWithOffsetStepAndResult() {
        val s = AutoCenterRun.Step(5, "lan:x", 1, .31234, -.2, -.12345, .1, .05, null, "better")
        val j = s.json()
        assertEquals(.312, j.getDouble("offset_x"), 0.0); assertEquals(-.123, j.getDouble("pan"), 0.0)
        assertTrue(j.isNull("after_y")); assertEquals("better", j.getString("result"))
        assertTrue(s.line().contains("+31%")); assertTrue(s.result in AutoCenter.RESULTS)
    }
}
