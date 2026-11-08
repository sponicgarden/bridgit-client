package cc.bridgit.app.lan

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** The phone can't reach [host] on its LAN; the message says what the owner can change. */
class LanUnreachable(message: String) : IOException(message)

/**
 * TCP sockets to one host on the phone's direct LAN. [bind] pins a socket to the LAN network and returns false
 * when an active VPN that can't be bypassed refused that (Tailscale on Android does: EPERM). The socket then
 * uses the OS default route, but only when [vpnLeavesHost] says the VPN doesn't route [host]; otherwise traffic
 * would go into the tailnet (a subnet route for the same private range at another site) and never reach the
 * device here, so it fails with a [LanUnreachable] naming the Tailscale setting instead.
 */
class LanSockets(private val host: String, private val bind: (Socket) -> Boolean, private val vpnLeavesHost: () -> Boolean) : SocketFactory() {
    override fun createSocket(): Socket {
        val s = Socket()
        try {
            if (!bind(s) && !vpnLeavesHost()) throw LanUnreachable(vpnRoutes(host))
        } catch (e: Exception) { runCatching { s.close() }; throw e }
        return s
    }
    override fun createSocket(host: String, port: Int): Socket = connected(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = connected(InetSocketAddress(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

    private fun connected(to: InetSocketAddress, from: InetSocketAddress? = null): Socket = createSocket().also { s ->
        try { from?.let(s::bind); s.connect(to) } catch (e: Exception) { runCatching { s.close() }; throw e }
    }

    companion object {
        fun vpnRoutes(host: String) = "Your VPN sends $host to another network, so Bridgit can't reach this device on your Wi-Fi " +
            "(a Tailscale subnet route covers the same addresses). In Tailscale: Settings → Subnet routing → turn off " +
            "Use Tailscale subnets while using this device. Tailscale can stay connected."
    }
}
