package cc.bridgit.app.lan

import org.json.JSONArray
import org.json.JSONObject

/** Stable public hardware identifiers, checked against the manufacturer's model list. */
object DeviceModelNames {
    const val SOURCE = "https://support.apple.com/102869"
    val known = linkedMapOf(
        "Mac14,2" to "MacBook Air (M2, 2022)",
        "Mac14,15" to "MacBook Air (15-inch, M2, 2023)",
        "MacBookAir10,1" to "MacBook Air (M1, 2020)"
    )
    fun label(identifier: String) = known.entries.firstOrNull { it.key.equals(identifier, ignoreCase = true) }?.value ?: identifier
    fun json() = JSONArray(known.map { (id, name) -> JSONObject().put("identifier", id).put("name", name).put("brand", "apple").put("source", SOURCE) })
}
