package cc.bridgit.app.lan

/** Literal RFC1918 IPv4 only. No DNS, URL, VPN, loopback or routed targets. */
object LanRules {
    fun ipv4(host: String): Long? {
        val p = host.split('.')
        if (p.size != 4 || p.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() > 255 }) return null
        val n = p.map { it.toLong() }
        if (!(n[0] == 10L || n[0] == 172L && n[1] in 16L..31L || n[0] == 192L && n[1] == 168L)) return null
        return n.fold(0L) { a, b -> (a shl 8) or b }
    }
    fun host(n: Long) = (3 downTo 0).joinToString(".") { ((n ushr (it * 8)) and 255).toString() }
    fun onLink(host: String, local: String, prefix: Int): Boolean {
        val h = ipv4(host) ?: return false; val l = ipv4(local) ?: return false
        if (prefix !in 1..30 || h == l) return false
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val base = l and mask; val end = base or (mask xor 0xffffffffL)
        return h > base && h < end
    }
    fun boundedHosts(local: String, prefix: Int): List<String> {
        require(prefix in 24..30) { "Subnet inventory supports /24 through /30 only; supply explicit hosts on larger networks." }
        val n = requireNotNull(ipv4(local)); val mask = (0xffffffffL shl (32-prefix)) and 0xffffffffL
        return ((n and mask)+1 until ((n and mask) or (mask xor 0xffffffffL))).filter { it != n }.map(::host)
    }
    val adapters = setOf("shelly_gen1", "openwrt_ubus", "tapo_onvif")
    fun actions(adapter: String): List<String> = when(adapter) {
        "shelly_gen1" -> listOf("status", "relay_on", "relay_off")
        "openwrt_ubus" -> listOf("status", "dhcp_leases")
        "tapo_onvif" -> listOf("status") // capture/PTZ remain exclusively under lan_camera
        else -> emptyList()
    }
    fun validateAction(adapter: String, action: String, channel: Int) {
        require(action in actions(adapter)) { "Unsupported action for this adapter." }
        require(channel in 0..3) { "channel must be 0..3." }
    }
}
