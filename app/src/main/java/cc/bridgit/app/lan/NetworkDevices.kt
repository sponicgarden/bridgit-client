package cc.bridgit.app.lan

import org.json.JSONArray

/** Presentation only: an observation never becomes an enrollment or a verified identity. */
object NetworkDevices {
    data class Device(val host: String, val name: String, val services: List<String>, val cameraCandidate: Boolean, val modelHints: List<String> = emptyList(), val connected: Boolean = true, val lastSeen: Long = 0L,
        /** The last scan found it (even if that was more than two minutes ago); false once a full scan missed it. */
        val present: Boolean = connected)

    fun cleanName(value: String): String = value.filter {
        !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt()
    }.trim().replace(Regex("^[0-9a-fA-F]{12}@"), "").take(100)

    fun serviceLabel(service: String): String = DiscoveryCatalog.label(service) ?: when {
        service == "tapo_camera" -> "Tapo camera"
        service == "tplink_router" -> "Router management"
        service == "tplink_smart_device" -> "Smart device"
        service.contains("_linkplay.") -> "WiiM / LinkPlay audio"
        service.contains("InternetGatewayDevice", true) -> "Router service"
        service.contains("_googlecast.") -> "Google Cast"
        service.contains("_spotify-connect.") -> "Spotify Connect"
        service.contains("_airplay.") -> "AirPlay"
        service.contains("_raop.") -> "AirPlay"
        service.contains("_sonos.") -> "Sonos"
        service.contains("_rtsp.") -> "Camera / video stream"
        service == "onvif_candidate" -> "ONVIF camera"
        service.contains("_shelly.") -> "Shelly smart device"
        service.contains("_ipp.") -> "Printer"
        service.contains("_http.") || service.contains("_https.") -> "Web service"
        service.contains("MediaRenderer", true) -> "Media player / TV"
        service.contains("MediaServer", true) -> "Media server"
        else -> "Network service"
    }

    fun devices(observations: JSONArray): List<Device> {
        val rows = (0 until minOf(observations.length(), 256)).mapNotNull { observations.optJSONObject(it) }
            .filter { LanRules.ipv4(it.optString("host")) != null }
        return rows.groupBy { it.getString("host") }.map { (host, entries) ->
            val names = entries.map { cleanName(it.optString("name")) }.filter { it.isNotBlank() }.distinct()
                .sortedWith(compareBy<String> { it.matches(Regex("[0-9a-fA-F]{8,}.*")) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it })
            val services = entries.map { cleanName(it.optString("service")) }.filter { it.isNotBlank() }.distinct().sorted()
            Device(host, names.firstOrNull() ?: host, services, entries.any {
                it.optString("service") == "tapo_camera" || it.optString("source") == "onvif" || it.optString("service").contains("_rtsp._tcp")
            }, entries.map { cleanName(it.optString("advertised_model")) }.filter { it.isNotBlank() }.distinct())
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
