package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.capability.SettingsConfigurationApi
import cc.bridgit.app.core.Agent
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.ui.home.HomeStates
import org.json.JSONArray
import org.json.JSONObject

/** An existing owner-granted Super agent may delegate app-owned authority, including configuration bootstrap. */
class SuperAgentAccess(private val app: BridgeApp, private val caller: Agent) {
    fun require(): Agent {
        val live = SettingsConfigurationApi.requireWriter(app, caller.id, caller.keyHash)
        if (HomeStates.controlAgents(app).none { it.id == live.id })
            throw ErrorCatalog.error("forbidden", "Only owner-paired control agents may manage authority")
        return live
    }
    private fun target(id: String): Agent = HomeStates.controlAgents(app).firstOrNull { it.id == id }
        ?: throw ErrorCatalog.error("not_found", "No paired control agent $id")
    private fun strings(value: Any?, key: String, allowed: Set<String>): Set<String> {
        val array = value as? JSONArray ?: throw ErrorCatalog.error("invalid_parameter", "$key must be an array")
        if (array.length() > allowed.size) throw ErrorCatalog.error("invalid_parameter", "Too many $key values")
        return (0 until array.length()).map { array.opt(it) as? String ?: throw ErrorCatalog.error("invalid_parameter", "$key values must be text") }
            .toSet().also { if (!allowed.containsAll(it)) throw ErrorCatalog.error("invalid_parameter", "Unknown $key value") }
    }
    private fun generation(agent: Agent): String = cc.bridgit.app.platform.PlatformStore.digest(
        JSONObject().put("id", agent.id).put("credential", agent.keyHash).put("salt", agent.keySalt).put("created", agent.keyCreatedAt))
    private fun configuredScopes(agent: Agent): List<String> {
        val grants = app.platform.store.grants(agent.id)
        val now = System.currentTimeMillis()
        return Capabilities.all.filter { grants.optLong(it.id) > now }.map { it.id }
    }
    private fun view(agent: Agent): JSONObject = JSONObject().put("id", agent.id).put("name", agent.name)
        .put("capabilities", agent.caps?.sorted()?.let(::JSONArray) ?: JSONObject.NULL)
        .put("super_powers", agent.superPowers).put("fixer_mode", agent.fixer).put("paused", agent.paused)
        .put("maximum_control", agent.maximumControl).put("key_created_at", agent.keyCreatedAt).put("generation", generation(agent))
        .put("control_scopes", JSONArray(configuredScopes(agent)))
        .put("effective_control_scopes", JSONArray(Capabilities.all.filter { app.platform.hasGrant(agent.id, it.id) }.map { it.id }))
    fun get(): JSONObject {
        require()
        return JSONObject().put("authority", "existing_agent_super_powers").put("approval_required", false)
            .put("agents", JSONArray(HomeStates.controlAgents(app).map(::view)))
            .put("capabilities", JSONArray(app.registry.modules.map { JSONObject().put("id", it.id).put("name", it.title) }))
            .put("features", JSONArray(Capabilities.all.filter { !it.privateOnly || BuildConfig.PRIVATE_CONTROL }.map {
                JSONObject().put("id", it.id).put("name", it.label).put("enabled", Capabilities.enabled(app, it.id))
                    .put("blockers", JSONArray(Capabilities.blockers(app, it)))
            })).put("agents_url", "/v1/owner/configuration/access/agents/{agent}")
            .put("features_url", "/v1/owner/configuration/access/features")
            .put("human_authority", JSONArray(listOf("first Agent Super Powers grant", "initial key pairing", "Android consent", "account sign-in", "biometric/security credentials")))
    }
    fun features(body: JSONObject): JSONObject {
        require(); ConfigurationAccess.only(body, "changes")
        val changes = body.optJSONObject("changes") ?: throw ErrorCatalog.error("invalid_parameter", "changes must be an object")
        if (changes.length() !in 1..Capabilities.all.size) throw ErrorCatalog.error("invalid_parameter", "Send 1-${Capabilities.all.size} feature changes")
        val allowed = Capabilities.all.filter { !it.privateOnly || BuildConfig.PRIVATE_CONTROL }.map { it.id }.toSet()
        val parsed = changes.keys().asSequence().associateWith { id ->
            if (id !in allowed) throw ErrorCatalog.error("invalid_parameter", "Unknown feature $id")
            ConfigurationAccess.bool(changes, id)
        }
        require()
        parsed.forEach { (id, on) -> Capabilities.setEnabled(app, id, on) }
        app.log.system("audit", "configuration", "${caller.name} changed enabled device features: ${parsed.keys.sorted().joinToString()}")
        return JSONObject().put("changed", JSONArray(parsed.keys.sorted())).put("approval_required", false)
    }
    fun agent(id: String, body: JSONObject): JSONObject {
        require(); val before = target(id)
        ConfigurationAccess.only(body, "expected_generation", "capabilities", "control_scopes", "paused", "super_powers", "fixer_mode", "maximum_control")
        val expected = ConfigurationAccess.text(body, "expected_generation", 64)
        if (expected != generation(before)) throw ErrorCatalog.error("conflict", "Control key changed; inspect access again")
        val flags = listOf("paused", "super_powers", "fixer_mode", "maximum_control").filter(body::has).associateWith { ConfigurationAccess.bool(body, it) }
        if (body.length() < 2) throw ErrorCatalog.error("invalid_parameter", "No access changes supplied")
        if (flags["maximum_control"] == true && body.length() != 2) throw ErrorCatalog.error("invalid_parameter", "maximum_control:true is a complete maximum grant; send it alone with expected_generation")
        val caps = if (body.has("capabilities") && !body.isNull("capabilities")) strings(body.opt("capabilities"), "capabilities", app.registry.modules.map { it.id }.toSet()) else null
        val scopes = if (body.has("control_scopes")) strings(body.opt("control_scopes"), "control_scopes", Capabilities.all.filter { !it.privateOnly || BuildConfig.PRIVATE_CONTROL }.map { it.id }.toSet()) else null
        synchronized(app.agents) { synchronized(app.platform.store) {
            require(); val current = target(id)
            if (expected != generation(current)) throw ErrorCatalog.error("conflict", "Control key changed; inspect access again")
            if (flags["maximum_control"] == true) cc.bridgit.app.power.PowerAccess(app).grantAll(id)
            else {
                if (body.has("capabilities")) app.agents.update(id) {
                    it.copy(caps = if (caps == null && it.connection == cc.bridgit.app.core.Connection.RELAY) app.registry.modules.map { m -> m.id }.toSet() else caps)
                }
                scopes?.let { wanted ->
                    val existing = app.platform.store.grants(id).keys().asSequence().toSet()
                    (existing - wanted).forEach { app.platform.revoke(id, it) }
                    wanted.forEach { app.platform.grant(id, it, Long.MAX_VALUE) }
                }
                app.agents.update(id) { live ->
                    if (expected != generation(live)) throw ErrorCatalog.error("conflict", "Control key changed")
                    live.copy(paused = flags["paused"] ?: live.paused, superPowers = flags["super_powers"] ?: live.superPowers,
                        fixer = flags["fixer_mode"] ?: live.fixer, maximumControl = flags["maximum_control"] ?: live.maximumControl)
                }
            }
            val after = target(id)
            val narrowed = (!current.paused && after.paused) || (current.superPowers && !after.superPowers) ||
                (current.fixer && !after.fixer) || (after.caps != null && (current.caps == null || !after.caps.containsAll(current.caps)))
            if (narrowed) app.platform.invalidate(id)
            app.log.system("audit", "configuration", "${caller.name} changed paired control-agent access for ${before.name}: ${body.keys().asSequence().filter { it != "expected_generation" }.sorted().joinToString()}")
            return view(after).put("approval_required", false)
        } }
    }
}
