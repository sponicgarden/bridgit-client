package cc.bridgit.app.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.update.UpdateLogic.Problem
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import cc.bridgit.app.outbound.Outbound
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import cc.bridgit.app.core.Compat

/**
 * Network, file and PackageManager side of the update flow (spec 12.14). Ported in concept
 * from Spotka's AppUpdateInstaller, with HttpURLConnection instead of OkHttp and stricter
 * trust rules (UPD-03): no redirects, signing certificate must equal the installed app's.
 *
 * Network and disk work runs on Dispatchers.IO. Every check, download and refusal is a
 * `diagnostic` log entry with the host only, the versions and the result (UPD-07).
 */
object UpdateInstaller {
    private const val DIR = "updates"
    private const val STALE_MS = 60L * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val APK_MIME = "application/vnd.android.package-archive"

    sealed class Check {
        data class UpToDate(val manifest: UpdateLogic.Manifest) : Check()
        data class Newer(val manifest: UpdateLogic.Manifest) : Check()
        /** The installed build is newer than the published one (a local release build). */
        data class LocalAhead(val manifest: UpdateLogic.Manifest) : Check()
        data class Refused(val problem: Problem) : Check()
    }

    sealed class Download {
        data class Ready(val file: File, val manifest: UpdateLogic.Manifest) : Download()
        data class Refused(val problem: Problem) : Download()
    }

    private class Refusal(val problem: Problem, val detail: String) : IOException(detail)

    // ---- Check ----

    suspend fun check(app: BridgeApp): Check = withContext(Dispatchers.IO) {
        val installed = installedVersionCode(app)
        val host = UpdateLogic.hostOf(UpdateLogic.MANIFEST_URL)
        try {
            val body = fetchManifest(userAgent(app))
            when (val p = UpdateLogic.parseManifest(body, app.packageName)) {
                is UpdateLogic.Parsed.Refused -> {
                    log(app, "Update check refused: ${p.problem.name.lowercase()} (${p.detail}) from $host", "warn")
                    Check.Refused(p.problem)
                }
                is UpdateLogic.Parsed.Ok -> {
                    val m = p.manifest
                    val result = when (UpdateLogic.compare(installed, m.versionCode)) {
                        UpdateLogic.Compare.NEWER -> Check.Newer(m)
                        UpdateLogic.Compare.CURRENT -> Check.UpToDate(m)
                        UpdateLogic.Compare.LOCAL_AHEAD -> Check.LocalAhead(m)
                        UpdateLogic.Compare.UNCOMPARABLE -> Check.Refused(Problem.BAD_MANIFEST)
                    }
                    val word = when (result) {
                        is Check.Newer -> "newer version available"
                        is Check.UpToDate -> "up to date"
                        is Check.LocalAhead -> "installed build is ahead"
                        is Check.Refused -> "versions not comparable"
                    }
                    log(app, "Update check from $host: installed $installed, published ${m.versionName} (${m.versionCode}): $word")
                    result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Refusal) {
            log(app, "Update check refused: ${e.problem.name.lowercase()} (${e.detail}) from $host", "warn")
            Check.Refused(e.problem)
        } catch (e: Exception) {
            log(app, "Update check failed: ${e.javaClass.simpleName} from $host", "warn")
            Check.Refused(Problem.NETWORK)
        }
    }

    /** GET latest.json, with one retry on a transient status (408, 429, 5xx). */
    private suspend fun fetchManifest(ua: String): String {
        var attempt = 0
        var manifestText: String? = null
        while (true) {
            attempt++
            val conn = open(UpdateLogic.MANIFEST_URL, "application/json", ua)
            val retry = Outbound.call(UpdateLogic.MANIFEST_URL) { meter ->
                meter.headers["User-Agent"] = ua
                try {
                    val code = conn.responseCode
                    meter.status = code
                    if (code != 200) {
                        if (attempt == 1 && UpdateLogic.isTransientHttpStatus(code)) return@call true
                        throw Refusal(UpdateLogic.problemForStatus(code), "HTTP $code")
                    }
                    val declared = conn.contentLengthLong
                    if (declared > UpdateLogic.MAX_MANIFEST_BYTES) throw Refusal(Problem.BAD_MANIFEST, "manifest too large")
                    val bytes = conn.inputStream.use { readCapped(it, UpdateLogic.MAX_MANIFEST_BYTES) }
                    meter.receivedBytes = bytes.size.toLong(); meter.received = bytes; meter.receivedType = conn.contentType
                    manifestText = bytes.toString(Charsets.UTF_8)
                    false
                } finally {
                    conn.disconnect()
                }
            }
            if (retry) { delay(2_000); continue }
            return manifestText!!
        }
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) throw Refusal(Problem.BAD_MANIFEST, "manifest too large")
        }
        return out.toByteArray()
    }

    /** HTTPS only, no redirects, timeouts set. Refuses any URL that fails the trust rules. */
    private fun open(url: String, accept: String, ua: String): HttpsURLConnection {
        val safe = if (url.endsWith(".apk")) UpdateLogic.isSafeApkUrl(url) else UpdateLogic.isSafeManifestUrl(url)
        if (!safe) throw Refusal(Problem.UNSAFE_URL, "url host ${UpdateLogic.hostOf(url)}")
        val conn = URL(url).openConnection() as? HttpsURLConnection
            ?: throw Refusal(Problem.UNSAFE_URL, "not https")
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-cache")
        return conn
    }

    // ---- Download and verify ----

    /**
     * Streams the APK to cacheDir/updates/<unique>.apk.part, checks size and SHA-256, renames,
     * then validates the archive against the manifest and the installed app (UPD-03).
     * [onProgress] gets 0..100, or -1 when the length is unknown. Cancellation deletes the files.
     */
    suspend fun download(app: BridgeApp, m: UpdateLogic.Manifest, onProgress: (Int) -> Unit): Download =
        withContext(Dispatchers.IO) {
            val host = UpdateLogic.hostOf(m.url)
            val dir = cacheDir(app)
            val dest = File(dir, "bridgit-${m.versionCode}-${UUID.randomUUID()}.apk")
            val part = File(dir, dest.name + ".part")
            log(app, "Update download started from $host: ${m.versionName} (${m.versionCode}), ${m.sizeBytes} bytes")
            try {
                if (m.sizeBytes > dir.usableSpace) throw Refusal(Problem.STORAGE, "insufficient space for update")
                if (!UpdateLogic.isSafeApkUrl(m.url) || !UpdateLogic.isValidSha256(m.sha256))
                    throw Refusal(Problem.UNSAFE_URL, "url host $host")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                val conn = open(m.url, "$APK_MIME,application/octet-stream", userAgent(app))
                Outbound.call(m.url) { meter -> meter.headers["User-Agent"] = userAgent(app); try {
                    val code = conn.responseCode
                    meter.status = code; meter.receivedType = conn.contentType
                    if (code != 200) throw Refusal(UpdateLogic.problemForStatus(code), "HTTP $code")
                    val declared = conn.contentLengthLong
                    if (declared >= 0 && declared != m.sizeBytes) throw Refusal(Problem.SIZE_MISMATCH, "Content-Length $declared")
                    val expected = m.sizeBytes
                    var last = -2
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                if (n.toLong() > expected - total) throw Refusal(Problem.SIZE_MISMATCH, "longer than size_bytes")
                                total += n
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                val pct = ((total.toDouble() / expected) * 100).toInt().coerceIn(0, 100)
                                if (pct != last) { last = pct; onProgress(pct) }
                            }
                            out.fd.sync()
                        }
                    }
                } finally {
                    meter.receivedBytes = total
                    conn.disconnect()
                } }
                coroutineContext.ensureActive()
                if (total != m.sizeBytes) throw Refusal(Problem.SIZE_MISMATCH, "got $total bytes")
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (got != m.sha256.lowercase()) throw Refusal(Problem.CHECKSUM, "sha256 mismatch")
                if (!part.renameTo(dest)) throw Refusal(Problem.STORAGE, "rename failed")
                verifyArchive(app, dest, m)
                log(app, "Update download verified from $host: ${m.versionName} (${m.versionCode}), $total bytes, " +
                    "checksum, package, version and signing certificate match")
                Download.Ready(dest, m)
            } catch (e: CancellationException) {
                part.delete(); dest.delete()
                log(app, "Update download cancelled: ${m.versionName} (${m.versionCode})")
                throw e
            } catch (e: Refusal) {
                part.delete(); dest.delete()
                log(app, "Update refused: ${e.problem.name.lowercase()} (${e.detail}), ${m.versionName} (${m.versionCode}) from $host", "warn")
                Download.Refused(e.problem)
            } catch (e: Exception) {
                part.delete(); dest.delete()
                log(app, "Update download failed: ${e.javaClass.simpleName}, ${m.versionName} (${m.versionCode}) from $host", "warn")
                Download.Refused(if (e is IOException) Problem.NETWORK else Problem.STORAGE)
            }
        }

    /**
     * Test builds only (the debug hook): runs the same archive checks on an APK already on the phone,
     * with a manifest made from the file itself, since a test package has no published manifest.
     */
    internal suspend fun verifyLocalForTest(app: BridgeApp, file: File): Download = withContext(Dispatchers.IO) {
        try {
            val info = Compat.packageArchiveInfo(app.packageManager, file.absolutePath, 0)
                ?: throw Refusal(Problem.NOT_AN_APK, "unreadable archive")
            val sha = file.inputStream().use { input ->
                val d = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
                d.digest().joinToString("") { "%02x".format(it) }
            }
            val m = UpdateLogic.Manifest(app.packageName, info.versionName ?: "?", info.longVersionCode,
                "https://${UpdateLogic.HOST}${UpdateLogic.PATH_PREFIX}test.apk", sha, file.length(), null, null)
            verifyArchive(app, file, m)
            log(app, "Update test file verified: ${m.versionName} (${m.versionCode}), package, version and signing certificate match")
            Download.Ready(file, m)
        } catch (e: Refusal) {
            log(app, "Update test file refused: ${e.problem.name.lowercase()} (${e.detail})", "warn")
            Download.Refused(e.problem)
        }
    }

    /** Recheck a cached download against its ORIGINAL official manifest immediately before install. */
    suspend fun reverify(app:BridgeApp,ready:Download.Ready):Download = withContext(Dispatchers.IO) {
        try {
            val file=ready.file; val m=ready.manifest
            if(!file.exists() || file.length()!=m.sizeBytes) throw Refusal(Problem.SIZE_MISMATCH,"cached update size changed")
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buf=ByteArray(64*1024); while(true) { coroutineContext.ensureActive(); val n=input.read(buf); if(n<0) break; digest.update(buf,0,n) } }
            if(digest.digest().joinToString("") { "%02x".format(it) }!=m.sha256.lowercase()) throw Refusal(Problem.CHECKSUM,"cached update checksum changed")
            verifyArchive(app,file,m); ready
        } catch(e:CancellationException) { throw e }
        catch(e:Refusal) { Download.Refused(e.problem) }
        catch(_:Exception) { Download.Refused(Problem.STORAGE) }
    }

    /** Package, versionCode (equal to the manifest, above installed) and signing certificates (UPD-03). */
    private fun verifyArchive(ctx: Context, file: File, m: UpdateLogic.Manifest) {
        val pm = ctx.packageManager
        val archive = Compat.packageArchiveInfo(pm, file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw Refusal(Problem.NOT_AN_APK, "unreadable archive")
        val installed = Compat.packageInfo(pm, ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        UpdateLogic.checkArchive(archive.packageName, archive.longVersionCode, m, ctx.packageName, installed.longVersionCode)
            ?.let { throw Refusal(it, "archive ${archive.packageName} ${archive.longVersionCode}") }
        val a = certDigests(archive)
        if (a.isEmpty()) throw Refusal(Problem.UNSIGNED, "no signing certificate")
        if (!UpdateLogic.certSetsMatch(a, certDigests(installed))) throw Refusal(Problem.CERT_MISMATCH, "signing certificate differs")
    }

    private fun certDigests(info: PackageInfo): List<String> {
        // apkContentsSigners: the certificates the APK is signed with now (one, or several for multi-signer APKs).
        val certs = info.signingInfo?.apkContentsSigners ?: return emptyList()
        return certs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    // ---- Install ----

    fun canRequestInstalls(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Android's "Install unknown apps" page for Bridgit. */
    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Hands the verified file to Android's package installer, which always asks to confirm (UPD-04): the fallback before Android 12. */
    fun installIntent(ctx: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun logInstallHandoff(app: BridgeApp, m: UpdateLogic.Manifest) =
        log(app, "Update handed to Android's installer: ${m.versionName} (${m.versionCode})")

    fun logInstallBlocked(app: BridgeApp) =
        log(app, "Update waiting: Install unknown apps is off for Bridgit; opened Android's page", "warn")

    // ---- Install through Android's PackageInstaller session (Android 12+, no Android confirm) ----

    sealed class SessionInstall {
        /** Android reported success before stopping Bridgit (rare: it usually stops Bridgit first). */
        object Installed : SessionInstall()
        /** Android still wants its own confirm; start [confirm] to show it (the same box as before). */
        data class NeedsConfirm(val confirm: Intent) : SessionInstall()
        /** Use Android's installer the old way; [reason] is for the log only. */
        data class Fallback(val reason: String) : SessionInstall()
    }

    private const val KEY_SESSION_PENDING = "session_install_pending"
    private const val KEY_SESSION_AT = "session_install_at"

    /** Android 12 added installs without the system confirm for an app updating itself. */
    fun sessionInstallAvailable(): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    /**
     * Installs the verified [file] with a PackageInstaller session that asks Android to skip its own
     * "Update this app?" box (USER_ACTION_NOT_REQUIRED), so Bridgit's branded Ready card is the only
     * confirm. Android allows that for an app updating itself that holds UPDATE_PACKAGES_WITHOUT_USER_ACTION
     * and the "Install unknown apps" grant; the same signing key is still required. If Android insists
     * on its confirm, [SessionInstall.NeedsConfirm] carries its screen. Any failure returns [SessionInstall.Fallback].
     */
    @android.annotation.SuppressLint("NewApi")
    suspend fun installViaSession(app: BridgeApp, file: File, m: UpdateLogic.Manifest): SessionInstall {
        val v = "${m.versionName} (${m.versionCode})"
        fun fallback(why: String, level: String = "warn"): SessionInstall {
            log(app, "Update without Android's confirm not used: $why; handing $v to Android's installer", level)
            return SessionInstall.Fallback(why)
        }
        if (!sessionInstallAvailable()) return fallback("Android older than 12", "info")
        if (!file.exists() || file.length() != m.sizeBytes) return fallback("verified file missing")
        val installer = app.packageManager.packageInstaller
        val action = "${app.packageName}.UPDATE_SESSION_STATUS"
        val answer = kotlinx.coroutines.CompletableDeferred<Intent>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { answer.complete(i) }
        }
        androidx.core.content.ContextCompat.registerReceiver(app, receiver, android.content.IntentFilter(action),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        var sessionId = -1
        try {
            withContext(Dispatchers.IO) {
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { s ->
                    s.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 64 * 1024) }
                        s.fsync(out)
                    }
                    val pi = android.app.PendingIntent.getBroadcast(app, sessionId,
                        Intent(action).setPackage(app.packageName),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
                    prefs(app).edit().putLong(KEY_SESSION_PENDING, m.versionCode)
                        .putLong(KEY_SESSION_AT, System.currentTimeMillis()).commit()
                    log(app, "Update without Android's confirm: installing $v; Bridgit closes and opens again")
                    s.commit(pi.intentSender)
                }
            }
            // Normally Android stops Bridgit during this wait. Otherwise read its answer.
            val status = kotlinx.coroutines.withTimeoutOrNull(120_000) { answer.await() }
                ?: run { clearSessionPending(app); return fallback("no answer from Android after 2 minutes") }
            val code = status.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,
                android.content.pm.PackageInstaller.STATUS_FAILURE)
            return when (code) {
                android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                    log(app, "Update without Android's confirm installed $v")
                    SessionInstall.Installed
                }
                android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = androidx.core.content.IntentCompat.getParcelableExtra(status, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm == null) { clearSessionPending(app); fallback("Android asked to confirm but gave no screen") }
                    else {
                        log(app, "Update: Android still asks to confirm $v; showing its box", "info")
                        SessionInstall.NeedsConfirm(confirm)
                    }
                }
                else -> {
                    clearSessionPending(app)
                    val msg = status.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $code"
                    fallback("Android refused it: ${msg.take(80)}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clearSessionPending(app)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            return fallback(e.javaClass.simpleName)
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    private fun clearSessionPending(app: BridgeApp) =
        prefs(app).edit().remove(KEY_SESSION_PENDING).remove(KEY_SESSION_AT).apply()

    /**
     * Android stops Bridgit to replace it and doesn't start it again. Called on MY_PACKAGE_REPLACED:
     * if Bridgit itself started that install in the last 10 minutes, open it again and log the result.
     */
    fun reopenAfterSessionInstall(app: BridgeApp) {
        val p = prefs(app)
        val pending = p.getLong(KEY_SESSION_PENDING, 0L)
        val at = p.getLong(KEY_SESSION_AT, 0L)
        if (pending <= 0) return
        clearSessionPending(app)
        if (System.currentTimeMillis() - at > 10 * 60_000L) return
        val installed = installedVersionCode(app)
        if (installed < pending) { log(app, "Update to $pending did not finish; still on $installed", "warn"); return }
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "$installed"
        log(app, "Updated without Android's confirm: now on $version; opening Bridgit again")
        // Android usually blocks an app opening itself from the background, so a tap-to-open notice comes too.
        // MainActivity clears it when Bridgit is opened (clearUpdatedNotice).
        runCatching {
            val pi = android.app.PendingIntent.getActivity(app, NOTICE_ID, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(app, cc.bridgit.app.interact.InteractionNotifier.CHANNEL_NOTICES)
                .setSmallIcon(cc.bridgit.app.R.drawable.ic_notif)
                .setColor(app.getColor(cc.bridgit.app.R.color.brand_primary))
                .setContentTitle(app.getString(cc.bridgit.app.R.string.update_done_notice_title, version))
                .setContentText(app.getString(cc.bridgit.app.R.string.update_done_notice_text))
                .setContentIntent(pi).setAutoCancel(true).build()
            androidx.core.app.NotificationManagerCompat.from(app).notify(NOTICE_ID, n)
        }.onFailure { log(app, "Couldn't post the updated notice: ${it.javaClass.simpleName}", "warn") }
        runCatching { app.startActivity(launch) }
    }

    private const val NOTICE_ID = 0x0b0d

    /** Removes the "Bridgit updated, tap to open" notice once Bridgit is open. */
    fun clearUpdatedNotice(ctx: Context) {
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).cancel(NOTICE_ID) }
    }

    // ---- Install over Bridgit's own ADB link (no Android confirm) ----

    sealed class AdbInstall {
        /** Android installed it but Bridgit wasn't stopped (should not happen for a same-package update). */
        object Installed : AdbInstall()
        /** Use Android's installer instead; [reason] is for the log only. */
        data class Fallback(val reason: String) : AdbInstall()
    }

    private const val KEY_ADB_PENDING = "adb_install_pending"

    /** True when Bridgit has its own ADB link: private build, paired, not revoked by Revoke Super Powers. */
    fun adbInstallAvailable(app: BridgeApp): Boolean = AdbInstallLogic.available(
        cc.bridgit.app.BuildConfig.PRIVATE_CONTROL, app.adb.paired,
        app.getSharedPreferences("adb", Context.MODE_PRIVATE).getBoolean(cc.bridgit.app.adb.SuperPowers.REVOKED, false))

    /**
     * Installs the verified [file] as uid shell over Bridgit's own ADB link, so Android doesn't ask to confirm.
     * Only ever this file: it is streamed to /data/local/tmp (shell-only), its size and SHA-256 are checked
     * there against the manifest, and Android itself requires the same signing key for an update.
     * The install runs as its own shell session, so it finishes when Android stops Bridgit to replace it,
     * and then starts Bridgit again. If Bridgit is not stopped, this waits for the result: any failure
     * returns [AdbInstall.Fallback] and the caller hands the file to Android's installer as before.
     */
    suspend fun installViaAdb(app: BridgeApp, file: File, m: UpdateLogic.Manifest): AdbInstall = withContext(Dispatchers.IO) {
        val v = "${m.versionName} (${m.versionCode})"
        fun fallback(why: String, level: String = "warn"): AdbInstall {
            log(app, "Update over ADB not used: $why; handing $v to Android's installer", level)
            return AdbInstall.Fallback(why)
        }
        if (!adbInstallAvailable(app)) return@withContext fallback("no ADB link", "info")
        val pkg = app.packageName
        val paths = AdbInstallLogic.paths(pkg, m.versionCode) ?: return@withContext fallback("unexpected package name")
        val component = app.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
        val write = AdbInstallLogic.writeCommand(paths.apk, m.sizeBytes)
        val verify = AdbInstallLogic.verifyCommand(paths.apk)
        val install = component?.let { AdbInstallLogic.installCommand(pkg, paths, it) }
        if (write == null || verify == null || install == null) return@withContext fallback("unexpected launcher or path")
        if (!file.exists() || file.length() != m.sizeBytes) return@withContext fallback("verified file missing")
        try {
            log(app, "Update over ADB: copying $v to the shell's folder")
            app.adb.execWithInput(write, file, AdbInstallLogic.WROTE_MARKER, 120_000)
                ?: return@withContext fallback("copy did not finish")
            val check = app.adb.shell(verify, 30_000)
            if (check.exitCode != 0 || !AdbInstallLogic.verifyMatches(check.output, m.sizeBytes, m.sha256)) {
                runCatching { app.adb.shell("rm -f '${paths.apk}'", 10_000) }
                return@withContext fallback("copy did not match the verified checksum")
            }
            prefs(app).edit().putLong(KEY_ADB_PENDING, m.versionCode).commit()
            log(app, "Update over ADB: checksum matches in the shell's folder; installing $v, Bridgit restarts by itself")
            val started = app.adb.shell(install, 15_000)
            if (started.exitCode != 0) {
                prefs(app).edit().remove(KEY_ADB_PENDING).apply()
                return@withContext fallback("install did not start (exit ${started.exitCode})")
            }
            // Normally Android stops Bridgit during this wait. If not, read the job's result.
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                val out = runCatching { app.adb.shell("cat '${paths.log}' 2>/dev/null", 10_000).output }.getOrNull()
                when (val s = AdbInstallLogic.parseLog(out)) {
                    is AdbInstallLogic.JobState.Running -> continue
                    is AdbInstallLogic.JobState.Installed -> {
                        log(app, "Update over ADB installed $v")
                        return@withContext AdbInstall.Installed
                    }
                    is AdbInstallLogic.JobState.Failed -> {
                        prefs(app).edit().remove(KEY_ADB_PENDING).apply()
                        runCatching { app.adb.shell("rm -f '${paths.apk}' '${paths.log}'", 10_000) }
                        return@withContext fallback("Android refused it: ${s.reason.take(80)}")
                    }
                }
            }
            prefs(app).edit().remove(KEY_ADB_PENDING).apply()
            fallback("no result after 2 minutes")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs(app).edit().remove(KEY_ADB_PENDING).apply()
            fallback("${e.javaClass.simpleName}")
        }
    }

    /** At start-up after an ADB install: logs that Bridgit came back on the new version, and clears the job's log. */
    fun noteAdbInstallResult(app: BridgeApp) {
        val p = prefs(app)
        val pending = p.getLong(KEY_ADB_PENDING, 0L)
        if (pending <= 0) return
        p.edit().remove(KEY_ADB_PENDING).apply()
        val installed = installedVersionCode(app)
        if (installed >= pending) log(app, "Updated over ADB without a confirm: now on version $installed, Bridgit started again")
        else log(app, "Update over ADB to $pending did not finish; still on $installed", "warn")
        AdbInstallLogic.paths(app.packageName, pending)?.let { paths ->
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch { runCatching { app.adb.shell("rm -f '${paths.log}' '${paths.apk}'", 10_000) } }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)

    // ---- Housekeeping ----

    /** cacheDir/updates, with files older than an hour removed (UPD-05). */
    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - STALE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return dir
    }

    /** Called on app start-up paths (the dialog) so stale downloads don't linger. */
    fun cleanStale(ctx: Context) { runCatching { cacheDir(ctx) } }

    private fun userAgent(ctx: Context): String = UpdateLogic.userAgent(
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?",
        android.os.Build.VERSION.RELEASE ?: "?")

    private fun installedVersionCode(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)

    private fun log(app: BridgeApp, message: String, level: String = "info") =
        runCatching { app.log.system("diagnostic", "service", message, level) }
}
