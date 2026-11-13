package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.*
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

object ConfigurationEndpoints {
    private fun read(path: String, what: String, returns: String) = EndpointSpec("GET", "/owner/configuration$path", "owner_features", what,
        example = "GET /v1/owner/configuration$path", returns = returns, controlScope = "owner.configuration")
    private fun write(method: String, path: String, what: String, body: List<Param>, example: String, returns: String) = EndpointSpec(method,
        "/owner/configuration$path", "owner_features", "$what; explicit owner.configuration grant required; no per-change phone approval",
        body = body, example = "$method /v1/owner/configuration$path $example", returns = returns,
        errors = listOf("invalid_parameter", "forbidden", "agent_not_allowed", "not_found", "conflict", "upstream_failed"),
        idempotent = true, idempotencyRequired = true, controlScope = "owner.configuration", privateFields = setOf("*"), maxBody = 32768)
    val INDEX = read("", "Discover unattended app-owned configuration for owner-authorized control agents", "scope, approval_required=false, settings, actions, keys, connections, callbacks, notifications, feeding, email; remaining OS/security/account authority")
    val SETTINGS = write("POST", "/settings", "Set app-owned settings, listener/callback policy, captures and feature switches; secrets never returned; listener disable/port change needs reconnection",
        listOf(Param("changes", "object", "1-50 keys from GET /v1/owner/configuration settings; captures.owner_upload_url accepts text or null to clear. OS/security/agent authority fields refused", true)),
        "{\"changes\":{\"captures.agent_files\":true}}", "changed key names, listener {enabled,port,reconnect_required}; read values through discovery; upload URL is write-only")
        .let { it.copy(errors = it.errors + "platform_unsupported") } // security.approvers_enabled can't be turned on in the published build yet
    val ACTION = write("POST", "/actions", "Apply an allowlisted app-owned configuration action with existing feature/capture grants",
        listOf(Param("action", "string", "Configuration action", true, ConfigurationResources.ACTIONS.sorted()), Param("options", "object", "Same exact options as GET /v1/owner/features; credential fields refused here, use dedicated write-only routes", true)),
        "{\"action\":\"updates.offers\",\"options\":{\"enabled\":true}}", "native configuration result, approval_required=false; account linking may still require sign-in")
    val KEYS = read("/keys", "Discover model-key providers, write-only field formats and configured flags", "providers[{id,name,configured,write_only,fields[{label,pattern,secret}],write_url}]; no saved suffixes/values")
    val KEY_SET = write("POST", "/keys/{provider}", "Set/replace a provider's write-only model credentials using native validation",
        listOf(Param("credentials", "array", "Provider fields in GET discovery order; strings; the whole credential is replaced", true)), "{\"credentials\":[\"<new receiver-owned key>\"]}", "provider,configured,write_only; no credential echoed")
    val KEY_DELETE = write("DELETE", "/keys/{provider}", "Remove a provider's saved model credentials", emptyList(), "", "provider,configured=false,write_only")
    val CONNECTIONS = read("/connections", "Manage connection lifecycle; discover preset names and safe metadata, including revoked connections", "connections with id,name,preset,enabled and configured flags only; presets with header support and extra field names; no URLs or secrets")
    private val connectionFields = listOf(Param("name", "string", "1-80 character name"), Param("url", "string", "Write-only preset URL, up to 2048 characters; required on create"),
        Param("preset", "string", "Discovered preset; otherwise recognised from URL"), Param("allow_private", "boolean", "Native private-network HTTP/port policy, default false"),
        Param("headers", "object|null", "Write-only native headers; null clears; omitted preserves on update"), Param("extra", "object|null", "Write-only preset fields; null clears; omitted preserves on update"),
        Param("enabled", "boolean", "Disable cancels pending deliveries; true re-enables subject to limits"), Param("signing_secret", "string", "Write-only Standard Webhooks whsec_ + base64 of 32 receiver-generated random bytes; replacement rotates with 24-hour overlap"),
        Param("alert_secret", "string", "Write-only alerts/event-delivery HMAC key: 64 lowercase hex characters encoding 32 receiver-generated random bytes; replacement has no overlap"))
    val CONNECTION_CREATE = write("POST", "/connections", "Create a connection with native URL/header validation; request UUID/digest durably prevents duplicate creation after process restart",
        connectionFields.map { if (it.name in setOf("name", "url")) it.copy(required = true) else it }, "{\"name\":\"Home hook\",\"url\":\"https://example.com/hook\",\"preset\":\"json\"}", "safe connection metadata; supplied/generated keys are never returned; provision receiver-owned keys for signature validation")
    val CONNECTION_UPDATE = write("PATCH", "/connections/{id}", "Update connection fields; omitted fields preserve existing values; null headers/extra clear them", connectionFields,
        "{\"name\":\"Kitchen hook\"}", "safe connection metadata; no credentials")
    val CONNECTION_DELETE = write("DELETE", "/connections/{id}", "Revoke a connection and cancel queued sends/callback delivery", emptyList(), "", "safe metadata,enabled=false; historical record retained as in native UI")
    val CONNECTION_ROTATE = write("POST", "/connections/{id}/rotate", "Rotate the Standard Webhooks signing key to a receiver-provisioned value; previous key remains for 24 hours; same-value retries do not rotate again",
        listOf(Param("signing_secret", "string", "whsec_ + base64 of 32 random bytes known to the receiver", true)), "{\"signing_secret\":\"<receiver-generated whsec key>\"}", "safe configured status; neither old nor new key returned")
    val CALLBACKS = read("/callbacks", "Callback presence per agent; receiver secret and destination remain write-only", "callbacks[{agent_id,configured,write_only}]")
    val CALLBACK_SET = write("POST", "/callbacks/{agent}", "Set/change callback URL and receiver-provisioned signing key; apply native network policy; URL required; omitted secret preserves existing",
        listOf(Param("url", "string", "Write-only destination URL; native tailnet/public HTTPS policy", true), Param("secret", "string", "Required when new; cbs_ + unpadded base64url of 32 random receiver-owned bytes; same format as native callback secrets")),
        "{\"url\":\"https://example.com/callback\",\"secret\":\"<receiver-generated cbs key>\"}", "agent_id,configured,write_only; no URL or signing secret")
    val CALLBACK_DELETE = write("DELETE", "/callbacks/{agent}", "Remove an agent callback URL and signing key", emptyList(), "", "agent_id,configured=false")
    val NOTIFICATIONS = read("/notifications/{agent}", "Read an agent's selected notification-app packages and available native choices; no notification content", "agent_id,packages,available,android_access_granted; selection does not grant notification-read/reply or OS consent")
    val NOTIFICATIONS_SET = write("POST", "/notifications/{agent}", "Replace an agent's notification-app selection; [] clears; native removal also pauses affected workflows",
        listOf(Param("packages", "array", "At most 20 unique package strings from available; this does not grant notification scope or OS access", true)), "{\"packages\":[]}", "same selection/status as GET; private build only")
    val EMAIL = read("/email", "Read alert-address enrollment status; verification remains a real email step", "email,state,human_verification_required")
    val EMAIL_SET = write("POST", "/email", "Set/replace/clear alert address using native enrollment; this never fabricates confirmation",
        listOf(Param("email", "string|null", "Valid email; null removes after server confirmation", true)), "{\"email\":\"owner@example.com\"}", "email,state,human_verification_required,delivery; pending/failed delivery does not imply verification")
    val NAME = read("/name", "Read this Bridgit's Device Domain Name (bridgit.cc/names); asks the team backend", "status unregistered|registered,name,label,visible,advice,how")
    val NAME_SET = write("POST", "/name", "Register this Bridgit's two names in one step on the first control call (Device Domain Name + friendly name), or change its friendly name, label or visibility; the domain name is kept for good",
        listOf(Param("name", "string", "Required when unregistered: 3-30 lowercase letters, digits and hyphens, about 10 characters, cute and friendly (kitchen-tab); first come, first served"),
            Param("friendly_name", "string", "Required when registering (unless the owner already named this phone): up to 40 characters, any style, unique among the owner's devices; displayed nearly everywhere, never public. Also changes it later"),
            Param("label", "string|null", "Up to 60 characters shown on public lookup; null clears"), Param("visible", "boolean", "Public lookup shows label and online; default true; false hides")),
        "{\"name\":\"kitchen-tab\",\"friendly_name\":\"Kitchen tablet\"}", "status,name,friendly_name,needs_friendly_name,label,visible; 409 name_taken (lookup) means suggest another domain name; 409 friendly_name_taken (held_by) means another friendly name; 409 already_named means the name is fixed")
    val NAME_DELETE = write("DELETE", "/name", "Hide this Bridgit's Device Domain Name and clear its label; the name stays this Bridgit's", emptyList(), "", "status,name,label=null,visible=false")
    val SPECS = read("/specs", "Read whether this Bridgit stores its static spec sheet with Bridgit (default on), the sheet it sends and the copy the team backend holds",
        "share, name, specs (the current sheet), stored {available, specs, specs_updated_at} or {available:false, reason}, uploaded_at, delete_pending, lookup (the public GET agents use)")
    val SPECS_SET = write("POST", "/specs", "Switch storing the static spec sheet with Bridgit on or off (off deletes the stored copy) or store the current sheet now; same logic as Settings › Team",
        listOf(Param("share", "boolean", "true stores the current sheet now and keeps it up to date; false deletes the stored sheet (retried later while the backend can't be reached) and stops sharing"),
            Param("refresh", "boolean", "true stores the current sheet now; needs sharing on")),
        "{\"share\":false}", "the same as GET plus backend: uploaded|unchanged|deleted|pending|not_named|failed")
    val FEEDING = read("/feeding", "Read saved Pet monitor food-view and eating-sound response configuration", "enabled,camera,position{x,y},view_saved,status; no camera/network access or capture is triggered by the read")
    val FEEDING_SET = write("POST", "/feeding", "Save current enrolled Pet monitor camera position, enable/disable eating-sound response or clear saved view; existing camera/audio grants and motion lease checks apply",
        listOf(Param("save_current_camera", "string", "Enrolled lan:<id> in Pet monitor; reads its current ONVIF position, never moves it"), Param("enabled", "boolean", "Needs a saved food view"), Param("clear", "boolean", "true clears position and disables response; incompatible with save/enable")),
        "{\"enabled\":false}", "food-view status and saved position; camera failure does not claim a saved view")
    val MODES = read("/modes", "Read built-in, custom and current mode definitions", "modes,custom,mode,since,set_by,log")
    val MODES_SET = write("POST", "/modes", "Replace custom modes using the native name/limit validation; [] clears; built-in and current modes protected",
        listOf(Param("custom", "array", "Complete list of up to 8 unique custom mode strings; native normalization applies", true)), "{\"custom\":[\"guests\"]}", "same mode state as GET")
    val LISTENING = read("/listening", "Read complete wake-word definitions and sound-event recipient configuration", "wake_words[{phrase,agent_id,send_clip}],sounds{agent_id,sensitivity,send_clip} or null")
    val LISTENING_SET = write("POST", "/listening", "Replace supplied listening configuration with native validation; wake_words=[] or sounds=null clears; existing recipient/capture and Android permission checks remain",
        listOf(Param("wake_words", "array", "Complete wake-word object list {phrase,agent_id,send_clip}; 0-10; native grammar, overlap and available vocabulary validation"), Param("sounds", "object|null", "Recipient {agent_id,sensitivity:quiet|normal|loud,send_clip}; null clears")), "{\"wake_words\":[]}", "same listening state as GET; no authority grants or model downloads")
    val APPROVALS = read("/approvals", "List app-owned requests a delegated control agent can approve or decline", "approvals[{kind,id,expected_digest,can_decide,can_decline,blocked_reason,review_url}],kinds,human_authority")
    val APPROVAL = read("/approvals/{kind}/{id}", "Inspect one exact pending applet, schedule, workflow, operation or control-session request", "kind,id,expected_digest,can_decide,can_decline,blocked_reason,review; credentials never returned")
    val APPROVAL_DECIDE = write("POST", "/approvals/{kind}/{id}", "Approve or decline the exact inspected request using native activation and current delegated authority; existing action scopes required",
        listOf(Param("approve", "boolean", "true approves; false declines", true), Param("expected_digest", "string", "Exact SHA-256 digest from the preceding review; changed requests conflict", true)),
        "{\"approve\":true,\"expected_digest\":\"<digest from review>\"}", "kind,id,approved,result with actual native status; no initial control grants, OS permission or account verification bypass")
    val ACCESS = EndpointSpec("GET", "/owner/configuration/access", "settings", "Existing Super agents can inspect and delegate app-owned feature switches, capabilities, roles and control grants without a separate configuration grant",
        power = cc.bridgit.app.core.Power.SUPER, example = "GET /v1/owner/configuration/access", returns = "authority,approval_required=false,agents[{id,name,generation,key_created_at,capabilities,control_scopes,effective_control_scopes,paused,api_access,super_powers,fixer_mode,maximum_control,connection,created_at,last_used_at,request_count,model,harness,device_lock,notes}],features[{id,name,enabled,blockers}],capabilities; no keys or hashes")
    val ACCESS_FEATURES = EndpointSpec("POST", "/owner/configuration/access/features", "settings", "Super agent changes app-owned enabled device features; actual Android consent remains required",
        power = cc.bridgit.app.core.Power.SUPER, idempotent = true, idempotencyRequired = true, privateFields = setOf("*"),
        body = listOf(Param("changes", "object", "Feature id to boolean from access discovery; validated together before changes", true)),
        example = "POST /v1/owner/configuration/access/features {\"changes\":{\"owner.configuration\":true}}", returns = "changed,approval_required=false")
    val ACCESS_AGENT = EndpointSpec("POST", "/owner/configuration/access/agents/{agent}", "settings", "An already authorized Super agent replaces grants or changes roles for an existing paired control agent, including its own configuration bootstrap; applets, portals and device peers excluded",
        power = cc.bridgit.app.core.Power.SUPER, idempotent = true, idempotencyRequired = true, privateFields = setOf("*"),
        body = listOf(Param("expected_generation", "string", "Exact opaque generation from access discovery; changes on key rotation", true),
            Param("capabilities", "array|null", "Replace legacy capability ids; null allows all (relay stores explicit ids)"),
            Param("control_scopes", "array", "Replace control grants with these known scopes, durable until revoked; [] clears; native capture revocation also narrows corresponding legacy permissions"),
            Param("paused", "boolean", "Pause/resume this paired control agent"),
            Param("api_access", "boolean", "API access (on by default): false refuses every API call from this agent while it stays paired; true restores it"),
            Param("name", "string", "Rename the agent: 1-24 letters, numbers, spaces, - or _, unique on this phone"),
            Param("notes", "string", "The owner's notes about the agent, up to 200 characters; \"\" clears"), Param("super_powers", "boolean", "Delegate/revoke Super Powers; caller must already have them"),
            Param("fixer_mode", "boolean", "Delegate/revoke Fixer Mode"), Param("maximum_control", "boolean", "Preserves the separate Superpowers role. true alone grants native maximum capabilities/roles/scopes; false stops future maximum inheritance")),
        example = "POST /v1/owner/configuration/access/agents/example {\"expected_generation\":\"<generation from discovery>\",\"control_scopes\":[\"owner.configuration\"]}", returns = "sanitized control-agent access,approval_required=false; no key creation or Android consent bypass")
    val PERMISSIONS = read("/permissions", "Read the permission home (A0115-A0118): every control agent, this device (profile: purposes, type, model; its own switches) and every linked device, each with granted/total and the layer that decided each permission",
        "layers, agents[{id,name,kind,granted,total,super_powers,maximum_control,all_permissions,permissions[{id,group,on,layer,can_change}],change_url}], device{name,profile{purposes,device_type,model,platform,generation,synced,inherited_defaults},granted,total,permissions[{id,group,on,layer,inherited}]}, linked_devices[{id,name,kind,granted,total,can_be_given,permissions,change_url}]")
    val PERMISSIONS_DEVICE = write("POST", "/permissions/device", "Change this device's profile (purposes, device type, model; same rules as the tab and the team backend) and its own permission switches: true/false sets one specifically, \"default\" returns it to the inherited (type, purpose) default",
        listOf(Param("profile", "object", "{purposes: [up to 8 short ids such as primary, dedicated, network_controller, monitor, shared], device_type: phone|tablet|computer|tv|watch|camera|other, model: 1-80 characters}; omitted fields stay"),
            Param("switches", "object", "Switch id (GET permissions device.permissions[].id) to true, false or \"default\"")),
        "{\"profile\":{\"purposes\":[\"network_controller\"]},\"switches\":{\"camera\":\"default\"}}", "changed{profile,switches}, backend synced|pending|unchanged, device (as in GET); same values change nothing")
    val PERMISSIONS_LINKED = write("POST", "/permissions/linked/{id}", "Replace a linked device's permissions on this phone (a portal viewer, hub or satellite), within what its pairing gave it; Superpowers required, as for agent grants",
        listOf(Param("capabilities", "array", "Permission ids, a subset of can_be_given; [] removes all but the always-allowed ones", true)),
        "{\"capabilities\":[\"portal\"]}", "the linked device as in GET plus changed; forbidden beyond can_be_given")
    val ALL = listOf(ACCESS, ACCESS_FEATURES, ACCESS_AGENT, APPROVALS, APPROVAL, APPROVAL_DECIDE, INDEX, SETTINGS, ACTION, KEYS, KEY_SET, KEY_DELETE, CONNECTIONS, CONNECTION_CREATE, CONNECTION_UPDATE, CONNECTION_DELETE, CONNECTION_ROTATE,
        CALLBACKS, CALLBACK_SET, CALLBACK_DELETE, NOTIFICATIONS, NOTIFICATIONS_SET, EMAIL, EMAIL_SET, NAME, NAME_SET, NAME_DELETE, SPECS, SPECS_SET, FEEDING, FEEDING_SET, MODES, MODES_SET, LISTENING, LISTENING_SET, PERMISSIONS, PERMISSIONS_DEVICE, PERMISSIONS_LINKED) +
        ConfigurationLinks.CONFIG + ConfigurationApprovers.ALL + ConfigurationSpending.ALL
}

fun Route.installConfiguration(api: ApiServer, app: BridgeApp) = with(api) {
    endpoint(ConfigurationEndpoints.INDEX) {
        val access = ConfigurationAccess(app, agent!!); access.require()
        Reply.Json(ConfigurationSettings(app, agent!!).status().put("actions", JSONArray(ConfigurationResources.ACTIONS.sorted()))
             .put("approvals_url", "/v1/owner/configuration/approvals")
            .put("actions_url", "/v1/owner/configuration/actions").put("keys_url", "/v1/owner/configuration/keys")
            .put("connections_url", "/v1/owner/configuration/connections").put("callbacks_url", "/v1/owner/configuration/callbacks")
            .put("notifications_url", "/v1/owner/configuration/notifications/{agent}").put("feeding_url", "/v1/owner/configuration/feeding")
            .put("email_url", "/v1/owner/configuration/email").put("name_url", "/v1/owner/configuration/name").put("specs_url", "/v1/owner/configuration/specs").put("links_url", "/v1/owner/configuration/links").put("approvers_url", "/v1/owner/configuration/approvers").put("spending_url", "/v1/owner/configuration/spending").put("modes_url", "/v1/owner/configuration/modes")
            .put("listening_url", "/v1/owner/configuration/listening").put("permissions_url", "/v1/owner/configuration/permissions").put("human_authority", JSONArray(listOf("first Super Powers authority and initial key pairing", "OS consent", "account sign-in/verification", "biometric/security controls"))))
    }
    endpoint(ConfigurationEndpoints.ACCESS) { Reply.Json(SuperAgentAccess(app, agent!!).get()) }
    endpoint(ConfigurationEndpoints.ACCESS_FEATURES) { Reply.Json(SuperAgentAccess(app, agent!!).features(body)) }
    endpoint(ConfigurationEndpoints.ACCESS_AGENT) { Reply.Json(SuperAgentAccess(app, agent!!).agent(path("agent").orEmpty(), body)) }
    endpoint(ConfigurationEndpoints.APPROVALS) { Reply.Json(ConfigurationApprovals(app, agent!!).list()) }
    endpoint(ConfigurationEndpoints.APPROVAL) { Reply.Json(ConfigurationApprovals(app, agent!!).get(path("kind").orEmpty(), path("id").orEmpty())) }
    endpoint(ConfigurationEndpoints.APPROVAL_DECIDE) { Reply.Json(ConfigurationApprovals(app, agent!!).decide(path("kind").orEmpty(), path("id").orEmpty(), body)) }
    endpoint(ConfigurationEndpoints.SETTINGS) {
        val result = ConfigurationSettings(app, agent!!).update(body)
        listenerPauseCommitted = body.optJSONObject("changes")?.opt("listener.enabled") == false
        Reply.Json(result)
    }
    endpoint(ConfigurationEndpoints.ACTION) { Reply.Json(ConfigurationResources(app, agent!!).action(body)) }
    endpoint(ConfigurationEndpoints.KEYS) { Reply.Json(ConfigurationCredentials(app, agent!!).status()) }
    endpoint(ConfigurationEndpoints.KEY_SET) { Reply.Json(ConfigurationCredentials(app, agent!!).set(path("provider") ?: "", body)) }
    endpoint(ConfigurationEndpoints.KEY_DELETE) { Reply.Json(ConfigurationCredentials(app, agent!!).remove(path("provider") ?: "")) }
    endpoint(ConfigurationEndpoints.CONNECTIONS) { Reply.Json(ConfigurationConnections(app, agent!!).list()) }
    endpoint(ConfigurationEndpoints.CONNECTION_CREATE) { Reply.Json(ConfigurationConnections(app, agent!!).create(body, header("Idempotency-Key") ?: throw ErrorCatalog.error("invalid_parameter", "Idempotency-Key required"))) }
    endpoint(ConfigurationEndpoints.CONNECTION_UPDATE) { Reply.Json(ConfigurationConnections(app, agent!!).update(path("id") ?: "", body)) }
    endpoint(ConfigurationEndpoints.CONNECTION_DELETE) { Reply.Json(ConfigurationConnections(app, agent!!).remove(path("id") ?: "")) }
    endpoint(ConfigurationEndpoints.CONNECTION_ROTATE) { Reply.Json(ConfigurationConnections(app, agent!!).rotate(path("id") ?: "", body)) }
    endpoint(ConfigurationEndpoints.CALLBACKS) { Reply.Json(ConfigurationResources(app, agent!!).callbacks()) }
    endpoint(ConfigurationEndpoints.CALLBACK_SET) { Reply.Json(ConfigurationResources(app, agent!!).callback(path("agent") ?: "", body)) }
    endpoint(ConfigurationEndpoints.CALLBACK_DELETE) { Reply.Json(ConfigurationResources(app, agent!!).removeCallback(path("agent") ?: "")) }
    endpoint(ConfigurationEndpoints.NOTIFICATIONS) { Reply.Json(ConfigurationResources(app, agent!!).notifications(path("agent") ?: "")) }
    endpoint(ConfigurationEndpoints.NOTIFICATIONS_SET) { Reply.Json(ConfigurationResources(app, agent!!).notifications(path("agent") ?: "", body)) }
    endpoint(ConfigurationEndpoints.EMAIL) { Reply.Json(ConfigurationResources(app, agent!!).email()) }
    endpoint(ConfigurationEndpoints.EMAIL_SET) { Reply.Json(ConfigurationResources(app, agent!!).email(body)) }
    endpoint(ConfigurationEndpoints.NAME) { Reply.Json(ConfigurationResources(app, agent!!).name()) }
    endpoint(ConfigurationEndpoints.NAME_SET) { Reply.Json(ConfigurationResources(app, agent!!).name(body)) }
    endpoint(ConfigurationEndpoints.NAME_DELETE) { Reply.Json(ConfigurationResources(app, agent!!).hideName()) }
    endpoint(ConfigurationEndpoints.SPECS) { Reply.Json(ConfigurationResources(app, agent!!).specs()) }
    endpoint(ConfigurationEndpoints.SPECS_SET) { Reply.Json(ConfigurationResources(app, agent!!).specs(body)) }
    endpoint(ConfigurationEndpoints.FEEDING) { Reply.Json(ConfigurationResources(app, agent!!).feeding()) }
    endpoint(ConfigurationEndpoints.FEEDING_SET) { Reply.Json(ConfigurationResources(app, agent!!).feeding(body)) }
    endpoint(ConfigurationEndpoints.MODES) { Reply.Json(ConfigurationResources(app, agent!!).modes()) }
    endpoint(ConfigurationEndpoints.MODES_SET) { Reply.Json(ConfigurationResources(app, agent!!).modes(body)) }
    endpoint(ConfigurationEndpoints.LISTENING) { Reply.Json(ConfigurationResources(app, agent!!).listening()) }
    endpoint(ConfigurationEndpoints.LISTENING_SET) { Reply.Json(ConfigurationResources(app, agent!!).listening(body)) }
    endpoint(ConfigurationEndpoints.PERMISSIONS) { Reply.Json(ConfigurationPermissions(app, agent!!).get()) }
    endpoint(ConfigurationEndpoints.PERMISSIONS_DEVICE) { Reply.Json(ConfigurationPermissions(app, agent!!).device(body)) }
    endpoint(ConfigurationEndpoints.PERMISSIONS_LINKED) { Reply.Json(ConfigurationPermissions(app, agent!!).linked(path("id").orEmpty(), body)) }
    ConfigurationLinks.install(this@installConfiguration, api, app)
    ConfigurationApprovers.install(this@installConfiguration, api, app)
    ConfigurationSpending.install(this@installConfiguration, api, app)
}
