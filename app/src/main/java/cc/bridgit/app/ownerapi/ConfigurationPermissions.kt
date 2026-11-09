package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.platform.DeviceProfile
import cc.bridgit.app.ui.agents.AgentPermissionsDialog
import cc.bridgit.app.ui.agents.LinkedDevices
import cc.bridgit.app.ui.agents.PermissionHome
import cc.bridgit.app.ui.home.HomeStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The permission home over the API (A0115-A0118): the same agents, this device (its profile and own switches) and
 * linked devices as the Agents and Linked Devices tab, with the same counts and the layer that decided each
 * permission. Writes use the tab's own logic: [DeviceProfile] rules for the profile, [cc.bridgit.app.core.Settings]
 * for this device's switches (specific, or back to the inherited default), and the pairing ceiling for linked devices.
 * Agent grants keep their existing route (POST /v1/owner/configuration/access/agents/{agent}, Superpowers required),
 * so no agent can widen its own permissions here.
 */
class ConfigurationPermissions(private val app: BridgeApp, private val caller: Agent) {
    private val access = ConfigurationAccess(app, caller)

    fun get(): JSONObject {
        access.require()
        return JSONObject().put("approval_required", false)
            .put("vocabulary", "GET /v1/device-profiles/vocabulary on the team backend: the shared ids, groups, platforms and default tables")
            .put("layers", JSONArray(PermissionHome.Layer.entries.map { it.wire }))
            .put("agents", JSONArray(HomeStates.controlAgents(app).map { a ->
                principal(a.id, a.name, AgentPermissionsDialog.agentPerms(app, a)).put("kind", "agent")
                    .put("super_powers", a.superPowers).put("maximum_control", a.maximumControl).put("all_permissions", a.caps == null)
                    .put("change_url", "/v1/owner/configuration/access/agents/${a.id}")
            }))
            .put("device", device())
            .put("linked_devices", JSONArray(LinkedDevices.all(app).map { l ->
                principal(l.agent.id, l.name, AgentPermissionsDialog.linkedPerms(app, l)).put("kind", l.kind.wire)
                    .put("can_be_given", LinkedDevices.ceiling(app, l)?.sorted()?.let(::JSONArray) ?: JSONObject.NULL)
                    .put("change_url", "/v1/owner/configuration/permissions/linked/${l.agent.id}")
            }))
    }

    private fun principal(id: String, name: String, perms: List<PermissionHome.Perm>): JSONObject {
        val s = PermissionHome.summary(perms)
        return JSONObject().put("id", id).put("name", name).put("granted", s.granted).put("total", s.total)
            .put("permissions", JSONArray(perms.map { p ->
                JSONObject().put("id", p.id).put("group", p.group).put("on", p.on).put("layer", p.layer.wire).put("can_change", p.canChange)
            }))
    }

    private fun device(): JSONObject {
        val profile = DeviceProfile(app).get()
        val perms = AgentPermissionsDialog.devicePerms(app)
        val s = PermissionHome.summary(perms)
        return JSONObject().put("name", cc.bridgit.app.platform.DeviceName.of(app)).put("profile", profile.json())
            .put("granted", s.granted).put("total", s.total)
            .put("permissions", JSONArray(perms.map { p ->
                JSONObject().put("id", p.id).put("group", p.group).put("on", p.on).put("layer", p.layer.wire)
                    .put("inherited", profile.defaults[p.id]?.let { if (it) "on" else "off" } ?: JSONObject.NULL)
            }))
            .put("change_url", "/v1/owner/configuration/permissions/device")
    }

    /** {profile?: {purposes?, device_type?, model?}, switches?: {id: true | false | "default"}}; validated together first. */
    suspend fun device(body: JSONObject): JSONObject {
        access.require(); ConfigurationAccess.only(body, "profile", "switches")
        if (!body.has("profile") && !body.has("switches")) throw ErrorCatalog.error("invalid_parameter", "Send profile, switches or both")
        val profile = if (body.has("profile")) body.opt("profile") as? JSONObject ?: throw ErrorCatalog.error("invalid_parameter", "profile must be an object") else null
        profile?.let { ConfigurationAccess.only(it, "purposes", "device_type", "model") }
        val purposes = profile?.takeIf { it.has("purposes") }?.let { p ->
            val a = p.opt("purposes") as? JSONArray ?: throw ErrorCatalog.error("invalid_parameter", "purposes must be an array")
            DeviceProfile.Rules.purposes((0 until a.length()).map { a.opt(it) as? String ?: throw ErrorCatalog.error("invalid_parameter", "purposes must be text ids") })
        }
        val type = profile?.takeIf { it.has("device_type") }?.let { DeviceProfile.Rules.type(it.opt("device_type") as? String ?: "") }
        val model = profile?.takeIf { it.has("model") }?.let { DeviceProfile.Rules.model(it.opt("model") as? String ?: throw ErrorCatalog.error("invalid_parameter", "model must be text")) }
        val keys = app.registry.switchable().associate { it.id to it.switchKey!! }
        val switches = if (body.has("switches")) {
            val o = body.opt("switches") as? JSONObject ?: throw ErrorCatalog.error("invalid_parameter", "switches must be an object")
            if (o.length() !in 1..keys.size) throw ErrorCatalog.error("invalid_parameter", "Send 1-${keys.size} switches")
            o.keys().asSequence().associateWith { id ->
                if (id !in keys) throw ErrorCatalog.error("invalid_parameter", "Unknown switch $id")
                when (val v = o.opt(id)) { true, false -> v; "default" -> "default"; else -> throw ErrorCatalog.error("invalid_parameter", "$id must be true, false or \"default\"") }
            }
        } else emptyMap()
        val store = DeviceProfile(app)
        val changedSwitches = mutableListOf<String>()
        val changedProfile = withContext(Dispatchers.Main) {
            access.require()
            val changed = store.update(purposes, type, model, by = caller.name)
            switches.forEach { (id, v) ->
                val key = keys.getValue(id)
                val was = app.settings.isOn(key) to app.settings.isSpecific(key)
                if (v == "default") { if (was.second) app.settings.clearSpecific(key, store.inherited(id)) }
                else if (was != (v as Boolean to true)) app.settings.setOn(key, v)
                if (was != (app.settings.isOn(key) to app.settings.isSpecific(key))) changedSwitches += id
            }
            changed
        }
        changedSwitches.sort()
        if (changedProfile.isNotEmpty() || changedSwitches.isNotEmpty())
            app.log.byAgent(caller.id, caller.name, "configuration", "${caller.name} changed this device's permissions: " +
                (changedProfile.map { "profile.$it" } + changedSwitches).joinToString())
        // The profile goes to the team backend, which answers with the defaults it inherits; offline it goes next time.
        val backend = if (changedProfile.isEmpty() && store.get().synced) "unchanged" else withContext(Dispatchers.IO) {
            runCatching { store.sync(); "synced" }.getOrDefault("pending")
        }
        return JSONObject().put("changed", JSONObject().put("profile", JSONArray(changedProfile)).put("switches", JSONArray(changedSwitches)))
            .put("backend", backend).put("device", device()).put("approval_required", false)
    }

    /** {capabilities: [...]} replaces a linked device's permissions, within what its pairing gave it. Superpowers required, as for agents. */
    suspend fun linked(id: String, body: JSONObject): JSONObject {
        SuperAgentAccess(app, caller).require(); ConfigurationAccess.only(body, "capabilities")
        val l = LinkedDevices.get(app, id) ?: throw ErrorCatalog.error("not_found", "No linked device $id")
        val a = body.opt("capabilities") as? JSONArray ?: throw ErrorCatalog.error("invalid_parameter", "capabilities must be an array")
        val known = AgentPermissionsDialog.items(app).map { it.id }.toSet()
        val wanted = (0 until a.length()).map { a.opt(it) as? String ?: throw ErrorCatalog.error("invalid_parameter", "capabilities must be text ids") }.toSet()
        wanted.firstOrNull { it !in known }?.let { throw ErrorCatalog.error("invalid_parameter", "Unknown capability $it") }
        val ceiling = LinkedDevices.ceiling(app, l)
        if (ceiling != null) wanted.firstOrNull { it !in ceiling }?.let { throw ErrorCatalog.error("forbidden", "$it is more than this linked device was paired with") }
        val before = l.agent.caps
        withContext(Dispatchers.Main) {
            SuperAgentAccess(app, caller).require()
            if (before == null || before != wanted) {
                app.agents.update(id) { it.copy(caps = wanted) }
                if (before == null || !wanted.containsAll(before)) app.platform.invalidate(id)
                app.log.byAgent(caller.id, caller.name, "configuration", "${caller.name} changed linked device ${l.name}'s permissions",
                    JSONObject().put("target_agent_id", id).put("capabilities", JSONArray(wanted.sorted())))
            }
        }
        val after = LinkedDevices.get(app, id) ?: throw ErrorCatalog.error("not_found", "No linked device $id")
        return principal(id, after.name, AgentPermissionsDialog.linkedPerms(app, after)).put("kind", after.kind.wire)
            .put("changed", before != wanted).put("approval_required", false)
    }
}
