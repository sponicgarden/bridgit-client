package cc.bridgit.app.platform

import org.json.JSONArray
import org.json.JSONObject

/**
 * Input schemas for the control tools, shared by `/mcp`, `/v2/tools`, the relay and bridgit.cc/learn.
 * They mirror what the handlers check: always-needed fields are `required`, fixed choices are enums,
 * and limits are minimum/maximum/maxLength.
 *
 * The root stays a plain object with no oneOf/anyOf/allOf: the Claude API rejects tools whose
 * schema starts with one, which would break every Claude-based MCP client. Fields needed only for
 * some `type` values are named in the field's and the schema's description instead.
 */
object ToolSchemas {
    private fun str(desc: String? = null, max: Int? = null, enum: List<String>? = null) = JSONObject().put("type", "string").apply {
        desc?.let { put("description", it) }; max?.let { put("maxLength", it) }; enum?.let { put("enum", JSONArray(it)) }
    }
    private fun int(desc: String? = null, min: Long? = null, max: Long? = null) = JSONObject().put("type", "integer").apply {
        desc?.let { put("description", it) }; min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
    }
    private fun num(min: Double, max: Double, desc: String? = null) = JSONObject().put("type", "number").put("minimum", min).put("maximum", max).apply { desc?.let { put("description", it) } }
    private fun bool(desc: String? = null) = JSONObject().put("type", "boolean").apply { desc?.let { put("description", it) } }
    private fun list(items: JSONObject, desc: String? = null, min: Int? = null, max: Int? = null) = JSONObject().put("type", "array").put("items", items).apply {
        desc?.let { put("description", it) }; min?.let { put("minItems", it) }; max?.let { put("maxItems", it) }
    }
    private fun obj(props: Map<String, JSONObject>, required: List<String> = emptyList(), desc: String? = null) = JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } }).put("required", JSONArray(required)).put("additionalProperties", false)
        .apply { desc?.let { put("description", it) } }

    /** A plain-text flow field: text, a {"$step"}/{"$trigger"} reference, or a list of them joined. */
    private fun flowText(desc: String) = JSONObject().put("description", "$desc. Text, a {\"\$step\":\"id.path\"} or {\"\$trigger\":\"path\"} reference, or a list of texts and references")
        .put("anyOf", JSONArray().put(JSONObject().put("type", "string").put("maxLength", 4096)).put(JSONObject().put("type", "object")).put(JSONObject().put("type", "array").put("maxItems", 20)))

    private class Spec(val props: Map<String, JSONObject>, val required: List<String> = emptyList(), val description: String? = null)

    private val id = str("The id returned when it was created")
    private val wait = int("Seconds to wait for a change before replying", 0, 55)
    private val sessionId = str("A control session id from session.request, once the owner approved it")
    private val fence = str("The session's current fence, from session.list or screen.observe")
    private val expect = obj(linkedMapOf("package" to str("App that must be in front", 256), "text" to str("Text that must be visible", 512)),
        desc = "Postcondition: give package, text or both")
    private val camera = str("Which camera (default rear)", enum = listOf("rear", "front"))
    private val elementActions = listOf("tap", "long_press", "set_text", "scroll")

    val DEVICE_ACTIONS = listOf("open_link", "open_app", "calendar_draft", "message_draft", "email_draft", "dial", "alarm", "timer", "contact_picker", "open_file",
        "clipboard", "speak", "camera.snapshot", "microphone.clip", "flashlight", "volume", "dnd", "brightness", "ring", "media", "battery_saver", "screen_timeout")
    val PERIPHERAL_ACTIONS = listOf("ble.scan", "ble.read", "ble.write", "usb.transfer")

    private fun spec(name: String): Spec = when (name) {
        "app.foreground.targets" -> Spec(emptyMap(), description="Discover Bridgit screens, your cards, installed applet pages and consumer portals available to this control agent. No navigation or capture.")
        "app.foreground" -> Spec(linkedMapOf("reason" to str("Required audit reason",256),
            "target" to obj(linkedMapOf("type" to str(enum=listOf("screen","card","applet","portal")), "id" to str("Exact id from app.foreground.targets",200)),listOf("type","id"))), listOf("reason"),
            "Bring Bridgit or a discovered target forward using app.foreground authority; applet/portal targets also need applet_portals. Minimum 1 second between switches, maximum 12 per minute per device. Result foreground confirms the requested target is visible; otherwise a notification fallback may need an owner tap. Does not unlock or grant OS consent.")
        "context.status" -> Spec(emptyMap(), description = "Read the context vault: durable facts and temporary context with source, confidence and expiry. Unknown and expired values are null. Needs context.read; never acquires location or calls a model.")
        "configuration.get", "configuration.set" -> Spec(linkedMapOf(
            "operation" to str("Exact method and path template from configuration discovery; GET defaults to its index", enum = cc.bridgit.app.ownerapi.ConfigurationTools.operations(name == "configuration.get")),
            "parameters" to JSONObject().put("type", "object").put("description", "Path parameter values, e.g. {provider:openrouter}; omit when none"),
            "body" to JSONObject().put("type", "object").put("description", "Exact native configuration payload; omit on reads/deletes. Credentials are write-only and never saved in operation history")
        ), if (name == "configuration.set") listOf("operation") else emptyList(), "Configuration delegation required; access operations also accept existing Agent Super Powers with App settings for bootstrap. Returns the result directly, never a durable operation or per-change GUI approval")
        "owner.configure" -> Spec(linkedMapOf("action" to str(enum = cc.bridgit.app.ownerapi.OwnerActions.NAMES), "options" to JSONObject().put("type","object").put("description","Exact action options from GET /v1/owner/features")), listOf("action", "options"))
        "applet.event", "application.event" -> Spec(linkedMapOf("workflow_id" to id, "event_id" to str("Stable source event id; retries must preserve it",128), "payload" to JSONObject().put("type","object").put("description","Source JSON, at most 16 KiB; normalized by the owner-approved applet trigger")), listOf("workflow_id","event_id","payload"))
        "events" -> Spec(linkedMapOf("after" to int("Return events after this sequence number", 0), "wait" to wait))
        "operation.get", "operation.cancel" -> Spec(linkedMapOf("id" to id), listOf("id"))
        "interaction.get" -> Spec(linkedMapOf("id" to id, "wait" to wait), listOf("id"))
        "workflow.run" -> Spec(linkedMapOf("id" to id, "with" to JSONObject().put("type", "object").put("description", "For a called flow: the values it takes (its trigger's with), read by its steps as {\"\$trigger\":\"with.<name>\"}")), listOf("id"))
        "workflow.simulate" -> Spec(linkedMapOf(
            "flow" to JSONObject().put("type", "object").put("description", "An inline workflow.validate definition with name, trigger and steps"),
            "trigger" to JSONObject().put("type", "object").put("description", "Example event values available to trigger references"),
            "fixtures" to JSONObject().put("type", "object").put("description", "Synthetic results keyed by group and step id, e.g. photos.shot, ask_model.look and ask_owner.check. Omit fixtures for skipped steps")),
            listOf("flow"), "Preview a flow with fixtures. Returns simulated, status, steps and would. No device actions or paid calls.")
        "workflow.runs" -> Spec(linkedMapOf("id" to id, "limit" to int("Newest runs to return (default 10)", 1, 50)), listOf("id"))
        "interaction.create" -> Spec(linkedMapOf(
            "kind" to str("question waits for the owner's answer; notice just shows (default question)", enum = listOf("question", "notice")),
            "title" to str(max = 120), "text" to str(max = 4096),
            "choices" to list(str(max = 256), "Answers the owner can tap", max = 6),
            "expires_seconds" to int(min = 30, max = 86400)), listOf("title"))
        "interaction.cancel", "session.stop", "session.heartbeat", "file.get", "workflow.pause",
        "stream.stop", "stream.status", "stream.frame", "alert.cancel" -> Spec(linkedMapOf("id" to id), listOf("id"))
        "session.request" -> Spec(linkedMapOf(
            "apps" to list(str("Android package name", 256), "Apps the session may use", 1, 10),
            "purpose" to str("Shown to the owner when they approve", 256),
            "duration_seconds" to int(min = 30, max = 1800)), listOf("apps", "purpose"))
        "screen.observe" -> Spec(linkedMapOf("session_id" to sessionId, "screenshot" to bool("Include a screenshot (default true)")))
        "screen.wait" -> Spec(linkedMapOf("session_id" to sessionId, "expect" to expect, "timeout_seconds" to int(min = 1, max = 30)), listOf("session_id", "expect"))
        "ui.action" -> Spec(linkedMapOf(
            "session_id" to sessionId, "fence" to fence, "observation_id" to str("From the latest screen.observe"),
            "type" to str(enum = elementActions + listOf("back", "home")),
            "ref" to str("Element ref from the observation. Required for tap, long_press, set_text and scroll", 64),
            "text" to str("Required for set_text", 4096),
            "direction" to str("For scroll (default forward)", enum = listOf("forward", "backward")),
            "expect" to expect), listOf("session_id", "fence", "observation_id", "type"),
            "ref is required for tap, long_press, set_text and scroll; text is required for set_text")
        "ui.sequence" -> Spec(linkedMapOf("session_id" to sessionId, "fence" to fence,
            "steps" to list(obj(linkedMapOf(
                "type" to str(enum = elementActions), "package" to str("App the step runs in; must be in the session", 256),
                "label" to str(max = 512), "resource_id" to str(max = 512), "text" to str("Required for set_text", 4096),
                "direction" to str(enum = listOf("forward", "backward")), "expect" to expect), listOf("type", "package", "expect")), min = 1, max = 8)),
            listOf("session_id", "fence", "steps"))
        "device.action" -> Spec(linkedMapOf(
            "type" to str(enum = DEVICE_ACTIONS),
            "url" to str("Required for open_link: https, geo, tel or mailto", 2048),
            "package" to str("Required for open_app", 256),
            "text" to str("Required for speak, clipboard, message_draft and email_draft", 4096),
            "language" to str("BCP 47 tag for speak", 32),
            "recipient" to str("Required for message_draft and email_draft", 256),
            "subject" to str("For email_draft", 256),
            "number" to str("Required for dial", 64),
            "title" to str("Required for calendar_draft", 256),
            "begin_ms" to int("For calendar_draft"), "end_ms" to int("For calendar_draft"),
            "hour" to int("For alarm", 0, 23), "minute" to int("For alarm", 0, 59),
            "seconds" to int("For timer or screen_timeout", 1),
            "label" to str("For alarm and timer", 120),
            "file_id" to str("Required for open_file"),
            "on" to bool("For flashlight and battery_saver (default true)"),
            "percent" to int("For volume and brightness", 0, 100),
            "stream" to str("For volume (default media)", enum = listOf("media", "alarm")),
            "mode" to str("For dnd (default priority)", enum = listOf("priority", "all")),
            "command" to str("Required for media", enum = listOf("play_pause", "next", "previous")),
            "duration_seconds" to int("For microphone.clip (1-120), flashlight, dnd and ring", 1, 3600),
            "camera" to camera,
            "max_width_px" to int("For camera.snapshot", 256, 4096)), listOf("type"),
            "Besides type: open_link needs url; open_app needs package; speak and clipboard need text; message_draft and email_draft need recipient and text; " +
                "dial needs number; calendar_draft needs title; open_file needs file_id; media needs command")
        "notification.reply" -> Spec(linkedMapOf("id" to str("From notification.list", 1024), "fingerprint" to str("From notification.list; a changed notification is refused", 64),
            "action_index" to int("Reply action index from notification.list (default 0)", 0, 20), "text" to str(max = 4096)), listOf("id", "fingerprint", "text"))
        "file.put" -> Spec(linkedMapOf("name" to str(max = 120), "mime" to str(max = 120), "data_base64" to str("File bytes, base64, up to about 520 KB", 720_000), "note" to str(max = 1024)),
            listOf("name", "mime", "data_base64"))
        "workflow.propose", "workflow.validate" -> Spec(linkedMapOf(
            "name" to str(max = 120),
            "trigger" to obj(linkedMapOf(
                "type" to str("called: only a watch rule, a schedule or workflow.run starts it", enum = listOf("button", "interval", "power", "battery", "sun", "charging", "geofence", "watch", "called", "mode", "applet.event", "application.event")),
                "to" to str("For mode: start when the phone's mode changes to this (home, away, night, vacation or one the owner added); omitted: any change", 24),
                "from" to str("For mode: only a change from this mode", 24),
                "for_s" to int("For power: unplugged fires once the phone has been off charge this long (600 = a power cut); restored fires when charging comes back after at least this long off; plugged after this long charging (default 0)", 0, cc.bridgit.app.power.PowerRules.MAX_FOR_S.toLong()),
                "offset_min" to int("For sun: minutes before (negative) or after sunrise or sunset (default 0)", -cc.bridgit.app.power.SunTimes.MAX_OFFSET_MIN.toLong(), cc.bridgit.app.power.SunTimes.MAX_OFFSET_MIN.toLong()),
                "with" to JSONObject().put("type", "object").put("description", "For called: the values callers may hand over, {name: text|number|boolean} (0-8); steps read them as {\"\$trigger\":\"with.<name>\"}"),
                "watch" to str("Required for watch: the id of one of your watches", 64), "on" to str("For watch: match (default) or rule. For power: unplugged, plugged or restored. For battery: ${WorkflowDefinition.BATTERY_ONS.joinToString(", ")}. For sun: sunrise or sunset",
                    enum = (listOf("match", "rule") + cc.bridgit.app.power.PowerRules.ONS + WorkflowDefinition.BATTERY_ONS + cc.bridgit.app.power.SunTimes.ONS).distinct()),
                "rule" to str("For watch with on: rule: the tally rule id", 40),
                "seconds" to int("For interval (default 900)", 30, 86400),
                "latitude" to num(-90.0, 90.0, "Required for geofence; optional for sun (otherwise the phone saves a rough position once, scope location)"), "longitude" to num(-180.0, 180.0, "Required for geofence; optional for sun"),
                "applet_id" to str("Applet identifier for applet.event",80), "application_id" to str("Legacy alias for applet_id; if both are supplied they must agree",80), "source_id" to str("Required for applet.event (and legacy application.event)",80),
                "transport" to str(enum=listOf("webhook","notification")), "package" to str("Required for notification transport",256),
                "adapter" to str(enum=listOf("generic","tapo")), "event_type" to str("Only dispatch this normalized event type; omitted accepts all",80),
                "cooldown_seconds" to int("Per device and normalized type (default 30); if given, also the flow's cooldown_s (its old name)",0,86400),
                "mapping" to obj(listOf("type","device_id","device_name","title","text","occurred_at").associateWith { str("Dotted source object path",256) }),
                "radius_m" to int("For geofence (default 200)", 100, 10000)), listOf("type"), "geofence needs latitude and longitude"),
            "steps" to list(obj(linkedMapOf(
                "type" to str(enum = FlowSteps.STEPS.keys.toList()),
                "id" to str("Name this step to use its result later: {\"\$step\":\"<id>.<field>\"}", 32),
                "only_if" to JSONObject().put("type", "object").put("description", "Run only if (docs/applet-reach.md D1). One test: {\"step\":\"<id>.<path>\" or \"trigger\":\"<path>\", plus one of is, is_not, in, at_least, below, exists}; {\"time\":{\"between\":[\"22:00\",\"07:00\"],\"days\":[\"mon\",…]}}; {\"sun\":{\"after\" or \"before\":\"sunrise\" or \"sunset\",\"offset_min\":0}}; {\"phone\":\"charging\",\"is\":true} (battery at_least/below, network is wifi/mobile/none); {\"zone\":{latitude,longitude,radius_m},\"is\":true}; {\"mode\":true,\"is\":\"away\"}; {\"count\":{\"table\",\"since\":\"today\"|\"this_week\"|\"all\" or \"hours\":N,\"where\":{column,is}},\"below\":3}; {\"sum\":{…,\"column\"},\"at_least\":50}. Or {\"all\":[2-8 tests]} / {\"any\":[…]}, one level"),
                "on_error" to str("stop (default: end the run), skip (carry on) or retry", enum = listOf("stop", "skip", "retry")),
                "retries" to int("With on_error retry", 1, 3),
                "title" to flowText("Required for notice and ask_owner"), "text" to flowText("Required for speak, alert and event, and for send to a chat service; optional for notice, ask_owner and send"),
                "seconds" to int("For ring (1-30) and wait (1-300, required)", 1, 300),
                "destinations" to list(str(), "Required for alert: 1-3 owner-approved destination ids", 1, 3),
                "to" to str("Required for send: a connection id from GET /v1/connections (never a URL)", 256),
                "data" to JSONObject().put("type", "object").put("description", "For send: up to 32 keys, each a literal or a {\"\$step\"} / {\"\$trigger\"} reference (16 KiB)"),
                "wait_reply" to JSONObject().put("type", "boolean").put("description", "For send: wait up to 15 s and return status and reply (JSON) or reply_text"),
                "camera" to str("For photo: rear (default), front or a local camera id lan:…", 128),
                "model" to str("Required for ask_model", enum = listOf("jev", "clef", "glm")),
                "questions" to JSONObject().put("type", "object").put("description", "Required for ask_model: {id: {type: choice|noul|…, instructions, criteria?}}, as in watches"),
                "photo" to JSONObject().put("anyOf", JSONArray().put(JSONObject().put("type", "object")).put(JSONObject().put("type", "string"))).put("description", "For ask_model: {\"\$step\":\"<photo step id>.photo\"}; for send: {\"\$step\":\"<photo step id>\"} or a kept photo"),
                "choices" to list(str(max = 80), "Required for ask_owner: 2-6 buttons", 2, 6),
                "wait_s" to int("For ask_owner: how long to wait for an answer (default 300)", 30, 3600),
                "priority" to str("For notice: normal (default) or urgent", enum = listOf("normal", "urgent")),
                "mode" to str("Required for set_mode: one of the phone's modes (GET /v2/mode lists them)", 24),
                "table" to str("For record, update and lookup: a table of the applet", 32),
                "where" to JSONObject().put("type", "object").put("description", "For lookup: only rows whose column is this value, {\"column\":\"taken\",\"is\":false}"),
                "since" to str("For lookup: today, this_week or all (default)", enum = FlowConditions.SINCE),
                "hours" to int("For lookup: only rows from the last N hours (instead of since)", 1, FlowConditions.MAX_HOURS.toLong()),
                "limit" to int("For lookup: how many of the newest rows to return (default 1); count counts them all", 1, FlowConditions.MAX_LOOKUP.toLong())), listOf("type")), min = 1, max = 32),
            "only_if" to JSONObject().put("type", "object").put("description", "The whole flow runs only if this holds, checked before the first step (it may read the trigger, not a step); a start that doesn't is skipped and counted in skipped_runs. Same tests as a step's only_if"),
            "max_paid_per_day" to int("With ask_model steps: paid questions per day (default 50)", 1, 500),
            "cooldown_s" to int("After a run starts, further starts within this many seconds are skipped and counted in skipped_runs", 0, 86400),
            "max_runs_per_day" to int("After this many runs since local midnight, further starts are skipped", 1, 1000),
            "ui" to obj(linkedMapOf("template" to str(enum = listOf("button", "status", "help")), "title" to str(max = 120), "text" to str(max = 1024)), listOf("template"))),
            listOf("name", "trigger", "steps"))
        "stream.start" -> Spec(linkedMapOf("camera" to str("rear (default), front, or an enrolled lan:<id>; local cameras also need lan_camera permission",80), "duration_seconds" to int(min = 10, max = 1800), "interval_seconds" to int(min = 2, max = 30)))
        "images.ocr", "images.barcode" -> Spec(linkedMapOf("file_id" to str("A file from file.put"), "gallery_id" to str("A gallery photo id"),
            "image_base64" to str("Image bytes, base64", 720_000)), description = "Give exactly one of file_id, gallery_id or image_base64")
        "translation.run" -> Spec(linkedMapOf("source" to str("Language tag, e.g. en", 16), "target" to str("Language tag, e.g. de", 16), "text" to str(max = 10_000)), listOf("source", "target", "text"))
        "translation.download" -> Spec(linkedMapOf("source" to str("Language tag, e.g. en", 16), "target" to str("Language tag, e.g. de", 16)), listOf("source", "target"))
        "speech.transcribe" -> Spec(linkedMapOf("language" to str("BCP 47 tag (default the phone's)", 32)))
        "alert.trigger" -> Spec(linkedMapOf("destinations" to list(str(), "1-3 owner-approved destination ids", 1, 3), "text" to str(max = 1024)), listOf("destinations", "text"))
        "sensors.read" -> Spec(linkedMapOf("which" to list(str(enum = listOf("light", "pressure", "motion", "proximity", "temperature")), "Default light, pressure and motion", max = 5)))
        "peripheral.action" -> Spec(linkedMapOf(
            "type" to str(enum = PERIPHERAL_ACTIONS),
            "seconds" to int("For ble.scan (default 5)", 1, 10),
            "address" to str("Required for ble.read and ble.write", 17),
            "service_uuid" to str("Required for ble.read and ble.write", 36), "characteristic_uuid" to str("Required for ble.read and ble.write", 36),
            "data_base64" to str("Required for ble.write and for usb.transfer to an output endpoint", 90_000),
            "device_id" to str("Required for usb.transfer", 256),
            "interface" to int("For usb.transfer (default 0)", 0), "endpoint" to int("For usb.transfer (default 0)", 0),
            "max_bytes" to int("For usb.transfer from an input endpoint", 1, 65536)), listOf("type"),
            "ble.read needs address, service_uuid and characteristic_uuid; ble.write also needs data_base64; usb.transfer needs device_id")
        "note.create", "feedback.create" -> Spec(linkedMapOf("text" to str(max = 4096)), listOf("text"))
        else -> Spec(emptyMap())
    }

    fun forTool(name: String): JSONObject {
        val s = spec(name)
        val props = LinkedHashMap(s.props)
        val required = s.required.toMutableList()
        if (name in Commands.mutations || name == "configuration.set") {
            props["idempotency_key"] = str("Unique per logical request. Retries reuse the same key and exact input.")
            required.add(0, "idempotency_key")
        }
        return obj(props, required, s.description)
    }
}
