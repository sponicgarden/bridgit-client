package cc.bridgit.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import cc.bridgit.app.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code for [payload], [size] px square, in the app's QR colours. Used by Add agent and Bridgit Portal. */
fun qrBitmap(context: Context, payload: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
    val dark = ContextCompat.getColor(context, R.color.qr_dark)
    val light = ContextCompat.getColor(context, R.color.qr_light)
    val px = IntArray(m.width * m.height)
    for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m.get(x, y)) dark else light
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}
