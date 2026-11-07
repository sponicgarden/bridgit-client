package cc.bridgit.app.lancamera

import cc.bridgit.app.BridgeApp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A short, soft two-note ding the web portal plays on a camera speaker so the owner can tell cameras apart. */
object PortalChime {
    const val RATE = 8000

    /** 8 kHz mono signed 16-bit little-endian PCM: two bell tones (E6 then A5) with a gentle decay. */
    fun pcm(): ByteArray {
        val notes = listOf(1318.5 to 0.0, 880.0 to 0.16)
        val seconds = 0.9
        val n = (RATE * seconds).toInt()
        val out = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((freq, start) in notes) {
                val local = t - start
                if (local < 0) continue
                val attack = (local / 0.008).coerceAtMost(1.0)
                val decay = exp(-local * 5.0)
                v += attack * decay * (sin(2 * PI * freq * local) + 0.25 * sin(2 * PI * freq * 2 * local))
            }
            val fadeOut = ((seconds - t) / 0.05).coerceIn(0.0, 1.0)
            out.putShort((v * fadeOut * 0.28 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort())
        }
        return out.array()
    }

    /** Why this camera can't play a sound, or null when it can. */
    fun blocked(app: BridgeApp, camera: LanCamera?): String? = when {
        camera == null -> "not_enrolled"
        !app.settings.isOn("lan_camera") || !app.settings.isOn("lan_camera_speaker") -> "speaker_off"
        camera.talkPassword.isEmpty() -> "needs_setup"
        else -> null
    }

    suspend fun play(app: BridgeApp, cameraId: String, authorized: () -> Unit) {
        val camera = app.lanCameras.get(cameraId)
        blocked(app, camera)?.let { error(it) }
        camera!!
        if (!CameraAudioModule.audioLock.tryLock()) error("busy")
        val service = cc.bridgit.app.BridgeService.running
        var token: Long? = null
        try {
            token = service?.captureStarted("Your web portal is playing a sound on ${camera.name}")
            val pcm = pcm()
            val job = currentCoroutineContext()[Job]
            withTimeout(20_000) {
                withContext(Dispatchers.IO) {
                    val check = {
                        authorized()
                        val current = app.lanCameras.get(cameraId)
                        check(blocked(app, current) == null && current!!.host == camera.host && current.talkPassword == camera.talkPassword) { "Camera changed" }
                    }
                    TapoTalk(camera.host, camera.talkPassword, LanNetwork.sockets(app, camera.host), pcm.size / 16L + 10_000, cancellationJob = job, authorized = check).use { it.play(pcm) }
                }
            }
        } finally {
            if (token != null) service?.captureEnded(token)
            CameraAudioModule.audioLock.unlock()
        }
    }
}
