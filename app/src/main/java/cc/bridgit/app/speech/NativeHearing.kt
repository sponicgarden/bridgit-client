package cc.bridgit.app.speech

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One live on-device turn. Uses the guarded microphone lease, never a cloud recognizer. */
object NativeHearing {
    fun available(app: BridgeApp) = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
    suspend fun listen(app: BridgeApp, seconds: Int, language: String?, shortCommand: Boolean = false): String = withContext(Dispatchers.Main) {
        require(seconds in 1..30)
        if (!available(app)) throw ErrorCatalog.error("speech_unavailable", "No on-device recognizer is installed.")
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        val until = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
        val deadline = launch { delay(seconds * 1000L); recognizer.stopListening() }
        var readyReported = false
        var maxRms = -100f
        try {
            withTimeout((seconds + 5) * 1000L) {
            while (true) {
            val result = suspendCancellableCoroutine<String> { c ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(b: Bundle?) {
                        if (!readyReported && cc.bridgit.app.BuildConfig.DEBUG) android.util.Log.i("BridgitSpeech", "native_hearing_ready")
                        readyReported = true
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) { maxRms = maxOf(maxRms, v) }
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                    override fun onError(error: Int) { if (c.isActive) {
                        if (error in setOf(6,7)) c.resume("")
                        else c.resumeWithException(ErrorCatalog.error("speech_unavailable", "On-device hearing failed (Android error $error). Nothing was sent to cloud recognition."))
                    } }
                    override fun onResults(b: Bundle?) { if (c.isActive) {
                        val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        c.resume(text)
                    } }
                })
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .apply { if (!shortCommand) putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, seconds * 1000L) }
                    .apply { language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } })
            }
            if (result.isNotBlank()) return@withTimeout result
            if (android.os.SystemClock.elapsedRealtime() >= until) throw ErrorCatalog.error("speech_no_match", "No speech was recognized during the listening turn.")
            // Some engines end early on initial silence. Re-arm locally for the remaining requested window.
            delay(250)
            }
            @Suppress("UNREACHABLE_CODE") ""
            }
        } finally {
            if (cc.bridgit.app.BuildConfig.DEBUG) android.util.Log.i("BridgitSpeech", "native_hearing_end max_rms=$maxRms ready=$readyReported")
            deadline.cancel(); recognizer.cancel(); recognizer.destroy()
        }
    }
}
