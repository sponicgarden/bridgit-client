package cc.bridgit.app.planned

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.*
import kotlinx.coroutines.*
import cc.bridgit.app.api.ErrorCatalog
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.os.Build
import android.annotation.SuppressLint

/** Transcribes a completed AAC clip through Android's explicitly on-device recognizer. No network fallback. Needs Android 13 (file audio source). */
@SuppressLint("NewApi")
object Transcriber {
    fun available(ctx: Context) = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    suspend fun transcribe(ctx: Context, bytes: ByteArray, language: String?): String {
        if (!available(ctx)) throw ErrorCatalog.error("not_supported", "No on-device speech recognizer is installed. Install an offline language model on the phone.")
        val source = File(ctx.cacheDir, "transcribe-${System.nanoTime()}.m4a")
        val pcm = File(ctx.cacheDir, "transcribe-${System.nanoTime()}.pcm")
        try {
            withContext(Dispatchers.IO) { source.writeBytes(bytes) }
            val format = withContext(Dispatchers.IO) { decode(source, pcm) }
            withContext(Dispatchers.IO) {
                val mono = cc.bridgit.app.speech.SpeechEngine.pcm16k(pcm.readBytes(), format.first, format.second, 121)
                pcm.writeBytes(mono)
            }
            return recognizePcm(ctx, pcm, 16000, 1, language)
        } finally { source.delete(); pcm.delete() }
    }
    suspend fun cloudWav(ctx: Context, bytes: ByteArray, seconds: Int): ByteArray {
        val source = File(ctx.cacheDir, "speech-${System.nanoTime()}.m4a")
        val pcm = File(ctx.cacheDir, "speech-${System.nanoTime()}.pcm")
        return withContext(Dispatchers.IO) { try {
            source.writeBytes(bytes)
            val (rate, channels) = decode(source, pcm)
            val decoded = pcm.readBytes()
            // AAC encoder padding and recorder scheduling can extend the final decoded frame.
            // Never upload more samples than the owner-authorized recording duration.
            val cap = rate.toLong() * channels * 2 * seconds.coerceIn(1,30)
            cc.bridgit.app.speech.SpeechEngine.wav(decoded.copyOf(minOf(decoded.size.toLong(), cap).toInt()), rate, channels)
        } finally { source.delete(); pcm.delete() } }
    }
    /** 16-bit PCM already on disk (wake words record PCM directly). Same on-device recogniser, same no-cloud rule. */
    suspend fun recognizePcm(ctx: Context, pcm: File, rate: Int, channels: Int, language: String?): String =
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT < 33) throw ErrorCatalog.error("not_supported", "File transcription needs Android 13 or newer.")
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            val fd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                withTimeout(45_000) { suspendCancellableCoroutine { c ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(b: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(b: Bundle?) {}
                        override fun onEvent(t: Int, b: Bundle?) {}
                        override fun onError(error: Int) { if (c.isActive) c.resumeWithException(ErrorCatalog.error("not_supported", "On-device recognizer refused file transcription (Android error $error). No audio was sent to a cloud recognizer.")) }
                        override fun onResults(b: Bundle?) { if (c.isActive) c.resume(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()) }
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fd).putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    language?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                    recognizer.startListening(intent)
                } }
            } finally { recognizer.cancel(); recognizer.destroy(); fd.close() }
        }
    private suspend fun decode(source: File, target: File): Pair<Int, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw ErrorCatalog.error("invalid_parameter", "No audio track.")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!); codec = decoder
            decoder.configure(input, null, null, 0); decoder.start()
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inputDone = false; var outputDone = false
            val info = MediaCodec.BufferInfo()
            withTimeout(30_000) { target.outputStream().use { out ->
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val i = decoder.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                            if (n < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val i = decoder.dequeueOutputBuffer(info, 10_000)
                    if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat; rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) throw ErrorCatalog.error("not_supported", "Recognizer requires 16-bit PCM.")
                    }
                    if (i >= 0) {
                        val buffer = decoder.getOutputBuffer(i)!!; buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size); buffer.get(data); out.write(data)
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(i, false)
                        if (target.length() > 24L * 1024 * 1024) throw ErrorCatalog.error("payload_too_large", "Decoded clip is too large.")
                    }
                }
            } }
            return rate to channels
        } finally { codec?.let { runCatching { it.stop() }; it.release() }; extractor.release() }
    }
}
