package cc.bridgit.app.lan

/** Fixed, reviewable discovery data shipped with the signed app. No downloaded code or arbitrary queries. */
object DiscoveryCatalog {
    const val VERSION = 1
    const val BATCH_SIZE = 6
    const val BATCH_MILLIS = 2000L
    data class Service(val type: String, val label: String, val group: String)
    val mdns = listOf(
        Service("_linkplay._tcp.", "WiiM / LinkPlay", "Audio & video"),
        Service("_googlecast._tcp.", "Google Cast", "Audio & video"),
        Service("_airplay._tcp.", "AirPlay", "Audio & video"),
        Service("_raop._tcp.", "AirPlay", "Audio & video"),
        Service("_spotify-connect._tcp.", "Spotify Connect", "Audio & video"),
        Service("_sonos._tcp.", "Sonos", "Audio & video"),
        Service("_rtsp._tcp.", "Camera stream", "Cameras"),
        Service("_androidtvremote2._tcp.", "Android TV remote", "Audio & video"),
        Service("_mediaremotetv._tcp.", "Apple TV remote", "Audio & video"),
        Service("_ipp._tcp.", "Network printing", "Printers"),
        Service("_ipps._tcp.", "Secure network printing", "Printers"),
        Service("_printer._tcp.", "Network printing", "Printers"),
        Service("_pdl-datastream._tcp.", "Direct printing", "Printers"),
        Service("_shelly._tcp.", "Shelly", "Smart home"),
        Service("_hue._tcp.", "Philips Hue", "Smart home"),
        Service("_hap._tcp.", "HomeKit", "Smart home"),
        Service("_matter._tcp.", "Matter", "Smart home"),
        Service("_matterc._udp.", "Matter setup", "Smart home"),
        Service("_home-assistant._tcp.", "Home Assistant", "Smart home"),
        Service("_esphomelib._tcp.", "ESPHome", "Smart home"),
        Service("_wled._tcp.", "WLED lighting", "Smart home"),
        Service("_mqtt._tcp.", "MQTT", "Smart home"),
        Service("_smb._tcp.", "File sharing", "Computers & storage"),
        Service("_adisk._tcp.", "Network storage", "Computers & storage"),
        Service("_ssh._tcp.", "Secure remote access", "Computers & storage"),
        Service("_sftp-ssh._tcp.", "Secure file transfer", "Computers & storage"),
        Service("_rfb._tcp.", "Screen sharing", "Computers & storage"),
        Service("_rdp._tcp.", "Remote desktop", "Computers & storage"),
        Service("_http._tcp.", "Web service", "Web services"),
        Service("_https._tcp.", "Secure web service", "Web services")
    )
    fun label(type: String): String? = mdns.firstOrNull { it.type.trimEnd('.') == type.trimEnd('.') }?.label
    fun summary(): String = mdns.groupBy { it.group }.entries.joinToString("\n\n") { (group, services) ->
        "$group\n${services.map { it.label }.distinct().joinToString(", ")}"
    } + "\n\nAlso: TP-Link / Tapo device discovery, UPnP / DLNA and router announcements, and ONVIF camera discovery."
}
