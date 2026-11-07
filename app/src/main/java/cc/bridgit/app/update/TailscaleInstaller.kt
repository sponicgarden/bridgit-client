package cc.bridgit.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.adb.AdbGrants
import cc.bridgit.app.core.Compat
import cc.bridgit.app.core.TailscaleApk
import cc.bridgit.app.core.TailscaleHelp
import cc.bridgit.app.outbound.Outbound
import cc.bridgit.app.platform.TailscaleStatus
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Installs Tailscale from Tailscale's own download site, so setting up Bridgit doesn't need the Play Store
 * (private build only; Play doesn't allow an app to install other apps, so the Play build keeps the Play link).
 *
 * Download (about 100 MB) → SHA-256 must match Tailscale's published one → package must be com.tailscale.ipn,
 * signed with Tailscale's key ([TailscaleApk.CERT_SHA256]) → install:
 *  - with Bridgit's ADB link: `pm install` as uid shell, no taps at all, then "Keep Tailscale on" right away;
 *  - without: Android's installer, which asks the owner once ("Do you want to install this app?").
 */
object TailscaleInstaller {
    private const val DIR = "tailscale"
    private const val APK_MIME = "application/vnd.android.package-archive"

    sealed class Result {
        /** Installed with no taps (ADB link); Keep-on settings applied too. */
        object Installed : Result()
        /** Android wants the owner to tap Install: start [confirm] from a visible screen (Android blocks it from the background). */
        data class AskedAndroid(val confirm: Intent) : Result()
        /** Android needs "Install unknown apps" turned on for Bridgit first; [settings] opens that page. */
        data class NeedsUnknownSources(val settings: Intent) : Result()
        /** Something went wrong; [why] is short and plain, for the screen. */
        data class Failed(val why: String) : Result()
    }

    private class Refusal(msg: String) : IOException(msg)

    /** Private build only: the Play build may not install other apps. */
    fun available(): Boolean = cc.bridgit.app.BuildConfig.PRIVATE_CONTROL

    /**
     * Downloads, verifies and installs Tailscale. [onProgress] gets 0..100 during the download (-1 unknown),
     * then 101 while installing. Call from a screen the owner is looking at: without ADB, Android's box opens on top.
     * [force] and [allowAdb] are for the test hook only: install over an existing Tailscale (an update), or skip ADB.
     */
    suspend fun install(app: BridgeApp, force: Boolean = false, allowAdb: Boolean = true, onProgress: (Int) -> Unit): Result {
        if (!available()) return Result.Failed("not in this build")
        if (TailscaleStatus.installed(app) && !force) return Result.Installed
        val adb = allowAdb && UpdateInstaller.adbInstallAvailable(app)
        if (!adb && !app.packageManager.canRequestPackageInstalls())
            return Result.NeedsUnknownSources(UpdateInstaller.unknownSourcesIntent(app))
        val file = try {
            download(app, onProgress)
        } catch (e: CancellationException) { throw e
        } catch (e: Refusal) { log(app, "Tailscale install refused: ${e.message}", "warn"); return Result.Failed(e.message ?: "refused")
        } catch (e: Outbound.Paused) { return Result.Failed("external calls are paused")
        } catch (e: Exception) { log(app, "Tailscale download failed: ${e.javaClass.simpleName}", "warn"); return Result.Failed("download failed, check the internet connection") }
        onProgress(101)
        try {
            if (adb) {
                val r = installViaAdb(app, file)
                if (r != null) return r
            }
            // ADB didn't work out: Android's installer needs "Install unknown apps" for Bridgit.
            if (!app.packageManager.canRequestPackageInstalls())
                return Result.NeedsUnknownSources(UpdateInstaller.unknownSourcesIntent(app))
            return installViaSession(app, file)
        } finally {
            // The session copies the file, and the ADB copy lives in the shell's folder, so ours can go.
            file.delete()
        }
    }

    // ---- Download and verify ----

    private suspend fun download(app: BridgeApp, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val ua = UpdateLogic.userAgent(runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?",
            android.os.Build.VERSION.RELEASE ?: "?")
        val release = TailscaleApk.parseIndex(fetchText(TailscaleApk.INDEX_URL, ua, TailscaleApk.MAX_INDEX_BYTES))
            ?: throw Refusal("Tailscale's download list didn't make sense")
        val sha = TailscaleApk.parseSha256(fetchText(release.shaUrl, ua, 1024))
            ?: throw Refusal("Tailscale's checksum file didn't make sense")
        val dir = File(app.cacheDir, DIR).apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val dest = File(dir, release.file)
        val part = File(dir, release.file + ".part")
        log(app, "Tailscale install: downloading ${release.version} from ${TailscaleApk.HOST}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val conn = open(release.apkUrl, "$APK_MIME,application/octet-stream", ua)
            Outbound.call(release.apkUrl) { meter ->
                meter.headers["User-Agent"] = ua
                try {
                    val code = conn.responseCode
                    meter.status = code; meter.receivedType = conn.contentType
                    if (code != 200) throw Refusal("Tailscale's site answered $code")
                    val declared = conn.contentLengthLong
                    if (declared > TailscaleApk.MAX_APK_BYTES) throw Refusal("the download is far bigger than Tailscale's app")
                    if (declared > 0 && declared > dir.usableSpace) throw Refusal("not enough free space on the phone")
                    var last = -2
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf); if (n < 0) break
                                total += n
                                if (total > TailscaleApk.MAX_APK_BYTES) throw Refusal("the download is far bigger than Tailscale's app")
                                out.write(buf, 0, n); digest.update(buf, 0, n)
                                val pct = TailscaleApk.percent(total, declared)
                                if (pct != last) { last = pct; onProgress(pct) }
                            }
                            out.fd.sync()
                        }
                    }
                    if (declared >= 0 && total != declared) throw Refusal("the download stopped early")
                } finally { meter.receivedBytes = total; conn.disconnect() }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != sha) throw Refusal("the download doesn't match Tailscale's checksum")
            if (!part.renameTo(dest)) throw Refusal("couldn't save the download")
            verifyArchive(app, dest)
            log(app, "Tailscale install: ${release.version} downloaded, $total bytes; checksum and Tailscale's signing key match")
            dest
        } catch (e: Throwable) {
            part.delete(); dest.delete(); throw e
        }
    }

    /** Package com.tailscale.ipn, signed with Tailscale's key and nothing else. */
    private fun verifyArchive(ctx: Context, file: File) {
        val info = Compat.packageArchiveInfo(ctx.packageManager, file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw Refusal("the download isn't an Android app")
        if (info.packageName != TailscaleHelp.PACKAGE) throw Refusal("the download isn't Tailscale")
        val certs = info.signingInfo?.apkContentsSigners.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        if (!TailscaleApk.signedByTailscale(certs)) throw Refusal("the download isn't signed by Tailscale")
    }

    private fun fetchText(url: String, ua: String, cap: Long): String {
        val conn = open(url, "*/*", ua)
        return Outbound.call(url) { meter ->
            meter.headers["User-Agent"] = ua
            try {
                val code = conn.responseCode
                meter.status = code
                if (code != 200) throw Refusal("Tailscale's site answered $code")
                val bytes = conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > cap) throw Refusal("Tailscale's reply was too big") }
                    out.toByteArray()
                }
                meter.receivedBytes = bytes.size.toLong(); meter.received = bytes; meter.receivedType = conn.contentType
                bytes.toString(Charsets.UTF_8)
            } finally { conn.disconnect() }
        }
    }

    /** HTTPS to pkgs.tailscale.com only, no redirects. */
    private fun open(url: String, accept: String, ua: String): HttpsURLConnection {
        if (!TailscaleApk.isSafeUrl(url)) throw Refusal("unexpected download address")
        return (URL(url).openConnection() as? HttpsURLConnection ?: throw Refusal("not https")).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000; readTimeout = 30_000; useCaches = false
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    // ---- Install ----

    /** Over Bridgit's own ADB link: no taps. Null means "use Android's installer instead". */
    private suspend fun installViaAdb(app: BridgeApp, file: File): Result? = withContext(Dispatchers.IO) {
        val code = Compat.packageArchiveInfo(app.packageManager, file.absolutePath, 0)?.longVersionCode ?: return@withContext null
        val paths = AdbInstallLogic.paths(TailscaleHelp.PACKAGE, code) ?: return@withContext null
        val write = AdbInstallLogic.writeCommand(paths.apk, file.length()) ?: return@withContext null
        val verify = AdbInstallLogic.verifyCommand(paths.apk) ?: return@withContext null
        val sha = file.inputStream().use { input ->
            val d = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
            d.digest().joinToString("") { "%02x".format(it) }
        }
        try {
            app.adb.execWithInput(write, file, AdbInstallLogic.WROTE_MARKER, 180_000)
                ?: return@withContext fallback(app, "copy to the shell's folder did not finish")
            val check = app.adb.shell(verify, 30_000)
            if (check.exitCode != 0 || !AdbInstallLogic.verifyMatches(check.output, file.length(), sha)) {
                runCatching { app.adb.shell("rm -f '${paths.apk}'", 10_000) }
                return@withContext fallback(app, "copy did not match")
            }
            // Bridgit isn't replaced here, so a plain wait for pm is fine.
            val r = app.adb.shell("pm install -r -i ${app.packageName} '${paths.apk}'; rc=\$?; rm -f '${paths.apk}'; exit \$rc", 180_000)
            if (r.exitCode != 0 || !r.output.contains("Success") || !TailscaleStatus.installed(app))
                return@withContext fallback(app, "pm install: ${r.output.trim().lineSequence().lastOrNull().orEmpty().take(80)}")
            log(app, "Tailscale installed over ADB with no taps")
            runCatching { AdbGrants.grantAll(app, setOf("tailscale")) }
            Result.Installed
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { fallback(app, e.javaClass.simpleName) }
    }

    private fun fallback(app: BridgeApp, why: String): Result? {
        log(app, "Tailscale install over ADB not used: $why; asking Android instead", "warn")
        return null
    }

    /** Android's own installer: one "Install" tap from the owner. */
    private suspend fun installViaSession(app: BridgeApp, file: File): Result {
        val installer = app.packageManager.packageInstaller
        val action = "${app.packageName}.TAILSCALE_INSTALL_STATUS"
        val answer = CompletableDeferred<Intent>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { answer.complete(i) } }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        var id = -1
        try {
            withContext(Dispatchers.IO) {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(TailscaleHelp.PACKAGE)
                    setSize(file.length())
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                }
                id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) }; s.fsync(out) }
                    val pi = PendingIntent.getBroadcast(app, id, Intent(action).setPackage(app.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(pi.intentSender)
                }
            }
            val status = withTimeoutOrNull(120_000) { answer.await() } ?: return Result.Failed("Android didn't answer")
            return when (val code = status.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_SUCCESS -> { log(app, "Tailscale installed"); Result.Installed }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(status, Intent.EXTRA_INTENT, Intent::class.java)
                        ?: return Result.Failed("Android didn't show its install box")
                    log(app, "Tailscale install: Android asks the owner to confirm")
                    Result.AskedAndroid(confirm)
                }
                else -> {
                    val msg = status.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $code"
                    log(app, "Tailscale install refused by Android: ${msg.take(80)}", "warn")
                    Result.Failed("Android refused it")
                }
            }
        } catch (e: CancellationException) { if (id >= 0) runCatching { installer.abandonSession(id) }; throw e
        } catch (e: Exception) {
            if (id >= 0) runCatching { installer.abandonSession(id) }
            log(app, "Tailscale install failed: ${e.javaClass.simpleName}", "warn")
            return Result.Failed("Android's installer failed")
        } finally { runCatching { app.unregisterReceiver(receiver) } }
    }

    private fun log(app: BridgeApp, message: String, level: String = "info") =
        runCatching { app.log.system("diagnostic", "service", message, level) }
}
