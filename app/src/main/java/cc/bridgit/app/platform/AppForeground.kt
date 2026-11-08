package cc.bridgit.app.platform

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.interact.MainActivityState
import cc.bridgit.app.ui.MainActivity
import cc.bridgit.app.ui.home.HomeStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import cc.bridgit.app.ui.applets.AppletPortalsFragment
import cc.bridgit.app.ui.applets.AppletPortalActivity

/** A control-agent capability, never a card or a way around Android consent/keyguard. */
class AppForeground(private val app: BridgeApp) {
    private data class CardRequest(val agent:String,val card:Long,val until:Long,val credential:String)
    private val cardRequests=java.util.concurrent.ConcurrentHashMap<String,CardRequest>()
    /** Only a fresh authenticated navigation request can override notification-history suppression. */
    fun consumeCardRequest(intent:Intent, card:Long): Boolean {
        val token=intent.getStringExtra(EXTRA_CARD_REQUEST) ?: return false
        intent.removeExtra(EXTRA_CARD_REQUEST)
        val request=cardRequests.remove(token) ?: return false
        if(request.card!=card || request.until<System.currentTimeMillis()) return false
        return runCatching { authorize(request.agent); if(app.platform.getAgent(request.agent).hash!=request.credential) return@runCatching false; cardFor(request.agent,app.interactions.get(card)); true }.getOrDefault(false)
    }
    private fun cardRequest(agent:String,card:Long):String {
        val now=System.currentTimeMillis(); cardRequests.entries.removeIf { it.value.until<now }
        return java.util.UUID.randomUUID().toString().also { cardRequests[it]=CardRequest(agent,card,now+300_000,app.platform.getAgent(agent).hash) }
    }
    private fun authorize(agent: String) {
        app.platform.authorize(agent, "app.foreground")
        if (HomeStates.controlAgents(app).none { it.id == agent })
            throw ApiError(403, "forbidden", "Only owner-authorized control agents can foreground Bridgit")
    }

    fun targets(agent: String): JSONObject {
        authorize(agent)
        val canPortals = app.agents.get(agent)?.allows("applet_portals") == true && app.settings.isOn("applet_portals")
        return JSONObject().put("screens", JSONArray(SCREENS.keys.map { id -> JSONObject().put("type","screen").put("id",id).apply { SCREEN_NOTES[id]?.let { put("description",it) } } }))
            .put("applets", if (canPortals) JSONArray(app.applets.all().filter { app.appletPortals.kind(it) != null || it.optString("native") == "dog" }.map {
                JSONObject().put("type","applet").put("id",it.getString("id")).put("name",it.optString("name")) }) else JSONArray())
            .put("portals", if (canPortals) JSONArray(AppletPortalsFragment.list(app).map { JSONObject().put("type","portal").put("id",it.id).put("name",it.name) }) else JSONArray())
            .put("cards", if(app.agents.get(agent)?.allows("interactions")==true && app.settings.isOn("interactions")) JSONArray(app.interactions.listFor(agent,null,300).map { JSONObject().put("type","card").put("id",it.id.toString()).put("name",it.title).put("kind",it.kind.id) }) else JSONArray())
            .put("platform","android").put("supported_target_types",JSONArray(listOf("screen","card","applet","portal")))
            .put("foreground_support","subject_to_android_background_launch_policy").put("notification_fallback",true)
            .put("target_device","this_android_device").put("other_platforms","Use the target device discovery; absent foreground support is unsupported, never success. iOS/macOS portal clients do not expose this control API.")
            .put("min_interval_ms",1000).put("max_per_minute",12)
    }

    private fun target(agent: String, args: JSONObject): Pair<String,String> {
        val t=args.optJSONObject("target") ?: return "app" to ""
        val type=t.getString("type"); val id=t.getString("id").let { if(type=="screen") LEGACY_SCREENS[it] ?: it else it }
        if(type=="screen") {
            if(id !in SCREENS) throw ApiError(404,"not_found","Unknown screen; read app.foreground.targets")
        } else if(type=="card") {
            if(app.agents.get(agent)?.allows("interactions") != true || !app.settings.isOn("interactions")) throw ApiError(403,"agent_not_allowed","Interactions access is required")
            cardFor(agent,id.toLongOrNull()?.let { app.interactions.get(it) })
        } else {
            if(app.agents.get(agent)?.allows("applet_portals") != true || !app.settings.isOn("applet_portals")) throw ApiError(403,"agent_not_allowed","Applet portals access is required")
            if(type=="applet") {
                val row=app.applets.get(id) ?: throw ApiError(404,"not_found","No installed applet with that id")
                if(row.optString("native")=="dog") return "screen" to "pet_monitor"
                if(app.applets.portalIsScreen(row)) {
                    val portal="pkg."+row.getString("package_id")
                    if(AppletPortalsFragment.list(app).none { it.id==portal }) throw ApiError(404,"not_found","The applet consumer portal is unavailable in the current device context")
                    return "portal" to portal
                }
                if(app.appletPortals.kind(row)==null) throw ApiError(422,"not_supported","This applet has no screen")
            } else if(AppletPortalsFragment.list(app).none { it.id==id }) throw ApiError(404,"not_found","No consumer portal with that id in the current device context")
        }
        return type to id
    }

    private fun visible(type: String,id: String): Boolean = when(type) {
        "screen" -> MainActivityState.inFront && MainActivityState.destination == SCREENS[id]
        "card" -> MainActivityState.inFront && (MainActivityState.cardId==id.toLongOrNull() || MainActivityState.textPanelId==id.toLongOrNull())
        "applet" -> app.appletPortals.list().let { a -> (0 until a.length()).any { a.getJSONObject(it).let { row -> row.optString("id")==id && row.optBoolean("on_screen") } } }
        "portal" -> MainActivityState.inFront && MainActivityState.destination==R.id.interactions && AppletPortalsFragment.foregroundId()==id
        else -> MainActivityState.inFront
    }

    suspend fun open(agent: String, args: JSONObject): JSONObject {
        val reason = validate(args)
        authorize(agent)
        val (type,id) = target(agent,args)
        val store = app.platform.store
        synchronized(store) {
            val now = System.currentTimeMillis()
            val last = store.setting("app.foreground:last_attempt").toLongOrNull()
            val wait = remaining(now, last)
            if (wait > 0) throw ApiError(429, "rate_limited", "Wait before bringing Bridgit forward again", mapOf("retry_after_seconds" to (wait + 999) / 1000))
            val history=runCatching { JSONArray(store.setting("app.foreground:attempts","[]")) }.getOrDefault(JSONArray())
            val recent=(0 until history.length()).map { history.getLong(it) }.filter { now-it < 60_000 }
            if(recent.size >= 12) throw ApiError(429,"rate_limited","At most 12 foreground switches per minute")
            store.setting("app.foreground:attempts",JSONArray(recent+now).toString(),true)
            // Persist before dispatch: process restarts and several agents cannot evade the cooldown.
            store.setting("app.foreground:last_attempt", now.toString(), true)
        }
        val identity = app.platform.getAgent(agent).name
        app.log.system("audit", "app.foreground", "$identity requested $type:$id in the foreground: $reason")
        return withContext(Dispatchers.Main) {
            authorize(agent)
            if(target(agent,args) != (type to id)) throw ApiError(409,"target_changed","Foreground target changed before launch")
            val locked = app.getSystemService(KeyguardManager::class.java).isDeviceLocked
            val intent = (if(type=="applet") AppletPortalActivity.intent(app,id) else Intent(app, MainActivity::class.java).apply {
                if(type=="screen") putExtra(EXTRA_SCREEN,id)
                if(type=="card") { putExtra(MainActivity.EXTRA_INTERACTION_ID,id.toLong()); putExtra(EXTRA_CARD_REQUEST,cardRequest(agent,id.toLong())) }
                if(type=="portal") putExtra(MainActivity.EXTRA_OPEN_PORTAL,id)
            }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            var launchError: String? = null
            if (!locked && !visible(type,id)) {
                try { app.startActivity(intent) } catch (e: RuntimeException) { launchError = e.javaClass.simpleName }
                // startActivity can silently be refused by Android. Only lifecycle evidence is success.
                for (attempt in 0 until if(type=="portal" || type=="applet") 100 else 20) {
                    if (visible(type,id)) break
                    delay(100)
                }
            }
            authorize(agent)
            val foreground = visible(type,id) && !app.getSystemService(KeyguardManager::class.java).isDeviceLocked
            val manager = app.getSystemService(NotificationManager::class.java)
            var posted = false
            if (!foreground && NotificationManagerCompat.from(app).areNotificationsEnabled() &&
                manager.getNotificationChannel(BridgeApp.INTERACTION_CHANNEL)?.importance?.let { it > NotificationManager.IMPORTANCE_NONE } == true) {
                val open = PendingIntent.getActivity(app, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                try {
                    manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(app, BridgeApp.INTERACTION_CHANNEL)
                        .setSmallIcon(R.drawable.ic_notif).setContentTitle("$identity: Open Bridgit")
                        .setContentText(reason).setContentIntent(open).setAutoCancel(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setPublicVersion(NotificationCompat.Builder(app, BridgeApp.INTERACTION_CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle("Open Bridgit").build())
                        .addAction(0, "Open Bridgit", open).build())
                    posted = true
                } catch (_: SecurityException) { /* Android notification permission was revoked. */ }
            } else if (foreground) manager.cancel(NOTIFICATION_ID)
            val outcome = if (foreground) "foreground" else if (posted) "notification_posted" else "blocked"
            app.log.system("audit", "app.foreground", "$identity: $outcome")
            // Home replays Device Diagnostics now if it is showing, else when the owner opens it from the
            // notification (expires after 5 minutes). A refused switch leaves nothing to replay later.
            if(type=="screen" && id==DEVICE_DIAGNOSTICS && (foreground || posted)) cc.bridgit.app.ui.home.DiagnosticsRerun.request()
            if(!foreground && !posted) throw ApiError(409, if(locked) "device_locked" else "foreground_not_confirmed",
                if(locked) "Unlock this device and open Bridgit" else "Android did not confirm the requested target and no notification could be posted",
                mapOf("foreground" to false,"platform" to "android","user_action_required" to true,"target" to JSONObject().put("type",type).put("id",id)))
            JSONObject().put("status",if(foreground) "visible" else "awaiting_user_action").put("foreground", foreground).put("outcome", outcome).put("notification_posted", posted)
                .put("error",if(foreground) JSONObject.NULL else if(locked) "device_locked" else if(posted) "foreground_user_action_required" else "foreground_not_confirmed")
                .put("platform","android").put("user_action_required", !foreground).put("device_locked", locked)
                .put("target",JSONObject().put("type",type).put("id",id)).put("cooldown_seconds", 1).put("max_per_minute",12).put("launch_error", launchError ?: JSONObject.NULL)
        }
    }

    companion object {
        internal fun cardFor(agent: String, card: cc.bridgit.app.interact.Interaction?): cc.bridgit.app.interact.Interaction =
            card?.takeIf { it.agentId==agent } ?: throw ApiError(404,"not_found","No card belonging to this agent")
        private const val EXTRA_CARD_REQUEST = "authenticated_foreground_card_request"
        const val EXTRA_SCREEN = "agent_foreground_screen"
        val SCREENS = linkedMapOf("home" to R.id.home, "inbox" to R.id.interactions, "agents" to R.id.agents,
            "activity" to R.id.activity, "more" to R.id.more, "settings" to R.id.settings,
            "features" to R.id.features, "network_devices" to R.id.networkDevices,
            "cameras" to R.id.cameras, "issues" to R.id.myIssues, "pet_monitor" to R.id.dogMonitor, "city_tour" to R.id.tour,
            DEVICE_DIAGNOSTICS to R.id.home, NETWORK_DIAGNOSTICS to R.id.networkDevices)
        /** Screen ids from before a rename, still accepted: "reports" is My issues (Issues Tracking, 8 Nov 2026). */
        val LEGACY_SCREENS = mapOf("reports" to "issues")
        const val DEVICE_DIAGNOSTICS = "device_diagnostics"
        const val NETWORK_DIAGNOSTICS = "network_diagnostics"
        /** User, 7 Nov 2026: "Device Diagnostics" and "Network Diagnostics" (were Level 1 and Level 2) in the app and APIs. */
        val SCREEN_NOTES = mapOf(
            DEVICE_DIAGNOSTICS to "Home, replaying Device Diagnostics: the visual check of every phone capability, same as Rerun. Read the results with the diagnostics tool.",
            NETWORK_DIAGNOSTICS to "Network devices, where Network Diagnostics runs. Run it with POST /v1/lan/discover (lan_discovery).")
        private const val NOTIFICATION_ID = 0x425246
        fun validate(args: JSONObject): String {
            args.only("reason","target")
            if(args.has("target")) {
                val target=args.optJSONObject("target") ?: throw ApiError(422,"invalid_parameter","target must be an object")
                target.only("type","id")
                if(target.opt("type") !in listOf("screen","card","applet","portal") || target.opt("id") !is String || target.getString("id").isBlank() || target.getString("id").length>200)
                    throw ApiError(422,"invalid_parameter","target requires a discovered type and id")
            }
            val reason = args.opt("reason") as? String ?: throw ApiError(422, "invalid_parameter", "reason must be text")
            if (reason.isBlank() || reason.length > 256 || reason.any { it.isISOControl() })
                throw ApiError(422, "invalid_parameter", "reason must be 1-256 printable characters")
            return reason
        }
        internal fun remaining(now: Long, last: Long?): Long = if (last == null) 0 else (1_000 - (now - last).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
