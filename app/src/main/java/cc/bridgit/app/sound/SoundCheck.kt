package cc.bridgit.app.sound

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.judgment.Jev
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64

/**
 * Asks Clef about one sound clip (the shared core of sound watching, see [SoundRules]): the caller's own typed
 * questions, the clip as `audio`. Only Clef takes audio. Throws [Jev.Failure]. [authorized] throws when access
 * changed while the answer was on its way.
 */
object SoundCheck {
    fun dataUrl(clip: ByteArray, mime: String = "audio/wav") = "data:$mime;base64," + Base64.getEncoder().encodeToString(clip)

    suspend fun ask(app: BridgeApp, clip: ByteArray, mime: String, questions: JSONObject, state: Any = "Listen to the attached short audio sample.",
                    applet: String? = null, authorized: () -> Unit = {}): Jev.Answer = withContext(Dispatchers.IO) {
        Jev.ask(app, state, questions, route = Jev.CLEF_ROUTE, audio = listOf(dataUrl(clip, mime)),
            applet = applet, authorized = authorized)
    }
}
