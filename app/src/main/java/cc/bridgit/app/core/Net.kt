package cc.bridgit.app.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Tailnet address rules (ARC-04). Pure, unit-tested. */
object Net {
    /** True for 100.64.0.0/10 (IPv4) and fd7a:115c:a1e0::/48 (Tailscale IPv6). */
    fun isTailnet(ip: String?): Boolean {
        val s = ip?.trim()?.removePrefix("/")?.substringBefore('%')?.removeSurrounding("[", "]") ?: return false
        // Literal addresses only, so getByName never does a DNS lookup.
        val literal = if (':' in s) s.all { it.isDigit() || it in "abcdefABCDEF.:" } else s.all { it.isDigit() || it == '.' }
        if (s.isEmpty() || !literal) return false
        val a = runCatching { InetAddress.getByName(s) }.getOrNull() ?: return false
        val b = a.address
        return when (a) {
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            is Inet6Address -> (b[0].toInt() and 0xFF) == 0xfd && (b[1].toInt() and 0xFF) == 0x7a &&
                (b[2].toInt() and 0xFF) == 0x11 && (b[3].toInt() and 0xFF) == 0x5c &&
                (b[4].toInt() and 0xFF) == 0xa1 && (b[5].toInt() and 0xFF) == 0xe0
            else -> false
        }
    }

    fun isIpv6(ip: String) = ip.contains(':')

    /** One spelling per address, so the device lock matches whichever server path saw it: no "/", zone or brackets, lower case. */
    fun normalise(ip: String): String = ip.trim().removePrefix("/").filterNot { it == '[' || it == ']' }.substringBefore('%').lowercase().let { s ->
        if (':' in s) runCatching { InetAddress.getByName(s).hostAddress?.lowercase() }.getOrNull() ?: s else s
    }
}
