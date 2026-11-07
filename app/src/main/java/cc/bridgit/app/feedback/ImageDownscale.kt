package cc.bridgit.app.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/*
 * Ported from the Spotka app (sponic repo, apps/mobile/.../util/ImageDownscale.kt), FBK-07:
 * images in reports are at most 1600 px on the long edge and JPEG <= 1 MB.
 */

/**
 * Downscale a bitmap to <= [maxDim] on the long edge and JPEG-encode under
 * [maxBytes]. Used for the "last screen" capture, picked screenshots and agent images.
 */
fun bitmapToDownscaledJpeg(
    source: Bitmap,
    maxDim: Int = 1600,
    maxBytes: Int = 1024 * 1024,
): ByteArray? {
    return try {
        var bmp = source
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1))
        if (scale < 1f) {
            val nw = (bmp.width * scale).toInt().coerceAtLeast(1)
            val nh = (bmp.height * scale).toInt().coerceAtLeast(1)
            bmp = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        }
        var quality = 90
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            quality -= 15
        } while (bytes.size > maxBytes && quality >= 40)
        // Recycle only the intermediate we created; the caller owns [source].
        if (bmp !== source) bmp.recycle()
        bytes
    } catch (_: Exception) {
        null
    }
}

/**
 * Decode a picked image, downscale it to <= [maxDim] on the long edge, and
 * JPEG-encode under [maxBytes]. Runs off the main thread; returns null on any
 * failure.
 *
 * ImageDecoder handles the HEIC/AVIF picks common on Pixels.
 */
fun loadDownscaledJpeg(
    context: Context,
    uri: Uri,
    maxDim: Int = 1600,
    maxBytes: Int = 1024 * 1024,
): ByteArray? {
    val bmp = try {
        decodeBitmap(context, uri, maxDim)
    } catch (_: Exception) {
        null
    } ?: return null
    return try {
        bitmapToDownscaledJpeg(bmp, maxDim, maxBytes)
    } finally {
        if (!bmp.isRecycled) bmp.recycle()
    }
}

private fun decodeBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                if (longEdge > maxDim) {
                    // ImageDecoder requires a power-of-two sample size.
                    val ratio = longEdge.toFloat() / maxDim
                    var sample = 1
                    while (sample * 2 <= ratio) sample *= 2
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                }
            }
        } catch (_: Exception) {
            // Some vendor HEIC/AVIF decoders reject ImageDecoder sampling; use
            // the broadly compatible BitmapFactory route below before failing.
        }
    }
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longEdge / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (_: Exception) {
        null
    }
}

/** Decodes encoded image bytes (from an agent) and downscales them the same way. Null if not an image. */
fun bytesToDownscaledJpeg(bytes: ByteArray, maxDim: Int = 1600, maxBytes: Int = 1024 * 1024): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longEdge / sample > maxDim * 2) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return try { bitmapToDownscaledJpeg(bmp, maxDim, maxBytes) } finally { if (!bmp.isRecycled) bmp.recycle() }
}
