package cc.bridgit.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import cc.bridgit.app.outbound.Outbound
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.java.TfLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.InterpreterApi
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Face embeddings on the phone ([FaceModel]): TFLite from Google Play services (only its small Java API is in the
 * APK), the model downloaded on demand into the no-backup files folder, only on an unmetered network, checked by
 * size and SHA-256 before use (deleted on mismatch), retried with backoff. Until it is [READY], callers send
 * sightings without an embedding. [ensure] is called only while a people watch runs.
 */
class FaceEmbedder(private val ctx: Context, private val log: (String, String) -> Unit) {
    @Volatile var state: String = NOT_READY; private set
    /** Why it isn't ready (plain words), or null. */
    @Volatile var reason: String? = null; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var interpreter: InterpreterApi? = null
    private val runLock = Any()
    @Volatile private var failures = 0
    @Volatile private var nextTryAt = 0L

    private val file get() = File(File(ctx.noBackupFilesDir, "models"), FaceModel.FILE_NAME)

    /** Gets the model ready in the background (download, check, load) unless it is, or is on its way, or waits for a retry. */
    @Synchronized fun ensure() {
        if (interpreter != null || job?.isActive == true || System.currentTimeMillis() < nextTryAt) return
        job = scope.launch { prepare() }
    }

    private suspend fun prepare() {
        try {
            if (!FaceModel.pinned()) { notReady("the model's hash isn't pinned in this build", 6 * 3600_000L); return }
            val f = file
            if (!verified(f)) {
                f.delete(); prefs.edit().remove(VERIFIED).apply()
                if (metered()) { notReady("waiting for Wi-Fi to download the face model (66 MB)", 15 * 60_000L); return }
                state = DOWNLOADING; reason = null
                download(f)
            }
            Tasks.await(TfLite.initialize(ctx), 60, TimeUnit.SECONDS)
            val i = InterpreterApi.create(f, InterpreterApi.Options().setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY))
            val inShape = i.getInputTensor(0).shape(); val outShape = i.getOutputTensor(0).shape()
            if (!inShape.contentEquals(intArrayOf(1, FaceModel.INPUT, FaceModel.INPUT, FaceModel.CHANNELS)) || outShape.last() != FaceModel.DIM) {
                i.close(); throw IllegalStateException("unexpected tensor shapes ${inShape.toList()} -> ${outShape.toList()}")
            }
            interpreter = i
            failures = 0; state = READY; reason = null
            log("diagnostic", "Face model ${FaceModel.ID} ready")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            failures++
            notReady("couldn't get the face model ready (${e.message ?: e.javaClass.simpleName})", FaceModel.backoffMs(failures))
            log("warn", "Face model not ready (try $failures): ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun notReady(why: String, retryInMs: Long) {
        state = NOT_READY; reason = why; nextTryAt = System.currentTimeMillis() + retryInMs
    }

    private fun metered(): Boolean = runCatching { ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    private suspend fun download(target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            Outbound.call(FaceModel.URL, hint = Outbound.Hint("Bridgit team backend", "Download the on-phone face model (${FaceModel.ID}) for the kitchen applet's People", listOf(cc.bridgit.app.outbound.Categories.NONE))) { meter ->
                val conn = URL(FaceModel.URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                meter.status = conn.responseCode; meter.receivedType = conn.contentType
                if (conn.responseCode != 200) throw IllegalStateException("the model server answered ${conn.responseCode}")
                var done = 0L
                try {
                    conn.inputStream.use { input -> part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                            if (done > FaceModel.SIZE_BYTES) throw IllegalStateException("the download is larger than expected")
                        }
                    } }
                } finally { meter.receivedBytes = done }
            }
            currentCoroutineContext().ensureActive()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != FaceModel.SIZE_BYTES || hash != FaceModel.SHA256) throw IllegalStateException("the download didn't match the expected model, so it wasn't used")
            if (!part.renameTo(target)) throw IllegalStateException("couldn't save the model")
            remember(target)
        } finally { part.delete() }
    }

    private val prefs get() = ctx.getSharedPreferences("face_model", Context.MODE_PRIVATE)

    /** The file's size and time once its hash was checked, so the 66 MB file is hashed once, not on every start. */
    private fun stamp(f: File) = "${FaceModel.SHA256}:${f.length()}:${f.lastModified()}"
    private fun remember(f: File) { prefs.edit().putString(VERIFIED, stamp(f)).apply() }

    /** Whether [f] is the pinned model: checked by hash once, then by size and modification time. */
    private fun verified(f: File): Boolean {
        if (!f.exists() || f.length() != FaceModel.SIZE_BYTES) return false
        if (prefs.getString(VERIFIED, null) == stamp(f)) return true
        if (sha256(f) != FaceModel.SHA256) return false
        remember(f); return true
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(256 * 1024); var n: Int; while (input.read(buf).also { n = it } >= 0) d.update(buf, 0, n) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** The L2-normalised embedding of an aligned [FaceModel.INPUT]-px face, or null while the model isn't ready. */
    fun embed(aligned: Bitmap): FloatArray? {
        val i = interpreter ?: return null
        val n = FaceModel.INPUT
        val px = IntArray(n * n)
        val bmp = if (aligned.width == n && aligned.height == n) aligned else Bitmap.createScaledBitmap(aligned, n, n, true)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        val input = ByteBuffer.allocateDirect(4 * n * n * FaceModel.CHANNELS).order(ByteOrder.nativeOrder())
        for (p in px) { // RGB order, not BGR
            input.putFloat(FaceModel.norm((p shr 16) and 0xff)); input.putFloat(FaceModel.norm((p shr 8) and 0xff)); input.putFloat(FaceModel.norm(p and 0xff))
        }
        input.rewind()
        val out = arrayOf(FloatArray(FaceModel.DIM))
        synchronized(runLock) { i.run(input, out) }
        return FaceModel.l2(out[0])
    }

    companion object {
        const val READY = "ready"
        const val DOWNLOADING = "downloading"
        const val NOT_READY = "not_ready"
        private const val VERIFIED = "verified"
    }
}
