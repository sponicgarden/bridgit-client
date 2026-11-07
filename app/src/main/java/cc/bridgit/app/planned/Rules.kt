package cc.bridgit.app.planned

import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONObject

object Rules {
    /** BitmapFactory treats zero as one internally; callers must never divide by its zero default. */
    fun imageSampleSize(width: Int, height: Int, maxEdge: Int = 4096): Int {
        require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000 && maxEdge > 0)
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > maxEdge || (height.toLong() + sample - 1) / sample > maxEdge) sample *= 2
        return sample
    }

    fun leaseBaseline(current: Int, previousBefore: Int?, previousApplied: Int?): Int =
        if (previousBefore != null && current == previousApplied) previousBefore else current

    fun installable(name: String, mime: String) = mime.equals("application/vnd.android.package-archive", true) ||
        name.substringAfterLast('.', "").lowercase() in setOf("apk", "apks", "xapk", "aab")

    fun transferFits(totalBytes: Long, count: Int, remoteBytes: Long, remoteCount: Int, agentBytes: Long, agentCount: Int, incoming: Long, remote: Boolean): Boolean =
        incoming <= 25L * 1024 * 1024 && totalBytes + incoming <= 100L * 1024 * 1024 && count < 100 &&
            (!remote || (remoteBytes + incoming <= 50L * 1024 * 1024 && remoteCount < 50 && agentBytes + incoming <= 25L * 1024 * 1024 && agentCount < 25))

    fun integer(value: Any?, name: String, min: Int, max: Int, default: Int? = null): Int {
        if (value == null && default != null) return default
        val n = when (value) { is Number -> value.toDouble(); is String -> value.toDoubleOrNull(); else -> null }
        if (n == null || !n.isFinite() || n % 1.0 != 0.0 || n < min || n > max)
            throw ErrorCatalog.error("invalid_parameter", "$name must be a whole number from $min to $max.")
        return n.toInt()
    }
    fun number(value: Any?, name: String, min: Double, max: Double): Double {
        val n = (value as? Number)?.toDouble()
        if (n == null || !n.isFinite() || n !in min..max) throw ErrorCatalog.error("invalid_parameter", "$name must be $min to $max.")
        return n
    }
    fun bool(b: JSONObject, name: String, default: Boolean): Boolean =
        if (!b.has(name)) default else b.opt(name) as? Boolean ?: throw ErrorCatalog.error("invalid_parameter", "$name must be true or false.")
    fun name(value: String): String {
        if (value.isBlank() || value.length > 120 || value == "." || value == ".." || value.any { it == '/' || it == '\\' || it.isISOControl() })
            throw ErrorCatalog.error("invalid_parameter", "name must be a filename, 1–120 characters, without path separators.")
        return value
    }
}
