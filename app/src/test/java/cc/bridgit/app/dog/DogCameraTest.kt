package cc.bridgit.app.dog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DogCameraTest {
    @Test fun onlyFixedNetworkCameraModeAllowsTheScreenToSleep() {
        assertFalse(DogCamera.requiresScreen("lan:12345678-1234-1234-1234-123456789012"))
        assertTrue(DogCamera.requiresScreen(DogCamera.AUTO))
        assertTrue(DogCamera.requiresScreen(DogCamera.REAR))
        assertTrue(DogCamera.requiresScreen(DogCamera.FRONT))
    }
    private val R = DogCamera.REAR
    private val F = DogCamera.FRONT

    @Test fun picksTheCameraThatSeesTheDogBest() {
        val c = DogCamera.pick(mapOf(R to 0.1, F to 0.93), current = R, at = 1)
        assertEquals(listOf(F), c.cameras)
        assertEquals(DogCamera.Why.BEST, c.why)
    }

    @Test fun noDogAnywhereKeepsTheLastGoodCamera() {
        val c = DogCamera.pick(mapOf(R to 0.2, F to 0.4), current = F, at = 1)
        assertEquals(listOf(F), c.cameras)
        assertEquals(DogCamera.Why.NO_DOG, c.why)
        assertEquals(listOf(R), DogCamera.pick(mapOf(R to 0.1, F to 0.1), current = null, at = 1).cameras)
    }

    @Test fun twoGoodViewsTakeTurnsBestFirst() {
        val c = DogCamera.pick(mapOf(R to 0.85, F to 0.95), current = F, at = 1)
        assertEquals(listOf(F, R), c.cameras)
        assertEquals(DogCamera.Why.SHARED, c.why)
        assertEquals(F, DogCamera.forPhoto(c.cameras, 0))
        assertEquals(R, DogCamera.forPhoto(c.cameras, 1))
        assertEquals(F, DogCamera.forPhoto(c.cameras, 2))
    }

    @Test fun nearlyAsGoodCurrentCameraLeadsSoItDoesNotFlap() {
        // Front is a bit better but within the margin: stay led by rear, front joins in turns.
        val c = DogCamera.pick(mapOf(R to 0.86, F to 0.9), current = R, at = 1)
        assertEquals(listOf(R, F), c.cameras)
    }

    @Test fun aClearlyBetterCameraTakesOverAlone() {
        val c = DogCamera.pick(mapOf(R to 0.65, F to 0.97), current = R, at = 1)
        assertEquals(listOf(F), c.cameras)
        assertEquals(DogCamera.Why.BEST, c.why)
    }

    @Test fun aCameraThatCouldNotTakeAPhotoIsSkipped() {
        val c = DogCamera.pick(mapOf(R to null, F to 0.9), current = R, at = 1)
        assertEquals(listOf(F), c.cameras)
        assertEquals(DogCamera.Why.ONLY_ONE, c.why)
        // Current camera broken and no dog on the other: use the one that works.
        assertEquals(listOf(F), DogCamera.pick(mapOf(R to null, F to 0.1), current = R, at = 1).cameras)
        // Nothing worked: keep what was in use.
        assertEquals(listOf(F), DogCamera.pick(mapOf(R to null, F to null), current = F, at = 1).cameras)
    }

    @Test fun searchesAtSixtySecondsAndRepeatsWithoutTheOldBackoff() {
        val seen = mapOf(R to 10_000L)
        val delay = { _: String -> 60_000L }
        assertFalse(DogCamera.rescanDue(69_999, seen, 0, listOf(R), delay))
        assertTrue(DogCamera.rescanDue(70_000, seen, 0, listOf(R), delay))
        assertFalse(DogCamera.rescanDue(129_999, seen, 70_000, listOf(R), delay))
        assertTrue(DogCamera.rescanDue(130_000, seen, 70_000, listOf(R), delay))
    }

    @Test fun eachActiveCameraUsesItsOwnDelayAndAVisiblePetPreventsSearching() {
        val delays = { camera: String -> if (camera == R) 30_000L else 120_000L }
        val cameras = listOf(R, F)
        assertFalse(DogCamera.rescanDue(119_999, emptyMap(), 0, cameras, delays))
        assertTrue(DogCamera.rescanDue(120_000, emptyMap(), 0, cameras, delays))
        assertFalse(DogCamera.rescanDue(120_000, mapOf(R to 119_000L), 0, cameras, delays))
        assertFalse(DogCamera.rescanDue(120_000, mapOf(F to 119_000L), 0, cameras, delays))
        assertFalse(DogCamera.rescanDue(120_000, emptyMap(), 0, emptyList(), delays))
        assertFalse(DogCamera.rescanDue(10_000, emptyMap(), 20_000, cameras, delays))
    }

    @Test fun readingsRememberTheirCameraAndOldLogsLoadAsRear() {
        val r = Reading(5, true, 0.9, "sleeping", 0.8, "relaxed", 0.7, "clef", camera = F)
        assertEquals(F, Reading.from(r.json()).camera)
        val old = r.json().apply { remove("camera") }
        assertEquals(R, Reading.from(old).camera)
    }

    @org.junit.Test fun explicitCamerasCanBeCombinedAndAutoIsExclusive() {
        val one=DogCamera.toggle(DogCamera.AUTO,listOf(DogCamera.FRONT),DogCamera.REAR,true)
        assertEquals(DogCamera.MANUAL,one.first);assertEquals(listOf(DogCamera.REAR),one.second)
        val two=DogCamera.toggle(one.first,one.second,"lan:one",true)
        assertEquals(listOf(DogCamera.REAR,"lan:one"),two.second)
        assertEquals(DogCamera.AUTO to emptyList<String>(),DogCamera.toggle(two.first,two.second,DogCamera.AUTO,true))
        assertEquals(DogCamera.AUTO to emptyList<String>(),DogCamera.toggle(one.first,one.second,DogCamera.REAR,false))
        assertTrue(DogCamera.requiresScreen(DogCamera.MANUAL,two.second))
        assertFalse(DogCamera.requiresScreen(DogCamera.MANUAL,listOf("lan:one","lan:two")))
        assertEquals("lan:two",DogCamera.forPhoto(listOf("lan:one","lan:two"),3))
    }
}
