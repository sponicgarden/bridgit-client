package cc.bridgit.app.lan.packs

import android.content.Context
import cc.bridgit.app.core.SafeHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's device packs (docs/device-packs.md): the last good signed index and the packs it has downloaded.
 *
 * - The index is checked once a day ([DevicePackWorker]), during discovery and before a pack is used when the
 *   phone's copy is over [FRESH_MS] old. It is kept only if it is signed, for this channel and not older.
 * - A pack is downloaded the first time an agent enrolls a device with it, then updated to each newer version the
 *   index lists. Versions never go down. A download that fails its size/SHA-256/content check is refused and the
 *   installed version keeps working; so does an older version when the newest needs a newer app.
 * - A pack the index withdraws (enabled false, or no longer listed) stops working at once.
 */
class DevicePackStore private constructor(private val ctx: Context) {
    companion object {
        const val FRESH_MS = 6 * 60 * 60 * 1000L
        const val MIN_GAP_MS = 60 * 1000L
        @Volatile private var instance: DevicePackStore? = null
        fun get(context: Context): DevicePackStore = instance ?: synchronized(this) {
            instance ?: DevicePackStore(context.applicationContext).also {
                instance = it
                runCatching { DevicePackWorker.schedule(context.applicationContext) }
            }
        }
        /** Packs from the last good index this app can use, for matching discovered devices. Empty before the first check. */
        @Volatile var known: List<DevicePack.Entry> = emptyList(); private set
    }

    private val dir = File(ctx.noBackupFilesDir, "device_packs")
    private val indexFile = File(dir, "index.json")
    private val prefs = ctx.getSharedPreferences("device_packs", Context.MODE_PRIVATE)
    private val appVersion get() = cc.bridgit.app.BuildConfig.VERSION_CODE
    @Volatile var index: DevicePack.Index? = null; private set
    private val packErrors = mutableMapOf<String, String>()

    init { index = runCatching { DevicePack.readIndex(indexFile.readText(), 0) }.getOrNull(); publish() }

    private fun publish() { known = index?.packs?.filter { DevicePack.unsupported(it, appVersion) == null }.orEmpty() }

    private fun fetch(url: String, max: Long): ByteArray {
        val r = SafeHttp.get(url, SafeHttp.Policy.PUBLIC, 15_000, max + 1, headers = mapOf("User-Agent" to "Bridgit/${cc.bridgit.app.BuildConfig.VERSION_NAME}"))
        if (r.status != 200) throw DevicePack.Bad("the download host answered HTTP ${r.status}")
        val b = r.bytes ?: ByteArray(0)
        if (b.size > max) throw DevicePack.Bad("the download is larger than ${max / 1024} KB")
        return b
    }

    /** Fetches the index now and updates installed packs. Returns [status]. Never throws; failures are recorded. */
    @Synchronized fun check(): JSONObject {
        val last = prefs.getLong("attempted_at", 0)
        if (System.currentTimeMillis() - last < MIN_GAP_MS) return status().put("note", "checked less than a minute ago")
        prefs.edit().putLong("attempted_at", System.currentTimeMillis()).apply()
        try {
            val text = String(fetch(DevicePack.INDEX_URL, DevicePack.MAX_INDEX_BYTES), Charsets.UTF_8)
            val floor = maxOf(prefs.getLong("serial_floor", 0), index?.serial ?: 0)
            val i = DevicePack.readIndex(text, floor)
            dir.mkdirs(); File(dir, "index.tmp").apply { writeText(text); renameTo(indexFile) }
            index = i; publish()
            prefs.edit().putLong("serial_floor", i.serial).putLong("checked_at", System.currentTimeMillis()).remove("last_error").apply()
            for (id in installedIds()) {
                val e = i.entry(id) ?: continue
                val have = installed(id)
                if (DevicePack.unsupported(e, appVersion) == null && (have == null || DevicePack.compareVersions(e.version, have.version) > 0))
                    runCatching { download(e) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            prefs.edit().putString("last_error", (e.message ?: e.javaClass.simpleName).take(200)).apply()
        }
        return status()
    }

    /** [check] when the phone's index is missing or over six hours old. */
    fun refreshIfStale() {
        if (index == null || System.currentTimeMillis() - prefs.getLong("checked_at", 0) > FRESH_MS) check()
    }

    private fun packDir(id: String) = File(File(dir, "packs"), id)
    private fun installedIds(): List<String> = File(dir, "packs").listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

    /** The newest pack version on the phone for [id], or null. Files are app-private and were checked when downloaded. */
    @Synchronized fun installed(id: String): DevicePack.Pack? =
        packDir(id).listFiles()?.mapNotNull { f -> runCatching { DevicePack.parsePack(f.readText()) }.getOrNull()?.takeIf { it.id == id && f.name == "${it.version}.json" } }
            ?.maxWithOrNull { a, b -> DevicePack.compareVersions(a.version, b.version) }

    private fun download(e: DevicePack.Entry): DevicePack.Pack {
        try {
            val bytes = fetch(e.url, DevicePack.MAX_PACK_BYTES)
            val p = DevicePack.readPack(bytes, e)
            val d = packDir(e.id).apply { mkdirs() }
            File(d, "${p.version}.tmp").apply { writeBytes(bytes); require(renameTo(File(d, "${p.version}.json"))) }
            d.listFiles()?.filter { it.name != "${p.version}.json" }?.forEach { it.delete() }
            synchronized(packErrors) { packErrors.remove(e.id) }
            return p
        } catch (x: Exception) {
            synchronized(packErrors) { packErrors[e.id] = "version ${e.version} was refused: ${x.message ?: x.javaClass.simpleName}".take(200) }
            throw x
        }
    }

    /**
     * The pack to run for [id]: the index's current version, downloaded now if the phone doesn't have it; the
     * installed version when the newest can't be fetched or needs a newer app. Throws [PackEngine.PackError].
     */
    @Synchronized fun pack(id: String): DevicePack.Pack {
        runCatching { refreshIfStale() }
        val i = index ?: throw PackEngine.PackError("Bridgit hasn't downloaded the device pack list yet; check the phone's internet connection and retry.", false)
        val e = i.entry(id) ?: throw PackEngine.PackError("There is no device pack called $id; GET /v1/lan/packs lists them.", false)
        if (!e.enabled) throw PackEngine.PackError("Bridgit withdrew the $id device pack.", false)
        val have = installed(id)
        if (have != null && DevicePack.compareVersions(have.version, e.version) >= 0) return have
        DevicePack.unsupported(e, appVersion)?.let { why -> return have ?: throw PackEngine.PackError("The $id pack can't run here: $why.", false) }
        return try { download(e) } catch (x: Exception) {
            have ?: throw PackEngine.PackError("The $id device pack couldn't be downloaded: ${x.message ?: "network error"}.", false)
        }
    }

    fun status(): JSONObject {
        val i = index
        return JSONObject().put("engine_version", PackEngine.VERSION).put("channel", DevicePack.CHANNEL)
            .put("index", JSONObject().put("serial", i?.serial ?: 0).put("published_at", i?.publishedAt ?: JSONObject.NULL)
                .put("checked_at", prefs.getLong("checked_at", 0)).put("last_error", prefs.getString("last_error", null) ?: JSONObject.NULL))
            .put("auto_update", "Daily, during discovery, and before a pack is used when the list is over 6 hours old. Versions only go up; a refused download keeps the installed version.")
            .put("packs", JSONArray((i?.packs.orEmpty().map { it.id } + installedIds()).distinct().sorted().map { id ->
                val e = i?.entry(id); val have = installed(id)
                val why = e?.let { DevicePack.unsupported(it, appVersion) }
                JSONObject().put("id", id).put("title", e?.title ?: have?.title ?: id)
                    .put("latest_version", e?.version ?: JSONObject.NULL).put("installed_version", have?.version ?: JSONObject.NULL)
                    .put("state", when {
                        e == null || !e.enabled -> "withdrawn"
                        have != null && DevicePack.compareVersions(have.version, e.version) >= 0 -> "installed"
                        why != null -> if (have != null) "installed_update_needs_app" else "unsupported"
                        have != null -> "update_pending"
                        else -> "available"
                    })
                    .put("reason", why ?: synchronized(packErrors) { packErrors[id] } ?: JSONObject.NULL)
                    .put("match_services", JSONArray(e?.servicesAny.orEmpty()))
                    .put("actions", have?.let { JSONArray(it.actions.map { a -> a.name }) } ?: JSONObject.NULL)
            }))
    }
}

/** The daily device-pack check (docs/device-packs.md). */
class DevicePackWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DevicePackStore.get(applicationContext).check() }
        return Result.success()
    }
    companion object {
        fun schedule(ctx: Context) {
            val req = androidx.work.PeriodicWorkRequestBuilder<DevicePackWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
            androidx.work.WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("device-packs", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
