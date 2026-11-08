package cc.bridgit.app.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import cc.bridgit.app.R
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.capability.address
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.LogQuery
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Device Diagnostics on Home: the scene from docs/animation/takeover.html, bundled as
 * assets/diagnostics/scene.html, fed with the phone's real state.
 *
 * - Node states come from the capability registry: a node's first known capability decides
 *   (switched off = "off", on but blocked = "blocked", else "on"). A node with no capability in this
 *   build is off. Tailscale is on when the phone has a Tailscale address.
 * - Check lines are only what the app knows without side effects: the switch, the access, the address.
 * - Live packets come from new activity-log entries; listening halos from the wake-word listener.
 *
 * The page is Bridgit's own asset: no network, no file access beyond android_asset.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
/**
 * Another phone Home shows instead of this one, read-only: a phone paired through Bridgit Portal (over the relay)
 * or, on a satellite, its hub (over Tailscale). [get] reads its /v1/portal/home and /v1/portal/logs.
 */
class RemoteHome(val device: String, val name: String, val viaSatellite: Boolean,
                 val get: (path: String, query: Map<String, String>) -> JSONObject)

class DiagnosticsScene(
    context: Context,
    private val app: BridgeApp,
    /** A tap: "addAgent", or a node id ("camera", "tailscale", "agent", …). */
    private val onOpen: (String) -> Unit,
    /** The Active / Paused switch: true asks to pause. */
    private val onPauseAsked: (Boolean) -> Unit,
    /**
     * Bridgit Portal viewer: another phone to show instead of this one, read-only, through the relay. Its Home data
     * comes from its /v1/portal/home and /v1/portal/logs (the same shapes this phone's Home uses). Null = this phone.
     */
    private val remote: RemoteHome? = null,
    /** In remote mode: (last good data at, last error or null) after every fetch, for Home's status line. */
    private val onRemoteStatus: (Long, String?) -> Unit = { _, _ -> },
) : WebView(context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var released = false
    private var nodes: List<Pair<String, List<String>>> = emptyList()
    private var lastState = ""
    private var lastHomeStatus = ""
    private var pendingPlay = false
    private var lastSeen = 0L
    private var oldestLoaded: Long? = null
    private var initialLogLoaded = false
    private var pollingLog = false
    private var loadingOlder = false
    private var hasOlder = true
    @Volatile private var remoteHome: JSONObject? = null
    private var remoteFeed: Thread? = null

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.domStorageEnabled = false
        isVerticalScrollBarEnabled = true // the page scrolls: status, map, then the full log
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, cc.bridgit.app.R.color.b_surface))
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.toString().startsWith(ASSET)) null
                else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        addJavascriptInterface(Bridge(), "BridgitHome")
        contentDescription = context.getString(cc.bridgit.app.R.string.home_scene_cd)
    }

    /** Loads the page; [play] true runs the full diagnostics animation once it is ready. */
    fun load(play: Boolean) {
        pendingPlay = play
        loadUrl(ASSET + "scene.html?theme=" + if (isDarkTheme()) "dark" else "light")
    }

    private fun isDarkTheme() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun release() {
        released = true
        remoteFeed?.interrupt()
        main.removeCallbacksAndMessages(null)
        runCatching {
            (parent as? android.view.ViewGroup)?.removeView(this)
            removeJavascriptInterface("BridgitHome"); stopLoading(); destroy()
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() = main.post { onReady() }.let { }
        @JavascriptInterface fun setPaused(p: Boolean) = main.post {
            // Read-only view of another phone: the switch shows its state but can't change it.
            if (!released) { if (remote != null) sync() else onPauseAsked(p) }
        }.let { }
        @JavascriptInterface fun open(id: String) = main.post { if (!released) onOpen(id) }.let { }
        @JavascriptInterface fun older() = main.post { if (!released) loadActivity() }.let { }
        /** One log line, new or updated (a check line gets its value after it is printed). */
        @JavascriptInterface fun line(id: Int, json: String) = keepLine(id, json)
        /** The Rerun button: read the phone's state again and play the full run from the start. */
        @JavascriptInterface fun rerun() = main.post { if (!released) play() }.let { }
    }

    private fun onReady() {
        if (released) return
        evaluateJavascript("JSON.stringify(BridgitScene.nodes.map(function(n){return [n.id, n.caps];}))") { raw ->
            if (released) return@evaluateJavascript
            nodes = runCatching {
                val arr = JSONArray(JSONArray("[$raw]").getString(0))
                (0 until arr.length()).map { i ->
                    val n = arr.getJSONArray(i); val caps = n.getJSONArray(1)
                    n.getString(0) to (0 until caps.length()).map { caps.getString(it) }
                }
            }.getOrDefault(emptyList())
            ready = true
            // The shared portal overlay (also used by the iOS app): here it only draws who is viewing this phone.
            runCatching { context.assets.open("diagnostics/portal-overlay.js").bufferedReader().use { it.readText() } }
                .getOrNull()?.let { js(it) }
            if (historyKey != sourceKey) synchronized(history) { history.clear(); nextLine = 1; historyKey = sourceKey }
            if (remote == null) restoreLog()
            // Backfill the stored log; Home is not the start of the activity history.
            loadActivity()
            if (remote != null) startRemoteFeed()
            if (pendingPlay) play() else refresh(force = true)
        }
    }

    /** Runs the full Device Diagnostics animation (tap to skip). */
    fun play() {
        if (!ready || (remote != null && remoteHome == null)) { pendingPlay = true; return }
        pendingPlay = false
        val opts = options()
        lastState = opts.toString()
        js("BridgitScene.setup($opts)")
        sync()
    }

    /** Redraws the finished scene when a switch, access or the agent changed; then syncs pause and halos. */
    fun refresh(force: Boolean = false) {
        if (!ready || (remote != null && remoteHome == null)) return
        val opts = options()
        val key = opts.toString()
        if (force || key != lastState) {
            lastState = key
            js("BridgitScene.apply($opts)") // waits for the end of a running full run
        }
        sync()
    }

    /** Lightweight session refresh; no diagnostic capability checks on the one-second tick. */
    fun refreshStatus() { if (ready && !released) sync() }

    private fun sync() {
        if (remote != null) { syncRemote(); return }
        syncHub()
        syncViewer()
        js("BridgitScene.setPaused(${ListenerCard.isPaused(app)})")
        js("BridgitScene.status(${JSONObject().put("listening", JSONArray(listening()))})")
        val payload = HomeStates.homeStatus(app).toString()
        if (payload != lastHomeStatus) {
            lastHomeStatus = payload
            js("BridgitHomeStatus.update(JSON.parse(${JSONObject.quote(payload)}))")
        }
    }

    private val sourceKey get() = remote?.device ?: "local"

    /** This phone's short name for the viewer node and the hexagon: the owner's name for it, else e.g. "Galaxy Tab A11+". */
    private fun ownShortName(): String = cc.bridgit.app.portal.PortalPairing.phoneName(app).removePrefix("Samsung ").take(24)

    private var hubShown = ""

    /**
     * With other phones paired, the hexagon names the phone shown and a tap on it opens the phone chooser
     * (portal-overlay.js hub()). Without any, Home looks as it always has.
     */
    private fun syncHub() {
        val name = if (remote != null) remote.name
            else if (runCatching { cc.bridgit.app.portal.PortalLinks(app).count() > 0 || app.hubLinks.ids().isNotEmpty() }.getOrDefault(false))
                cc.bridgit.app.portal.PortalPairing.phoneName(app)
            else return
        val other = remote != null
        val key = "$other:$name"
        if (key == hubShown) return
        hubShown = key
        // other: another phone is shown, so the hexagon becomes the turning star with "HUB".
        js("window.BridgitPortal && BridgitPortal.hub(${JSONObject.quote(name)}, $other)")
    }

    /** Remote mode: the other phone's pause, listening halos and status, and this phone drawn as the viewer. */
    private fun syncRemote() {
        syncHub()
        val scene = remoteHome?.optJSONObject("scene") ?: return
        js("BridgitScene.setPaused(${scene.optBoolean("paused")})")
        js("BridgitScene.status(${JSONObject().put("listening", scene.optJSONArray("listening") ?: JSONArray())})")
        scene.optJSONObject("home_status")?.toString()?.let { payload ->
            if (payload != lastHomeStatus) { lastHomeStatus = payload; js("BridgitHomeStatus.update(JSON.parse(${JSONObject.quote(payload)}))") }
        }
        // Drawn every tick: a full diagnostics run redraws the scene's groups.
        js("window.BridgitPortal && BridgitPortal.viewers(${JSONArray().put(JSONObject().put("kind", ownShortName())
            .put("sub", if (remote?.viaSatellite == true) "Satellite" else "Portal").put("live", true).put("self", true))})")
    }

    /** Remote mode: the other phone's Home every 10 s, then its new log entries. One thread; stops on release. */
    private fun startRemoteFeed() {
        val link = remote ?: return
        if (remoteFeed != null) return
        remoteFeed = Thread {
            while (!released) {
                val r = runCatching { link.get("/v1/portal/home", emptyMap()) }
                main.post {
                    if (released) return@post
                    r.onSuccess { home ->
                        val first = remoteHome == null
                        remoteHome = home
                        cc.bridgit.app.portal.PortalViewer.remember(link.device, home)
                        onRemoteStatus(System.currentTimeMillis(), null)
                        if (first && pendingPlay) play() else refresh()
                        pollRemoteLog()
                    }.onFailure { onRemoteStatus(0L, it.message ?: it.javaClass.simpleName) }
                }
                try { Thread.sleep(REMOTE_EVERY_MS) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "portal-viewer"; start() }
    }

    /** Remote mode: one page of the other phone's log (scene-shaped, redacted entries), oldest first. */
    private fun remoteLogs(query: Map<String, String>): JSONObject =
        remote!!.get("/v1/portal/logs", query)

    private fun controlAgents() = HomeStates.controlAgents(app)

    private var lastViewer = ""

    /**
     * Top right (owner, 5 Nov 2026): every phone connected to this one, drawn as one tile (a stack when several) that
     * lists them when tapped. On a hub its satellites and its Bridgit Portals; on a satellite or a portal, its hubs.
     */
    private fun syncViewer() {
        val list = runCatching { connectedNodes() }.getOrDefault(JSONArray())
        val key = list.toString()
        // Drawn every tick while shown: a full diagnostics run redraws the scene's groups.
        if (list.length() == 0 && key == lastViewer) return
        lastViewer = key
        js("window.BridgitPortal && BridgitPortal.viewers($key)")
    }

    private fun connectedNodes(): JSONArray {
        val out = JSONArray()
        // The phones this one is attached to, as a satellite or as a portal: both are its hubs (owner: same display).
        // Every hub link, including one whose hub hasn't checked in yet (it shows by its link name until it does).
        val names = app.hubLinks.hubNames().toMap()
        app.hubLinks.ids().forEach { id ->
            val name = names[id] ?: app.agents.get(id)?.name?.removePrefix("Hub ") ?: return@forEach
            out.put(JSONObject().put("kind", name.removePrefix("Google ").take(24)).put("sub", "Hub").put("live", names[id] != null).put("idle", names[id] == null))
        }
        runCatching { cc.bridgit.app.portal.PortalLinks(app).all() }.getOrDefault(emptyList()).forEach { l ->
            val seen = cc.bridgit.app.portal.PortalViewer.seen(l.device)?.at ?: 0L
            val recent = System.currentTimeMillis() - seen <= cc.bridgit.app.portal.PortalPairing.SHOWN_MS
            out.put(JSONObject().put("kind", l.name.removePrefix("Google ").take(24)).put("sub", "Hub").put("live", recent).put("idle", !recent))
        }
        app.satellites.summaries().forEach { (name, online) ->
            out.put(JSONObject().put("kind", name.removePrefix("Google ").take(24)).put("sub", "Satellite").put("live", online).put("idle", !online))
        }
        val store = cc.bridgit.app.relay.RelayStore(app)
        val recent = cc.bridgit.app.portal.PortalPairing.viewers(app, store).associateBy { it.agentId }
        cc.bridgit.app.portal.PortalPairing.paired(app, store).forEach { a ->
            val v = recent[a.id]
            out.put(JSONObject().put("kind", v?.kind ?: cc.bridgit.app.portal.PortalModule.clientOf(a.id) ?: "iPhone/iPad")
                .put("sub", "Portal").put("live", v?.live == true).put("idle", v == null))
        }
        return out
    }

    /** Older stored activity, newest page first; no channel or success filter. */
    private fun loadActivity() {
        if (!ready || released || loadingOlder || !hasOlder) return
        loadingOlder = true
        if (remote != null) {
            val initial = !initialLogLoaded
            val q = mutableMapOf("limit" to LOG_PAGE.toString())
            if (!initial) oldestLoaded?.let { q["until"] = it.toString() }
            Thread {
                val result = runCatching { remoteLogs(q) }
                main.post {
                    if (released) return@post
                    loadingOlder = false
                    result.fold(onSuccess = { page ->
                        hasOlder = page.optBoolean("has_older")
                        if (!page.isNull("oldest_id")) oldestLoaded = page.optLong("oldest_id")
                        if (initial) { lastSeen = page.optLong("next_since"); initialLogLoaded = true }
                        js("BridgitScene.history(${page.optJSONArray("entries") ?: JSONArray()}, $hasOlder)")
                    }, onFailure = { js("BridgitScene.historyFailed()") })
                }
            }.start()
            return
        }
        val initial = !initialLogLoaded
        val snapshot = if (initial) app.log.latestId.value else lastSeen
        val before = if (initial) snapshot + 1 else oldestLoaded
        Thread {
            val result = runCatching { app.log.query(LogQuery(untilId = before, limit = LOG_PAGE + 1)) }
            main.post {
                if (released) return@post
                loadingOlder = false
                result.fold(onSuccess = { rows ->
                    val page = rows.take(LOG_PAGE).asReversed()
                    hasOlder = rows.size > LOG_PAGE
                    page.firstOrNull()?.let { oldestLoaded = it.id }
                    if (initial) { lastSeen = snapshot; initialLogLoaded = true }
                    val entries = JSONArray().apply { page.forEach { put(entryJson(it)) } }
                    js("BridgitScene.history($entries, $hasOlder)")
                    pollLog() // catch requests arriving while this page was read
                }, onFailure = {
                    js("BridgitScene.historyFailed()")
                })
            }
        }.start()
    }

    /** Remote mode: the other phone's new log entries (called by the feed, not by this phone's own log). */
    private fun pollRemoteLog() {
        if (!ready || released || !initialLogLoaded || pollingLog) return
        pollingLog = true
        run {
            val since = lastSeen
            Thread {
                val result = runCatching { remoteLogs(mapOf("since" to since.toString(), "limit" to LOG_PAGE.toString())) }
                main.post {
                    if (released) return@post
                    pollingLog = false
                    result.getOrNull()?.let { page ->
                        lastSeen = maxOf(lastSeen, page.optLong("next_since"))
                        val entries = page.optJSONArray("entries") ?: JSONArray()
                        if (entries.length() > 0) js("BridgitScene.events($entries)")
                    }
                }
            }.start()
            return
        }
    }

    /** All new stored entries appear in the terminal; only matching successes animate packets. */
    fun pollLog() {
        if (remote != null || !ready || released || !initialLogLoaded || pollingLog) return
        pollingLog = true
        val since = lastSeen
        val snapshot = app.log.latestId.value
        Thread {
            val result = runCatching {
                app.log.query(LogQuery(sinceId = since, ascending = true, limit = LOG_PAGE))
            }
            main.post {
                if (released) return@post
                pollingLog = false
                result.getOrNull()?.let { list ->
                    if (list.isNotEmpty()) {
                        lastSeen = list.last().id
                        val entries = JSONArray().apply { list.forEach { put(entryJson(it)) } }
                        js("BridgitScene.events($entries)")
                    } else {
                        // Retention may have removed rows; do not retry the same empty range.
                        lastSeen = maxOf(lastSeen, snapshot)
                    }
                    // A collector wakeup can arrive while this query is in flight, even if it was empty.
                    if (list.size == LOG_PAGE || app.log.latestId.value > lastSeen) pollLog()
                }
            }
        }.start()
    }

    private fun restoreLog() {
        val (lines, next) = synchronized(history) { history.map { it.second } to nextLine }
        js("BridgitScene.restore([${lines.joinToString(",")}], $next)")
    }

    private fun js(code: String) { if (!released) evaluateJavascript(code, null) }

    private fun entryJson(e: LogEntry): JSONObject = JSONObject()
        .put("id", e.id).put("ts", e.ts).put("channel", e.channel).put("level", e.level)
        .put("result", e.result ?: "").put("kind", e.kind).put("method", e.method ?: "").put("endpoint", e.endpoint ?: "")
        .put("params", e.params ?: JSONObject()).put("http", e.http ?: 0).put("message", e.message)
        .put("data", e.data ?: JSONObject()).put("agent", e.agent ?: "")

    private fun listening(): List<String> = HomeStates.listening(app)

    private fun tailscaleAddress(): String? = HomeStates.tailscaleAddress(app)

    /** The state the scene draws, from the registry, the agents and the listener. */
    private fun options(): JSONObject =
        if (remote != null) remoteHome?.optJSONObject("scene")?.optJSONObject("options") ?: JSONObject()
        else HomeStates.options(app, nodes)

    companion object {
        private const val ASSET = "file:///android_asset/diagnostics/"
        private const val MAX_LINES = 2000

        /** The Home log, kept for the life of the app process so it survives leaving Home: (line id, segments JSON). */
        private val history = ArrayDeque<Pair<Int, String>>()
        private var nextLine = 1
        /** Whose lines [history] holds: "local" or the other phone's device id. */
        private var historyKey = "local"
        private const val LOG_PAGE = 100
        private const val REMOTE_EVERY_MS = 10_000L

        private fun keepLine(id: Int, json: String) {
            // Only a JSON array of segments from our own page is kept; anything else is dropped.
            if (runCatching { JSONArray(json) }.isFailure) return
            synchronized(history) {
                val i = history.indexOfLast { it.first == id }
                if (i >= 0) history[i] = id to json else history.addLast(id to json)
                while (history.size > MAX_LINES) history.removeFirst()
                nextLine = maxOf(nextLine, id + 1)
            }
        }
    }
}
