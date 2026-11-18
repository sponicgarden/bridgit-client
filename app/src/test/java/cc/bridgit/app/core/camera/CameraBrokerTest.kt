package cc.bridgit.app.core.camera

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.capability.CameraArmCore
import cc.bridgit.app.core.camera.CameraLeases.Lease
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A0455 phase 2: the broker that replaced `registry.cameraLock` keeps the mutex's answers. */
class CameraBrokerTest {
    private val snap = Lease.OneOff("agent-1", "snapshot")
    private val watch = Lease.OneOff("applet-1", "watch")
    private val screen = Lease.Exclusive("camera_screen")

    @Test fun aBusyCameraRefusesTryAcquireLikeTryLock() {
        val b = CameraBroker()
        val t = b.tryAcquire(snap, "rear")!!
        assertNull("another one-off is busy", b.tryAcquire(watch, "front"))
        assertNull("the camera screen is busy", b.tryAcquire(screen))
        assertEquals(snap, b.holder())
        assertTrue(b.release(t))
        assertTrue(b.isFree())
        val s = b.tryAcquire(screen)!!
        assertNull("a one-off is busy behind the camera screen", b.tryAcquire(snap, "rear"))
        assertEquals(screen, b.holder())
        b.release(s)
    }

    @Test fun staleOrRepeatedReleaseIsIgnored() {
        val b = CameraBroker()
        val old = b.tryAcquire(snap)!!
        assertTrue(b.release(old))
        assertFalse("released twice: nothing happens", b.release(old))
        val now = b.tryAcquire(watch)!!
        assertFalse("the old generation can't free the new lease", b.release(old))
        assertEquals(watch, b.holder())
        assertNull(b.tryAcquire(snap))
        assertTrue(b.release(now))
        assertFalse(b.release(null))
    }

    @Test fun acquireWaitsInOrderAndTryAcquireNeverJumpsTheQueue() = runBlocking<Unit> {
        val b = CameraBroker()
        val first = b.tryAcquire(snap)!!
        val w1 = async(start = CoroutineStart.UNDISPATCHED) { b.acquire(watch) }
        val w2 = async(start = CoroutineStart.UNDISPATCHED) { b.acquire(Lease.OneOff("applet-2", "watch")) }
        assertEquals(2, b.waiting())
        b.release(first)
        assertNull("handed straight to the first waiter, like Mutex.unlock", b.tryAcquire(screen))
        val t1 = w1.await()
        assertEquals(watch, t1.lease)
        assertFalse(w2.isCompleted)
        b.release(t1)
        val t2 = w2.await()
        assertEquals("applet-2", (t2.lease as Lease.OneOff).agentId)
        b.release(t2)
        assertTrue(b.isFree())
        assertEquals(0, b.waiting())
    }

    @Test fun acquireWithinGivesUpWhenTheCameraStaysBusy() = runBlocking<Unit> {
        val b = CameraBroker()
        val held = b.tryAcquire(snap)!!
        assertNull("zero wait is tryLock", b.acquireWithin(0, watch))
        assertNull("busy for the whole wait: the watch reading is skipped", b.acquireWithin(100, watch))
        assertEquals("a waiter that gave up leaves no trace", 0, b.waiting())
        b.release(held)
        assertTrue(b.isFree())
        val t = b.acquireWithin(100, watch)!!
        b.release(t)
    }

    @Test fun aCancelledWaiterNeverKeepsTheCamera() = runBlocking<Unit> {
        val b = CameraBroker()
        val held = b.tryAcquire(snap)!!
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { b.acquire(watch) }
        waiter.cancel()
        b.release(held)
        assertTrue("the cancelled waiter did not take the camera", b.isFree())
        assertNotNull(b.tryAcquire(screen))
    }

    @Test fun acquireWithinReleasedMidWaitGetsTheCamera() = runBlocking<Unit> {
        val b = CameraBroker()
        val held = b.tryAcquire(snap)!!
        val w = async { b.acquireWithin(2_000, watch) }
        delay(50)
        b.release(held)
        val t = withTimeout(2_000) { w.await() }!!
        assertEquals(watch, t.lease)
        b.release(t)
    }

    // --- The arm (POST /v1/camera/arm) on the broker, with a fake camera. ---

    private class FakeCamera : CameraArmCore.Device<String> {
        var next = 0L
        val open = mutableSetOf<Long>()
        val released = mutableListOf<String>()
        override fun captureStarted(note: String): Long = (++next).also { open += it }
        override fun captureEnded(token: Long) { open -= token }
        override suspend fun warmUp(rear: Boolean) = if (rear) "rear-binding" else "front-binding"
        override suspend fun release(binding: String) { released += binding }
    }

    private suspend fun until(what: String, ok: () -> Boolean) {
        withTimeoutOrNull(3_000) { while (!ok()) delay(20) } ?: fail(what)
    }

    @Test fun armHoldsALeaseAndOthersGetTheBusyAnswer() = runBlocking<Unit> {
        val b = CameraBroker(); val cam = FakeCamera(); val arm = CameraArmCore<String>(b)
        arm.arm(cam, "grok", "armed", rear = true, seconds = 10) { true }
        assertEquals(Lease.OneOff("grok", "arm"), b.holder())
        try { arm.arm(cam, "claude", "armed", rear = true, seconds = 10) { true }; fail("busy") }
        catch (e: ApiError) { assertEquals("sensor_busy", e.code) }
        assertNull("a snapshot without the arm is busy", b.tryAcquire(snap))
        // The arming agent's own snapshot takes the arm over, and its finish frees the camera.
        val h = arm.takeFor("grok")!!
        assertEquals("rear-binding", h.binding)
        arm.finish(cam, h)
        assertTrue(b.isFree())
        assertTrue(cam.open.isEmpty())
    }

    @Test fun reArmKeepsTheSameLease() = runBlocking<Unit> {
        val b = CameraBroker(); val cam = FakeCamera(); val arm = CameraArmCore<String>(b)
        arm.arm(cam, "grok", "armed", rear = true, seconds = 10) { true }
        val first = arm.slot.claim("grok")!!.also { arm.slot.put(it.agentId, it.rear, it.binding, it.token, it.openedAt, it.lease) }
        arm.arm(cam, "grok", "armed", rear = false, seconds = 10) { true }
        val second = arm.takeFor("grok")!!
        assertEquals(first.lease!!.generation, second.lease!!.generation)
        assertEquals("front-binding", second.binding)
        assertEquals(listOf("rear-binding"), cam.released)
        arm.finish(cam, second)
        assertTrue(b.isFree())
    }

    @Test fun stopOrPauseReleasesTheArmedCamera() = runBlocking<Unit> {
        val b = CameraBroker(); val cam = FakeCamera(); val arm = CameraArmCore<String>(b)
        arm.arm(cam, "grok", "armed", rear = true, seconds = 10) { true }
        arm.releaseAll(cam)
        until("Stop/Pause frees the camera") { b.isFree() }
        assertTrue(cam.open.isEmpty())
        assertEquals(listOf("rear-binding"), cam.released)
        assertNull("nothing is left armed", arm.takeFor("grok"))
    }

    @Test fun losingAccessReleasesTheArmedCamera() = runBlocking<Unit> {
        val b = CameraBroker(); val cam = FakeCamera(); val arm = CameraArmCore<String>(b)
        val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
        arm.arm(cam, "grok", "armed", rear = true, seconds = 10) { allowed.get() }
        delay(300)
        assertFalse(b.isFree())
        allowed.set(false)
        until("the camera is freed once the agent loses access") { b.isFree() }
        assertTrue(cam.open.isEmpty())
        assertNotNull("another agent can take a photo now", b.tryAcquire(snap))
    }

    @Test fun aLateArmTimerCannotFreeANewerLease() = runBlocking<Unit> {
        val b = CameraBroker(); val cam = FakeCamera(); val arm = CameraArmCore<String>(b)
        arm.arm(cam, "grok", "armed", rear = true, seconds = 1) { true }
        val h = arm.takeFor("grok")!!
        arm.finish(cam, h)
        // Someone else takes the camera; the first arm's timer runs out meanwhile and must not touch it.
        val other = b.tryAcquire(snap)!!
        delay(1_400)
        assertEquals(snap, b.holder())
        assertFalse("finishing the old arm again is a stale release", b.release(h.lease))
        assertEquals(snap, b.holder())
        b.release(other)
    }
}
