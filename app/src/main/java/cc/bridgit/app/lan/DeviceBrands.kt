package cc.bridgit.app.lan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bundled, provenance-tracked manufacturer assets. Discovery never makes logo-service calls. */
class DeviceBrands(context: Context) {
    data class Brand(val id: String, val name: String, val pattern: Regex, val asset: String,
        val resource: String, val mime: String, val dark: Boolean, val source: String, val sha256: String) {
        val logoPath get() = "/v1/lan/brands/$id/logo?raw=1"
        fun json() = JSONObject().put("id", id).put("name", name).put("logo_url", logoPath)
            .put("mime", mime).put("background", if (dark) "dark" else "light").put("source", source).put("sha256", sha256)
    }
    val version: Int
    val all: List<Brand>
    init {
        val catalog = context.assets.open("device-brands/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        version = catalog.getInt("version")
        val rows = catalog.getJSONArray("brands")
        all = (0 until rows.length()).map { index ->
            val b = rows.getJSONObject(index)
            Brand(b.getString("id"), b.getString("name"), Regex(b.getString("pattern"), RegexOption.IGNORE_CASE),
                b.getString("asset"), b.getString("resource"), b.getString("mime"), b.optString("background") == "dark", b.getString("source"), b.getString("sha256"))
        }
    }
    fun get(id: String): Brand? = all.firstOrNull { it.id == id }
    fun find(device: NetworkDevices.Device): Brand? {
        val names = device.name + " " + NetworkDevicePresentation.describe(device).title + " " + device.modelHints.joinToString(" ")
        return all.firstOrNull { it.pattern.containsMatchIn(names) }
    }
    fun common(devices: List<NetworkDevices.Device>): List<Pair<Brand, Int>> = devices.mapNotNull(::find)
        .groupBy { it.id }.values.map { it.first() to it.size }
        .sortedWith(compareByDescending<Pair<Brand, Int>> { it.second }.thenBy { it.first.name })
    fun json() = JSONObject().put("version", version).put("brands", JSONArray(all.map { it.json() }))
    fun logo(context: Context, brand: Brand) = context.assets.open("device-brands/${brand.asset}").use { it.readBytes() }
}
