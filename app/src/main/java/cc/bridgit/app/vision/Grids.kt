package cc.bridgit.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Turns a photo into [FrameDiff]'s grid on the phone. */
object Grids {
    /** The photo shrunk to [FrameDiff.SIZE] x [FrameDiff.SIZE] brightness values (0-255), or null if it won't decode. */
    fun fromJpeg(bytes: ByteArray): IntArray? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val n = FrameDiff.SIZE
        val g = Bitmap.createScaledBitmap(small, n, n, true)
        val px = IntArray(n * n).also { g.getPixels(it, 0, n, 0, 0, n, n) }
        if (g !== small) g.recycle(); small.recycle()
        return IntArray(px.size) { val c = px[it]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
    }
}
