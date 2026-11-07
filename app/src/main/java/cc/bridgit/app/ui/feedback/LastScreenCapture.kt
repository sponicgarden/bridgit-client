package cc.bridgit.app.ui.feedback

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import cc.bridgit.app.R
import cc.bridgit.app.feedback.ReportKind
import cc.bridgit.app.feedback.bitmapToDownscaledJpeg
import kotlin.coroutines.resume
import kotlin.math.max

/*
 * FBK-02 "last screen". Ported from the Spotka app (sponic repo, apps/mobile/.../util/
 * LastScreenCapture.kt and ScreenshotCapture.kt) from Compose to Views: PixelCopy of our
 * own Activity window, no MediaProjection.
 */

/**
 * Quality gate and selection for the "last screen" JPEG (Spotka, near as-is).
 * Production uses [selectLastScreen]: settled primary, then an immediate retry.
 */
object LastScreenCapture {

    /** Minimum encoded size for a non-empty full-screen JPEG. */
    const val MIN_USABLE_JPEG_BYTES: Int = 2_048

    /** Minimum luminance variance (0–255 scale, 8×8 sample grid) for a real screen rather than a flat frame. */
    const val MIN_USABLE_LUMINANCE_VARIANCE: Double = 18.0

    /** How long to let the overflow menu (or a sheet) finish closing before the shot. */
    const val SETTLE_MS: Long = 250L

    data class QualityMetrics(val byteCount: Int, val width: Int, val height: Int, val luminanceVariance: Double)

    /** Whether a JPEG is good enough to attach as "last screen". */
    fun isUsableLastScreenJpeg(data: ByteArray?): Boolean {
        if (data == null || data.size < MIN_USABLE_JPEG_BYTES) return false
        val metrics = qualityMetrics(data) ?: return false
        return metrics.width >= 8 && metrics.height >= 8 && metrics.luminanceVariance >= MIN_USABLE_LUMINANCE_VARIANCE
    }

    /**
     * Prefer the deterministic early capture; replace it only when early fails the gate
     * and later passes. Kept from Spotka for its tests; [selectLastScreen] is what runs.
     */
    fun preferEarlyCapture(
        early: ByteArray?,
        later: ByteArray?,
        isUsable: (ByteArray?) -> Boolean = ::isUsableLastScreenJpeg,
    ): ByteArray? {
        if (isUsable(early)) return early
        if (isUsable(later)) return later
        return early ?: later
    }

    /** Priority: clean settled frame, immediate retry, tap-time salvage. */
    fun selectLastScreen(
        primary: ByteArray?,
        retry: ByteArray?,
        tapTime: ByteArray?,
        isUsable: (ByteArray?) -> Boolean = ::isUsableLastScreenJpeg,
    ): ByteArray? = when {
        isUsable(primary) -> primary
        isUsable(retry) -> retry
        isUsable(tapTime) -> tapTime
        else -> primary ?: retry ?: tapTime
    }

    /**
     * Bridgit differs from Spotka here: FBK-02 says a blank image is rejected, so when no
     * candidate passes the gate the form opens without a last screen.
     */
    fun selectUsable(
        primary: ByteArray?,
        retry: ByteArray?,
        isUsable: (ByteArray?) -> Boolean = ::isUsableLastScreenJpeg,
    ): ByteArray? = selectLastScreen(primary, retry, null, isUsable)?.takeIf(isUsable)

    fun qualityMetrics(data: ByteArray): QualityMetrics? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode a small sample for variance without loading a full-res buffer.
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / sample > 48) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
        return try {
            QualityMetrics(data.size, bmp.width, bmp.height, luminanceVariance(bmp))
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    /** Sample an 8×8 grid; return luminance variance in 0–255 units². */
    fun luminanceVariance(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0.0
        val steps = 8
        var sum = 0.0; var sumSq = 0.0; var count = 0.0
        for (gy in 0 until steps) {
            val y = minOf(height - 1, (gy * (height - 1)) / maxOf(steps - 1, 1))
            for (gx in 0 until steps) {
                val x = minOf(width - 1, (gx * (width - 1)) / maxOf(steps - 1, 1))
                val c = bitmap.getPixel(x, y)
                val yLum = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                sum += yLum; sumSq += yLum * yLum; count += 1
            }
        }
        if (count <= 1) return 0.0
        val mean = sum / count
        return maxOf(0.0, (sumSq / count) - (mean * mean))
    }

    /** True when the window must never be captured (setup prompt, pair code: AGT-08). */
    fun isSecure(activity: Activity): Boolean =
        (activity.window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
}

/**
 * Hands the capture from [openReport] to the form without a Bundle (a 1 MB JPEG
 * would blow the transaction limit). The form takes it once.
 */
object ReportCaptureHolder {
    private var jpeg: ByteArray? = null

    @Synchronized fun put(bytes: ByteArray?) { jpeg = bytes }
    @Synchronized fun take(): ByteArray? = jpeg.also { jpeg = null }
}

/** Synchronous double-tap guard: the capture is async. */
private var launching = false

/**
 * FBK-01/FBK-02: capture the screen [fragment] shows, then open the report form.
 * [screen] is the screen name sent in the context (FBK-06); [screenContext] a short
 * hint such as the agent or the filters; [logEntryId] attaches that log entry.
 * Never captures a FLAG_SECURE window. Navigation happens in `finally`, so a capture
 * failure never leaves the menu item dead.
 */
fun openReport(
    fragment: Fragment,
    kind: ReportKind,
    screen: String,
    screenContext: String? = null,
    logEntryId: Long = -1,
) {
    if (launching || !fragment.isAdded || fragment.view == null) return
    val activity = fragment.requireActivity()
    val owner = fragment.viewLifecycleOwner
    launching = true
    owner.lifecycleScope.launch {
        var jpeg: ByteArray? = null
        try {
            if (!LastScreenCapture.isSecure(activity)) {
                delay(LastScreenCapture.SETTLE_MS)
                val primary = runCatching { captureWindowJpeg(activity) }.getOrNull()
                val retry = if (!withContext(Dispatchers.Default) { LastScreenCapture.isUsableLastScreenJpeg(primary) })
                    runCatching { captureWindowJpeg(activity) }.getOrNull() else null
                jpeg = withContext(Dispatchers.Default) { LastScreenCapture.selectUsable(primary, retry) }
            }
        } catch (_: Throwable) {
            jpeg = null
        } finally {
            launching = false
            // Skip if the owner left the screen while we waited (the scope was cancelled).
            if (isActive && fragment.isAdded) runCatching {
                ReportCaptureHolder.put(jpeg)
                fragment.findNavController().navigate(R.id.reportForm, bundleOf(
                    "kind" to kind.id, "screen" to screen, "screenContext" to screenContext, "logEntryId" to logEntryId))
            }.onFailure { ReportCaptureHolder.put(null) }
        }
    }
}

/** The Activity window as a downscaled JPEG (FBK-07 limits), or null. Spotka `captureWindowJpeg`. */
suspend fun captureWindowJpeg(activity: Activity): ByteArray? = withContext(Dispatchers.Main) {
    val window = activity.window ?: return@withContext null
    val view = window.decorView
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return@withContext null
    val bitmap = try { pixelCopyBitmap(window, view, width, height) } catch (_: Exception) { null } ?: return@withContext null
    withContext(Dispatchers.IO) {
        try { bitmapToDownscaledJpeg(bitmap) } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }
}

private suspend fun pixelCopyBitmap(window: Window, view: View, width: Int, height: Int): Bitmap? =
    suspendCancellableCoroutine { cont ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (cont.isActive) {
                    if (result == PixelCopy.SUCCESS) cont.resume(bitmap)
                    else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        // Software fallback if PixelCopy fails (some OEM surfaces).
                        cont.resume(drawViewBitmap(view, width, height))
                    }
                } else if (!bitmap.isRecycled) bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            if (cont.isActive) cont.resume(drawViewBitmap(view, width, height))
        }
        cont.invokeOnCancellation { if (!bitmap.isRecycled) bitmap.recycle() }
    }

private fun drawViewBitmap(view: View, width: Int, height: Int): Bitmap? = try {
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
} catch (_: Exception) {
    null
}
