package cc.bridgit.app.ui.home

import android.view.accessibility.AccessibilityManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Connection
import cc.bridgit.app.listen.WakeListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * How Home decides what a node shows. Shared by the Home scene ([DiagnosticsScene]) and the Bridgit Portal
 * API (GET /v1/portal/home), so the iPhone/iPad viewer can't drift from the phone's own Home.
 */
object HomeStates {
    /** A node's first known capability decides: switched off = "off", on but blocked = "blocked", else "on". */
    fun capState(app: BridgeApp, caps: List<String>): String {
        val m = caps.firstNotNullOfOrNull { app.registry.byId(it) } ?: return "off"
        if (!m.enabled) return "off"
        val reason = runCatching { m.blockedReason() }.getOrNull()
        // A paused listener is shown by the scene's own Paused state, not as missing access.
        return if (reason == null || reason == "listener_off") "on" else "blocked"
    }

    /** Node ids with a listening halo: voice recognition and sound alerts while the wake listener runs. */
    fun listening(app: BridgeApp): List<String> {
        val st = app.wake.state.value
        val run = app.wake.running
        val on = st !is WakeListener.State.Off && st !is WakeListener.State.Paused
        return listOfNotNull(
            "voicerec".takeIf { on && !run?.words.isNullOrEmpty() },
            "sounds".takeIf { on && run?.sound != null },
        )
    }

    /** The phone's Tailscale IPv4 address, or null when it has none. */
    fun tailscaleAddress(app: BridgeApp): String? = app.listener.value.boundTo.ifEmpty { BridgeService.tailscaleAddresses() }
        .firstOrNull { !it.contains(':') }

    /** External control agents: applets have their own list, and portal, hub and satellite links are devices. */
    fun controlAgents(app: BridgeApp): List<Agent> {
        val portal = runCatching { cc.bridgit.app.portal.PortalPairing.paired(app, cc.bridgit.app.relay.RelayStore(app)).map { it.id }.toSet() }
            .getOrDefault(emptySet())
        // A hub's link here and a satellite's return link are phone-to-phone links, not agents: the map draws them as devices.
        return app.agents.all().filter { it.connection != Connection.APPLET && it.id !in portal &&
            !app.hubLinks.isHub(it.id) && !app.satellites.isReturnAgent(it.id) }
    }

    /**
     * What `BridgitScene.setup()/apply()` draws: node states and check lines for [nodes] (scene node id to its
     * capabilities), the agents, external cameras and the email reminder. Also sent as `scene.options` by GET /v1/portal/home.
     */
    fun options(app: BridgeApp, nodes: List<Pair<String, List<String>>>): JSONObject {
        val states = JSONObject()
        val checks = JSONObject()
        val ip = tailscaleAddress(app)
        for ((id, caps) in nodes) {
            when (id) {
                "tailscale" -> {
                    states.put(id, if (ip != null) "on" else "off")
                    val s = app.listener.value
                    val port = if (s.port > 0) s.port else app.settings.port
                    checks.put(id, JSONArray()
                        .put(JSONArray().put("Tailscale Address").put(ip ?: "None"))
                        .put(JSONArray().put("Listener Port").put(if (s.running) "$port Open" else "$port Closed")))
                }
                "clef" -> states.put(id, capState(app, listOf("watches")))
                "jev" -> states.put(id, capState(app, caps))
                else -> {
                    states.put(id, capState(app, caps))
                    capCheck(app, caps)?.let { checks.put(id, it) }
                }
            }
        }
        val all = controlAgents(app)
        val agent = all.maxWithOrNull(compareBy<Agent> { it.lastUsedAt }.thenBy { -it.createdAt })
        // Every agent with a key: set up, and connected once it has made a call. Connected ones first.
        val agents = JSONArray()
        all.sortedWith(compareByDescending<Agent> { it.lastUsedAt > 0L }.thenBy { it.createdAt })
            .forEach { agents.put(JSONObject().put("name", it.name).put("called", it.lastUsedAt > 0L)) }
        checks.put("agent", JSONArray()
            .put(JSONArray().put("Private Tunnel").put(if (ip != null) "Open" else "Down"))
            .put(JSONArray().put("Key").put("Created")))
        return JSONObject()
            // Owner, 4 Nov 2026: external devices left Home for the Devices tab's Map and List.
            // The key stays (empty) so the page and the Portal viewer keep their shape.
            .put("externalDevices", JSONArray())
            .put("emailReminder", app.alerts.gate().showReminder)
            .put("agent", agent?.name ?: JSONObject.NULL)
            .put("called", (agent?.lastUsedAt ?: 0L) > 0L)
            .put("agents", agents)
            .put("nodes", states)
            .put("checks", checks)
    }

    /** Honest check lines: the feature switch, then access (the line a blocked node fails on). */
    fun capCheck(app: BridgeApp, caps: List<String>): JSONArray? {
        val m = caps.firstNotNullOfOrNull { app.registry.byId(it) } ?: return null
        val reason = runCatching { m.blockedReason() }.getOrNull()
        val access = when {
            reason == null || reason == "listener_off" -> "OK"
            reason.startsWith("permission:") -> "Needs " + UiActions.permissionWord(app, reason.removePrefix("permission:"))
            else -> reason.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        return JSONArray()
            .put(JSONArray().put("Feature Switch").put(if (m.enabled) "On" else "Off"))
            .put(JSONArray().put("Access").put(access))
    }

    /** What `BridgitHomeStatus.update()` shows under the scene: agents and running applets, with their states. */
    fun homeStatus(app: BridgeApp): JSONObject {
        val listener = app.listener.value
        val paused = ListenerCard.isPaused(app)
        // Both agents.all() and watches.tallies() are snapshots of in-memory collections, with no disk reads.
        val agents = controlAgents(app).sortedBy { it.createdAt }.map { agent ->
            statusEntry(app, agent.name, when {
                agent.paused || paused -> "Paused"
                !listener.running -> "Starting"
                listener.bindError != null || listener.boundTo.isEmpty() -> "Waiting for connection"
                else -> "Listening"
            })
        }
        val applets = buildList {
            if (app.dog.state.value.running) add(statusEntry(app, app.getString(R.string.dog_title), "Running").put("icon", "🐕"))
            app.tours.current()?.let { tour ->
                add(statusEntry(app, app.getString(R.string.tour_title), if (tour.paused) "Paused" else "Running").put("icon", "🗺️"))
            }
            val now = System.currentTimeMillis()
            app.watches.tallies().filter { it.expiresAt > now }.forEach { watch ->
                add(statusEntry(app, watch.spec.label,
                    if (watch.userPaused || watch.pausedUntil > now) "Paused" else "Running").put("icon", "👁️"))
            }
        }
        return JSONObject().put("agents", JSONArray(agents)).put("applets", JSONArray(applets))
            .put("emptyApplets", app.getString(R.string.home_status_no_applets))
            .put("position", app.getString(R.string.home_status_position, "{index}", "{count}"))
            .put("rotate", app.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true)
    }

    fun statusEntry(app: BridgeApp, name: String, state: String): JSONObject {
        val label = app.getString(when (state) {
            "Listening" -> R.string.home_state_listening
            "Paused" -> R.string.home_state_paused
            "Starting" -> R.string.home_state_starting
            "Waiting for connection" -> R.string.home_status_waiting
            else -> R.string.home_status_running
        })
        return JSONObject().put("name", name).put("state", state).put("label", label)
            .put("description", if (state == "Listening") app.getString(R.string.home_status_listening_from, name)
                else app.getString(R.string.home_status_named, label, name))
    }
}
