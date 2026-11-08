package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.capability.SettingsController
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.CallbackRules
import cc.bridgit.app.core.SettingDef
import cc.bridgit.app.core.SettingLock
import cc.bridgit.app.core.SettingsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The existing settings store and validation, with owner-delegated writes to app-owned policies. */
class ConfigurationSettings(private val app: BridgeApp, caller: Agent) {
    private val access = ConfigurationAccess(app, caller)
    private val controller = SettingsController(app)
    companion object {
    private val appOwned = setOf("listener.enabled", "listener.port", "listener.allow_internet_callbacks",
        "privacy.pause_external_calls", "captures.agent_files", "captures.agent_upload", "captures.max_keep_minutes",
        "captures.any_agent_can_fetch", "captures.owner_files", "captures.owner_upload_url", "captures.locked_camera_approval")

    fun supported(def: SettingDef) = def.writable || def.key in appOwned || def.section == "features"
    fun pathFor(def: SettingDef): String? = when {
        supported(def) -> "/v1/owner/configuration/settings"
        def.key == "alerts.email" -> "/v1/owner/configuration/email"
        def.key.startsWith("listening.") -> "/v1/owner/configuration/listening"
        def.key == "privacy.remote_view" -> "/v1/owner/configuration/actions"
        else -> null
    }
    }

    fun status(): JSONObject {
        val live = access.require()
        return JSONObject().put("settings", JSONArray(controller.defs().filter(::supported).map {
            controller.describe(it, live, true).put("writable", controller.blocker(it) == null)
                .put("write_url", "/v1/owner/configuration/settings").apply {
                    remove("locked_reason"); remove("why")
                    controller.blocker(it)?.let { reason -> put("locked_reason", reason).put("why", controller.blockerWhy(reason)) }
                    if (it.key == "captures.owner_upload_url") put("write_only", true)
                }
        })).put("scope", "owner.configuration").put("approval_required", false)
    }

    private fun checked(changes: JSONObject): Map<String, Any> {
        if (changes.length() !in 1..50) throw ErrorCatalog.error("invalid_parameter", "Send 1-50 changes")
        val defs = controller.defs().associateBy { it.key }
        return changes.keys().asSequence().associateWith { key ->
            val def = defs[key] ?: throw ErrorCatalog.error("invalid_parameter", "Unknown setting")
            if (!supported(def)) throw ErrorCatalog.error("setting_locked", "This setting retains its OS, account or security authority", mapOf("key" to key))
            controller.blocker(def)?.let { throw ErrorCatalog.error("access_needed", controller.blockerWhy(it)) }
            if (key == "captures.owner_upload_url") {
                val value = changes.opt(key)
                if (value == JSONObject.NULL || value == "") return@associateWith ""
                val url = value as? String ?: throw ErrorCatalog.error("invalid_parameter", "Upload URL must be text or null")
                if (url.length > 2048 || CallbackRules.check(url, CallbackRules.tailnetDomain(app.listener.value.magicDns), true) !is CallbackRules.Check.Ok)
                    throw ErrorCatalog.error("invalid_parameter", "Invalid upload URL")
                return@associateWith url
            }
            when (val parsed = SettingsCatalog.parse(def, changes.opt(key))) {
                is SettingsCatalog.Parsed.Ok -> parsed.value
                is SettingsCatalog.Parsed.Bad -> throw ErrorCatalog.error("invalid_parameter", parsed.message)
            }
        }
    }

    suspend fun update(body: JSONObject): JSONObject {
        access.require()
        ConfigurationAccess.only(body, "changes")
        val changes = body.optJSONObject("changes") ?: throw ErrorCatalog.error("invalid_parameter", "changes must be an object")
        val values = checked(changes)
        withContext(Dispatchers.Main) {
            val live = access.require()
            checked(changes) // platform access and available definitions may have changed during dispatch
            for ((key, value) in values.entries.sortedBy { it.key.startsWith("listener.") }) {
                when (key) {
                    "listener.enabled" -> app.settings.listenerEnabled = value as Boolean
                    "listener.port" -> app.settings.port = value as Int
                    "listener.allow_internet_callbacks" -> app.settings.allowInternetCallbacks = value as Boolean
                    "privacy.pause_external_calls" -> app.settings.externalCallsPaused = value as Boolean
                    "captures.agent_files" -> app.settings.capturesAgentFiles = value as Boolean
                    "captures.agent_upload" -> app.settings.capturesAgentUpload = value as Boolean
                    "captures.max_keep_minutes" -> app.settings.capturesMaxKeepMinutes = value as Int
                    "captures.any_agent_can_fetch" -> app.settings.capturesShareAll = value as Boolean
                    "captures.owner_files" -> app.settings.capturesOwnerFiles = value as Boolean
                    "captures.locked_camera_approval" -> app.settings.capturesLockedCameraApproval = value as Boolean
                    "captures.owner_upload_url" -> app.settings.capturesOwnerUploadUrl = (value as String).ifEmpty { null }
                    else -> controller.apply(key, value, live.name)
                }
            }
            access.audit("settings: " + values.keys.sorted().joinToString(", "))
            if (values.keys.any { it == "listener.enabled" || it == "listener.port" }) {
                // Persist the selected state now; give the existing response time to flush before closing/rebinding sockets.
                app.platform.scope.launch(Dispatchers.Main) {
                    delay(1000)
                    if (app.settings.listenerEnabled) {
                        BridgeService.running?.poke("configuration changed") ?: BridgeService.start(app)
                    } else BridgeService.pause(app)
                }
            }
        }
        // Do not re-authorize through the paused listener just to describe a successful pause.
        return JSONObject().put("changed", JSONArray(values.keys.toList()))
            .put("listener", JSONObject().put("enabled", app.settings.listenerEnabled).put("port", app.settings.port)
                .put("reconnect_required", values.keys.any { it == "listener.enabled" || it == "listener.port" }))
    }
}
