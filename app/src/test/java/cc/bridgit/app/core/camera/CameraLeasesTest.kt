package cc.bridgit.app.core.camera

import cc.bridgit.app.core.camera.CameraLeases.Grant
import cc.bridgit.app.core.camera.CameraLeases.Lease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLeasesTest {
    private val watches = Lease.Standing(setOf("w1"))
    private val snap = Lease.OneOff("agent-1", "snapshot")
    private val screen = Lease.Exclusive("camera_screen")

    private fun granted(g: Grant<String>) = (g as Grant.Granted<String>)

    @Test fun oneOffOnTheSameLensSharesTheStandingBinding() {
        val l = CameraLeases<String>()
        val s = granted(l.acquire(watches, "rear", "stream")).held
        val o = granted(l.acquire(snap, "rear", "new-binding"))
        assertTrue(o.held.shared)
        assertEquals("stream", o.held.binding)
        assertNull(o.pausedStanding)
        assertFalse(l.isStandingPaused())
        val r = l.release(o.held.generation)!!
        assertNull(r.resumedStanding)
        assertEquals(s, l.standing())
    }

    @Test fun oneOffOnAnotherLensPausesThenResumes() {
        val l = CameraLeases<String>()
        val s = granted(l.acquire(watches, "rear", "stream")).held
        val o = granted(l.acquire(snap, "front", "front-binding"))
        assertFalse(o.held.shared)
        assertEquals("front-binding", o.held.binding)
        assertEquals(s, o.pausedStanding)
        assertTrue(l.isStandingPaused())
        assertEquals(s, l.release(o.held.generation)!!.resumedStanding)
        assertFalse(l.isStandingPaused())
    }

    @Test fun exclusiveBeatsStandingAndBlocksOneOffs() {
        val l = CameraLeases<String>()
        val s = granted(l.acquire(watches, "rear", "stream")).held
        val e = granted(l.acquire(screen, "rear", "viewfinder"))
        assertEquals(s, e.pausedStanding)
        assertTrue(l.isStandingPaused())
        assertEquals(screen, (l.acquire(snap, "rear", "b") as Grant.Busy).holder)
        assertEquals(screen, (l.acquire(Lease.Exclusive("other"), "rear", "b") as Grant.Busy).holder)
        assertEquals(s, l.release(e.held.generation)!!.resumedStanding)
        assertFalse(l.isStandingPaused())
        // With the screen gone, a snapshot shares the running stream again.
        assertTrue(granted(l.acquire(snap, "rear", "b")).held.shared)
    }

    @Test fun exclusiveWaitsForAOneOffMidCapture() {
        val l = CameraLeases<String>()
        granted(l.acquire(snap, "rear", "b"))
        assertEquals(snap, (l.acquire(screen, "rear", "v") as Grant.Busy).holder)
        assertEquals(snap, (l.acquire(Lease.OneOff("agent-2", "arm"), "rear", "v") as Grant.Busy).holder)
    }

    @Test fun standingStartedBehindTheOwnerScreenStartsPaused() {
        val l = CameraLeases<String>()
        val e = granted(l.acquire(screen, "rear", "viewfinder")).held
        val s = granted(l.acquire(watches, "rear", "stream")).held
        assertTrue(l.isStandingPaused())
        assertEquals(s, l.release(e.generation)!!.resumedStanding)
        // Only one standing stream; changes go through renegotiate.
        assertTrue(l.acquire(Lease.Standing(setOf("w2")), "rear", "x") is Grant.Busy)
        assertTrue(l.renegotiate(s.generation, setOf("w1", "w2")))
        assertEquals(Lease.Standing(setOf("w1", "w2")), l.standing()!!.lease)
        assertFalse(l.renegotiate(s.generation + 100, setOf("w3")))
    }

    @Test fun staleReleaseIsIgnored() {
        val l = CameraLeases<String>()
        val old = granted(l.acquire(snap, "rear", "a")).held
        assertNotNull(l.release(old.generation))
        val fresh = granted(l.acquire(snap, "rear", "b")).held
        // The first caller's late timer releases again: the newer lease stays.
        assertNull(l.release(old.generation))
        assertEquals(fresh, l.oneOff())
        assertNull(l.release(9999))
    }

    @Test fun manualPauseAndResume() {
        val l = CameraLeases<String>()
        val s = granted(l.acquire(watches, "rear", "stream")).held
        val o = granted(l.acquire(snap, "rear", "b")).held
        // A shared one-off doesn't pause the stream.
        assertNull(l.pauseStandingFor(o))
        assertNull(l.resumeStanding())
        l.release(o.generation)
        val e = granted(l.acquire(screen, "front", "v")).held
        // Already paused, and can't resume while the screen holds the camera.
        assertNull(l.pauseStandingFor(e))
        assertNull(l.resumeStanding())
        l.release(e.generation)
        assertEquals(s, l.standing())
        assertFalse(l.isStandingPaused())
    }

    @Test fun releasingTheLastStandingLeaseLeavesTheSlotFree() {
        val l = CameraLeases<String>()
        val s = granted(l.acquire(watches, "rear", "stream")).held
        val r = l.release(s.generation)!!
        assertEquals(s, r.held)
        assertNull(r.handedTo)
        assertTrue(l.isFree())
        assertNull(l.release(s.generation))
    }

    @Test fun aSharedOneOffKeepsTheBindingWhenTheStreamStops() {
        val l = CameraLeases<String>()
        val s = granted(l.acquire(watches, "rear", "stream")).held
        val o = granted(l.acquire(snap, "rear", "x")).held
        val r = l.release(s.generation)!!
        assertEquals(o.generation, r.handedTo!!.generation)
        assertFalse(l.oneOff()!!.shared)
        assertEquals("stream", l.oneOff()!!.binding)
        l.release(o.generation)
        assertTrue(l.isFree())
    }
}
