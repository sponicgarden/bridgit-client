package cc.bridgit.app.ui.tour

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Build

/**
 * The tour's Ask button (8.25 step 4): Android's speech recognizer, on the phone when it can
 * (createOnDeviceSpeechRecognizer), else the default one. Main thread only.
 * [onPartial] gets text while listening; [onDone] gets the final text, or null if nothing was heard.
 */
class TourSpeech(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onDone: (String?) -> Unit,
) : RecognitionListener {
    private var sr: SpeechRecognizer? = null
    private var onDevice = false
    /** Set when the on-device recognizer can't do this language; the default one is used from then on. */
    private var skipOnDevice = false
    private var lastPartial = ""
    var listening = false; private set

    fun available(): Boolean = (!skipOnDevice && Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)) ||
        SpeechRecognizer.isRecognitionAvailable(ctx)

    private fun recognizer(): SpeechRecognizer? {
        sr?.let { return it }
        var r: SpeechRecognizer? = null
        if (!skipOnDevice && Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)) {
            r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrNull()
            onDevice = r != null
        }
        if (r == null && SpeechRecognizer.isRecognitionAvailable(ctx)) {
            r = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull()
            onDevice = false
        }
        r?.setRecognitionListener(this)
        sr = r
        return r
    }

    /** Starts listening; false if no recognizer is available. */
    fun start(): Boolean {
        val r = recognizer() ?: return false
        lastPartial = ""
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        return runCatching { r.startListening(i); listening = true }.isSuccess
    }

    /** Stops listening; the result arrives in onDone. */
    fun stop() { if (listening) runCatching { sr?.stopListening() } }

    fun destroy() {
        listening = false
        runCatching { sr?.destroy() }
        sr = null
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (t.isNotEmpty()) { lastPartial = t; onPartial(t) }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        onDone(t.ifEmpty { lastPartial }.ifEmpty { null })
    }

    override fun onError(error: Int) {
        listening = false
        if (onDevice && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            skipOnDevice = true
            destroy()
        }
        onDone(lastPartial.ifEmpty { null })
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
