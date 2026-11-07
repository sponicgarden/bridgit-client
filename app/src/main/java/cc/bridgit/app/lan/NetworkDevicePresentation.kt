package cc.bridgit.app.lan

/** Local hints for humans. This never changes enrollment, supported adapters or authorization. */
object NetworkDevicePresentation {
    enum class Kind(val label: String) {
        CAMERA("Camera"), TV("TV"), SPEAKER("Speaker"), AUDIO("Audio device"),
        COMPUTER("Computer"), MOBILE("Phone or tablet"), PRINTER("Printer"),
        ROUTER("Router"), SMART("Smart device"), CAST("Cast device"), UNKNOWN("Network device")
    }
    enum class Category(val title: String, val expandedByDefault: Boolean = true) {
        CAMERAS("Cameras"), MEDIA("TV & video"), AUDIO("Audio"),
        NETWORK("Routers & switches"), PRINTERS("Printers"), SMART("Smart home"),
        COMPUTERS("Computers", false), MOBILE("Phones & tablets", false), OTHER("Other devices", false)
    }
    data class Group(val category: Category, val devices: List<NetworkDevices.Device>)
    fun category(kind: Kind): Category = when (kind) {
        Kind.CAMERA -> Category.CAMERAS
        Kind.TV, Kind.CAST -> Category.MEDIA
        Kind.SPEAKER, Kind.AUDIO -> Category.AUDIO
        Kind.ROUTER -> Category.NETWORK
        Kind.PRINTER -> Category.PRINTERS
        Kind.SMART -> Category.SMART
        Kind.COMPUTER -> Category.COMPUTERS
        Kind.MOBILE -> Category.MOBILE
        Kind.UNKNOWN -> Category.OTHER
    }
    /** Groups for the list. Within a group: added to Bridgit first, then ones Bridgit can link to, then reachable. */
    fun groups(devices: List<NetworkDevices.Device>, added: Set<String> = emptySet()): List<Group> = devices.groupBy { category(describe(it).kind) }
        .entries.sortedBy { it.key.ordinal }.map { (category, list) -> Group(category,
            list.sortedWith(compareBy<NetworkDevices.Device> { linkRank(it, added) }.thenBy { !it.present }.thenBy { !it.connected }.thenBy { remotePriority(it) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { describe(it).title }.thenBy { it.host })) }

    /** 0 = added to Bridgit, 1 = Bridgit can link to it, 2 = everything else. */
    fun linkRank(device: NetworkDevices.Device, added: Set<String>): Int = when {
        device.host in added -> 0
        DeviceInterfaces.available(device) != null -> 1
        else -> 2
    }

    data class Info(val title: String, val kind: Kind, val model: String = "", val identifier: String = "")
    private val vendors = Regex("(?i)WiiM|Denon|Sonos|HomePod|Hikvision|Dahua|Reolink|Tapo|Eufy|Amcrest|Axis|MacBook|iMac|Chromecast|Bravia|Samsung|Bose|JBL")
    private val cameraBrand = Regex("(?i)\\b(Hikvision|Dahua|Reolink|Tapo|Eufy|Amcrest|Axis)\\b")
    private val serial = Regex("\\s[-–]\\s([A-Za-z0-9]{7,})$")

    fun describe(device: NetworkDevices.Device): Info {
        var name = NetworkDevices.cleanName(device.name)
        val identifiers = mutableListOf<String>()
        vendors.find(name)?.let { match ->
            val prefix = name.take(match.range.first)
            if (prefix.length >= 8 && prefix.all { it in "0123456789abcdefABCDEF" }) {
                identifiers.add(prefix); name = name.substring(match.range.first)
            }
        }
        val kind = classify(name, device)
        val brand = cameraBrand.find(name)?.value
        if (kind == Kind.CAMERA && brand != null) {
            serial.find(name)?.let { match -> identifiers.add(match.groupValues[1]); name = name.removeRange(match.range) }
            val model = name.replaceFirst(Regex("(?i)\\b${Regex.escape(brand)}\\b"), "").trim(' ', '-', '_')
            val title = if (brand.equals("Tapo", true) && model.matches(Regex("C[0-9A-Za-z-]{2,10}"))) "Tapo $model"
                else brand.lowercase().replaceFirstChar { it.uppercase() } + " camera"
            return Info(title, kind, model, identifiers.joinToString(" · "))
        }
        val looksLikeId = name.matches(Regex("(?i)[0-9a-f:_-]{12,}")) ||
            (name.length >= 16 && name.none(Char::isWhitespace) && name.count(Char::isDigit) >= name.length / 3)
        if (looksLikeId) { identifiers.add(name); name = "" }
        if (name == device.host) name = ""
        return Info(name.ifBlank { kind.label }, kind, model = device.modelHints.firstOrNull().orEmpty(), identifier = identifiers.joinToString(" · "))
    }

    fun remotePriority(device: NetworkDevices.Device): Int = when (describe(device).kind) {
        Kind.CAMERA -> if (device.cameraCandidate) 0 else 1
        Kind.TV, Kind.CAST -> 2
        Kind.SPEAKER, Kind.AUDIO -> 3
        Kind.ROUTER -> 4
        Kind.PRINTER -> 5
        Kind.SMART -> 6
        Kind.COMPUTER -> 7
        Kind.MOBILE -> 8
        Kind.UNKNOWN -> 9
    }

    private fun classify(name: String, device: NetworkDevices.Device): Kind {
        val services = device.services.joinToString(" ").lowercase()
        val n = (name + " " + device.modelHints.joinToString(" ")).lowercase()
        fun named(pattern: String) = Regex(pattern).containsMatchIn(n)
        return when {
            "tplink_smart_device" in services -> Kind.SMART
            "tplink_router" in services -> Kind.ROUTER
            "_androidtvremote2." in services || "_mediaremotetv." in services -> Kind.TV
            device.cameraCandidate || cameraBrand.containsMatchIn(name) || named("\\b(camera|webcam)\\b") -> Kind.CAMERA
            named("macbook|imac|mac studio|mac mini|macmini|macpro|thinkpad|laptop|desktop|windows pc|\\bmac[0-9]+,[0-9]+\\b") -> Kind.COMPUTER
            named("iphone|ipad|pixel|galaxy") || named("android") && !named("\\btv\\b") -> Kind.MOBILE
            listOf("_ipp.", "_ipps.", "_printer.", "_pdl-datastream.").any { it in services } || named("\\b(printer|epson|brother)\\b") -> Kind.PRINTER
            named("wiim|denon|sonos|homepod|\\bspeaker\\b|\\bbose\\b|\\bjbl\\b") -> Kind.SPEAKER
            "_androidtvremote2." in services || "_mediaremotetv." in services || named("\\btv\\b|television|chromecast|bravia|roku") -> Kind.TV
            named("\\brouter\\b|openwrt|fritz!box|dream machine|tp-link deco") || "tplink_router" in services || "internetgatewaydevice" in services -> Kind.ROUTER
            // Switches and access points count with routers (owner, 4 Nov 2026: "Routers & switches"); a Nintendo Switch does not.
            named("\\bswitch\\b|\\busw-|\\btl-sg|\\bgs[0-9]{3}|access point|\\beap[0-9]{3}") && !named("nintendo") -> Kind.ROUTER
            listOf("tplink_smart_device", "_shelly.", "_hue.", "_hap.", "_matter.", "_matterc.", "_home-assistant.", "_esphomelib.", "_wled.", "_mqtt.").any { it in services } || named("\\bshelly\\b") -> Kind.SMART
            "_googlecast." in services -> Kind.CAST
            listOf("_sonos.", "_linkplay.").any { it in services } -> Kind.AUDIO
            listOf("_smb.", "_adisk.", "_ssh.", "_sftp-ssh.", "_rfb.", "_rdp.").any { it in services } -> Kind.COMPUTER
            listOf("_airplay.", "_raop.", "_spotify-connect.").any { it in services } -> Kind.AUDIO
            else -> Kind.UNKNOWN
        }
    }
}
