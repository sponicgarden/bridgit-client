package cc.bridgit.app.headless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.core.Connection
import cc.bridgit.app.power.PowerAccess
import org.json.JSONObject

/**
 * Owner console, protected by Android's signature-level DUMP permission in the manifest.
 * Not an HTTP endpoint: applets, ordinary apps and Bridgit control-agent calls cannot bootstrap here.
 * The companion setup tool requires the owner to click its enrollment button before sending enroll.
 * Never log resultData: enrollment returns a new key exactly once to the authorized shell caller.
 */
class HeadlessAdminReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        try {
            require(isOrderedBroadcast) { "Use an ordered owner-console broadcast" }
            val action = intent.getStringExtra("command") ?: "status"
            val result = when (action) {
                "status" -> status(app)
                "configure" -> {
                    val mode = intent.getStringExtra("mode") ?: "auto"
                    HeadlessMode.set(app, mode)
                    if (intent.hasExtra("listener")) {
                        app.settings.listenerEnabled = intent.getBooleanExtra("listener", false)
                        runCatching { if (app.settings.listenerEnabled) BridgeService.startInBackground(app) else BridgeService.pause(app) }
                    }
                    app.log.system("audit", "setting", "Owner USB console configured headless presentation")
                    status(app)
                }
                "revoke" -> {
                    val id = intent.getStringExtra("agent_id") ?: throw IllegalArgumentException()
                    require(app.agents.get(id)?.connection == Connection.DIRECT)
                    app.agents.remove(id)
                    app.log.system("audit", "agent", "Owner USB console revoked a control agent")
                    status(app)
                }
                "enroll" -> {
                    require(HeadlessMode.active(app)) { "Enable headless mode before enrollment" }
                    val name = intent.getStringExtra("name") ?: ""
                    app.agents.checkName(name.trim())?.let { throw IllegalArgumentException(it) }
                    val (agent, key) = app.agents.add(name.trim(), Connection.DIRECT)
                    val grantsApplied = runCatching {
                        PowerAccess(app).apply {
                            allSwitchesOn()
                            grantAll(agent.id, superPowers = intent.getBooleanExtra("super_powers", false))
                        }
                    }.isSuccess
                    app.settings.firstRunDone = true
                    app.settings.listenerEnabled = true
                    val startRequested = runCatching { BridgeService.startInBackground(app) }.isSuccess
                    app.log.system("audit", "agent", "Owner USB console enrolled ${agent.name}")
                    status(app).put("agent_id", agent.id).put("key", key)
                        .put("grants_applied", grantsApplied).put("service_start_requested", startRequested)
                }
                else -> throw IllegalArgumentException("Unknown owner-console command")
            }
            resultCode = 1
            resultData = Base64.encodeToString(result.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (_: Exception) {
            // Do not echo untrusted extras or credential material into shell/Android logs.
            resultCode = 0
            resultData = "Owner-console request refused; check command, mode and unique agent name"
        }
    }

    private fun status(app: BridgeApp) = HeadlessMode.status(app)
        .put("listener_enabled", app.settings.listenerEnabled).put("port", app.settings.port)
        .put("agents", app.agents.count)
        .put("agent_records", org.json.JSONArray(app.agents.all().filter { it.connection != Connection.APPLET }.map {
            JSONObject().put("id", it.id).put("name", it.name)
        }))
}
