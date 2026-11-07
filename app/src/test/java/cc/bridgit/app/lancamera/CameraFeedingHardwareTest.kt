package cc.bridgit.app.lancamera

import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import javax.net.SocketFactory

/** Explicit owner-authorized PTZ test: a tiny nudge followed by return to the same saved food view. */
class CameraFeedingHardwareTest {
    @Test fun tapoReturnsToSavedFoodViewAndAcknowledgesStop() {
        assumeTrue(System.getenv("BRIDGIT_FEEDING_HARDWARE")=="1")
        val p=JSONObject(File(System.getProperty("user.home"),".config/bridgit/cameras/dog.json").readText())
        val camera=LanCamera("lan:feeding-hardware","Dog Cam",p.getString("host"),p.getString("username"),p.getString("password"))
        val token="feeding-hardware"
        assertTrue(CameraMotion.acquire(listOf(camera.id),token))
        val client=Onvif(camera,SocketFactory.getDefault(),token)
        val profile=client.info().profile
        val original=client.feedingPosition(profile)
        assertNotNull("Shared camera search can read this Tapo pose too",client.position(profile))
        try {
            client.move(profile,.03,0.0);Thread.sleep(500)
            client.aimAt(profile,original)
            var reached=false
            for(attempt in 0 until 12) {
                Thread.sleep(1000);val at=client.feedingPosition(profile)
                if(kotlin.math.abs(at.x-original.x)<=.025 && kotlin.math.abs(at.y-original.y)<=.025) { reached=true;break }
            }
            assertTrue("Camera must return to the saved food view",reached)
            client.stop(profile)
        } finally {
            runCatching { client.aimAt(profile,original) }
            try { client.stop(profile) } finally { CameraMotion.release(token) }
        }
    }
}
