package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.CallbackRules
import cc.bridgit.app.platform.NotificationSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigurationResources(private val app: BridgeApp, private val caller: Agent) {
    private val access = ConfigurationAccess(app, caller)
    companion object {
        val ACTIONS = setOf("service.model.set","service.budget.set", "dog.configure", "applet.configure", "wake.configure", "sounds.configure", "remote.configure", "delivery.subscribe", "updates.offers", "updates.auto", "portal.update_prompts")
    }
    suspend fun action(body: JSONObject): JSONObject {
        access.require()
        val action = body.opt("action") as? String
        if (action !in ACTIONS) throw ErrorCatalog.error("invalid_parameter", "This action retains its existing approval or OS flow; use the configuration discovery allowlist")
        OwnerActions.validate(body)
        app.ownerFeatures.review(caller.id, body)
        access.require()
        return app.ownerFeatures.execute(caller.id, body).put("approval_required", false)
    }
    fun notifications(id: String): JSONObject {
        access.require()
        return NotificationSelection(app).status(id)
    }
    suspend fun notifications(id: String, body: JSONObject): JSONObject {
        access.require(); ConfigurationAccess.only(body, "packages")
        val packages = body.optJSONArray("packages") ?: throw ErrorCatalog.error("invalid_parameter", "packages must be an array")
        val selected = (0 until packages.length()).map { packages.opt(it) as? String ?: throw ErrorCatalog.error("invalid_parameter", "Each package must be text") }
        if (!cc.bridgit.app.BuildConfig.PRIVATE_CONTROL) throw ErrorCatalog.error("forbidden", "Notification selection requires the private control build")
        withContext(Dispatchers.Main) { access.require(); NotificationSelection(app).set(id, selected); access.audit("notification selection") }
        return notifications(id)
    }
    fun callbacks(): JSONObject {
        access.require()
        return JSONObject().put("callbacks", JSONArray(app.agents.all().map { a ->
            JSONObject().put("agent_id", a.id).put("configured", app.callbacks.get(a.id) != null).put("write_only", true)
        }))
    }
    fun callback(id: String, body: JSONObject): JSONObject {
        access.require(); ConfigurationAccess.only(body, "url", "secret")
        if (app.agents.get(id) == null) throw ErrorCatalog.error("not_found", "Unknown agent")
        val url = ConfigurationAccess.text(body, "url", 2048)
        if (CallbackRules.check(url, CallbackRules.tailnetDomain(app.listener.value.magicDns), app.settings.allowInternetCallbacks) !is CallbackRules.Check.Ok)
            throw ErrorCatalog.error("invalid_parameter", "Callback URL does not match the current private-network/public HTTPS policy")
        val secret = if (body.has("secret")) ConfigurationAccess.text(body, "secret", 100) else null
        if (secret != null && !Regex("cbs_[A-Za-z0-9_-]{43}").matches(secret)) throw ErrorCatalog.error("invalid_parameter", "secret must be cbs_ plus 32 random bytes encoded as unpadded base64url")
        access.require()
        try { app.callbacks.setProvided(id, url, secret) { access.require() } }
        catch (e: ApiError) { throw e }
        catch (_: IllegalStateException) { throw ErrorCatalog.error("invalid_parameter", "A new callback needs its receiver-owned secret") }
        access.audit("callback")
        return JSONObject().put("agent_id", id).put("configured", true).put("write_only", true)
    }
    fun removeCallback(id: String): JSONObject {
        access.require()
        if (app.agents.get(id) == null) throw ErrorCatalog.error("not_found", "Unknown agent")
        app.callbacks.remove(id); access.audit("callback")
        return JSONObject().put("agent_id", id).put("configured", false)
    }
    fun email(): JSONObject {
        access.require()
        return JSONObject().put("email", app.settings.alertEmail ?: JSONObject.NULL).put("state", app.settings.alertState)
            .put("human_verification_required", app.settings.alertEmail != null && app.settings.alertState != cc.bridgit.app.alerts.Alerts.STATE_CONFIRMED)
    }
    suspend fun email(body: JSONObject): JSONObject {
        access.require(); ConfigurationAccess.only(body, "email")
        if (!body.has("email")) throw ErrorCatalog.error("invalid_parameter", "email is required; null removes the address")
        val value = body.opt("email")
        if (value != JSONObject.NULL && (value !is String || value.length > 254 || !android.util.Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()))
            throw ErrorCatalog.error("invalid_parameter", "Invalid email address")
        return withContext(Dispatchers.IO) {
            access.require()
            if (value == JSONObject.NULL) {
                if (!app.alerts.removeAddress { access.require() }) throw ErrorCatalog.error("upstream_failed", "Email removal was not confirmed; local address retained")
                access.require()
                access.audit("alert address")
                email()
            } else {
                val result = app.alerts.subscribe(value as String, caller.name) { access.require() }
                access.require()
                email().put("delivery", result.name.lowercase())
            }
        }
    }
    suspend fun name(): JSONObject { access.require(); return withContext(Dispatchers.IO) { cc.bridgit.app.feedback.DeviceNames.refresh(app) } }
    suspend fun name(body: JSONObject): JSONObject {
        access.require(); ConfigurationAccess.only(body, "name", "label", "visible")
        val name = if (body.has("name")) (body.opt("name") as? String)?.let { cc.bridgit.app.feedback.DeviceNames.parse(it) }
            ?: throw ErrorCatalog.error("invalid_parameter", "name must be a Device Domain Name. ${cc.bridgit.app.feedback.DeviceNames.ADVICE}") else null
        val label = body.opt("label")
        if (body.has("label") && label != JSONObject.NULL && (label !is String || label.length > 60)) throw ErrorCatalog.error("invalid_parameter", "label must be text up to 60 characters, or null to clear")
        if (body.has("visible") && body.opt("visible") !is Boolean) throw ErrorCatalog.error("invalid_parameter", "visible must be true or false")
        if (body.length() == 0) throw ErrorCatalog.error("invalid_parameter", "Send name to register, or label/visible to change")
        return withContext(Dispatchers.IO) {
            val result = cc.bridgit.app.feedback.DeviceNames.set(app, name, (label as? String)?.trim()?.ifEmpty { null }, body.has("label"),
                if (body.has("visible")) body.getBoolean("visible") else null)
            access.audit("bridgit name"); result
        }
    }
    suspend fun hideName(): JSONObject { access.require(); return withContext(Dispatchers.IO) { cc.bridgit.app.feedback.DeviceNames.hide(app).also { access.audit("bridgit name") } } }
    fun modes(): JSONObject { access.require(); return app.platform.modes.json().put("custom", JSONArray(app.platform.modes.custom())) }
    suspend fun modes(body: JSONObject): JSONObject {
        access.require()
        val custom = cc.bridgit.app.capability.SettingsConfigurationApi.customModes(body)
        withContext(Dispatchers.Main) { access.require(); app.platform.modes.replaceCustom(custom); access.audit("custom modes") }
        return modes()
    }
    fun listening(): JSONObject {
        access.require()
        return JSONObject().put("wake_words", JSONArray(app.settings.wakeWords))
            .put("sounds", app.settings.soundsConfig.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject.NULL)
    }
    suspend fun listening(body: JSONObject): JSONObject {
        val live = access.require()
        ConfigurationAccess.only(body, "wake_words", "sounds")
        if (body.length() == 0) throw ErrorCatalog.error("invalid_parameter", "No listening settings supplied")
        val words = if (body.has("wake_words")) {
            if (!live.allows("wakewords")) throw ErrorCatalog.error("agent_not_allowed", "Wake-word access is required")
            val input = body.optJSONArray("wake_words") ?: throw ErrorCatalog.error("invalid_parameter", "wake_words must be an array")
            if (input.length() > cc.bridgit.app.listen.WakeRules.MAX_WORDS) throw ErrorCatalog.error("invalid_parameter", "Too many wake words")
            val next = mutableListOf<cc.bridgit.app.listen.WakeWord>()
            for (i in 0 until input.length()) {
                val w = input.optJSONObject(i) ?: throw ErrorCatalog.error("invalid_parameter", "Each wake word must be an object")
                ConfigurationAccess.only(w, "phrase", "agent_id", "send_clip")
                val phrase = cc.bridgit.app.listen.WakeRules.normalize(ConfigurationAccess.text(w, "phrase", 160))
                val target = ConfigurationAccess.text(w, "agent_id", 128)
                val send = if (w.has("send_clip")) ConfigurationAccess.bool(w, "send_clip") else false
                val word = cc.bridgit.app.listen.WakeWord(phrase, target, send)
                if (cc.bridgit.app.listen.WakeRules.problem(next, word, { app.agents.get(it) != null }, if (app.wake.model.ready) app.wake.model::knows else null) != null)
                    throw ErrorCatalog.error("invalid_parameter", "Wake word has invalid grammar, conflicts, recipient or model vocabulary", mapOf("index" to i))
                next.add(word)
            }
            next
        } else null
        val sound = if (body.has("sounds") && !body.isNull("sounds")) {
            if (!live.allows("sounds")) throw ErrorCatalog.error("agent_not_allowed", "Sound-listening access is required")
            val input = body.optJSONObject("sounds") ?: throw ErrorCatalog.error("invalid_parameter", "sounds must be an object or null")
            ConfigurationAccess.only(input, "agent_id", "sensitivity", "send_clip")
            val target = ConfigurationAccess.text(input, "agent_id", 128)
            if (app.agents.get(target) == null) throw ErrorCatalog.error("not_found", "Unknown sound recipient")
            val sensitivity = if (input.has("sensitivity")) ConfigurationAccess.text(input, "sensitivity", 16) else "normal"
            if (sensitivity !in cc.bridgit.app.listen.SoundConfig.SENSITIVITIES) throw ErrorCatalog.error("invalid_parameter", "Unknown sound sensitivity")
            val send = if (input.has("send_clip")) ConfigurationAccess.bool(input, "send_clip") else true
            cc.bridgit.app.listen.SoundConfig(target, sensitivity, send)
        } else null
        if (body.has("sounds") && !live.allows("sounds")) throw ErrorCatalog.error("agent_not_allowed", "Sound-listening access is required")
        withContext(Dispatchers.Main) {
            val current = access.require()
            if ((words != null && !current.allows("wakewords")) || (body.has("sounds") && !current.allows("sounds")))
                throw ErrorCatalog.error("agent_not_allowed", "Listening access changed")
            val recipients = words.orEmpty().map { it.agentId } + listOfNotNull(sound?.agentId)
            if (recipients.any { app.agents.get(it) == null }) throw ErrorCatalog.error("not_found", "Listening recipient was removed")
            words?.let { app.wake.saveWords(it) }
            if (body.has("sounds")) app.settings.soundsConfig = sound?.toJson()?.toString().orEmpty()
            app.wake.sync(); access.audit("listening configuration")
        }
        return listening()
    }
    private fun feedingAccess() {
        val live = access.require()
        if (!live.allows("camera") || !live.allows("lan_camera") || !live.allows("lan_camera_audio")) throw ErrorCatalog.error("agent_not_allowed", "Food-view configuration needs camera, local camera and camera-audio access")
        if (!app.settings.isOn("lan_camera") || !app.settings.isOn("lan_camera_audio")) throw ErrorCatalog.error("capability_disabled", "Local camera and camera-audio features are off")
    }
    fun feeding(): JSONObject { access.require(); return app.dog.feeding.status() }
    suspend fun feeding(body: JSONObject): JSONObject {
        access.require(); ConfigurationAccess.only(body, "save_current_camera", "enabled", "clear")
        if (body.length() == 0) throw ErrorCatalog.error("invalid_parameter", "No feeding setting supplied")
        val clear = if (body.has("clear")) ConfigurationAccess.bool(body, "clear") else false
        val enabled = if (body.has("enabled")) ConfigurationAccess.bool(body, "enabled") else null
        val camera = if (body.has("save_current_camera")) ConfigurationAccess.text(body, "save_current_camera", 128) else null
        if (clear && (camera != null || enabled == true)) throw ErrorCatalog.error("invalid_parameter", "clear cannot be combined with save or enable")
        if (enabled == true && camera == null && app.dog.feeding.camera.isEmpty()) throw ErrorCatalog.error("invalid_parameter", "Save a food view before enabling")
        if (clear) { app.dog.feeding.clear(); access.audit("food view"); return feeding() }
        camera?.let { app.dog.feeding.save(it) { feedingAccess() } }
        if (enabled == true) feedingAccess() else access.require()
        enabled?.let { app.dog.feeding.setEnabled(it) }
        access.audit("food view")
        return feeding()
    }
}
