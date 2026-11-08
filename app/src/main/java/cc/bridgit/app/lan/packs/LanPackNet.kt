package cc.bridgit.app.lan.packs

import cc.bridgit.app.core.SafeHttp
import cc.bridgit.app.lan.HostLan
import cc.bridgit.app.outbound.Outbound
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/** [PackEngine.Net] on the phone's one direct LAN: every request is re-checked against [lan] and [authorized]. */
class LanPackNet(private val lan: HostLan, private val authorized: () -> Unit) : PackEngine.Net {
    override fun locate(host: String, st: String): String? {
        authorized(); val target = lan.address(host)
        val text = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $st\r\n\r\n".toByteArray()
        val group = InetAddress.getByName("239.255.255.250")
        DatagramSocket(null).use { socket ->
            lan.bindDiscoverySocket(socket, group); socket.bind(InetSocketAddress(lan.local, 0)); socket.soTimeout = 250
            val started = System.currentTimeMillis()
            Outbound.check("udp://$host:1900", "DISCOVER", "udp")
            // Unicast first (UPnP 1.1), then the multicast group; only the enrolled host's answer counts.
            socket.send(DatagramPacket(text, text.size, target, 1900))
            socket.send(DatagramPacket(text, text.size, group, 1900))
            Outbound.record("udp://$host:1900", "DISCOVER", "udp", sentBytes = text.size * 2L, status = 0, startedAt = started)
            val until = System.nanoTime() + 2_500_000_000L
            var packets = 0
            while (System.nanoTime() < until && packets < 128) {
                authorized()
                val packet = DatagramPacket(ByteArray(4096), 4096)
                try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                packets++
                if (packet.address.hostAddress != host) continue
                val reply = String(packet.data, 0, packet.length, Charsets.UTF_8)
                reply.lineSequence().firstOrNull { it.startsWith("LOCATION:", true) }?.substringAfter(':')?.trim()?.let { return it }
            }
        }
        return null
    }

    override fun http(url: String, headers: Map<String, String>, body: ByteArray?): PackEngine.Reply {
        authorized()
        val host = URI(url).host ?: throw IllegalArgumentException()
        lan.check(host)
        val r = SafeHttp.localPack(url, headers, body, lan.network.socketFactory)
        return PackEngine.Reply(r.status, r.bytes ?: ByteArray(0))
    }
}
