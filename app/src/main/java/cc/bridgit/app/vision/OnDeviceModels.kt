package cc.bridgit.app.vision

import android.content.Context
import android.net.ConnectivityManager
import cc.bridgit.app.outbound.Outbound
import cc.bridgit.app.update.UpdateLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The opt-in on-phone vision engines (docs/design/on-device-detection-benchmark.md, docs/software-manifest.md). They
 * never run unless a caller names them: POST /v1/images/find|crop with engine or outline, or an applet package that
 * declares them. Which models answer for each engine comes from the signed [SoftwareManifest]; only the native
 * runtime is pinned here. Nothing is in the APK: [OnDeviceModels] downloads on first request. Pure: no Android.
 */
object OnDeviceEngines {
    data class ModelFile(val name: String, val size: Long, val sha256: String, val url: String)

    /** One engine as the app runs it: the manifest item that answers for [id] (or the runtime). */
    data class Engine(
        val id: String, val kind: String, val model: String, val version: String, val folder: String, val files: List<ModelFile>,
        val goodAt: String, val notGoodAt: String, val speed: String, val memory: String, val licence: String, val pipeline: String,
    ) {
        val sizeBytes get() = files.sumOf { it.size }
        val sizeMb get() = Math.round(sizeBytes / 1e6).toInt()
    }

    /** Bridgit's public download bucket (the one the APKs come from); tools/upload-models.sh puts files there. */
    const val BASE = SoftwareManifest.BASE
    /** The engine names the app's API uses for its two features. Others can come with the manifest. */
    const val DETECTOR = "on_device_detector"
    const val OUTLINE = "on_device_outline"

    fun of(i: SoftwareManifest.Item) = Engine(i.engine, i.kind, i.id, i.version, i.folder,
        i.files.map { ModelFile(it.name, it.size, it.sha256, it.url) }, i.goodAt, i.notGoodAt, i.speed, i.memory, i.licence, i.pipeline)

    /**
     * ONNX Runtime's native libraries, which both engines need. They are not in the APK (31 MB for the two ARM
     * builds): they download with the first engine, once, for this phone's processor. Loaded by [OrtLoader].
     */
    private fun runtime(abi: String, core: ModelFile, jni: ModelFile) = Engine(
        id = "onnx_runtime", kind = "runtime", model = "onnxruntime-1.22.0", version = "1.22.0", folder = "onnxruntime-1.22.0-$abi", files = listOf(core, jni),
        goodAt = "Runs the on-phone engines", notGoodAt = "", speed = "", memory = "", licence = "MIT (ONNX Runtime)", pipeline = "")
    val RUNTIMES = mapOf(
        "arm64-v8a" to runtime("arm64-v8a",
            ModelFile("libonnxruntime.so", 18_214_224, "999ecfdb5b5a13e4097487773b6d71ce8a075408a237daab072e8f5e817bd78e", "$BASE/onnxruntime-1.22.0-arm64-v8a/libonnxruntime.so"),
            ModelFile("libonnxruntime4j_jni.so", 100_600, "5614bd018cca9031c4dbcfc2a3bece42fae52861a92ff09c4bd2491bd93ac758", "$BASE/onnxruntime-1.22.0-arm64-v8a/libonnxruntime4j_jni.so")),
        "armeabi-v7a" to runtime("armeabi-v7a",
            ModelFile("libonnxruntime.so", 13_242_336, "2ca20b18eecc56d066018b4c741dbeaeb2627187e1277b63682377ade6608b39", "$BASE/onnxruntime-1.22.0-armeabi-v7a/libonnxruntime.so"),
            ModelFile("libonnxruntime4j_jni.so", 73_676, "36be6827cac176340ff9d3bbd170699f6726ef4f7fb495c05598ef78da8951d1", "$BASE/onnxruntime-1.22.0-armeabi-v7a/libonnxruntime4j_jni.so")),
    )

    /** The runtime for the first of [abis] (the phone's supported ABIs, best first) that has one, or null. Pure. */
    fun runtimeFor(abis: List<String>): Engine? = abis.firstNotNullOfOrNull { RUNTIMES[it] }
}

/**
 * Keeps the on-phone engines' files: reads the signed [SoftwareManifest] (bundled copy, or a newer one fetched at
 * most once a day by [SoftwareCheckWorker] and on request), and downloads, checks and keeps the files of engines
 * someone asked for (an API call naming them, or an installed applet's runtime.engines) in the no-backup files
 * folder: only on an unmetered network, each file checked by size and SHA-256, retried with backoff. Never
 * downloads an engine nobody asked for. When a new version of a kept engine verifies, older folders are deleted.
 */
class OnDeviceModels(private val ctx: Context, private val appletEngines: () -> Set<String>, private val log: (String, String) -> Unit) {
    class State(@Volatile var state: String = NOT_DOWNLOADED, @Volatile var reason: String? = null,
                @Volatile var doneBytes: Long = 0, @Volatile var failures: Int = 0, @Volatile var nextTryAt: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val states = java.util.concurrent.ConcurrentHashMap<String, State>()
    private fun st(e: OnDeviceEngines.Engine): State = states.getOrPut(e.folder) { State() }
    /** ONNX Runtime's libraries for this phone's processor, or null when there is no build for it. */
    val runtime: OnDeviceEngines.Engine? = OnDeviceEngines.runtimeFor(android.os.Build.SUPPORTED_ABIS.toList())

    private val root get() = File(ctx.noBackupFilesDir, "models")
    private fun dir(e: OnDeviceEngines.Engine) = File(root, e.folder)
    private val prefs get() = ctx.getSharedPreferences("on_device_models", Context.MODE_PRIVATE)
    private val appVersion: Int get() = cc.bridgit.app.BuildConfig.VERSION_CODE

    // ---- the manifest -----------------------------------------------------------------------------------------

    @Volatile private var source = "bundled"
    @Volatile private var current: SoftwareManifest.Manifest = loadManifest()
    @Volatile var checkedAt = 0L; private set
    @Volatile var lastError: String? = null; private set

    /** Debug builds may point at a test manifest (DebugSetupReceiver software_manifest_url); it must say channel test. */
    private val url get() = prefs.getString("manifest_url", null) ?: SoftwareManifest.URL
    private val channel get() = if (prefs.getString("manifest_url", null) != null) "test" else SoftwareManifest.CHANNEL
    private val cache get() = File(File(ctx.noBackupFilesDir, "software"), if (channel == "test") "manifest-test.json" else "manifest.json")

    private fun loadManifest(): SoftwareManifest.Manifest {
        val bundled = ctx.assets.open("software/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val b = SoftwareManifest.read(bundled)
        val cached = runCatching { SoftwareManifest.read(cache.readText(), 0, channel) }.getOrNull()
        return if (cached != null && (channel == "test" || cached.serial >= b.serial)) cached.also { source = "downloaded" } else b.also { source = "bundled" }
    }

    /** Debug only: read manifests from [u] (on Bridgit's download host, under /bridget/), or null for the real one. */
    fun setManifestUrl(u: String?) {
        if (u != null) require(isOurs(u)) { "the manifest must come from https://${UpdateLogic.HOST}/bridget/" }
        prefs.edit().putString("manifest_url", u).putLong("serial_floor", 0).apply()
        current = loadManifest().also { if (source != "downloaded") source = "bundled" }
    }

    private fun isOurs(u: String) = runCatching { java.net.URI(u) }.getOrNull()?.let { it.scheme == "https" && it.host == UpdateLogic.HOST && it.path.startsWith(UpdateLogic.PATH_PREFIX) && it.query == null } == true

    val manifest: SoftwareManifest.Manifest get() = current

    /** The engine [name] runs on this app right now (the manifest's active item), or null. */
    fun engine(name: String): OnDeviceEngines.Engine? = SoftwareManifest.active(current, name, appVersion)?.let(OnDeviceEngines::of)

    /**
     * An earlier version of [name] that is complete on the phone, for when [active] failed to download or check
     * (a bad release must not take away a working engine). Null when there is none.
     */
    fun fallback(name: String, active: OnDeviceEngines.Engine): OnDeviceEngines.Engine? =
        current.items.filter { it.engine == name && it.id != active.model && SoftwareManifest.unsupported(it, appVersion) == null }
            .map(OnDeviceEngines::of).firstOrNull { files(it) != null }

    /** Why [name] can't run here (unknown, or every item for it unsupported or off), or null when it can. */
    fun whyNot(name: String): String? {
        if (engine(name) != null) return null
        val items = current.items.filter { it.engine == name }
        if (items.isEmpty()) return "there is no on-phone engine called $name (GET /v1/images/engines lists them)"
        return items.firstNotNullOfOrNull { SoftwareManifest.unsupported(it, appVersion) } ?: "$name is switched off in the software manifest"
    }

    /** Fetches the manifest now and keeps it if it is newer and checks out; then updates the kept engines. */
    suspend fun check(): JSONObject {
        checkedAt = System.currentTimeMillis()
        try {
            val text = fetch()
            val floor = if (channel == "test") 0 else maxOf(current.serial, prefs.getLong("serial_floor", 0))
            val m = SoftwareManifest.read(text, floor, channel)
            cache.parentFile?.mkdirs(); cache.writeText(text)
            prefs.edit().putLong("serial_floor", if (channel == "test") 0 else m.serial).apply()
            val newer = m.serial != current.serial
            current = m; source = "downloaded"; lastError = null
            if (newer) log("info", "Software manifest ${m.serial} (${m.items.size} items)")
            refresh()
        } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) {
            lastError = x.message ?: x.javaClass.simpleName
            log("warn", "Software manifest check failed: $lastError")
        }
        return manifestJson()
    }

    /** The daily check: only when some engine is kept or asked for, so phones that never use them never fetch. */
    suspend fun dailyCheck() { if (wanted().isNotEmpty()) check() }

    /** Engines someone asked for: named in an API call (kept until removed) or by an installed applet. */
    fun wanted(): Set<String> = (prefs.getStringSet("wanted", emptySet()) ?: emptySet()) + runCatching { appletEngines() }.getOrDefault(emptySet())

    /** Gets every wanted engine's active version (downloads only what's missing); old folders go once it verifies. */
    fun refresh() { for (name in wanted()) engine(name)?.let { ensure(it, remember = false) } }

    private fun fetch(): String {
        val u = url
        check(isOurs(u)) { "manifest URL is not on Bridgit's download host" }
        var out: String? = null
        Outbound.call(u, hint = Outbound.Hint("Bridgit model downloads", "Check the list of optional on-phone software (models for the engines agents and applets asked for)", listOf(cc.bridgit.app.outbound.Categories.NONE))) { meter ->
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Bridgit/${cc.bridgit.app.BuildConfig.VERSION_NAME}")
            meter.status = conn.responseCode
            if (conn.responseCode != 200) throw IllegalStateException("the manifest server answered ${conn.responseCode}")
            if (conn.contentLengthLong > SoftwareManifest.MAX_BYTES) throw IllegalStateException("the manifest is too large")
            val bytes = conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream(); val b = ByteArray(16 * 1024); var n: Int
                while (input.read(b).also { n = it } >= 0) { buf.write(b, 0, n); if (buf.size() > SoftwareManifest.MAX_BYTES) throw IllegalStateException("the manifest is too large") }
                buf.toByteArray()
            }
            meter.receivedBytes = bytes.size.toLong()
            out = String(bytes, Charsets.UTF_8)
        }
        return out ?: throw IllegalStateException("no manifest")
    }

    // ---- files ------------------------------------------------------------------------------------------------

    /** The engine's files once all are present and checked, else null (and nothing is started). */
    fun files(e: OnDeviceEngines.Engine): List<File>? {
        val fs = e.files.map { File(dir(e), it.name) }
        if (!e.files.zip(fs).all { (m, f) -> verified(e, m, f) }) return null
        if (e.kind != "runtime" && prefs.getString("owner:" + e.folder, null) == null) prefs.edit().putString("owner:" + e.folder, e.id).apply()
        st(e).apply { state = READY; reason = null; doneBytes = e.sizeBytes }
        return fs
    }

    fun state(e: OnDeviceEngines.Engine): State = st(e).also { if (it.state != READY && it.state != DOWNLOADING) files(e) }

    /**
     * Starts getting [e]'s files in the background unless they're here, on their way, or waiting for a retry.
     * [remember]: someone asked for it, so the daily check keeps it up to date.
     */
    @Synchronized fun ensure(e: OnDeviceEngines.Engine, remember: Boolean = true) {
        if (remember && e.kind != "runtime") prefs.getStringSet("wanted", emptySet())!!.let { w -> if (e.id !in w) prefs.edit().putStringSet("wanted", w + e.id).apply() }
        if (e.kind != "runtime") runtime?.let { ensure(it, false) }
        if (files(e) != null) { if (e.kind != "runtime") sweep(); return }
        val s = st(e)
        if (jobs[e.folder]?.isActive == true || System.currentTimeMillis() < s.nextTryAt) return
        if (s.state == NOT_DOWNLOADED) s.state = DOWNLOADING // so the caller's reply already says so
        jobs[e.folder] = scope.launch { prepare(e, s) }
    }

    /** Deletes [e]'s files and stops keeping it up to date. */
    @Synchronized fun remove(e: OnDeviceEngines.Engine) {
        prefs.edit().putStringSet("wanted", prefs.getStringSet("wanted", emptySet())!! - e.id).apply()
        jobs.remove(e.folder)?.cancel()
        dir(e).deleteRecursively()
        states.remove(e.folder)
    }

    /**
     * Deletes model folders no engine uses any more: an older version once the engine's new one is downloaded and
     * checked, or an engine the manifest dropped. The runtime and anything still downloading are kept.
     */
    @Synchronized private fun sweep() {
        val ready = HashMap<String, String>() // engine -> its active folder, only when that folder is complete
        val active = HashSet<String>()
        for (name in SoftwareManifest.engines(current)) engine(name)?.let { e -> active += e.folder; if (files(e) != null) ready[name] = e.folder }
        val busy = jobs.filterValues { it.isActive }.keys
        root.listFiles()?.filter { it.isDirectory }?.forEach { f ->
            if (f.name == runtime?.folder || f.name in active || f.name in busy) return@forEach
            val owner = prefs.getString("owner:" + f.name, null)
            // A folder from before the manifest (no owner recorded) goes once every engine in use is complete.
            val replacementReady = if (owner != null) owner in ready || engine(owner) == null else wanted().all { it in ready }
            if (replacementReady && f.deleteRecursively()) log("info", "Removed old on-phone model ${f.name}")
        }
    }

    private suspend fun prepare(e: OnDeviceEngines.Engine, s: State) {
        try {
            if (metered()) { s.state = WAITING_FOR_WIFI; s.reason = "waiting for Wi-Fi to download ${e.sizeMb} MB"; s.nextTryAt = System.currentTimeMillis() + 5 * 60_000L; return }
            s.state = DOWNLOADING; s.reason = null
            dir(e).mkdirs()
            var done = 0L
            for (m in e.files) {
                val f = File(dir(e), m.name)
                if (!verified(e, m, f)) { f.delete(); download(e, m, f) { n -> s.doneBytes = done + n } }
                done += m.size; s.doneBytes = done
            }
            s.state = READY; s.reason = null; s.failures = 0
            prefs.edit().putString("owner:" + e.folder, e.id).apply()
            log("info", "On-phone model ${e.model} ${e.version} ready (${e.sizeMb} MB)")
            if (e.kind != "runtime") sweep()
        } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) {
            s.failures++
            s.state = FAILED; s.reason = "couldn't download ${e.model} (${x.message ?: x.javaClass.simpleName})"
            s.nextTryAt = System.currentTimeMillis() + FaceModel.backoffMs(s.failures)
            log("warn", "On-phone model ${e.model} not ready (try ${s.failures}): ${x.message ?: x.javaClass.simpleName}")
        }
    }

    private fun metered(): Boolean = runCatching { ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    private suspend fun download(e: OnDeviceEngines.Engine, m: OnDeviceEngines.ModelFile, target: File, progress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val url = m.url
        check(isOurs(url)) { "${m.name} is not on Bridgit's download host" }
        try {
            Outbound.call(url, hint = Outbound.Hint("Bridgit model downloads", "Download the on-phone ${e.kind} model (${e.model}) an agent or applet asked for", listOf(cc.bridgit.app.outbound.Categories.NONE))) { meter ->
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false; conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                meter.status = conn.responseCode; meter.receivedType = conn.contentType
                if (conn.responseCode != 200) throw IllegalStateException("the model server answered ${conn.responseCode}")
                var done = 0L
                try {
                    conn.inputStream.use { input -> part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024); var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n); digest.update(buf, 0, n); done += n; progress(done)
                            if (done > m.size) throw IllegalStateException("the download is larger than expected")
                        }
                    } }
                } finally { meter.receivedBytes = done }
            }
            currentCoroutineContext().ensureActive()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != m.size || hash != m.sha256) throw IllegalStateException("${m.name} didn't match the expected file, so it wasn't used")
            if (!part.renameTo(target)) throw IllegalStateException("couldn't save ${m.name}")
            prefs.edit().putString(key(e, m), stamp(m, target)).apply()
        } finally { part.delete() }
    }

    private fun key(e: OnDeviceEngines.Engine, m: OnDeviceEngines.ModelFile) = e.folder + "/" + m.name
    private fun stamp(m: OnDeviceEngines.ModelFile, f: File) = "${m.sha256}:${f.length()}:${f.lastModified()}"

    /** Whether [f] is [m]: hashed once, then trusted by size and modification time. */
    private fun verified(e: OnDeviceEngines.Engine, m: OnDeviceEngines.ModelFile, f: File): Boolean {
        if (!f.exists() || f.length() != m.size) return false
        if (prefs.getString(key(e, m), null) == stamp(m, f)) return true
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(256 * 1024); var n: Int; while (input.read(buf).also { n = it } >= 0) d.update(buf, 0, n) }
        if (d.digest().joinToString("") { "%02x".format(it) } != m.sha256) return false
        prefs.edit().putString(key(e, m), stamp(m, f)).apply(); return true
    }

    // ---- what agents see --------------------------------------------------------------------------------------

    /** One engine as GET /v1/images/engines shows it. */
    fun json(e: OnDeviceEngines.Engine): JSONObject {
        val own = state(e); val rt = runtime?.let { state(it) }
        // Ready only when the runtime it needs is here too; otherwise show whichever part is still missing.
        val s = when {
            runtime == null -> State(UNSUPPORTED, "There is no on-phone runtime for this phone's processor (${android.os.Build.SUPPORTED_ABIS.joinToString()}).")
            own.state == READY && rt!!.state != READY -> rt
            else -> own
        }
        return JSONObject().put("engine", e.id).put("kind", e.kind).put("model", e.model).put("version", e.version)
            .put("good_at", e.goodAt).put("not_good_at", e.notGoodAt)
            .put("speed", e.speed).put("memory", e.memory).put("size_mb", e.sizeMb).put("licence", e.licence)
            .put("cost", "free; runs on the phone, works offline once downloaded")
            .put("installed", s.state == READY).put("state", s.state).put("kept_up_to_date", e.id in wanted())
            .put("downloaded_mb", Math.round(own.doneBytes / 1e6)).apply { s.reason?.let { put("reason", it) } }
            .apply { runtime?.let { r -> put("runtime", JSONObject().put("model", r.model).put("size_mb", r.sizeMb).put("state", rt!!.state)
                .put("note", "Shared by every engine, downloaded once with the first one")) } }
            .put("how_to_use", if (e.kind == "detector") "POST /v1/images/find or /v1/images/crop with \"engine\":\"${e.id}\"" else "POST /v1/images/find or /v1/images/crop with \"outline\":true")
    }

    fun manifestJson(): JSONObject = JSONObject().put("serial", current.serial).put("published_at", current.publishedAt)
        .put("source", source).put("checked_at", if (checkedAt > 0) cc.bridgit.app.capability.isoAt(checkedAt) else JSONObject.NULL)
        .put("signed_by", "the Bridgit team").apply { lastError?.let { put("last_error", it) } }

    /** Every engine the manifest lists: runnable ones in full, the rest with why not. */
    fun jsonAll(): JSONArray = JSONArray(SoftwareManifest.engines(current).map { name ->
        engine(name)?.let { e ->
            val st = state(e)
            // A failed update keeps the version already on the phone running; say both.
            if (st.state != READY) fallback(name, e)?.let { old -> json(old).put("update", JSONObject().put("model", e.model).put("version", e.version)
                .put("state", st.state).apply { st.reason?.let { put("reason", it) } }.put("note", "The phone keeps using version ${old.version} until the update downloads and checks out")) }
            else null
        } ?: engine(name)?.let { json(it) } ?: JSONObject().put("engine", name).put("installed", false).put("state", UNSUPPORTED).put("reason", whyNot(name))
            .put("items", JSONArray(current.items.filter { it.engine == name }.map { JSONObject().put("model", it.id).put("version", it.version).put("good_at", it.goodAt) }))
    })

    companion object {
        const val READY = "ready"; const val DOWNLOADING = "downloading"; const val NOT_DOWNLOADED = "not_downloaded"
        const val WAITING_FOR_WIFI = "waiting_for_wifi"; const val FAILED = "failed"; const val UNSUPPORTED = "unsupported"
    }
}

/** The once-a-day software manifest check (docs/software-manifest.md). Fetches only if some engine is in use. */
class SoftwareCheckWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        (applicationContext as cc.bridgit.app.BridgeApp).onDeviceVision.models.dailyCheck()
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = androidx.work.PeriodicWorkRequestBuilder<SoftwareCheckWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
            androidx.work.WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("software-manifest", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
