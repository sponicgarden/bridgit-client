package cc.bridgit.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A0504: the Camera tab's picture-in-picture window (lineage A0433, A0406, A0427, A0430). */
class CameraPipTest {
    @Test fun liveCameraTabEntersPip() =
        assertTrue(CameraPip.shouldEnter(supported = true, onCameraTab = true, previewLive = true, inPip = false, finishing = false))

    @Test fun otherScreensDeadPreviewsAndUnsupportedDevicesDoNot() {
        assertFalse(CameraPip.shouldEnter(supported = true, onCameraTab = false, previewLive = true, inPip = false, finishing = false))
        assertFalse(CameraPip.shouldEnter(supported = true, onCameraTab = true, previewLive = false, inPip = false, finishing = false))
        assertFalse(CameraPip.shouldEnter(supported = false, onCameraTab = true, previewLive = true, inPip = false, finishing = false))
        assertFalse(CameraPip.shouldEnter(supported = true, onCameraTab = true, previewLive = true, inPip = true, finishing = false))
        assertFalse(CameraPip.shouldEnter(supported = true, onCameraTab = true, previewLive = true, inPip = false, finishing = true))
    }

    @Test fun aspectFollowsThePreviewWithinAndroidsLimits() {
        assertEquals(1080 to 1920, CameraPip.aspect(1080, 1920))
        assertEquals(1920 to 1080, CameraPip.aspect(1920, 1080))
        // Android throws for anything beyond 2.39:1 either way.
        assertEquals(238 to 100, CameraPip.aspect(3000, 1000))
        assertEquals(100 to 238, CameraPip.aspect(400, 2000))
        assertEquals(3 to 4, CameraPip.aspect(0, 0))
        for ((w, h) in listOf(1 to 10_000, 10_000 to 1, 720 to 1600, 1600 to 720)) {
            val (n, d) = CameraPip.aspect(w, h)
            val r = n.toDouble() / d
            assertTrue("$w x $h -> $r", r > 1 / 2.39 && r < 2.39)
        }
    }

    /** The defect itself: the installed manifest declared no PiP, so Android never offered a PiP window. */
    @Test fun mainActivityDeclaresPictureInPicture() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        val main = Regex("""<activity\s+android:name="cc\.bridgit\.app\.ui\.MainActivity"[^>]*>""").find(manifest)?.value.orEmpty()
        assertTrue(main, main.contains("""android:supportsPictureInPicture="true""""))
        // Entering the PiP window must resize, not recreate, the activity, or the live preview is torn down.
        val changes = Regex("""android:configChanges="([^"]*)"""").find(main)?.groupValues?.get(1).orEmpty().split('|')
        assertTrue(main, changes.containsAll(listOf("screenSize", "smallestScreenSize", "screenLayout")))
    }
}
