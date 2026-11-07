package cc.bridgit.app.dog

import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.vision.FrameDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class DogChecksTest {
    private val at = 1_793_016_000_000L
    private val grid = IntArray(FrameDiff.SIZE * FrameDiff.SIZE) { (it * 7) % 200 }
    private fun reading(model: String, time: Long = at, activity: String = "sleeping") =
        Reading(time, true, 0.95, activity, 0.9, "relaxed", 0.9, model)

    @Test fun changingEditionDuringTheFirstModelDiscardsTheFrame() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        for (models in listOf(listOf("jev"), listOf("clef","jev"))) {
            var current = true
            val calls = mutableListOf<String>()
            val failure = runCatching {
                checks.check("rear", grid, at, models, force = true, verifySelection = { check(current) { "edition changed" } }) {
                    calls.add(it)
                    current = false
                    reading(it)
                }
            }.exceptionOrNull()
            assertEquals("edition changed", failure?.message)
            assertEquals(listOf(models.first()), calls)
        }
    }

    @Test fun unchangedSceneMarkerSurvivesReuseButBreaksOnFreshChecksAndOtherCameras() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        suspend fun check(camera: String, time: Long, force: Boolean = false) = checks.check(camera, grid, time, listOf("jev"), force) { reading(it, time) }.tracked!!
        val first = check("rear", at)
        val same = check("rear", at + 10_000)
        assertEquals(at, first.unchangedSince)
        assertEquals(at, same.unchangedSince)
        assertTrue(same.reused)
        assertEquals(at + 20_000, check("front", at + 20_000).unchangedSince)
        assertEquals(at + 30_000, check("rear", at + 30_000, true).unchangedSince)
        assertEquals(same.unchangedSince, Reading.from(same.json()).unchangedSince)
    }

    @Test fun failedChangedFrameBreaksContinuityEvenWhenTheOldViewReturns() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        checks.check("rear", grid, at, listOf("jev")) { reading(it) }
        val changed = IntArray(grid.size) { 255 - grid[it] }
        val failure = checks.check("rear", changed, at + 10_000, listOf("jev")) { throw Jev.Failure("offline", "No answer") }
        assertNull(failure.tracked)
        val returned = checks.check("rear", grid, at + 20_000, listOf("jev")) { reading(it, at + 20_000) }.tracked!!
        assertFalse(returned.reused)
        assertEquals(at + 20_000, returned.unchangedSince)
    }

    @Test fun defaultsAndLegacyChoicesArePreservedAndSwitchesAreIndependent() {
        assertEquals(listOf("clef"), DogModels.restore(null, null))
        assertEquals(listOf("jev"), DogModels.restore(null, "jev"))
        assertEquals(listOf("clef"), DogModels.restore(null, "unknown"))
        val both = DogModels.toggle(listOf("clef"), "jev", true)
        assertEquals(listOf("clef", "jev"), both)
        assertEquals(listOf("jev"), DogModels.toggle(both, "clef", false))
        assertEquals(emptyList<String>(), DogModels.toggle(listOf("jev"), "jev", false))
        assertEquals(emptyList<String>(), DogModels.restore(emptySet(), "jev"))
        assertEquals(both, DogModels.restore(setOf("jev", "clef", "unknown"), "jev"))
    }

    @Test fun bothCheckTheSamePhotoAndTotalsOnlyCountOneReading() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        val calls = mutableListOf<String>()
        val result = checks.check("rear", grid, at, listOf("jev", "clef")) {
            calls += it
            reading(it, activity = if (it == "clef") "sleeping" else "drinking")
        }
        assertEquals(listOf("clef", "jev"), calls)
        assertEquals(2, result.readings.size)
        assertEquals("clef", result.primary!!.model)
        val tracker = DogTracker()
        tracker.add(result.tracked!!)
        val day = tracker.day(at, TimeZone.getDefault())
        assertEquals(1, day.photos)
        assertEquals(1, day.naps)
        assertEquals(0, day.drinks)
    }

    @Test fun cachesAreSeparateForEachModelAndCameraAndNewlyEnabledModelsAreAsked() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        val calls = mutableListOf<String>()
        suspend fun check(camera: String, time: Long, models: List<String>) = checks.check(camera, grid, time, models) {
            calls += "$camera:$it"; reading(it, time)
        }
        check("rear", at, listOf("clef"))
        val both = check("rear", at + 10_000, DogJev.MODELS)
        assertEquals(listOf("rear:clef", "rear:jev"), calls)
        assertTrue(both.readings[0].reused)
        assertFalse(both.readings[1].reused)
        assertFalse(both.reused)
        val tracker = DogTracker()
        tracker.add(both.tracked!!)
        assertEquals(0, tracker.day(at + 10_000).free)
        assertTrue(check("rear", at + 20_000, DogJev.MODELS).reused)
        check("front", at + 30_000, DogJev.MODELS)
        assertEquals(listOf("rear:clef", "rear:jev", "front:clef", "front:jev"), calls)
        check("rear", at + 3_600_000, DogJev.MODELS)
        assertEquals("rear:clef", calls.last()) // Jev's own hour has not elapsed yet.
    }

    @Test fun oneModelFailureStillAllowsTheOtherToAnswerAndFailuresAreRetried() = runBlocking {
        for (failed in DogJev.MODELS) {
            val checks = DogChecks(DogMonitor.CHANGE)
            val result = checks.check("rear", grid, at, DogJev.MODELS) {
                if (it == failed) throw Jev.Failure("quota", "Unavailable")
                reading(it)
            }
            assertEquals(DogJev.MODELS.filter { it != failed }, result.readings.map { it.model })
            assertEquals(setOf(failed), result.failures.keys)
            val retried = mutableListOf<String>()
            val recovered = checks.check("rear", grid, at + 10_000, DogJev.MODELS) {
                retried += it; reading(it, at + 10_000)
            }
            assertEquals(listOf(failed), retried)
            assertEquals(2, recovered.readings.size)
        }
    }

    @Test fun hourlyMissesStayWithinTheSameModelAndRestartClearsCaches() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        checks.check("rear", grid, at, DogJev.MODELS) { reading(it) }
        val hourly = checks.check("rear", grid, at + 3_600_000, DogJev.MODELS) {
            reading(it, at + 3_600_000, if (it == "clef") "sleeping" else "drinking")
        }
        assertEquals(1, hourly.missed)
        checks.clear()
        val fresh = checks.check("rear", grid, at + 3_610_000, DogJev.MODELS) { reading(it, at + 3_610_000) }
        assertFalse(fresh.reused)
        assertEquals(0, fresh.missed)
    }

    @Test fun cameraTestsCanForceBothModelsAndChangedPhotosAskBothAgain() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        val calls = mutableListOf<String>()
        checks.check("rear", grid, at, DogJev.MODELS) { reading(it) }
        checks.check("rear", grid, at + 10_000, DogJev.MODELS, force = true) {
            calls += it; reading(it, at + 10_000)
        }
        assertEquals(DogJev.MODELS, calls)
        calls.clear()
        val changed = IntArray(grid.size) { 255 - grid[it] }
        checks.check("rear", changed, at + 20_000, DogJev.MODELS) {
            calls += it; reading(it, at + 20_000)
        }
        assertEquals(DogJev.MODELS, calls)
    }

    @Test fun allFailuresKeepTheirModelNamesAndOnlyAllPermanentFailuresStopWatching() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        val mixed = checks.check("rear", grid, at, DogJev.MODELS) {
            throw Jev.Failure(if (it == "clef") "clef_quota" else "busy", "Unavailable", retryable = it == "jev")
        }
        assertNull(mixed.primary)
        assertEquals("clef_quota", (mixed.failures["clef"] as Jev.Failure).reason)
        assertEquals("busy", (mixed.failures["jev"] as Jev.Failure).reason)
        assertFalse(mixed.terminal)
        val permanent = checks.check("rear", grid, at, DogJev.MODELS) {
            throw Jev.Failure("quota", "Unavailable")
        }
        assertTrue(permanent.terminal)
        val partial = checks.check("rear", grid, at, DogJev.MODELS) {
            if (it == "clef") throw Jev.Failure("quota", "Unavailable")
            reading(it)
        }
        assertFalse(partial.terminal)
    }

    @Test fun noModelsMeansNoCallsAndCancellationNeverStartsTheSecondModel() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        val empty = checks.check("rear", grid, at, emptyList()) { error("must not call") }
        assertNull(empty.primary)
        assertFalse(empty.reused)
        val calls = mutableListOf<String>()
        try {
            checks.check("rear", grid, at, DogJev.MODELS) { calls += it; throw CancellationException() }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf("clef"), calls)
    }

    /** The Pet monitor's camera scans: a rescan of an idle camera whose picture hasn't changed costs no model call. */
    @Test fun rescanReusesUnchangedIdleCameraButFullScanAndChangedPictureAsk() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        var calls = 0
        suspend fun scanCheck(full: Boolean, picture: IntArray, time: Long) =
            checks.check("lan:dog", picture, time, listOf("jev"), DogChecks.scanForce(full)) { calls++; reading(it, time).copy(dog = false, dogP = 0.12) }
        val first = scanCheck(full = true, picture = grid, time = at)
        assertEquals(1, calls)
        assertEquals(0.12, first.primary!!.dogP, 1e-9)
        // Rescan 80 s later, same picture: reused, and its score still feeds the camera choice.
        val rescan = scanCheck(full = false, picture = grid, time = at + 80_000)
        assertEquals(1, calls)
        assertTrue(rescan.reused)
        assertEquals(0.12, rescan.primary!!.dogP, 1e-9)
        // A full scan (start, Find the best camera) always asks.
        scanCheck(full = true, picture = grid, time = at + 160_000)
        assertEquals(2, calls)
        // The picture changed: the rescan asks.
        val changed = IntArray(grid.size) { 255 - grid[it] }
        scanCheck(full = false, picture = changed, time = at + 240_000)
        assertEquals(3, calls)
        // Unchanged for over an hour: the change gate re-asks.
        scanCheck(full = false, picture = changed, time = at + 240_000 + 60 * 60_000L + 1)
        assertEquals(4, calls)
    }

    // A9 at night: full-resolution average ~8/255, brightest pixel 21; the 32x32 grid is no brighter.
    private val black = IntArray(grid.size) { 4 + (it * 13) % 17 }

    @Test fun darkPhotoIsNeverSentToAModelAndNeverReusesTheEarlierDogAnswer() = runBlocking {
        val checks = DogChecks(DogMonitor.CHANGE)
        var calls = 0
        suspend fun check(picture: IntArray, time: Long, force: Boolean = false) =
            checks.check("front", picture, time, listOf("clef", "jev"), force) { calls++; reading(it, time, "resting") }
        assertTrue(check(grid, at).tracked!!.dog)
        assertEquals(2, calls)
        val dark = check(black, at + 10_000)
        val r = dark.tracked!!
        assertEquals(0, dark.missed)
        assertTrue(r.dark); assertFalse(r.dog); assertFalse(r.reused); assertFalse(dark.reused)
        assertEquals(0.0, r.dogP, 0.0); assertNull(r.activity); assertNull(r.mood)
        assertEquals("clef", r.model); assertEquals("front", r.camera)
        assertEquals(1, dark.readings.size)
        // A dark night is one moment: every dark photo shares when the darkness began, even a forced check.
        val later = check(black, at + 20 * 60_000L, force = true).tracked!!
        assertTrue(later.dark)
        assertEquals(at + 10_000, r.unchangedSince)
        assertEquals(at + 10_000, later.unchangedSince)
        assertEquals(2, calls)
        // Lights back on with the old scene: asked fresh, not the pre-dark answer.
        val lit = check(grid, at + 21 * 60_000L).tracked!!
        assertEquals(4, calls)
        assertFalse(lit.reused); assertFalse(lit.dark)
        assertEquals(at + 21 * 60_000L, lit.unchangedSince)
        // A new dark stretch starts its own moment.
        assertEquals(at + 22 * 60_000L, check(black, at + 22 * 60_000L).tracked!!.unchangedSince)
    }

    @Test fun darkFlagRoundTripsAndOldSavedReadingsAreNotDark() {
        val r = Reading(at, false, 0.0, null, 0.0, null, 0.0, "jev", dark = true)
        assertTrue(Reading.from(r.json()).dark)
        val old = r.json().apply { remove("dark") }
        assertFalse(Reading.from(old).dark)
    }

    @Test fun trackerCountsDarkPhotosApartFromNoDogAndEndsNoNap() {
        val t = DogTracker()
        t.add(reading("jev", at, "resting"), TimeZone.getTimeZone("UTC"))
        t.add(Reading(at + 10_000, false, 0.0, null, 0.0, null, 0.0, "jev", dark = true), TimeZone.getTimeZone("UTC"))
        val day = t.day(at + 10_000, TimeZone.getTimeZone("UTC"))
        assertEquals(1, day.views["dark"]); assertNull(day.views["no_dog"])
        assertEquals(1, t.episodes().single().frames)
    }
}
