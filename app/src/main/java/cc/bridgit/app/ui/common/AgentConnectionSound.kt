package cc.bridgit.app.ui.common

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** One quiet, short acknowledgement, even when the setup screen has been left. */
object AgentConnectionSound {
    fun play(context: Context) {
        Handler(Looper.getMainLooper()).post {
            val audio = context.getSystemService(AudioManager::class.java) ?: return@post
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                notifications?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return@post
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 18)
                try {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 110)
                    Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 350)
                } catch (e: RuntimeException) {
                    tone.release()
                }
            }
        }
    }
}
