package cc.bridgit.app.api

import cc.bridgit.app.capability.ArmSlot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** POST /v1/camera/arm: who holds the warm camera, and that only one party ever releases it. */
class CameraArmTest {

    @Test fun snapshotTakesOverItsOwnArmOnly() {
        val slot = ArmSlot<String>()
        slot.put("grok", rear = false, binding = "cam", token = 7)
        assertNull("another agent can't take it", slot.claim("claude"))
        assertEquals("grok", slot.holder())
        val h = slot.claim("grok")!!
        assertEquals("cam", h.binding); assertEquals(7L, h.token); assertEquals(false, h.rear)
        assertNull("claimed once", slot.claim("grok"))
        assertNull(slot.holder())
    }

    @Test fun timerAndSnapshotNeverBothRelease() {
        val slot = ArmSlot<String>()
        val g = slot.put("grok", true, "cam", 1)
        assertNotNull(slot.claim("grok"))              // the snapshot wins
        assertNull(slot.claimGeneration(g))            // the timer then finds nothing to release
        val g2 = slot.put("grok", true, "cam", 1)
        assertNotNull(slot.claimGeneration(g2))        // the timer wins
        assertNull(slot.claim("grok"))                 // the snapshot goes cold
    }

    @Test fun reArmMakesTheOldTimerStale() {
        val slot = ArmSlot<String>()
        val g1 = slot.put("grok", false, "cam", 1)
        val prev = slot.claim("grok")!!
        val g2 = slot.put("grok", false, prev.binding, prev.token)
        assertNull("old timer must not release the new arm", slot.claimGeneration(g1))
        assertEquals("cam", slot.claimGeneration(g2)!!.binding)
    }

    @Test fun stopReleasesWhoeverHoldsIt() {
        val slot = ArmSlot<String>()
        slot.put("grok", false, "cam", 1)
        assertEquals("grok", slot.claim()!!.agentId)
        assertNull(slot.claim())
    }

    @Test(expected = IllegalStateException::class) fun cannotArmTwice() {
        val slot = ArmSlot<String>()
        slot.put("grok", false, "a", 1)
        slot.put("claude", false, "b", 2)
    }

    @Test fun aSnapshotRightAfterArmWaitsForExposure() {
        val h = ArmSlot.Held("grok", false, "cam", 1, 1, openedAt = 10_000)
        assertEquals(1000, ArmSlot.settleWaitMs(h, 10_000))
        assertEquals(600, ArmSlot.settleWaitMs(h, 10_400))
        assertEquals(0, ArmSlot.settleWaitMs(h, 11_000))
        assertEquals(0, ArmSlot.settleWaitMs(h, 13_000))
        assertEquals("clock went back: never more than the settle time", 1000, ArmSlot.settleWaitMs(h, 0))
    }

    @Test fun secondsAreOneToTen() {
        assertEquals(10, ArmSlot.seconds(null))
        assertEquals(10, ArmSlot.seconds(JSONObject.NULL))
        assertEquals(1, ArmSlot.seconds(1))
        assertEquals(10, ArmSlot.seconds(10L))
        assertEquals(5, ArmSlot.seconds(5.0))
        assertNull(ArmSlot.seconds(0))
        assertNull(ArmSlot.seconds(11))
        assertNull(ArmSlot.seconds(2.5))
        assertNull(ArmSlot.seconds("10"))
        assertNull(ArmSlot.seconds(true))
    }

    @Test fun endpointIsListedWithTheCameraAndInTheChanges() {
        assertTrue(Endpoints.CAMERA_ARM in Endpoints.all)
        assertEquals("camera", Endpoints.CAMERA_ARM.capability)
        assertFalse("the photo counts toward the capture limit, not the arm", Endpoints.CAMERA_ARM.capture)
        assertTrue(Endpoints.CAMERA_ARM.errors.containsAll(listOf("capability_disabled", "permission_denied", "device_locked", "sensor_busy")))
        val rev = ApiChanges.revisions.first { r -> r.items.any { it.endpoint == "/v1/camera/arm" } }
        assertEquals(21, rev.revision) // its own revision; later ones may follow
        assertNotNull(McpTools.byName("camera_arm"))
    }
}
