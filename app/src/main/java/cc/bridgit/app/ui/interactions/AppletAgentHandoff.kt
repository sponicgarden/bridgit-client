package cc.bridgit.app.ui.interactions

import androidx.fragment.app.FragmentActivity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import cc.bridgit.app.platform.DeviceName
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiDocs
import cc.bridgit.app.applets.AppletEditAccess
import cc.bridgit.app.applets.AppletEvolution
import cc.bridgit.app.security.OwnerCheck
import cc.bridgit.app.core.Connection
import cc.bridgit.app.ui.home.HomeStates
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

object AppletAgentHandoff {
    fun prompt(instanceId: String?, name: String? = null, device: String? = null, deviceId: String? = null, agentName: String? = null): String = buildString {
        if (instanceId == null) append("Help me create a Bridgit applet.")
        else {
            append("Help me modify the installed Bridgit applet ").append(name?.takeIf { it.isNotBlank() } ?: instanceId)
                .append(" (instance ").append(instanceId).append(").")
        }
        device?.let { append(" Device: ").append(it).append(". Use your existing Bridgit connection to this device.") }
        deviceId?.let { append(" Target device_id: ").append(it).append(". Verify it in GET /v2/capabilities (device_id) before doing anything on a phone.") }
        agentName?.let { append(" Selected control agent: ").append(it).append(".") }
        append(" If your connector cannot reach this exact device, stop and ask me to return to Bridgit > Add control agent to finish provisioning. Do not use another device's connection.")
        if (instanceId == null) {
            append(" Read the compact builder quickstart first: ").append(ApiDocs.APPLET_QUICKSTART_URL)
            append(" Ask what I want the applet to achieve before proposing ideas. If I ask for inspiration, use the current templates and this device's discovered capabilities to offer three specific, useful ideas; do not default to generic receipt, note or briefing suggestions.")
        }
        else {
            append(" Read GET /v1/applets/installed/").append(instanceId).append("/development for its current package, configuration and revision.")
            append(" Instructions: ").append(AppletEvolution.GUIDE)
            append(" Preserve its existing data and behavior. Ask me what I want to change, then propose that change through the normal owner approval flow.")
        }
    }

    fun open(activity: FragmentActivity, app: BridgeApp, instanceId: String? = null, setup: () -> Unit) {
        val pm = activity.packageManager
        val agents = HomeStates.controlAgents(app).filter { !it.paused && it.connection != Connection.APPLET }
            .sortedByDescending { it.lastUsedAt }
        val choices = agents.mapNotNull { agent ->
            val creator = AppletCreators.configured(listOf(agent)).firstOrNull() ?: return@mapNotNull null
            if (!agent.allows("applet_install") || app.registry.byId("applet_install")?.enabled == false) return@mapNotNull null
            val text = prompt(instanceId, instanceId?.let { app.applets.get(it)?.optString("name") },
                DeviceName.of(app), app.platform.store.deviceId, agent.name)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(creator.packageName)
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, activity.getString(if (instanceId == null) R.string.create_applet else R.string.modify_applet))
            if (send.resolveActivity(pm) == null) return@mapNotNull null
            Triple(agent, creator, send)
        }
        fun deliver(index: Int) {
            val (agent, _, intent) = choices[index]
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val current = app.agents.get(agent.id)
            if (current == null || AppletCreators.configured(listOf(current)).isEmpty() || !current.allows("applet_install") || app.registry.byId("applet_install")?.enabled == false) {
                setup()
                return
            }
            val access = AppletEditAccess(app)
            if (instanceId != null) {
                if (app.applets.get(instanceId) == null) return
                access.grant(instanceId, agent.id)
            }
            try {
                // Some receiving apps accept ACTION_SEND but discard EXTRA_TEXT. Keep the exact prompt
                // ready to paste, and queue the agent's context before opening its foreground app.
                activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(activity.getString(if (instanceId == null) R.string.create_applet else R.string.modify_applet), text))
                if (instanceId != null) app.interactions.event(agent.id, "applet.modify_requested",
                    JSONObject().put("instance_id", instanceId).put("instructions", AppletEvolution.GUIDE).put("prompt", text))
                Toast.makeText(activity, activity.getString(R.string.applet_handoff_copied, agent.name), Toast.LENGTH_LONG).show()
                activity.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                if (instanceId != null) access.clear(instanceId)
                setup()
            }
        }
        fun send(index: Int) {
            if (instanceId == null) deliver(index)
            else OwnerCheck.run(activity, activity.getString(R.string.modify_applet)) { deliver(index) }
        }
        if (choices.isEmpty()) {
            setup()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (instanceId == null) R.string.create_applet else R.string.modify_applet)
            .setItems((choices.map { (agent, creator, _) -> "${agent.name} · ${creator.label}" } +
                activity.getString(R.string.agents_add)).toTypedArray()) { _, index ->
                if (index == choices.size) setup() else send(index)
            }
            .show()
    }
}
