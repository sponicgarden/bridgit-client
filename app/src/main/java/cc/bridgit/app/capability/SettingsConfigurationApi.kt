package cc.bridgit.app.capability

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.PowerGate
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.Power
import org.json.JSONObject

/** App-owned definitions under the existing App settings + Super Powers authority. */
object SettingsConfigurationApi {
    val MODES_GET = EndpointSpec("GET", "/settings/modes", "settings",
        "Read built-in and custom mode definitions and the current mode",
        example = "GET /v1/settings/modes", returns = "modes, custom, mode, since, set_by, log")
    val MODES_SET = EndpointSpec("POST", "/settings/modes", "settings",
        "Replace custom mode definitions; create, rename or remove unused custom modes; [] clears them. Built-in modes and the current mode cannot be removed. Needs App settings and Agent Super Powers",
        idempotent = true,
        body = listOf(Param("custom", "array", "Complete replacement list, at most 8 unique names, 1-24 letters/digits/underscore starting with a letter; spaces normalize to underscores; built-in names refused", required = true)),
        example = "POST /v1/settings/modes {\"custom\":[\"late_shift\",\"guests\"]}",
        returns = "modes, custom, mode, since, set_by, log", errors = listOf("invalid_parameter", "conflict", "forbidden", "agent_not_allowed", "capability_disabled"))
    val KEYS_GET = EndpointSpec("GET", "/settings/ai-keys", "settings", "Saved AI provider key presence; never keys or suffixes",
        example = "GET /v1/settings/ai-keys", returns = "providers[{provider,configured,readable:false,field_count}]")
    val KEYS_SET = EndpointSpec("POST", "/settings/ai-keys", "settings", "Set, replace or remove a saved AI key with App settings and Super Powers; write-only",
        privateFields = setOf("*"), body = listOf(Param("provider","string","Provider id from GET /v1/settings/ai-keys",true),
            Param("fields","array","Provider fields in displayed order; mutually exclusive with remove"), Param("remove","boolean","true clears this provider")),
        example = "POST /v1/settings/ai-keys {\"provider\":\"openrouter\",\"remove\":true}", returns = "provider,configured,readable:false; no secrets in logs or operation history")
    val CAPTURE_UPLOAD = EndpointSpec("POST", "/settings/capture-upload", "settings", "Set or clear the owner's write-only capture upload URL with App settings and Super Powers",
        privateFields = setOf("*"), body = listOf(Param("url","string","Upload URL; null or empty clears it",true)),
        example = "POST /v1/settings/capture-upload {\"url\":null}", returns = "configured; never the saved URL")
    val ALL = listOf(MODES_GET, MODES_SET, KEYS_GET, KEYS_SET, CAPTURE_UPLOAD)

    fun keyChange(body: JSONObject): Pair<String, String?> {
        if (body.keys().asSequence().any { it !in setOf("provider", "fields", "remove") }) throw ErrorCatalog.error("invalid_parameter", "Unknown key field")
        val provider = cc.bridgit.app.judgment.OwnKeys.PROVIDERS.firstOrNull { it.id == body.opt("provider") }
            ?: throw ErrorCatalog.error("invalid_parameter", "Unknown provider")
        if (body.has("remove")) {
            if (body.opt("remove") != true || body.has("fields")) throw ErrorCatalog.error("invalid_parameter", "Send remove:true without fields")
            return provider.id to null
        }
        val fields = body.optJSONArray("fields") ?: throw ErrorCatalog.error("invalid_parameter", "fields must be an array")
        val values = (0 until fields.length()).map { fields.opt(it) as? String ?: throw ErrorCatalog.error("invalid_parameter", "Key fields must be text") }
        val secret = cc.bridgit.app.judgment.OwnKeys.secretOf(provider, values)
            ?: throw ErrorCatalog.error("invalid_parameter", "Invalid provider key fields")
        return provider.id to secret
    }

    fun customModes(body: JSONObject): List<String> {
        if (body.keys().asSequence().toSet() != setOf("custom")) throw ErrorCatalog.error("invalid_parameter", "Send only custom: an array of mode names")
        val list = body.optJSONArray("custom") ?: throw ErrorCatalog.error("invalid_parameter", "custom must be an array")
        if (list.length() > 8) throw ErrorCatalog.error("invalid_parameter", "At most 8 custom modes")
        return (0 until list.length()).map { list.opt(it) as? String ?: throw ErrorCatalog.error("invalid_parameter", "Each custom mode must be text") }
    }

    fun requireWriter(app: BridgeApp, id: String?, expectedKeyHash: String? = null): Agent {
        val live = id?.let(app.agents::get)?.takeUnless { it.suspended }
            ?: throw ErrorCatalog.error("unauthorized", "Agent is no longer active")
        if (expectedKeyHash != null && live.keyHash != expectedKeyHash) throw ErrorCatalog.error("unauthorized", "Agent key changed")
        if (!app.settings.listenerEnabled || !app.settings.isOn("settings_api")) throw ErrorCatalog.error("capability_disabled", "App settings or the listener is off")
        if (!live.allows("settings")) throw ErrorCatalog.error("agent_not_allowed", "App settings access is required")
        PowerGate.require(live, Power.SUPER, "Changing configuration")
        return live
    }
}
