package cc.bridgit.app.lan

import cc.bridgit.app.outbound.Outbound
import java.util.concurrent.TimeUnit

/** One bounded ICMP echo to an existing on-link host; never scans or changes VPN policy. */
object DeviceRecheck {
    fun ping(lan: HostLan, host: String): Boolean {
        lan.check(host)
        val url = "icmp://$host"
        Outbound.check(url, "ECHO", "icmp")
        val started = System.currentTimeMillis()
        val process = ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "2",
            "-I", lan.local, host).redirectErrorStream(true).start()
        var replied = false
        try {
            replied = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
            return replied
        } finally {
            process.destroyForcibly()
            process.inputStream.close()
            Outbound.record(url, "ECHO", "icmp", status = if (replied) 200 else 0,
                startedAt = started, error = if (replied) null else "No ping reply")
        }
    }
}
