package cc.bridgit.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject

/** Uploaded PNGs stay on the device. No remote URLs, SVGs or external image loaders. */
object AgentBranding {
    const val MAX_ASSET_BYTES = 128 * 1024
    const val MAX_BODY_BYTES = 384 * 1024
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun asset(body: JSONObject, key: String, previous: String?): String? {
        if (!body.has(key)) return previous
        if (body.isNull(key)) return null
        val value = body.opt(key) as? String
            ?: throw ApiError(422, "invalid_parameter", "$key must be base64 PNG data or null")
        if (value.length > ((MAX_ASSET_BYTES + 2) / 3) * 4)
            throw ApiError(413, "payload_too_large", "$key exceeds 128 KiB")
        if (value.isEmpty() || !Regex("^[A-Za-z0-9+/]*={0,2}$").matches(value))
            throw ApiError(422, "invalid_parameter", "$key must contain standard base64 without a data URL prefix")
        val bytes = try { Base64.decode(value, Base64.NO_WRAP) } catch (_: IllegalArgumentException) {
            throw ApiError(422, "invalid_parameter", "$key contains invalid base64")
        }
        if (bytes.size > MAX_ASSET_BYTES)
            throw ApiError(413, "payload_too_large", "$key exceeds 128 KiB")
        if (!bytes.take(8).toByteArray().contentEquals(pngSignature))
            throw ApiError(422, "invalid_parameter", "$key must be a PNG")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxWidth = if (key == "icon_base64") 512 else 2048
        if (bounds.outMimeType != "image/png" || bounds.outWidth !in 1..maxWidth || bounds.outHeight !in 1..512)
            throw ApiError(422, "invalid_parameter", "$key must be at most ${maxWidth}x512 pixels")
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw ApiError(422, "invalid_parameter", "$key is not a decodable PNG")
        decoded.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun bitmap(value: String?): Bitmap? = value?.let {
        runCatching {
            val bytes = Base64.decode(it, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    fun json(agent: Policy.Agent): JSONObject = JSONObject()
        .put("id", agent.id).put("name", agent.name)
        .put("icon_base64", agent.iconBase64 ?: JSONObject.NULL)
        .put("wordmark_base64", agent.wordmarkBase64 ?: JSONObject.NULL)
}
