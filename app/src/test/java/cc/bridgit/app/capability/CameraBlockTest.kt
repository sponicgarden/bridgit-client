package cc.bridgit.app.capability

import androidx.camera.core.CameraState
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
}
