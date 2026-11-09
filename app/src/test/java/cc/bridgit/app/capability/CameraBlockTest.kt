package cc.bridgit.app.capability

import androidx.camera.core.CameraState
import androidx.camera.core.CameraState.Type.CLOSED
import androidx.camera.core.CameraState.Type.OPEN
import androidx.camera.core.CameraState.Type.OPENING
import androidx.camera.core.CameraState.Type.PENDING_OPEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A0064: a camera held by another app fails fast with a clear reason instead of a 20 s capture_timeout. */
class CameraBlockTest {
    @Test fun inUseIsSensorBusyWithReason() {
        for (code in listOf(CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE)) {
            val e = CameraBlock.error(code, rear = true)!!
            assertEquals(409, e.http)
            assertEquals("sensor_busy", e.code)
            assertEquals("camera_in_use", e.extra["reason"])
        }
        assertEquals("camera_disabled", CameraBlock.error(CameraState.ERROR_CAMERA_DISABLED, rear = false)!!.extra["reason"])
    }
    @Test fun otherStatesKeepWaiting() {
        assertNull(CameraBlock.error(null, rear = true))
        assertNull(CameraBlock.error(CameraState.ERROR_STREAM_CONFIG, rear = true))
    }

    /** A0253: another app opened the camera first, so CameraX waits to open with no error code at all. */
    @Test fun unbrokenWaitToOpenEndsAsCameraHeld() {
        val w = CameraBlock.Watch(rear = true)
        w.update(PENDING_OPEN, null, 1_000)
        assertNull(w.verdict(1_000 + CameraBlock.HELD_MS - 1))
        val e = w.verdict(1_000 + CameraBlock.HELD_MS)!!
        assertEquals(409, e.http)
        assertEquals("sensor_busy", e.code)
        assertEquals("camera_held", e.extra["reason"])
        assertEquals("pending_open", CameraBlock.name(w.last))
    }

    /** The draft rejected for A0253 failed normal opens: a short PENDING_OPEN (first state, front/rear switch) must not. */
    @Test fun briefWaitToOpenStillGetsItsPhoto() {
        val w = CameraBlock.Watch(rear = false)
        w.update(PENDING_OPEN, null, 0) // first state replayed when the photo starts watching
        w.update(OPENING, null, 400)
        w.update(OPEN, null, 700)
        assertNull(w.verdict(60_000))
        // A later wait counts from its own start, not from the first one.
        w.update(CLOSED, null, 10_000)
        w.update(PENDING_OPEN, null, 10_100)
        assertNull(w.verdict(10_100 + CameraBlock.HELD_MS - 1))
        assertEquals("camera_held", w.verdict(10_100 + CameraBlock.HELD_MS)!!.extra["reason"])
        // Repeated PENDING_OPEN reports don't restart the clock.
        val again = CameraBlock.Watch(rear = true)
        again.update(PENDING_OPEN, null, 0)
        again.update(PENDING_OPEN, null, 3_000)
        assertEquals("camera_held", again.verdict(CameraBlock.HELD_MS)!!.extra["reason"])
    }

    @Test fun reportedErrorsKeepTheirShorterGrace() {
        val w = CameraBlock.Watch(rear = true)
        w.update(PENDING_OPEN, CameraState.ERROR_CAMERA_IN_USE, 0)
        assertNull(w.verdict(CameraBlock.GRACE_MS - 1))
        assertEquals("camera_in_use", w.verdict(CameraBlock.GRACE_MS)!!.extra["reason"])
        // The camera came back within the grace: keep waiting for the photo.
        val back = CameraBlock.Watch(rear = true)
        back.update(PENDING_OPEN, CameraState.ERROR_CAMERA_IN_USE, 0)
        back.update(OPEN, null, 1_000)
        assertNull(back.verdict(30_000))
    }
}
