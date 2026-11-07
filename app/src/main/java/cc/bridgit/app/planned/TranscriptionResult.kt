package cc.bridgit.app.planned

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import cc.bridgit.app.api.ApiError

data class TranscriptionResult(val text: String?, val error: String? = null)
suspend fun transcribeKeepingAudio(returnAudio: Boolean, transcribe: suspend () -> String): TranscriptionResult = try {
    TranscriptionResult(transcribe())
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    if (e is CancellationException && e !is TimeoutCancellationException) throw e
    if (!returnAudio) throw e
    TranscriptionResult(null, if (e is ApiError) e.code else if (e is TimeoutCancellationException) "capture_timeout" else "transcription_failed")
}
