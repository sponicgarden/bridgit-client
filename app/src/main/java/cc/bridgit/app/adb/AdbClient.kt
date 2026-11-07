package cc.bridgit.app.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * ADB setup (8.27): Bridgit is an ADB client of this device's own adbd, the way Shizuku and LADB are.
 * Wireless debugging pairs once (TLS, 6-digit code); after that Bridgit connects on 127.0.0.1 and runs
 * commands as uid shell. A legacy `adb tcpip` port (5555) also works, after the owner allows Bridgit's key.
 * The key and certificate live in noBackupFilesDir and never leave the phone.
 */
class AdbClient(private val app: BridgeApp) {
    private val dir = File(app.noBackupFilesDir, "adb").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("adb", Context.MODE_PRIVATE)
    private val lock = Mutex()
    // libadb makes its own Conscrypt provider for TLS 1.3 and pairing; nothing is installed app-wide,
    // so Bridgit's other HTTPS (callbacks, updates, crash reports) keeps Android's default provider.
    @Volatile private var started = false
    private val manager: Manager by lazy {
        started = true
        Manager().apply { setApi(Build.VERSION.SDK_INT); setTimeout(10, TimeUnit.SECONDS) }
    }

    /** True once a pairing or an allowed TCP connection has worked; Wireless debugging remembers the key. */
    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        private set(v) = prefs.edit().putBoolean("paired", v).apply()

    val connected: Boolean get() = started && runCatching { manager.isConnected }.getOrDefault(false)

    data class Result(val exitCode: Int?, val output: String, val truncated: Boolean, val timedOut: Boolean)

    /** Pairs with Settings → Wireless debugging → Pair device with pairing code. */
    suspend fun pair(port: Int, code: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val ok = manager.pair("127.0.0.1", port, code.trim())
            if (!ok) throw IllegalStateException("Pairing was refused. Check the code and try again.")
            paired = true
            prefs.edit().remove(SuperPowers.REVOKED).apply() // the owner set ADB up again
            app.log.system("audit", "setting", "ADB: Bridgit paired with Wireless debugging")
        }
    }

    /** Connects if needed. Throws [AdbPairingRequiredException] when Wireless debugging doesn't know Bridgit yet. */
    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (manager.isConnected) return@withLock
            // With WRITE_SECURE_SETTINGS (granted during setup) Bridgit turns Wireless debugging back on by itself,
            // for example after a reboot. Android only allows it on Wi-Fi.
            // Never after Revoke Super Powers: only a new pairing by the owner brings ADB back.
            if (!wirelessDebugging(app) && AdbGrants.holdsSecureSettings(app) && !prefs.getBoolean(SuperPowers.REVOKED, false))
                runCatching { Settings.Global.putInt(app.contentResolver, ADB_WIFI_ENABLED, 1); Thread.sleep(1500) }
            var needsPairing: AdbPairingRequiredException? = null
            val ok = runCatching { manager.autoConnect(app, 8_000) }.getOrElse { e ->
                if (e is AdbPairingRequiredException) needsPairing = e
                false
            } || runCatching { manager.connect("127.0.0.1", LEGACY_PORT) }.getOrDefault(false)
            needsPairing?.takeIf { !ok }?.let { throw it }
            if (!ok) throw IllegalStateException(
                if (wirelessDebugging(app)) "Wireless debugging is on but Bridgit couldn't connect. Pair Bridgit again in ADB setup."
                else "Wireless debugging is off. Turn it on in Developer options (it needs Wi-Fi).")
            paired = true
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { lock.withLock { runCatching { manager.disconnect() } } }

    /**
     * Runs one command as uid shell. Output is stdout and stderr together, capped at [maxBytes].
     * The exit code comes from a marker line, since the plain `shell:` service doesn't report it.
     */
    suspend fun shell(command: String, timeoutMs: Long = 30_000, maxBytes: Int = 256 * 1024): Result {
        connect()
        return withContext(Dispatchers.IO) {
            // A subshell, so `exit N` in the command still reaches the marker line with its code.
            val stream = manager.openStream("shell:( $command\n) 2>&1; __r=\$?; echo; echo \"$MARKER\$__r\"")
            val out = ByteArrayOutputStream()
            var truncated = false
            val reader = Thread {
                runCatching {
                    stream.openInputStream().use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            synchronized(out) {
                                val room = maxBytes + 512 - out.size()
                                if (room > 0) out.write(buf, 0, minOf(n, room)) else truncated = true
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            reader.join(timeoutMs)
            val timedOut = reader.isAlive
            if (timedOut) runCatching { stream.close() }
            val text = synchronized(out) { out.toString(Charsets.UTF_8.name()) }
            val at = text.lastIndexOf(MARKER)
            val code = if (at >= 0) text.substring(at + MARKER.length).trim().toIntOrNull() else null
            var body = if (at >= 0) text.substring(0, at).removeSuffix("\n").removeSuffix("\n") else text
            if (body.length > maxBytes) { body = body.take(maxBytes); truncated = true }
            Result(code, body, truncated, timedOut)
        }
    }

    /**
     * Runs [command] with the `exec:` service (no terminal, so bytes pass through unchanged) and writes
     * [input] to its stdin. Returns the output once it contains [doneMarker], or null on timeout.
     * The command must stop reading by itself (for example `head -c <size>`), since stdin is never closed early.
     * The whole call, writing included, is bounded by [timeoutMs]: a stalled write is cut off and throws.
     */
    suspend fun execWithInput(command: String, input: File, doneMarker: String, timeoutMs: Long): String? {
        connect()
        return withContext(Dispatchers.IO) {
            val stream = manager.openStream("exec:$command")
            val out = ByteArrayOutputStream()
            val reader = Thread {
                runCatching {
                    stream.openInputStream().use { inp ->
                        val buf = ByteArray(1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            synchronized(out) { if (out.size() < 64 * 1024) out.write(buf, 0, n) }
                            if (synchronized(out) { out.toString(Charsets.UTF_8.name()) }.contains(doneMarker)) break
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            // If adbd stalls mid-write the blocking write never returns; closing the stream makes it throw.
            val watchdog = Thread {
                try { Thread.sleep(timeoutMs); runCatching { stream.close() } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            try {
                val sink = stream.openOutputStream()
                input.inputStream().use { src ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = src.read(buf); if (n < 0) break; sink.write(buf, 0, n) }
                }
                sink.flush()
                reader.join(timeoutMs)
            } finally {
                watchdog.interrupt()
                runCatching { stream.close() }
            }
            val text = synchronized(out) { out.toString(Charsets.UTF_8.name()) }
            text.takeIf { it.contains(doneMarker) }
        }
    }

    /** Forgets the key; Wireless debugging will ask to pair again. */
    fun forget() {
        if (started) { runCatching { manager.disconnect() }; manager.reset() }
        dir.listFiles()?.forEach { it.delete() }
        paired = false
    }

    private inner class Manager : AbsAdbConnectionManager() {
        private val keyFile = File(dir, "key.pk8")
        private val certFile = File(dir, "cert.der")
        private var key: PrivateKey? = null
        private var cert: Certificate? = null

        @Synchronized private fun load() {
            if (key != null) return
            if (keyFile.exists() && certFile.exists()) runCatching {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
                return
            }
            val gen = android.sun.security.x509.CertAndKeyGen("RSA", "SHA512withRSA").apply { generate(2048) }
            val c = gen.getSelfCertificate(android.sun.security.x509.X500Name("CN=Bridgit"), 3650L * 24 * 3600)
            keyFile.writeBytes(gen.privateKey.encoded); certFile.writeBytes(c.encoded)
            key = gen.privateKey; cert = c
        }

        @Synchronized fun reset() { key = null; cert = null }
        override fun getPrivateKey(): PrivateKey { load(); return key!! }
        override fun getCertificate(): Certificate { load(); return cert!! }
        override fun getDeviceName() = "Bridgit"
    }

    companion object {
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val LEGACY_PORT = 5555
        private const val MARKER = "__BRIDGIT_EXIT__="

        fun developerOptions(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        fun usbDebugging(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        fun wirelessDebugging(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }
}
