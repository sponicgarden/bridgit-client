package cc.bridgit.app.vision

import kotlin.math.sqrt

/**
 * The face embedding model the phone runs for the kitchen applet's People (watch option `people`), in one place.
 * AuraFace-v1 as int8 TFLite: input 1 x 112 x 112 x 3 (NHWC) float RGB, (x - 127.5) / 127.5, aligned to the
 * ArcFace 5-point template; output 512 floats, L2-normalised here. The model is not in the APK: [FaceEmbedder]
 * downloads it on demand from [URL] and uses it only when its size and SHA-256 match. Pure: no Android.
 */
object FaceModel {
    /** Sent with each embedding so the backend never compares vectors of different models. */
    const val ID = "auraface-v1"

    // ---- the file: where it comes from, its pinned hash and size (change together) ----
    // AuraFace-v1 by fal.ai (huggingface.co/fal/AuraFace-v1, Apache-2.0), int8 TFLite conversion by
    // eungsang/auraface-v1-tflite (Apache-2.0). Notice: assets/third-party-notices/auraface-v1.txt and NOTICE.
    /** TODO: placeholder until the model's hosting is set up. */
    const val URL = "https://bridgit-feedback.bridgit-app.workers.dev/v1/models/auraface-v1.tflite"
    const val SHA256 = "56c854e363b7ae99622deed87e27d95bda239b87dd145be565a71ed64d72ae46"
    const val SIZE_BYTES = 65_682_256L
    const val FILE_NAME = "auraface-v1.tflite"

    // ---- the tensors ----
    /** Input "data": float32 [1, 112, 112, 3] NHWC, RGB (not BGR). */
    const val INPUT_TENSOR = "data"
    /** Output "Identity": float32 [1, 512], not normalised. */
    const val OUTPUT_TENSOR = "Identity"
    const val INPUT = 112
    const val CHANNELS = 3
    const val MEAN = 127.5f
    const val SCALE = 127.5f
    const val DIM = 512

    /** Whether [SHA256] is a real pinned hash (a placeholder means: don't download). */
    fun pinned(sha: String = SHA256): Boolean = Regex("^[0-9a-f]{64}$").matches(sha)

    /** Wait before try [failures] + 1 to get the model: 1 min, doubling, at most 6 h. */
    fun backoffMs(failures: Int): Long = minOf(60_000L shl (failures - 1).coerceIn(0, 20), 6 * 3600_000L)

    /** One 0-255 channel value as the model wants it. */
    fun norm(v: Int): Float = (v - MEAN) / SCALE

    /** [v] scaled to length 1 (a zero vector stays zero). */
    fun l2(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x.toDouble() * x
        val n = sqrt(s)
        if (n == 0.0 || n.isNaN()) return v.copyOf()
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }
}
