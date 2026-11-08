package cc.bridgit.app.lancamera

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.lan.DeviceBrands
import cc.bridgit.app.lan.DeviceLastSeen
import cc.bridgit.app.lan.DiscoveryHistory
import cc.bridgit.app.lan.HostLan
import cc.bridgit.app.lan.NetworkDevicePresentation
import cc.bridgit.app.lan.NetworkDevicePresentation.Category as Group
import cc.bridgit.app.lan.NetworkDevices
import cc.bridgit.app.log.LogQuery
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The Devices tab's Map view (owner, 4 Nov 2026): Home's scene style for the devices around this phone,
 * bundled as assets/diagnostics/devices.html.
 *
 * - Added cameras and smart devices are "on" (a camera never checked is "blocked", drawn amber).
 * - Devices remembered from Find devices on this network are "seen" when last reachable, else "off".
 * - Requests an agent sends to an added device animate a packet along its line.
 * - Devices sit in group bubbles (Computers, Routers & switches, Cameras, Audio…); tapping one opens that group,
 *   and Back closes it again. Each device carries its group and, when known, its maker's bundled logo.
 *
 * Only names, hosts and kinds reach the page; passwords never do. No network, no file access beyond android_asset.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class DevicesScene(
    context: Context,
    private val app: BridgeApp,
    /** A tap: "find", "list", or a device id ("lan:…" camera, "net:…" smart device, "seen:<host>"). */
    private val onOpen: (String) -> Unit,
) : WebView(context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var released = false
    private var pendingPlay = false
    private var lastState = ""
    private var lastSeenLog = -1L
    private var pollingLog = false
    /** True while a Network Diagnostics scan streams devices onto the map; normal redraws wait for its end. */
    var scanning = false
        private set
    private var lastPush = 0L
    private var pendingFound: List<NetworkDevices.Device>? = null

    /** Another phone's devices (switched to its hub or portal link): drawn instead of this phone's. Read only. */
    class Remote(val name: String, val payload: JSONObject)
    private var remote: Remote? = null
    val isRemote get() = remote != null

    /** Switches the map to another phone's devices ([r]) or back to this phone's (null). Redraws at once. */
    fun setRemote(r: Remote?) {
        val before = remote
        remote = r
        if (r == null && before == null) return
        if (r != null && scanning) scanFinished()
        if (before == null || r == null || before.name != r.name) { lastState = ""; if (ready) play() else pendingPlay = true } else refresh()
    }

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.domStorageEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setBackgroundColor(if (isDarkTheme()) 0xFF070B14.toInt() else 0xFFF8F9FF.toInt())
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.toString().let { it.startsWith(ASSET) || it.startsWith(BRANDS) }) null
                else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        addJavascriptInterface(Bridge(), "BridgitDevicesApp")
        contentDescription = context.getString(R.string.devices_map_cd)
    }

    /** Loads the page; [play] true runs the opening animation once it is ready. */
    fun load(play: Boolean) {
        pendingPlay = play
        loadUrl(ASSET + "devices.html?theme=" + if (isDarkTheme()) "dark" else "light")
    }

    private fun isDarkTheme() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun release() {
        released = true
        backCallback.remove(); backCallback.isEnabled = false
        main.removeCallbacksAndMessages(null)
        runCatching {
            (parent as? android.view.ViewGroup)?.removeView(this)
            removeJavascriptInterface("BridgitDevicesApp"); stopLoading(); destroy()
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() = main.post { onReady() }.let { }
        @JavascriptInterface fun open(id: String) = main.post { if (!released) onOpen(id.take(200)) }.let { }
        /** The page opened a group ("" = back on the overview). */
        @JavascriptInterface fun view(id: String) = main.post { inGroup = id.isNotEmpty(); updateBack() }.let { }
    }

    private var inGroup = false
    /** System Back closes an open group first; the host adds it to the activity's dispatcher. */
    val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { inGroup = false; updateBack(); js("BridgitDevices.back()") }
    }
    private fun updateBack() { backCallback.isEnabled = inGroup && !released && visibility == VISIBLE }
    // Runs from the WebView constructor too, before the callback exists; hence runCatching.
    override fun setVisibility(visibility: Int) { super.setVisibility(visibility); runCatching { updateBack() } }
    private val brands by lazy { runCatching { DeviceBrands(context) }.getOrNull() }
    private fun logoOf(brand: DeviceBrands.Brand?) = brand?.let { "../device-brands/" + it.asset }

    private fun onReady() {
        if (released) return
        ready = true
        lastSeenLog = app.log.latestId.value // only requests from now on animate
        if (scanning) js("BridgitDevices.scanStart(${options(found = lastFound)})")
        else if (pendingPlay) play() else refresh(force = true)
    }

    /** Runs the opening animation: devices fly out from the phone to their places. */
    fun play() {
        if (scanning) return
        if (!ready) { pendingPlay = true; return }
        pendingPlay = false
        val opts = options()
        lastState = opts.toString()
        js("BridgitDevices.setup($opts)")
    }

    /** Redraws without animation when a device was added, removed or seen again. */
    fun refresh(force: Boolean = false) {
        if (!ready || released || scanning) return
        val opts = options()
        val key = opts.toString()
        if (force || key != lastState) {
            lastState = key
            js("BridgitDevices.apply($opts)")
        }
    }

    /** Network Diagnostics started: the map clears to the added devices and the phone starts its sweep. */
    fun scanStarted() {
        scanning = true; pendingPlay = false
        lastFound = emptyList(); knownHosts = emptySet()
        if (ready) js("BridgitDevices.scanStart(${options(found = emptyList())})")
    }

    private var lastFound: List<NetworkDevices.Device> = emptyList()
    private var knownHosts: Set<String> = emptySet()

    /** Devices seen so far in this scan; pushed at most every [PUSH_MS] so the ring re-spaces smoothly. */
    fun scanProgress(found: List<NetworkDevices.Device>) {
        if (!scanning || !ready || released) return
        pendingFound = found
        val wait = lastPush + PUSH_MS - System.currentTimeMillis()
        if (wait > 0) { main.removeCallbacks(flush); main.postDelayed(flush, wait); return }
        flush.run()
    }

    private val flush = Runnable {
        val found = pendingFound ?: return@Runnable
        pendingFound = null
        if (!scanning || released) return@Runnable
        lastPush = System.currentTimeMillis()
        val newest = found.map { it.host }.firstOrNull { it !in knownHosts }
        knownHosts = found.map { it.host }.toSet()
        lastFound = found
        val opts = options(found = found)
        if (newest != null) opts.put("newest", "seen:$newest")
        js("BridgitDevices.scanUpdate($opts)")
    }

    /** Network Diagnostics finished: settle on everything remembered for this network. */
    fun scanFinished() {
        if (!scanning) return
        main.removeCallbacks(flush); pendingFound = null
        scanning = false
        if (!ready || released) return
        val opts = options()
        lastState = opts.toString()
        js("BridgitDevices.scanEnd($opts)")
    }

    /** New log entries that reached an added device become packets on the map. */
    fun pollLog() {
        if (!ready || released || pollingLog || lastSeenLog < 0) return
        pollingLog = true
        val since = lastSeenLog
        Thread {
            val rows = runCatching { app.log.query(LogQuery(sinceId = since, ascending = true, limit = 100)) }.getOrNull()
            main.post {
                if (released) return@post
                pollingLog = false
                if (rows.isNullOrEmpty()) return@post
                lastSeenLog = rows.last().id
                val events = JSONArray()
                rows.forEach { e ->
                    targets(e.endpoint, e.params).forEach { id ->
                        events.put(JSONObject().put("target", id).put("ok", (e.http ?: 200) in 200..299))
                    }
                }
                if (events.length() > 0) js("BridgitDevices.events($events)")
                if (rows.size == 100) pollLog()
            }
        }.start()
    }

    private fun js(code: String) { if (!released) evaluateJavascript(code, null) }

    /** What the map draws: up to [MAX_NODES] devices, added first, then reachable, then most recently seen. */
    private fun options(found: List<NetworkDevices.Device>? = null): JSONObject {
        remote?.let { r -> return remoteOptions(r.payload, r.name, brandOf = ::brandOf, logoOf = ::logoOf) }
        val cameras = runCatching { app.lanCameras.all() }.getOrDefault(emptyList())
        val smart = runCatching { app.lanDevices.all() }.getOrDefault(emptyList())
        val seen = found ?: runCatching {
            val scope = HostLan(context).historyScope(context)
            DiscoveryHistory(context).read(scope)
        }.getOrDefault(emptyList())
        val addedHosts = (cameras.map { it.host } + smart.map { it.host }).toSet()

        // Added to Bridgit first (first in each group, highlighted), then devices Bridgit can link to, then the rest.
        val added = cameras.map { c ->
            Entry(c.id, c.name, c.host, "camera", if (c.verifiedAt != null) "on" else "blocked", true,
                group = Group.CAMERAS, brand = brandOf(c.name))
        } + smart.map { d ->
            val kind = kindOf(d.adapter)
            Entry(d.id, d.name, d.host, kind, "on", true, group = groupOfKind(kind), brand = brandOf(d.name + " " + d.adapter))
        }
        val observed = seen.filter { it.host !in addedHosts }
            .sortedWith(compareBy<NetworkDevices.Device> { NetworkDevicePresentation.linkRank(it, addedHosts) }
                .thenBy { !it.present }.thenByDescending { it.lastSeen })
            .map { d ->
                val info = NetworkDevicePresentation.describe(d)
                val linkable = cc.bridgit.app.lan.DeviceInterfaces.available(d) != null
                Entry("seen:${d.host}", info.title,
                    (if (linkable) "Bridgit · " else "") + if (d.connected || found != null) d.host else DeviceLastSeen.relative(d.lastSeen),
                    info.kind.name.lowercase(), if (d.connected || d.present || found != null) "seen" else "off", false, d.connected || found != null,
                    group = NetworkDevicePresentation.category(info.kind), brand = runCatching { brands?.find(d) }.getOrNull())
            }
        return assemble(added, observed, core = null, logoOf = ::logoOf)
    }

    private fun brandOf(text: String) = brands?.all?.firstOrNull { it.pattern.containsMatchIn(text) }

    internal data class Entry(val id: String, val name: String, val sub: String, val kind: String, val state: String, val added: Boolean,
        val online: Boolean = state == "on", val group: Group = Group.OTHER, val brand: DeviceBrands.Brand? = null)

    companion object {
        private const val ASSET = "file:///android_asset/diagnostics/"
        private const val BRANDS = "file:///android_asset/device-brands/"
        private const val MAX_DEVICES = 200
        /** The map's group order (owner, 4 Nov 2026): computers, routers & switches, cameras, audio, then the rest. */
        internal val MAP_ORDER = listOf(Group.COMPUTERS, Group.NETWORK, Group.CAMERAS, Group.AUDIO, Group.MEDIA,
            Group.PRINTERS, Group.SMART, Group.MOBILE, Group.OTHER)
        private fun groupOfKind(kind: String) = when (kind) { "camera" -> Group.CAMERAS; "speaker" -> Group.AUDIO; "computer" -> Group.COMPUTERS; else -> Group.SMART }
        private const val PUSH_MS = 650L

        /** What the page draws from the entries: every device once, counts, group order; [core] names the phone in the middle. */
        internal fun assemble(added: List<Entry>, observed: List<Entry>, core: String?, logoOf: (DeviceBrands.Brand?) -> String? = { null }): JSONObject {
        val all = added + observed
        // Every device goes to the page so each group counts right; a group shows at most 15 and links to the list.
        val shown = all.take(MAX_DEVICES)
        val devices = JSONArray().apply {
            shown.forEach { e -> put(JSONObject().put("id", e.id).put("name", e.name).put("sub", e.sub).put("kind", e.kind)
                .put("state", e.state).put("linked", e.added).put("group", e.group.name)
                .apply { e.brand?.let { put("brand", it.name).put("logo", logoOf(it)) } }) }
        }
        val groups = JSONArray().apply { MAP_ORDER.forEach { put(JSONObject().put("id", it.name).put("title", it.title)) } }
        val counts = JSONObject()
            .put("total", all.size)
            .put("added", added.size)
            .put("unchecked", added.count { it.state == "blocked" })
            .put("seen", observed.count { it.state == "seen" })
            .put("offline", observed.count { it.state == "off" })
            .put("online", all.count { it.online })
        return JSONObject().put("devices", devices).put("groups", groups).put("more", all.size - shown.size).put("counts", counts).apply { if (!core.isNullOrBlank()) put("core", core) }
        }

        /**
         * The map for another phone's devices (GET /v1/portal/devices on its hub or portal link), named [core]:
         * its own "added" cameras and smart devices first, then what it remembers on its network. Read only.
         */
        /** The remote phone's devices as text rows by group title: (title, ["name · detail", …]), added first. */
        internal fun remoteListRows(payload: JSONObject): List<Pair<String, List<String>>> {
            val o = remoteOptions(payload, "")
            val devs = o.optJSONArray("devices") ?: return emptyList()
            val rows = (0 until devs.length()).mapNotNull { devs.optJSONObject(it) }
            return MAP_ORDER.mapNotNull { g ->
                val inGroup = rows.filter { it.optString("group") == g.name }
                if (inGroup.isEmpty()) null else g.title to inGroup.map { j ->
                    j.optString("name") + (j.optString("sub").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") + if (j.optBoolean("linked")) " · added" else ""
                }
            }
        }

        internal fun remoteOptions(payload: JSONObject, core: String, now: Long = System.currentTimeMillis(),
                                   brandOf: (String) -> DeviceBrands.Brand? = { null }, logoOf: (DeviceBrands.Brand?) -> String? = { null }): JSONObject {
            val addedJson = payload.optJSONArray("added") ?: JSONArray()
            val added = (0 until addedJson.length()).mapNotNull { i -> addedJson.optJSONObject(i) }.mapNotNull { a ->
                val id = a.optString("id").ifEmpty { return@mapNotNull null }
                val kind = a.optString("kind", "smart").lowercase().ifEmpty { "smart" }
                val state = if (a.optString("state") == "blocked") "blocked" else "on"
                val name = a.optString("name").ifBlank { a.optString("host") }.ifBlank { "Device" }
                Entry(id, name, a.optString("host"), kind, state, true, group = groupOfKind(kind),
                    brand = brandOf(name + " " + a.optString("adapter")))
            }
            val addedHosts = added.map { it.sub }.filter { it.isNotEmpty() }.toSet()
            val devs = payload.optJSONArray("devices") ?: JSONArray()
            val seen = HashSet<String>()
            val observed = (0 until devs.length()).mapNotNull { i -> devs.optJSONObject(i) }.mapNotNull { d ->
                val id = d.optString("id").ifEmpty { return@mapNotNull null }
                val host = d.optString("host").ifEmpty { if (id.contains('.') || id.contains(':')) id else "" }
                if (!seen.add(id) || (host.isNotEmpty() && host in addedHosts)) return@mapNotNull null
                val connected = d.optBoolean("connected")
                val lastSeen = d.optLong("last_seen", 0)
                val name = d.optString("name").ifBlank { d.optString("advertised_name") }.ifBlank { host }.ifBlank { "Device" }
                val group = runCatching { Group.valueOf(d.optString("category").uppercase()) }.getOrDefault(Group.OTHER)
                val interfaced = d.optBoolean("bridgit_interfaced")
                Entry("seen:$id", name,
                    (if (interfaced) "Bridgit · " else "") + if (connected) host else if (lastSeen > 0) DeviceLastSeen.relative(lastSeen, now) else host,
                    d.optString("type").lowercase().ifEmpty { "unknown" }, if (connected) "seen" else "off", false, connected,
                    group = group, brand = brandOf(name))
            }.sortedWith(compareBy<Entry> { !it.online })
            return assemble(added, observed, core, logoOf)
        }

        internal fun kindOf(adapter: String) = when {
            adapter.contains("camera", true) || adapter.contains("tapo", true) -> "camera"
            adapter.contains("linkplay", true) || adapter.contains("sonos", true) || adapter.contains("speaker", true) -> "speaker"
            adapter.equals("ssh", true) -> "computer"
            else -> "smart"
        }

        /** The added device ids a logged request went to: `camera`, `cameras` or `device` in its body. */
        internal fun targets(endpoint: String?, params: JSONObject?): List<String> {
            if (endpoint == null || params == null) return emptyList()
            if (!endpoint.startsWith("/v1/lan-cameras/") && !endpoint.startsWith("/v1/lan/devices")) return emptyList()
            val ids = mutableListOf<String>()
            params.optString("camera").takeIf { it.isNotBlank() }?.let(ids::add)
            params.optString("device").takeIf { it.isNotBlank() }?.let(ids::add)
            params.optJSONArray("cameras")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(ids::add) }
            return ids.distinct().take(8)
        }
    }
}
