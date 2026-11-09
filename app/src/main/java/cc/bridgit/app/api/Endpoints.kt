package cc.bridgit.app.api

/** One query or body parameter, for the guide and openapi.json. */
data class Param(val name: String, val type: String, val description: String, val required: Boolean = false,
                 val enum: List<String>? = null)

/**
 * Describes one endpoint. The router, `/v1/guide` and `/v1/openapi.json` are all
 * generated from these, so they can't drift apart (API-09).
 */
data class EndpointSpec(
    val method: String,
    val path: String,                 // without /v1, e.g. "/camera/snapshot"; {id} for path params
    val capability: String,           // module id
    val summary: String,
    val auth: Boolean = true,
    val capture: Boolean = false,     // counts toward SEC-07 capture limits
    val idempotent: Boolean = false,  // accepts Idempotency-Key (API-13)
    val media: Boolean = false,       // returns an image or audio; accepts raw=1 where noted
    val query: List<Param> = emptyList(),
    val body: List<Param> = emptyList(),
    val example: String,
    val returns: String,
    val errors: List<String> = emptyList(),
    /** AGT-15: only agents the owner gave this power; checked before anything else, including replays. */
    val power: cc.bridgit.app.core.Power? = null,
    /** Body fields never copied into the log, only their length (issue text, notes, images). */
    val privateFields: Set<String> = emptySet(),
    /** Largest request body (API-11): 2 MB unless the endpoint takes media. Non-JSON bodies on such endpoints are streamed to a temp file (Req.takeRawFile); JSON stays at 2 MB. */
    val maxBody: Int = 2 * 1024 * 1024,
    val idempotencyRequired:Boolean = false,
    /** The backend owns durable result replay; do not shadow its fingerprint or retention locally. */
    val backendIdempotency: Boolean = false,
    val controlScope: String? = null,
) {
    fun loggedParam(name: String, value: Any?): Any? =
        when {
            "*" in privateFields || (name == "q" && name in privateFields) -> "[redacted]"
            name in privateFields -> "[${(value as? org.json.JSONArray)?.length()?.let { "$it items" } ?: "${value.toString().length} chars"}]"
            else -> value
        }
    val fullPath get() = "/v1$path"
    /** Needs Fixer Mode or Super Powers: checked on direct calls and owner-authorized maximum relay calls; never scheduled or shown to agents without it. */
    val privileged get() = power != null
}

object Endpoints {
    /** One `store` parameter for every capture endpoint (SEC-13, D33). */
    val STORE = Param("store", "array", "Keep this capture too: any of memory (fetch it later from GET /v1/captures/{id}), files (a copy in the phone's Downloads/Bridgit), upload (POSTed to your upload_url). [] or \"none\" = only return it. Default: your POST /v1/captures/storage choice, else only returned")
    private val sizing = listOf(
        Param("size", "string", "Preset: agent (1568 px, q85), thumb (512, q75), large (2560, q90), original", enum = listOf("agent", "thumb", "large", "original")),
        Param("max_edge_px", "integer", "Long edge limit, 64-20000; overrides the preset"),
        Param("max_width_px", "integer", "Width limit, 64-20000"),
        Param("quality", "integer", "JPEG quality 40-100"),
        Param("raw", "integer", "1 = return the image bytes, details in X-Bridgit-* headers"),
    )
    /** A0141-A0143: the people view of a screenshot or photo, found and cut on the phone. */
    private val peopleView = listOf(
        Param("view", "string", "full (default): the whole frame. people: the phone finds the people (offline) and returns one crop around all of them, each person's whole visible body plus a little padding (found_by face: only a face was found, so the body is estimated as head and shoulders); the reply's people_view gives the crop box in the source frame, each person's box, and the returned size and aspect ratio. Nobody found: no_person_found, and nothing is returned or kept", enum = listOf("full", "people")),
        Param("aspect", "string", "With view people: best_fit (default, whatever the people plus padding give) or a shape such as \"20:9\", \"1:1\", \"4:5\" (or a number, width / height, 0.1-10). The crop grows inside the frame to reach it; if the frame can't hold that shape around everyone, people_view.aspect_met is false and no one is cut off"),
        Param("padding", "number", "With view people: space around the people, 0-0.5 of their larger side (default 0.08)"),
    )

    val SERVICES = EndpointSpec("GET", "/services", "status", "Discover general decision and location-context services for any applet: Azure image generation, models, nearby places, air quality, pollen, time zones, maps and weather; call formats, grants and budgets; no paid call",
        example = "GET /v1/services", returns = "bridgit-services/1: services[id,name,provider,provider_model?,applet_compatible,paid,inputs,availability,call,applet,budget,docs]; catalog availability is configuration only; check grants and live call results")
    val HEALTH = EndpointSpec("GET", "/health", "status", "Is Bridgit up, and which agent you are",
        example = "GET /v1/health", returns = "status, app, app_version, build_type (release, or debug for a test build), distribution (private or play), build_tag (null for the real app; debug or test claude <feature> for test builds), package (cc.bridgit.app is the real app), app_label (the name Android lists this build under), mcp (this API's MCP server, /v1/mcp), agent_control (the second MCP server /mcp and /v2 routes for the control tools, with mode private or limited), agent, agent_id, model (what you last told POST /v1/me, or null), listener_uptime_seconds, battery_level, battery_runtime (estimated remaining/lower/upper seconds while unplugged, status and observed_seconds; learning until 10 minutes and 2% drain), charging, bind_address, port, listener {state (listening, rebinding, waiting_for_tailscale, bind_failed or stopped), binds (tailscale: Bridgit listens only on its Tailscale addresses, never the LAN), bound_to, port, tailscale_addresses (what the phone has now), on_tailnet, tailscale_installed, magic_dns, bind_error, ladder (what to do when a call fails)}, base_url (the address to use for every call), owner_email (the same status as GET /v1/owner/email), api_revision, whats_new (API changes since the revision you last saw), links (where to read more), display {width_px, height_px, aspect_ratio, orientation, full_screen_card {portrait, landscape}, details: /v1/device/display}, resource_monitor {enabled, since, interval, level, cpu_percent, rss_mb, details: /v1/device/resources}")
    val DEVICE_DISPLAY = EndpointSpec("GET", "/device/display", "status", "This phone's display right now and the canvas a card's page gets: full screen (presentation full_screen) per orientation and the standard card frame. Static specs (screen size, cameras) are also stored with Bridgit: ask GET <team backend>/v1/names/{name}/specs first when the phone may be busy or offline",
        example = "GET /v1/device/display", returns = "width_px, height_px (the full display in its current rotation), density_dpi, density, aspect_ratio (long:short, e.g. 20:9), rotation (0, 90, 180 or 270), orientation (portrait or landscape), refresh_hz, " +
            "full_screen_card {portrait {width_px, height_px}, landscape {width_px, height_px}} (the whole display less the camera cutout; compose full-screen images to this size), standard_card {width_px, height_px, measured} (the bordered card frame; measured false = estimated from the window)")
    val DEVICE_RESOURCES = EndpointSpec("GET", "/device/resources", "status", "Monitor resources (A0189): Bridgit's own footprint over time, never other apps or content: CPU, memory, network, disk, storage, context switches (the wakeup proxy), with battery and thermal state as device context. " +
        "Off by default; switch it and choose the interval (1s, 10s, 1m default, 1h) with the settings device.resource_monitor and device.resource_monitor_interval (POST /v1/settings, or POST /v1/owner/configuration/settings with the owner.configuration grant)",
        query = listOf(Param("view", "string", "summary (default: status, current hour, today), samples (raw samples), hourly or daily (summaries per period and build)", enum = cc.bridgit.app.monitor.ResourceMonitor.VIEWS),
            Param("since", "string", "Only entries at or after this time: ISO 8601 (2026-11-09T10:00:00Z) or epoch milliseconds"),
            Param("limit", "integer", "Newest entries to return, 1-1440 (default 100)")),
        example = "GET /v1/device/resources?view=hourly&limit=24",
        returns = "enabled, running, since, stopped_at, interval, interval_s, intervals, default_interval (1m), level (current hour: ok|elevated|high), latest (newest sample), scope (bridgit_only), platform, bridgit_name, build {version, version_code, commit}, " +
            "current_hour, today (summaries), thresholds {metric: {elevated, high}}, retention {samples, hours, days, samples_kept}, measures (what each number is; Android gives apps no per-app energy or wakeup count), view, and samples[] | hours[] | days[]. " +
            "A sample: at, interval_s, build, version_code, cpu_percent (of one core), cpu_device_percent, rss_mb, peak_rss_mb, java_heap_mb, native_heap_mb, threads, network_bytes {rx, tx}, disk_bytes {read, write}, context_switches, storage_mb, battery {percent, charging, temperature_c}, thermal, monitor_cost_ms. " +
            "A summary: period, start, end, build, version_code, samples, covered_s, cpu_percent {avg, peak}, rss_mb {avg, peak}, peak_rss_mb, network_bytes, network_mb_per_hour, disk_bytes, disk_write_mb_per_hour, context_switches, context_switches_per_s, storage_mb, battery_percent {first, last}, thermal_worst, monitor_cost_ms, level, over [{metric, value, level}]. Unavailable numbers are null",
        errors = listOf("invalid_parameter"))
    val DEVICES = EndpointSpec("GET", "/devices", "status", "Host and owner-configured external devices, supported actions and access status",
        example = "GET /v1/devices", returns = "devices: host Android device, cameras visible to your lan_camera grant and your own LAN enrollments when lan_control is granted and enabled; SSH profiles when ssh is granted and enabled; ids, names, configured state, availability (not_checked until an action), advertised pan/tilt and exact action paths/bodies. No credentials. Inventory is passive and does not scan the LAN. lan_path: read-only status of the agent path for other LAN devices (speakers such as WiiM): discover, enroll and control steps with agent grant, owner switch, usable, endpoints and the number of this agent's enrolled devices; granting and approval stay with the owner.")
    val ME = EndpointSpec("POST", "/me", "status", "Tell the owner which model you are and what runs you; shown on your agent card",
        body = listOf(Param("model", "string", "Your model, as exact as you know it, e.g. claude-opus-5-5 or gpt-6.1 (1-60 chars)", required = true),
            Param("harness", "string", "The program running you, e.g. Claude Code, Codex CLI, a Python script (1-40 chars)")),
        example = "POST /v1/me {\"model\":\"claude-opus-5-5\",\"harness\":\"Claude Code\"}", returns = "agent, model, harness",
        errors = listOf("invalid_parameter"))
    val OWNER_EMAIL = EndpointSpec("GET", "/owner/email", "status", "Whether the owner has verified their email. Bridgit works for 24 hours from the first agent call; after that, until they do, only the status endpoints (such as health, me, owner email, capabilities, guide, demo, samples, openapi, changes) and feedback keep working, plus the control tools capabilities, events, operation.get and feedback.create; every other call answers email_required",
        example = "GET /v1/owner/email", returns = "status (verified, grace or required), state (none, pending or confirmed), email (masked, e.g. w***@gmail.com, or null), grace_ends_at (ISO time, or null before the first call), hours_left, required_after_hours (24), how")
    val OWNER_EMAIL_SET = EndpointSpec("POST", "/owner/email", "status", "Give the owner's email address: bridgit.cc emails them a link, and tapping it verifies the address (the same address gets email alerts). Works during and after the 24 hours; not once an address is verified (the owner changes it on the phone)",
        body = listOf(Param("email", "string", "The owner's email address", required = true)),
        privateFields = setOf("email"),
        example = "POST /v1/owner/email {\"email\":\"owner@example.com\"}", returns = "status, state (pending), email (masked), next (what to tell the owner)",
        errors = listOf("invalid_parameter", "conflict", "rate_limited", "upstream_failed"))
    val CAPABILITIES = EndpointSpec("GET", "/capabilities", "status", "What's switched on and ready, and why not",
        example = "GET /v1/capabilities", returns = "per capability: title, enabled (the phone switch), ready (the device can do it), agent_allowed (the owner lets you use it), blocked_reason, details. Usable only when agent_allowed, enabled and ready are all true. enabled false means the owner's switch is off; blocked_reason names other known causes (agent_not_allowed, a missing Android permission, no_hardware) and can be null. Also notifications, callback {set, host}, listener")
    val LIMITS = EndpointSpec("GET", "/limits", "status", "Your account's limits right now, and what is used: Jev and Clef calls an hour, the monthly model budget, paid watch checks, and the limits this phone enforces itself. Limits differ per account and can change: read them here, never assume documented numbers",
        query = listOf(Param("refresh", "boolean", "true asks the team backend again instead of the copy from the last 10 minutes")),
        example = "GET /v1/limits", returns = "account {available, applies_to, verified, team, source (default, account or install), limits {model_calls_per_hour, model_usd_per_month, watch_paid_checks_per_hour: {value, used?, resets_at?, enforced_by, about}}, shared {total_usd_per_day, total_usd_per_month, unverified_usd_per_month, clef_free_daily_allowance}, prices, cached} or {available: false, reason} when the backend can't be reached; phone {captures_per_minute_per_agent, open_waits_per_agent, watches, cards, schedule}; watch_paid_checks_per_hour_in_effect; relay (where relay quotas are read)")
    val DISCOVER = EndpointSpec("GET", "/discover", "status", "Find capabilities and recipes for an intent without executing them",
        query = listOf(Param("q", "string", "Task intent, at most 256 characters"), Param("id", "string", "Exact stable capability or recipe ID"),
            Param("limit", "integer", "Maximum results, 1–20 (default 5)"), Param("detail", "string", "Response detail", enum = listOf("summary", "full"))),
        example = "GET /v1/discover?q=scan%20receipt&limit=5", returns = "start (with q: the closest recipe, why, you_decide, capabilities with this phone's ready and blockers, owner_must, first_calls, adapt, done_when, recipe_url; null when nothing matches), documentation_revision, api_revision, installed_api_revision, provenance, results[id,kind,score,reason,state,prerequisites,operations,boundaries]; unknown state is null. Full detail supplies exact operation references. No device actions.",
        errors = listOf("invalid_parameter", "rate_limited"), privateFields = setOf("q"))
    val DISCOVERY_REPORT = EndpointSpec("POST", "/discover/report", "status", "Report an explicit catalog selection or bounded self-reported feedback; never verified evidence",
        body = listOf(Param("event", "string", "selection, relevance, attempt or outcome", true, listOf("selection", "relevance", "attempt", "outcome")),
            Param("id", "string", "Catalog ID for selection; recipe ID for attempt"), Param("value", "string", "helpful/not_helpful for relevance; works/still_broken/unverified for outcome")),
        example = "POST /v1/discover/report {\"event\":\"selection\",\"id\":\"receipt-scanner\"}",
        returns = "accepted (validation only), measurement self_report; respects usage-tally opt-out. No content or evidence accepted.",
        errors = listOf("invalid_parameter", "rate_limited"), privateFields = setOf("*"), maxBody = 4096)
    val GUIDE = EndpointSpec("GET", "/guide", "status", "This guide, generated live (Markdown)",
        example = "GET /v1/guide", returns = "text/markdown")
    val START = EndpointSpec("GET", "/start", "status", "The starter (https://bridgit.cc/learn/start.md) served from the phone, so an agent without web access reads it too: the four stages of building an applet (Markdown, no key needed)",
        auth = false, example = "GET /v1/start", returns = "text/markdown")
    val WELCOME = EndpointSpec("GET", "/welcome", "status", "The Bridgit Interface Guide: this agent's full briefing (connection, rules, first calls, first replies, pointers), built live for this device and agent; the short setup prompt points here (plain text)",
        example = "GET /v1/welcome", returns = "text/plain")
    val DEMO = EndpointSpec("GET", "/demo", "status", "The demo to offer right after pairing: a five-minute first-run tour run from cards on the phone, then a menu of more demos picked by a few questions (Markdown)",
        example = "GET /v1/demo", returns = "text/markdown")
    val SAMPLES = EndpointSpec("GET", "/samples", "status", "Complete interaction samples for every kind (notice, question, html, text): the POST /v1/interactions body, the reply and the answer it produces (JSON). For cards that decide or watch (Jev, Clef, the free camera change check), read ${Learn.DECISIONS_URL}",
        query = listOf(Param("id", "string", "One sample, e.g. html-step"), Param("kind", "string", "Only samples of this kind", enum = listOf("notice", "question", "html", "text"))),
        example = "GET /v1/samples?id=html-step", returns = "api_revision, conventions, samples[id, kind, title, summary, request, answer, events, notes, page?, calls?]",
        errors = listOf("not_found"))
    val OPENAPI = EndpointSpec("GET", "/openapi.json", "status", "OpenAPI 3.1 description of every endpoint",
        example = "GET /v1/openapi.json", returns = "application/json")
    val REGISTRY = EndpointSpec("GET", "/registry", "status", "The API registry: every API group with its fixed name and number, its calls, and the one permission that governs them (API access, on by default, all-or-nothing for now)",
        example = "GET /v1/registry", returns = "permission {id: api_access, name, default: true, granular: false, you}, count, calls, apis[number, id, name, permission, default, allowed, calls[\"METHOD /v1/path\" or \"tool <name>\"]]; numbers never change or get reused")
    val CHANGES = EndpointSpec("GET", "/changes", "status", "What's new in the API since a revision you know",
        query = listOf(Param("since", "integer", "The API revision you last read about; 0 or absent = everything")),
        example = "GET /v1/changes?since=2", returns = "api_revision, app_version, changes[revision, app_version, date, items[what, endpoint]], links")
    val HELPER = EndpointSpec("GET", "/tools/bridgit", "status", "The `bridgit` command-line helper (Python, no secrets inside)",
        auth = false, example = "GET /v1/tools/bridgit", returns = "text/x-python")
    /** Deprecated alias of [HELPER] from before the Bridget → Bridgit rename (API revision 17). */
    val HELPER_LEGACY = EndpointSpec("GET", "/tools/bridget", "status", "Deprecated: the old address of GET /v1/tools/bridgit, same helper",
        auth = false, example = "GET /v1/tools/bridget", returns = "text/x-python")
    val PAIR = EndpointSpec("POST", "/pair", "status", "Fetch your key with a 6-digit code shown on the phone (alternative to the setup prompt)",
        auth = false, body = listOf(Param("code", "string", "6-digit code from the phone", required = true)),
        example = "POST /v1/pair {\"code\":\"482913\"}", returns = "key, agent, base_url (once per pairing window)",
        errors = listOf("pairing_closed", "unauthorized", "rate_limited"))

    val SNAPSHOT = EndpointSpec("POST", "/camera/snapshot", "camera", "Take a photo now. Works while the phone is locked; if the owner turned on captures.locked_camera_approval, a locked photo first waits up to 30 s for their Allow (approval_declined otherwise)",
        capture = true, idempotent = true, media = true,
        body = listOf(
            Param("viewfinder","boolean","Optional visible capture/review; native card, portal and MCP-App captures always require it"), Param("group_photo","boolean","Offer a spoken GO shutter using approved on-device microphone access"), Param("instructions","string","Short framing instructions above the live preview, up to 240 characters"), Param("countdown_seconds","integer","0–10 seconds after the visible shutter is tapped"), Param("camera", "string", "rear (default) or front", enum = listOf("rear", "front")),
            Param("return_image","boolean","false returns capture metadata only; requires store:[memory] or another enabled retention destination; default true"),
            Param("flash", "boolean", "Fire the flash (default false)"),
            Param("zoom", "number", "Zoom ratio, e.g. 0.5 (ultra-wide), 1, 2, 5 (telephoto); the range is in /v1/capabilities after the first photo"),
            Param("max_width_px", "integer", "Width limit, 64-20000, or null for full size (default 2048 unless size is given)"),
            STORE,
        ) + sizing.filter { it.name != "max_width_px" } + peopleView,
        example = "POST /v1/camera/snapshot {\"camera\":\"rear\",\"zoom\":2}",
        returns = "captured_at, width_px, height_px, format, size_bytes, view, data_base64 (or JPEG bytes with raw=1); capture {capture_id, stored, url, memory_until, file} when kept (X-Bridgit-Capture-Id with raw=1); with view people, people_view {crop {x, y, width, height}, source {width_px, height_px}, width_px, height_px, aspect_ratio, aspect_ratio_value, aspect_requested, aspect_met, people_count, people [{x, y, width, height, found_by}], detector} (X-Bridgit-Crop, X-Bridgit-Source-Size, X-Bridgit-Aspect-Ratio, X-Bridgit-People-Count with raw=1)",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "device_locked", "approval_declined", "sensor_busy", "capture_cancelled", "viewfinder_required", "capture_timeout", "service_start_blocked", "payload_too_large", "no_person_found"))
    // Not a capture for the SEC-07 rate limit: it returns nothing, and the snapshot that follows counts.
    val CAMERA_ARM = EndpointSpec("POST", "/camera/arm", "camera", "Open the camera ahead of a photo, so your next snapshot fires in about 0.3 s",
        body = listOf(
            Param("camera", "string", "rear (default) or front: the camera your next snapshot will use", enum = listOf("rear", "front")),
            Param("seconds", "integer", "How long to keep it ready, 1-10 (default 10). It closes after your next snapshot, at the end of this time, or when the owner pauses Bridgit"),
        ),
        example = "POST /v1/camera/arm {\"camera\":\"front\",\"seconds\":10}",
        returns = "camera, armed, seconds, armed_until, settle_ms (exposure needs this long after the camera opens; a snapshot sent sooner waits out the rest); the next snapshot says warm: true",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "device_locked", "sensor_busy", "service_start_blocked"))
    val POSE_START = EndpointSpec("POST", "/camera/pose/start", "camera",
        "Track a person's body for up to 2 minutes: the phone finds 33 body points in every camera frame (ML Kit, offline and free; no picture leaves the phone). Read the frames as they come with GET /v1/camera/pose/{id}",
        capture = true,
        body = listOf(
            Param("camera", "string", "front (default, the person can see the screen) or rear", enum = listOf("front", "rear")),
            Param("seconds", "integer", "How long to track, ${cc.bridgit.app.capability.PoseRules.MIN_SECONDS}-${cc.bridgit.app.capability.PoseRules.MAX_SECONDS} (default ${cc.bridgit.app.capability.PoseRules.DEFAULT_SECONDS}). It also stops when nobody has read it for 10 s"),
            Param("preview_every_ms", "integer", "How often to refresh the small preview picture, 150-2000 (default 400)"),
            Param("preview_edge_px", "integer", "Longest side of the preview picture, 120-480 (default 320)")),
        example = "POST /v1/camera/pose/start {\"camera\":\"front\",\"seconds\":45}",
        returns = "session_id, state starting|running, camera, mirror (true for front: draw it mirrored), max_ms, started_at",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "device_locked", "sensor_busy", "service_start_blocked", "invalid_parameter"))
    val POSE_READ = EndpointSpec("GET", "/camera/pose/{id}", "camera",
        "Read a body tracking session: its state and the frames since an index (each frame is the 33 points as [x, y, v, ...]: x and y fractions of the upright picture, v how sure the point is in view), plus the latest preview picture",
        query = listOf(
            Param("since", "integer", "First frame index to return (0 = from the start; pass the last reply's next)"),
            Param("limit", "integer", "Frames per reply, 1-${cc.bridgit.app.capability.PoseRules.MAX_LIMIT} (default ${cc.bridgit.app.capability.PoseRules.DEFAULT_LIMIT})"),
            Param("preview_after", "integer", "Include the preview picture when it is newer than this many ms into the session (-1 = the latest)"),
            Param("wait_ms", "integer", "Wait up to this long (0-1000) for a new frame before answering")),
        example = "GET /v1/camera/pose/pose_abc?since=0&preview_after=-1",
        returns = "session_id, state starting|running|done|stopped|failed, camera, mirror, width_px, height_px, elapsed_ms, max_ms, analyzed, fps, frames_total, frames [{i, t ms, p [99 numbers]}], next, thumbs (replay pictures kept), points (the 33 names, when since=0), preview {t, width_px, height_px, data_base64} when asked and newer",
        errors = listOf("not_found", "invalid_parameter"))
    val POSE_FRAME = EndpointSpec("GET", "/camera/pose/{id}/picture", "camera",
        "A small picture from a body tracking session (one is kept every half second), the one nearest a time, for a replay",
        query = listOf(Param("t", "integer", "Milliseconds into the session", required = true)),
        example = "GET /v1/camera/pose/pose_abc/picture?t=12000",
        returns = "t, width_px, height_px, format jpeg, data_base64",
        errors = listOf("not_found", "invalid_parameter"))
    val POSE_STOP = EndpointSpec("POST", "/camera/pose/{id}/stop", "camera", "Stop a body tracking session early and free the camera; its frames stay readable for 15 minutes",
        example = "POST /v1/camera/pose/pose_abc/stop", returns = "The session as GET /v1/camera/pose/{id} shows it (no frames)",
        errors = listOf("not_found"))
    val MIC = EndpointSpec("POST", "/mic/record", "mic", "Record an audio clip",
        capture = true, idempotent = true, media = true,
        body = listOf(Param("transcribe", "boolean", "Use only Android on-device recognition; no cloud fallback"), Param("return_audio", "boolean", "true by default; false requires transcribe=true"), Param("language", "string", "Optional offline language, e.g. en-US"), Param("duration_seconds", "integer", "Whole seconds, 1-120", required = true), STORE),
        example = "POST /v1/mic/record {\"duration_seconds\":5}",
        returns = "recorded_at, duration_seconds, format (m4a), sample_rate_hz, size_bytes, data_base64; capture {capture_id, stored, url, memory_until, file} when kept",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "sensor_busy", "capture_timeout", "service_start_blocked"))
    val SPEECH_HEAR = MIC.copy(path = "/speech/hear", summary = "Hear one bounded turn through the model registry",
        body = listOf(Param("duration_seconds", "integer", "Whole seconds, 1-30", required = true), Param("allow_cloud", "boolean", "Explicit permission to send audio to the registry-selected cloud model; default false"), Param("language", "string", "BCP 47 language"), Param("return_audio", "boolean", "Default false; original clip if true for cloud hearing only; device hearing does not record a clip"), STORE),
        example = "POST /v1/speech/hear {\"duration_seconds\":5}", returns = "transcript, on_device, speech{model, processing, cost_usd, elapsed_ms}; normal clip metadata; no audio by default")
    val SPEECH_MODELS = EndpointSpec("GET", "/speech/models", "status", "Speech selection and local readiness",
        example = "GET /v1/speech/models", returns = "mode, max_seconds, max_chars, hearing_on_device, speaking{ready,offline_voices}, hearing, speaking_model")
    val GALLERY_RECENT = EndpointSpec("GET", "/gallery/recent", "gallery", "Newest gallery images (metadata only)",
        query = listOf(Param("type", "string", "any, photo or screenshot"), Param("album", "string", "Exact album name"), Param("since", "string", "ISO-8601 UTC added-at lower bound"), Param("until", "string", "ISO-8601 UTC upper bound"), Param("limit", "integer", "1-100, default 20")),
        example = "GET /v1/gallery/recent?limit=10", returns = "access_mode, photos[id, taken_at, width_px, height_px, size_bytes, mime, album]",
        errors = listOf("capability_disabled", "permission_denied"))
    val GALLERY_PHOTO = EndpointSpec("GET", "/gallery/photo/{id}", "gallery", "One gallery image, sized for you",
        media = true, query = sizing,
        example = "GET /v1/gallery/photo/87567?size=agent", returns = "taken_at, width_px, height_px, format, size_bytes, data_base64",
        errors = listOf("capability_disabled", "permission_denied", "photo_not_found", "payload_too_large"))
    val IMAGE_LAST = EndpointSpec("GET", "/image/last", "gallery", "The newest image of a type; with since and wait, waits for the next one",
        media = true,
        query = listOf(
            Param("type", "string", "any (default), screenshot or photo", enum = listOf("any", "screenshot", "photo")),
            Param("since", "string", "ISO time or gallery id: only an image added after it"),
            Param("wait", "integer", "With since: hold up to this many seconds (max 55) for a new image"),
        ) + sizing,
        example = "GET /v1/image/last?type=screenshot", returns = "id, type, taken_at, added_at, album, width_px, height_px, size_bytes, data_base64",
        errors = listOf("capability_disabled", "permission_denied", "photo_not_found", "too_many_waits"))

    private val logFilters = listOf(
        Param("channel", "string", "Comma list of audit, diagnostic, agent"),
        Param("agent", "string", "Agent name or id; `me` for you; `bridgit` for Bridgit's own entries"),
        Param("kind", "string", "request, setting, agent, pairing, service, note, …"),
        Param("endpoint", "string", "Endpoint prefix, e.g. /v1/camera"),
        Param("result", "string", "ok, error, or an error code"),
        Param("level", "string", "Minimum level: debug, info, warn, error"),
        Param("since", "string", "Log id or ISO time (exclusive id)"),
        Param("until", "string", "Log id or ISO time"),
        Param("q", "string", "Text in message or endpoint"),
    )
    val LOG = EndpointSpec("GET", "/log", "log", "Read the log; with order=asc&since=<id>&wait=55 it follows new entries",
        query = logFilters + listOf(
            Param("limit", "integer", "1-500, default 100"),
            Param("order", "string", "desc (default) or asc", enum = listOf("desc", "asc")),
            Param("cursor", "string", "next_cursor from the previous page"),
            Param("wait", "integer", "Tail mode: hold up to this many seconds (max 55) for a new entry"),
        ),
        example = "GET /v1/log?agent=me&limit=20", returns = "entries[], next_cursor, latest_id",
        errors = listOf("invalid_parameter", "too_many_waits"))
    val LOG_ENTRY = EndpointSpec("GET", "/log/{id}", "log", "One log entry",
        example = "GET /v1/log/48213", returns = "the entry", errors = listOf("not_found"))
    val LOG_STATS = EndpointSpec("GET", "/log/stats", "log", "Counts, errors and p50/p95 time per agent and endpoint",
        query = listOf(Param("since", "string", "Log id or ISO time; default the last 24 h")),
        example = "GET /v1/log/stats?since=2026-10-27T00:00:00%2B01:00", returns = "by_agent[], by_endpoint[]")
    val LOG_LATENCY = EndpointSpec("GET", "/log/latency", "log", "How long API calls take on this device, from receiving the request to sending the response: count, median, p95 and max per API, and the slow calls with the device context they ran in (network, battery, thermal, app build, screen, load). Every transport: HTTP, MCP, relay, cards, schedules and the control server. Kept in memory: the newest 5000 calls, at most 7 days, empty after a restart. Calls you may see follow the log's visibility",
        query = listOf(Param("api", "string", "Only this API: its operation name as in openapi.json (e.g. getHealth), or tool <name> / <METHOD> <path> for the control server"),
            Param("slow_ms", "integer", "A call this slow or slower counts as slow, 1-600000, default 1000"),
            Param("limit", "integer", "Slow calls to return, newest first, 0-200, default 50"),
            Param("minutes", "integer", "Only calls from the last 1-10080 minutes; default every kept call")),
        example = "GET /v1/log/latency?slow_ms=2000",
        returns = "device {name (the Device Domain Name, or null before one is registered), magic_dns}, kept {calls, capacity, max_age_hours, oldest, since_restart}, in_flight, slow_ms, " +
            "apis[api, method, path, count, errors, median_ms, p95_ms, max_ms, slow] (slowest p95 first), " +
            "slow_calls[ts, api, method, path, via, request_id, agent, agent_id, duration_ms, outcome (success or error), http, result, bytes_in, bytes_out (0 where not counted), in_flight (calls running when it arrived, itself included), ran_on, context], " +
            "context_now. context: network {type wifi/cellular/ethernet/other/none, metered}, battery {percent, charging, power_save}, thermal, app {version, build, commit}, screen {on, locked}, listener_ready; sampled at most every 10 s. No parameters, bodies, addresses, location or sensor data",
        errors = listOf("invalid_parameter"))
    val LOG_EXPORT = EndpointSpec("GET", "/log/export", "log", "Every matching entry as JSONL or CSV; direct HTTP streams without a total size cap; MCP/relay envelopes capped at 8 MiB",
        query = listOf(Param("format", "string", "jsonl (default) or csv", enum = listOf("jsonl", "csv"))) + logFilters,
        example = "GET /v1/log/export?format=csv&agent=me", returns = "application/x-ndjson or text/csv")
    val DEVICE_LOG = EndpointSpec("GET", "/log/device", "log", "Snapshot of Android logcat visible to Bridgit's app UID; requires Fixer Mode",
        power = cc.bridgit.app.core.Power.FIXER,
        query = listOf(Param("buffer", "string", "main (default), system, crash, events or all", enum = listOf("main", "system", "crash", "events", "all")), Param("lines", "integer", "1-10000, default 1000; snapshot capped at 2 MiB")),
        example = "GET /v1/log/device?buffer=crash&lines=1000", returns = "scope, buffer, output, exit_code, truncated, timed_out, retention",
        errors = listOf("forbidden", "invalid_parameter"))
    val DEVICE_EXITS = EndpointSpec("GET", "/log/exits", "log", "Android 11+ Bridgit process exits, crashes and ANRs; Fixer Mode; Android-managed history",
        power = cc.bridgit.app.core.Power.FIXER,
        query = listOf(Param("before", "integer", "Exclusive timestamp_ms cursor from next_before"), Param("limit", "integer", "1-100, default 20; timestamp ties kept together"), Param("trace", "boolean", "Include available traces (default false), up to 2 MiB total per page; native tombstones are base64 protobuf")),
        example = "GET /v1/log/exits?limit=20&trace=true", returns = "entries[id, timestamp_ms, process, pid, reason, status, description, importance, pss_kib, rss_kib, trace?], next_before, retention",
        errors = listOf("forbidden", "invalid_parameter", "not_supported"))
    val LOG_NOTE = EndpointSpec("POST", "/log", "log", "Write a note to the agent channel",
        idempotent = true,
        body = listOf(
            Param("message", "string", "Up to 1 KB", required = true),
            Param("level", "string", "debug, info (default), warn, error"),
            Param("data", "object", "Up to 4 KB of JSON"),
        ),
        example = "POST /v1/log {\"message\":\"checked fridge photo, milk low\"}", returns = "id",
        errors = listOf("invalid_parameter", "rate_limited"))
    val AUDIT = EndpointSpec("GET", "/audit", "log", "Deprecated alias of /v1/log?channel=audit (removed in v3)",
        query = listOf(Param("limit", "integer", "1-500, default 50")),
        example = "GET /v1/audit?limit=50", returns = "entries[] (same shape as /v1/log)")

    // Issues Tracking (renamed from "reports" / /v1/feedback, 8 Nov 2026).
    val ISSUES_LIST = EndpointSpec("GET", "/issues", "feedback", "Issues Tracking: bug reports, feature requests and applet requests, the ones you filed, or every issue for agents in Bridgit Fixer Mode",
        query = listOf(
            Param("mine", "boolean", "Fixer Mode agents: true = only issues you filed (other agents always get only their own)"),
            Param("status", "string", "new, seen, in_progress, fixed, wont_fix, duplicate, confirmed, reopened"),
            Param("kind", "string", "bug, feature or applet (an applet request)", enum = listOf("bug", "feature", "applet")),
            Param("since", "string", "ISO time: only issues updated after it"),
            Param("wait", "integer", "With since: hold up to this many seconds (max 55) for a new or changed issue"),
            Param("limit", "integer", "1-100, default 50"),
        ),
        example = "GET /v1/issues?status=fixed", returns = "issues[id (this phone's id, used in URLs), ref (the team-wide number, e.g. M0042 from the owner, A0042 from an agent; null until sent to the Bridgit team), number (same as ref), team_id, kind, status, first_line, created_at, updated_at, no_autofix, image_count, filed_by, sent_to_team]",
        errors = listOf("forbidden", "invalid_parameter", "too_many_waits"))
    val ISSUES_GET = EndpointSpec("GET", "/issues/{id}", "feedback", "One issue with its status history, team note and what you can send back; agents in Fixer Mode also get images, context and the attached log (and their first read marks a new issue seen)",
        media = true, query = sizing.filter { it.name != "raw" },
        example = "GET /v1/issues/7", returns = "issue: ref (team number, e.g. M0042, or null until sent), description, applet (applet requests only), status, team_note, history[at, kind, status, by, note], followup_outcomes; Fixer Mode: context, images[data_base64], log[], allowed_next",
        errors = listOf("not_found"))
    val ISSUES_STATUS = EndpointSpec("POST", "/issues/{id}/status", "feedback", "Move an issue: in_progress, fixed, wont_fix or duplicate (agents in Bridgit Fixer Mode)",
        idempotent = true, power = cc.bridgit.app.core.Power.FIXER, privateFields = setOf("note"),
        body = listOf(Param("status", "string", "in_progress, fixed, wont_fix or duplicate", required = true),
            Param("note", "string", "Short note shown to the owner, e.g. \"Fix in PR #412\" (500 chars)")),
        example = "POST /v1/issues/7/status {\"status\":\"in_progress\",\"note\":\"Looking at it\"}", returns = "the issue summary",
        errors = listOf("forbidden", "not_found", "invalid_parameter"))
    val ISSUES_FOLLOWUP = EndpointSpec("POST", "/issues/{id}/followup", "feedback", "Follow up on an issue you filed: a note (\"still happens on 1.7.2\"), and once it's marked fixed, won't fix or duplicate, outcome works (closes it) or still_broken (reopens it)",
        idempotent = true, privateFields = setOf("note"),
        body = listOf(Param("note", "string", "What you see now (500 chars); needed when there's no outcome"),
            Param("outcome", "string", "works or still_broken: the owner's Confirm / Still broken, allowed once the issue is fixed, wont_fix or duplicate", enum = listOf("works", "still_broken"))),
        example = "POST /v1/issues/7/followup {\"outcome\":\"still_broken\",\"note\":\"Still returns 500 on 1.7.2\"}", returns = "the issue summary",
        errors = listOf("not_found", "invalid_parameter", "rate_limited"))
    val ISSUES_POST = EndpointSpec("POST", "/issues", "feedback", "Legacy phone-local issue filing (any agent; images only in Bridgit Fixer Mode). Control agents should file issues directly with Bridgit servers: https://bridgit.cc/learn/bugs.md. Send every bug and every feature request, however small, even if you doubt we can or will do it: we'd rather triage too many than miss one",
        idempotent = true, privateFields = setOf("description", "images", "applet"),
        body = listOf(Param("kind", "string", "bug, feature or applet (an applet request: a new applet, with its portal page, to be built)", required = true, enum = listOf("bug", "feature", "applet")),
            Param("description", "string", "What happened, up to 10,000 characters", required = true),
            Param("applet", "object", "kind applet only, every field optional: {\"name\" (120 chars), \"what_it_does\" (2,000), \"devices\" (up to 10, 80 chars each), \"data_used\" (up to 20, 120 chars each), \"portal_shows\" (2,000)}"),
            Param("no_autofix", "boolean", "true = leave it for a person (features and applet requests always are)"),
            Param("images", "array", "Fixer Mode only: up to 5 {\"data_base64\"} images"),
            Param("agent_info", "object", "About you, sent with the issue to the Bridgit team: e.g. {\"harness\":\"Claude Code\",\"harness_version\":\"2.3.1\",\"model\":\"Claude Opus 5.5\"} (up to 20 short string, number or boolean fields)")),
        example = "POST /v1/issues {\"kind\":\"bug\",\"description\":\"image/last returned 500 twice\",\"agent_info\":{\"harness\":\"Claude Code\",\"harness_version\":\"2.3.1\",\"model\":\"Claude Opus 5.5\"}}", returns = "id (use in URLs), ref and number (the team-wide number, e.g. A0042; null until the phone sends it to the Bridgit team, then GET url shows it), status, no_autofix, kind, applet (applet requests), sent_to_team, url (check back with GET), followup_url, team_api {base_url, key_endpoint, docs}",
        errors = listOf("forbidden", "invalid_parameter", "rate_limited"))
    /** FBK-15: this agent's own key for the Bridgit team's backend, so it can file, follow and re-test issues without the phone. */
    val ISSUES_TEAM_KEY = EndpointSpec("GET", "/issues/team-key", "feedback", "Your own key for the Bridgit team's Issues Tracking backend: file issues, check their status and verify fixes there directly. Agent issue filing always goes directly to the servers",
        query = listOf(Param("rotate", "boolean", "true = make a new key; the old one stops working")),
        example = "GET /v1/issues/team-key", returns = "base_url, key (bgk_...), agent_id, role, docs, how_to",
        errors = listOf("capability_disabled", "forbidden", "rate_limited", "team_unavailable"))

    /** Deprecated pre-rename aliases of the ISSUES_ endpoints (API revision 134): same behaviour, old JSON keys (`reports`), no MCP tools. Removed with the old names. */
    private fun legacy(of: EndpointSpec, old: String, returns: String = of.returns) = of.copy(path = old,
        summary = "Deprecated: the old address of ${of.method} /v1${of.path}, same behaviour" + (if (returns != of.returns) " (list key reports)" else ""),
        example = of.example.replace("/v1/issues", "/v1/feedback"), returns = returns)
    val FEEDBACK_LIST = legacy(ISSUES_LIST, "/feedback", returns = ISSUES_LIST.returns.replaceFirst("issues[", "reports["))
    val FEEDBACK_GET = legacy(ISSUES_GET, "/feedback/{id}")
    val FEEDBACK_STATUS = legacy(ISSUES_STATUS, "/feedback/{id}/status")
    val FEEDBACK_FOLLOWUP = legacy(ISSUES_FOLLOWUP, "/feedback/{id}/followup")
    val FEEDBACK_POST = legacy(ISSUES_POST, "/feedback")
    val FEEDBACK_TEAM_KEY = legacy(ISSUES_TEAM_KEY, "/feedback/team-key")
    val FEEDBACK_LEGACY = listOf(FEEDBACK_LIST, FEEDBACK_TEAM_KEY, FEEDBACK_GET, FEEDBACK_STATUS, FEEDBACK_FOLLOWUP, FEEDBACK_POST)

    val INTERACTION_CREATE = EndpointSpec("POST", "/interactions", "interactions", "Send the owner a notice, a question (buttons, text reply, choice, links), an HTML card or a live text panel. Default to a styled HTML card (kind html, from GET /v1/samples, e.g. html-yes-no) for every test, yes/no, choice, notice or form; a native question or notice is the fallback. Normal priority and no speech. Sending isn't seeing or answering: wait with GET /v1/interactions/{id}?wait=55 (or one /v1/events loop for several)",
        idempotent = true, privateFields = setOf("text", "image", "html"),
        body = listOf(
            Param("kind", "string", "notice (one-way), question, html (a card you draw; answer comes from Bridgit.respond), pieces (a card the phone draws from pieces and sources; no HTML) or text (a live text panel you stream into with /v1/text/stream)",
                required = true, enum = listOf("notice", "question", "html", "pieces", "text")),
            Param("pieces", "array", "kind=pieces: 1-24 pieces (header, status, text, note, metrics, table, timeline, gallery, video, people, devices, draft, buttons), values as {\"\$source\":\"<source>.<path>\"}. See https://bridgit.cc/learn/builder/applet-packages.md"),
            Param("sources", "object", "kind=pieces: up to 8 named sources: {\"watch\":\"<your watch id>\",\"read\":\"tally\"|\"status\"} or {\"call\":{\"path\":\"/v1/...\",\"query\":{}},\"every_s\":60-3600} (GET only)"),
            Param("fresh_s", "integer", "kind=pieces: 30-86400; older data shows the card as stale"),
            Param("states", "object", "kind=pieces: your own words for the card states empty, loading, stale, paused, error"),
            Param("title", "string", "Up to 80 characters", required = true),
            Param("text", "string", "Up to 2,000 characters"),
            Param("image", "object", "{\"gallery_id\"} or {\"data_base64\"}, up to 2 MB"),
            Param("html", "string", "kind=html: the card's HTML, up to 256 KB. Runs offline: no network, only data:image/* images; call Bridgit.respond(value) once per question view (up to 16 KB) to answer; value is a JSON string or a plain object/array, which is serialized for you. window.Bridget is the same object (the only name on 1.6.269 and older). After persistence, bridgit:answered lets the card show its confirmation; the trusted Back to questions control reloads the question for resubmission. Bridgit.act({method, path, query?, body?}) runs one of your own endpoints on the phone right away, as you, with your grants and the owner's switches, e.g. await Bridgit.act({path:'/v1/flashlight', body:{on:true, auto_off_seconds:1}}) resolves to {ok, status, body}. A request is at most 16 KB (bigger: error request_too_big, so send a capture_id or gallery_id, never photo bytes) and a JSON reply over 64 KB comes back ok: false, error reply_too_big with the fix (ask for less). You get an action.executed event for each call, so it works while you're offline. A single card can't be swiped: show progress as text (\"Step 2 of 5\"), not swipe or segment bars. Complete pages: GET /v1/samples. A card app that decides or watches (Jev for text, Clef for photos and sound, POST /v1/watches with the free camera change check): ${Learn.DECISIONS_URL}"),
            Param("controls", "array", "Question: 1-6 of {type: button|text|choice|link, id, label, style?, placeholder?, options?, url?, package?}. Notice: links only"),
            Param("priority", "string", "normal or urgent", enum = listOf("normal", "urgent")),
            Param("speak", "boolean", "Read it aloud on the phone"),
            Param("no_takeover", "boolean", "No takeover: true delivers this interaction to the Inbox and notifications without opening it, even while Bridgit is in front. Default false: opens automatically, subject to the owner setting and Android Display over other apps permission"),
            Param("follow_up", "boolean", "Card flows: true when you will post another card once the owner answers or closes this one. The card screen says \"Waiting for the next card…\" and stays up to 30 s; your next card replaces it in place, and Bridgit steps back to what the owner was doing only after the last card of the flow. Post the next card as soon as the answer or dismissed event arrives; leave it out (or false) on the last card. If the owner answered from the notification without the card on screen, nothing is held and the next card opens as usual. Ignored with no_takeover"),
            Param("expires_in_seconds", "integer", "60 to 604800, default 86400. After it, waits return expired and you get interaction.expired, but a question stays on the owner's phone and they can still answer it late (interaction.answered with late=true)"),
            Param("stack", "string", "kind=html: 1-32 letters, digits, - or _. Your cards with the same stack id form one stack the owner swipes through (drag left = next, right = previous) with \"n of m\" and a peek of the next card; each card keeps its own id, wait and answer. Up to 10 cards per stack id; an open stack counts as one of your 5 open questions. Leave it out for a single card, which is never swipeable. Don't rely on horizontal scrolling inside a stacked card. Wait on GET /v1/events?types=interaction.answered rather than one wait per card"),
            Param("stack_index", "integer", "kind=html with stack: position 0-9 (default: after the cards already posted)"),
            Param("swipe_answer", "object", "kind=html with stack: {\"left\": label, \"right\": label} (1-20 characters each). While the card waits, a swipe answers it and moves on: answer.data = {\"swipe\": \"left\"|\"right\", \"label\": ...}. Bridgit shows both labels under the card"),
            Param("presentation", "string", "standard (default: inside Bridgit's bordered card box, with the toolbar and the status/Dismiss strip) or full_screen (kind html only: edge to edge, system bars, toolbar and strip hidden; a tap on the screen edge or Back shows Dismiss and Exit full screen for a few seconds). " +
                "A full-screen page without width=device-width in its viewport meta is scaled down to fit whole, letterboxed in black, never cropped. Compose full-screen pictures to display.viewport (or GET /v1/device/display full_screen_card; static specs are also at GET <team backend>/v1/names/{name}/specs) and show them with object-fit: contain. Other values: invalid_parameter", enum = listOf("standard", "full_screen")),
            Param("content_aspect", "string", "kind=html: the page's fixed shape as width:height, e.g. \"16:9\" or \"1280x720\". When it differs from the canvas by more than 10 %, the reply's display.aspect_mismatch says so (also checked from width/height in the page's viewport meta)"),
        ),
        example = "POST /v1/interactions {\"kind\":\"question\",\"title\":\"Pay this invoice?\",\"controls\":[{\"type\":\"button\",\"id\":\"yes\",\"label\":\"Pay\",\"style\":\"primary\"},{\"type\":\"button\",\"id\":\"no\",\"label\":\"Not now\"}]}",
        returns = "interaction_id, status, expires_at, display {shown_as: full_screen|bridgit_open|notification, full_screen (Bridgit came to the front over other apps; it says nothing about the card's layout, which is presentation), missing?: [open_on_arrival, display_over_other_apps], fix?, " +
            "presentation (standard|full_screen), viewport {width_px, height_px, width_dp, height_dp, density} (the exact canvas the card's page gets in that presentation), aspect_mismatch? {content, viewport, advice} (the page's declared shape differs from the canvas by more than 10 %)}. " +
            "When full_screen is false and missing is set, only a notification was shown; pass fix on to the owner. no_takeover=true returns notification without missing or fix. " +
            "ignored? {field: why}: request fields Bridgit did not use (display is reply-only; ask for an edge-to-edge card with presentation)",
        errors = listOf("capability_disabled", "notifications_required", "invalid_parameter", "payload_too_large", "too_many_open", "photo_not_found"))
    val TEXT_STREAM = EndpointSpec("POST", "/text/stream", "interactions", "Add text to your live text panel (an interaction of kind text); it scrolls on the phone as it arrives",
        privateFields = setOf("append"),
        body = listOf(Param("interaction_id", "string", "The panel's interaction_id", required = true),
            Param("append", "string", "Text to add, up to 16 KB per call (the panel keeps the last 200 KB)"),
            Param("done", "boolean", "true: the stream is finished; the panel stays until the owner closes it")),
        example = "POST /v1/text/stream {\"interaction_id\":\"42\",\"append\":\"Claude: running the tests now...\\n\"}",
        returns = "interaction_id, length, status", errors = listOf("not_found", "invalid_parameter", "conflict", "rate_limited"))
    val CALLBACK_TEST = EndpointSpec("POST", "/callback/test", "interactions", "Send a signed test event to the callback URL the owner set for you",
        example = "POST /v1/callback/test", returns = "delivered, http_status, host", errors = listOf("not_found", "callback_failed"))
    val MCP = EndpointSpec("POST", "/mcp", "status", "Model Context Protocol (Streamable HTTP, JSON replies): every endpoint you can use is an MCP tool",
        maxBody = 2 * 1024 * 1024, privateFields = setOf("params"),
        body = listOf(Param("jsonrpc", "string", "\"2.0\"", required = true), Param("method", "string", "initialize, tools/list, tools/call, ping", required = true),
            Param("params", "object", "For tools/call: {name, arguments}"), Param("id", "integer", "Request id")),
        example = "POST /v1/mcp {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
        returns = "JSON-RPC result", errors = listOf("invalid_parameter"))
    val INTERACTION_LIST = EndpointSpec("GET", "/interactions", "interactions", "Your interactions, newest first",
        query = listOf(Param("status", "string", "pending, seen, answered, dismissed, expired or cancelled"), Param("limit", "integer", "1-100, default 50")),
        example = "GET /v1/interactions?status=answered", returns = "interactions[]")
    val INTERACTION_GET = EndpointSpec("GET", "/interactions/{id}", "interactions", "One of your interactions; with wait, holds until the owner answers or closes it",
        query = listOf(Param("wait", "integer", "Hold up to this many seconds (max 55) for the answer")),
        example = "GET /v1/interactions/12?wait=55", returns = "kind, status, answer{button, values} (html cards: answer{button: null, values: {}, data: what the page sent}), answered_at, closed_at",
        errors = listOf("not_found", "too_many_waits"))
    val INTERACTION_CANCEL = EndpointSpec("DELETE", "/interactions/{id}", "interactions", "Cancel one of your interactions",
        example = "DELETE /v1/interactions/12", returns = "the interaction", errors = listOf("not_found"))
    val INTERACTION_RENOTIFY = EndpointSpec("POST", "/interactions/{id}/renotify", "interactions",
        "Notify the owner again about one of your pending or seen cards. Opening a card clears its alert; only this explicit request restores it. Preserves seen status, answers and expiry; does not open the screen or speak. Closed or expired cards are refused",
        idempotent = true,
        example = "POST /v1/interactions/12/renotify {}", returns = "the interaction, notification_requested",
        errors = listOf("not_found", "conflict", "capability_disabled", "notifications_required"))
    val EVENTS = EndpointSpec("GET", "/events", "interactions", "Your events, oldest first: answers, closes, expiries, switch changes. interaction.renotified records an explicit card reminder. Repeated phone answers emit interaction.answered with resubmission=true and previous_answer; agents decide whether to accept the change. An answer given after the question expired comes as interaction.answered with late=true. action.executed: one of your HTML cards ran Bridgit.act (interaction_id, method, path, status, ok, error)",
        query = listOf(Param("since", "string", "next_cursor from the last call; omit for the newest 50 (still oldest first)"),
            Param("wait", "integer", "With since: hold up to 55 s for a new event"), Param("types", "string", "Comma list, e.g. interaction.answered"),
            Param("limit", "integer", "At most this many events, 1 to 100: the newest N without since (default 50), the first N after the cursor with it (default 100)")),
        example = "GET /v1/events?since=0&wait=55", returns = "events[id, type, at, data], next_cursor",
        errors = listOf("invalid_parameter", "too_many_waits"))

    val LOCATION = EndpointSpec("GET", "/location", "location", "Where the phone is now, with altitude, speed, heading and the compass",
        capture = true,
        query = listOf(Param("fresh", "boolean", "true (default): a new fix, up to 15 s; false: the last known one at once"),
            Param("accuracy", "string", "high (GPS, default) or balanced", enum = listOf("high", "balanced")),
            Param("compass", "boolean", "Include the compass (default true)")),
        example = "GET /v1/location", returns = "latitude, longitude, altitude_m, accuracy_m, vertical_accuracy_m, speed_mps, bearing_deg, fix_time, age_s, precision, compass{azimuth_deg, direction, pitch_deg, roll_deg}",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "capture_timeout", "service_start_blocked"))
    val SPEAKER_PLAY = EndpointSpec("POST", "/speaker/play", "speaker", "Play audio on the phone's speaker: send audio/* bytes as the body, or {\"url\"} (public https)",
        idempotent = true, maxBody = 25 * 1024 * 1024, privateFields = setOf("text"),
        query = listOf(Param("replace", "string", "after_current (default: replace what's queued from you), now, or queue", enum = listOf("after_current", "now", "queue")),
            Param("tour_id", "string", "Narration for your running City tour"), Param("urgent", "boolean", "Jump the queue (answers to questions)"),
            Param("text", "string", "Caption shown on the phone while it plays")),
        body = listOf(Param("loop","boolean","Repeat until stopped or replaced"),Param("url", "string", "https:// audio file (MP3, M4A, OGG, WAV, FLAC), up to 25 MB")),
        example = "POST /v1/speaker/play (Content-Type: audio/mpeg, body = the MP3)", returns = "play_id, position, status (playing, queued or silenced)",
        errors = listOf("capability_disabled", "notifications_required", "invalid_parameter", "payload_too_large", "rate_limited", "not_found"))
    val SPEAKER_SAY = EndpointSpec("POST", "/speaker/say", "speaker", "Read text aloud on the phone (its own text-to-speech)",
        idempotent = true, privateFields = setOf("text"),
        body = listOf(Param("text", "string", "Up to 4,000 characters", required = true), Param("language", "string", "e.g. en-GB, de-DE"),
            Param("rate", "number", "0.5-2, default 1"), Param("pitch", "number", "0.5-2, default 1"),
            Param("replace", "string", "after_current (default), now, or queue"), Param("tour_id", "string", "Narration for your City tour"),
            Param("urgent", "boolean", "Jump the queue")),
        example = "POST /v1/speaker/say {\"text\":\"On your left is the old town hall, built in 1360.\",\"tour_id\":\"t123\"}",
        returns = "play_id, position, status (playing, queued, or silenced: the phone is on silent, Do Not Disturb or in a call, so it won't be heard)",
        errors = listOf("capability_disabled", "invalid_parameter", "rate_limited", "not_found"))
    val SPEECH_SPEAK = SPEAKER_SAY.copy(path = "/speech/speak", summary = "Speak one turn through the model registry and guarded speaker queue",
        body = listOf(Param("text", "string", "1-600 characters", required = true), Param("language", "string", "BCP 47 language"), Param("allow_cloud", "boolean", "Explicit cloud processing permission; default false"), Param("presentation_token","string","Optional story presentation token; invalidated tokens refuse synthesis/enqueue"), Param("task_key","string","Optional registered applet narration task"),Param("wait_for_reveal","boolean","Prepare speech now and play after /story/reveal"),Param("expected_model","string","Optional registry model id; refuses before synthesis if selection changed"), Param("replace", "string", "after_current, now or queue")),
        example = "POST /v1/speech/speak {\"text\":\"The timer is ready.\"}", returns = "play_id, position, status, speech{model, processing, cost_usd, elapsed_ms}")
    val SPEAKER_STATUS = EndpointSpec("GET", "/speaker", "speaker", "What's playing, and your queued sounds",
        example = "GET /v1/speaker", returns = "playing{play_id, agent, text} (yours only, else null), busy (someone else's sound is playing), paused, queued[], quiet")
    val SPEAKER_STOP = EndpointSpec("DELETE", "/speaker", "speaker", "Stop your audio and clear your queue",
        example = "DELETE /v1/speaker", returns = "stopped")
    val TOUR_GET = EndpointSpec("GET", "/tour", "tour", "Your running City tour, if the owner started one with you",
        example = "GET /v1/tour", returns = "tour{tour_id, kinds, interval_s, started_at, ticks, last_tick_at, distance_m, photos_sent, brief} or null")
    val TOUR_PHOTO = EndpointSpec("GET", "/tour/photo/{n}", "tour", "A photo the owner took with Take photo on your tour (n from tour.photo), sized for you",
        media = true, query = sizing,
        example = "GET /v1/tour/photo/1?size=agent", returns = "taken_at, width_px, height_px, format, size_bytes, data_base64",
        errors = listOf("not_found", "photo_not_found", "payload_too_large"))
    val TOUR_END = EndpointSpec("DELETE", "/tour", "tour", "End your City tour",
        query = listOf(Param("tour_id", "string", "Only end this tour (it must be yours and still running)")),
        example = "DELETE /v1/tour", returns = "ended", errors = listOf("not_found"))

    val SCREEN_CAPTURE = EndpointSpec("POST", "/screen/capture", "screen", "What's on the owner's screen right now (no prompt once they've turned it on)",
        capture = true, idempotent = true, media = true, query = sizing,
        body = listOf(Param("mode", "string", "private (default): taken in memory, never put in the gallery", enum = listOf("private")), STORE) + peopleView,
        example = "POST /v1/screen/capture?size=agent", returns = "captured_at, width_px, height_px, format, size_bytes, mode, view, data_base64 (or raw=1 bytes); capture {capture_id, stored, url, memory_until, file} when kept (X-Bridgit-Capture-Id with raw=1); with view people, people_view {crop {x, y, width, height}, source {width_px, height_px}, width_px, height_px, aspect_ratio, aspect_ratio_value, aspect_requested, aspect_met, people_count, people [{x, y, width, height, found_by}], detector} (X-Bridgit-Crop, X-Bridgit-Source-Size, X-Bridgit-Aspect-Ratio, X-Bridgit-People-Count with raw=1)",
        errors = listOf("capability_disabled", "access_needed", "notifications_required", "device_locked", "rate_limited", "sensor_busy",
            "not_supported", "capture_timeout", "service_start_blocked", "payload_too_large", "no_person_found"))

    val LISTEN = EndpointSpec("GET", "/listen", "wakewords", "Wake words: whether the phone is listening, and the wake words the owner gave you",
        example = "GET /v1/listen", returns = "enabled, state (off|listening|hearing|paused), reason, muted_until, model (ready|not_downloaded|downloading|failed), model_name, max_seconds, wake_words[{phrase, send_clip}]. " +
            "Each hit arrives as a listen.heard event: wake_word, transcript, heard_at, ended_by (pause|max_seconds|no_speech|interrupted), duration_seconds, recogniser, clip_id, capture {capture_id, stored, url, memory_until, file}")
    val LISTEN_CLIP = EndpointSpec("GET", "/listen/clip/{id}", "wakewords", "The audio after a wake word, when the owner set that wake word to send it (kept in memory 10 minutes unless you chose longer with POST /v1/captures/storage; also GET /v1/captures/{id})",
        media = true, example = "GET /v1/listen/clip/c0123abcd", returns = "heard_at, duration_seconds, format (wav), sample_rate_hz, size_bytes, data_base64 (or raw=1 bytes)",
        errors = listOf("not_found"))

    val SOUNDS = EndpointSpec("GET", "/sounds", "sounds", "Listen for any sounds: whether the phone tells you when the room gets loud (8.23)",
        example = "GET /v1/sounds", returns = "enabled, for_you, state (off|listening|paused), reason, sensitivity (quiet|normal|loud), send_clip, cooldown_seconds, max_seconds. " +
            "Each sound arrives as a sound.heard event: heard_at, duration_seconds, peak_dbfs, mean_dbfs, sensitivity, clip_id, capture {capture_id, stored, url, memory_until, file}")
    val SOUNDS_CLIP = EndpointSpec("GET", "/sounds/clip/{id}", "sounds", "The audio of a sound (up to 10 s, from just before it started), when the owner lets sounds send audio (kept in memory 10 minutes unless you chose longer with POST /v1/captures/storage; also GET /v1/captures/{id})",
        media = true, example = "GET /v1/sounds/clip/c0123abcd", returns = "heard_at, duration_seconds, format (wav), sample_rate_hz, size_bytes, data_base64 (or raw=1 bytes)",
        errors = listOf("not_found"))


    val CAPTURES_STORAGE_GET = EndpointSpec("GET", "/captures/storage", "captures", "Where your captures go: your choice and what the owner allows",
        example = "GET /v1/captures/storage",
        returns = "yours {store, kinds, upload_url, upload_headers (names only), keep_minutes}, effective {<kind>: {store, keep_minutes}}, owner {agent_files, agent_upload, max_keep_minutes, any_agent_can_fetch, owner_files, owner_upload}, destinations, kinds")
    val CAPTURES_STORAGE_SET = EndpointSpec("POST", "/captures/storage", "captures", "Choose where your captures go, in any combination: memory, phone files, upload; and how long memory keeps them (within the owner's limits)",
        idempotent = true, privateFields = setOf("upload_headers"),
        body = listOf(
            Param("store", "array", "Any of memory, files, upload; [] = only return captures; null = back to the defaults (clips in memory, the rest only returned)", required = true),
            Param("kinds", "array", "Only these kinds: camera, screen, mic, wakewords, sounds; null = all"),
            Param("upload_url", "string", "Where uploads go (POST, the capture as the body, X-Bridgit-Capture-Id/Kind/Agent/Captured-At headers). Tailnet always; public HTTPS when the owner allows internet callbacks. Needed with upload"),
            Param("upload_headers", "object", "Up to 8 extra headers for the upload, e.g. {\"Authorization\": \"Bearer ...\"}; never shown back"),
            Param("keep_minutes", "integer", "How long memory keeps them, 1 to the owner's limit (default 10, owner limit 1440 unless changed)")),
        example = "POST /v1/captures/storage {\"store\":[\"memory\",\"files\",\"upload\"],\"upload_url\":\"https://example.com/in\",\"keep_minutes\":120}",
        returns = "as GET /v1/captures/storage", errors = listOf("invalid_parameter", "forbidden"))
    val CAPTURES_LIST = EndpointSpec("GET", "/captures", "captures", "Your kept captures (photos, screenshots, recordings, clips), newest first: where each is and until when",
        query = listOf(Param("kind", "string", "Only this kind", enum = cc.bridgit.app.capture.CaptureRules.KINDS), Param("limit", "integer", "1-200, default 50")),
        example = "GET /v1/captures?kind=camera", returns = "captures[id, kind, agent_id, mime, size_bytes, captured_at, in_memory, memory_until, file, upload {agent|owner: pending|sent|failed: why}, url, meta]",
        errors = listOf("invalid_parameter"))
    val CAPTURES_GET = EndpointSpec("GET", "/captures/{id}", "captures", "One kept capture: from memory, or from its phone file after memory let it go; optional max_edge_px returns a bounded image preview",
        media = true, query = listOf(Param("max_edge_px","integer","Optional image preview edge, 80–1600 pixels"),Param("raw", "integer", "1 = the bytes, details in X-Bridgit-* headers")),
        example = "GET /v1/captures/c0123abcd?raw=1", returns = "the capture's details (as in the list) and data_base64, or raw=1 bytes",
        errors = listOf("not_found", "payload_too_large"))
    val CAPTURES_DELETE = EndpointSpec("DELETE", "/captures/{id}", "captures", "Forget a capture: drops the memory copy and deletes its phone file (uploads can't be recalled)",
        example = "DELETE /v1/captures/c0123abcd", returns = "deleted", errors = listOf("not_found"))

    val SETTINGS_GET = EndpointSpec("GET", "/settings", "settings", "Every Bridgit setting and the phone settings it manages: value, type, range, and whether you may change it",
        query = listOf(Param("section", "string", "Only this section", enum = cc.bridgit.app.core.SettingsCatalog.SECTIONS),
            Param("key", "string", "Only this setting, e.g. appearance.theme")),
        example = "GET /v1/settings?section=phone",
        returns = "can_write, write_blocked_reason, write_blocked_why, sections, settings[key, section, type, value, description, enum, min, max, writable, locked_reason, why]",
        errors = listOf("invalid_parameter", "not_found"))
    val SETTINGS_SET = EndpointSpec("POST", "/settings", "settings", "Change settings (agents with Agent Super Powers, once the owner turns on App settings); every change is checked first, so one bad change changes nothing",
        idempotent = true,
        body = listOf(Param("changes", "object", "{\"<key>\": <value>, ...}, up to 50; keys and allowed values from GET /v1/settings", required = true)),
        example = "POST /v1/settings {\"changes\":{\"appearance.theme\":\"dark\",\"phone.volume_media\":40,\"features.camera\":true}}",
        returns = "changed[key, from, to], unchanged, settings (as in GET)",
        errors = listOf("capability_disabled", "forbidden", "setting_locked", "access_needed", "invalid_parameter"))

    val all = cc.bridgit.app.ssh.SshModule.ALL + cc.bridgit.app.lancamera.CameraAudioModule.ALL + cc.bridgit.app.ownerapi.OwnerFeatureEndpoints.ALL + cc.bridgit.app.appdevices.AppDeviceModule.ALL + cc.bridgit.app.lan.LanModule.ALL + cc.bridgit.app.lancamera.LanCameraModule.ALL + listOf(SPEECH_HEAR, SPEECH_SPEAK, SPEECH_MODELS, SERVICES, HEALTH, DEVICE_DISPLAY, DEVICE_RESOURCES, DEVICES, CAPABILITIES, LIMITS, DISCOVER, DISCOVERY_REPORT, GUIDE, START, WELCOME, DEMO, SAMPLES, OPENAPI, REGISTRY, CHANGES, HELPER, HELPER_LEGACY, PAIR, SNAPSHOT, CAMERA_ARM, POSE_START, POSE_READ, POSE_FRAME, POSE_STOP, MIC, GALLERY_RECENT, GALLERY_PHOTO,
        IMAGE_LAST, SCREEN_CAPTURE, LOG, LOG_ENTRY, LOG_STATS, LOG_LATENCY, LOG_EXPORT, DEVICE_LOG, DEVICE_EXITS, LOG_NOTE, AUDIT, ISSUES_LIST, ISSUES_TEAM_KEY, ISSUES_GET, ISSUES_STATUS, ISSUES_FOLLOWUP, ISSUES_POST, FEEDBACK_LIST, FEEDBACK_TEAM_KEY, FEEDBACK_GET, FEEDBACK_STATUS, FEEDBACK_FOLLOWUP, FEEDBACK_POST,
        INTERACTION_CREATE, INTERACTION_LIST, INTERACTION_GET, INTERACTION_CANCEL, INTERACTION_RENOTIFY, EVENTS, TEXT_STREAM, CALLBACK_TEST, MCP,
        LOCATION, SPEAKER_PLAY, SPEAKER_SAY, SPEAKER_STATUS, SPEAKER_STOP, TOUR_GET, TOUR_PHOTO, TOUR_END, ME, OWNER_EMAIL, OWNER_EMAIL_SET, LISTEN, LISTEN_CLIP, SOUNDS, SOUNDS_CLIP, CAPTURES_STORAGE_GET, CAPTURES_STORAGE_SET, CAPTURES_LIST, CAPTURES_GET, CAPTURES_DELETE, SETTINGS_GET, SETTINGS_SET) + cc.bridgit.app.capability.SettingsConfigurationApi.ALL + cc.bridgit.app.planned.PlannedEndpoints.all + cc.bridgit.app.schedule.ScheduleEndpoints.all + cc.bridgit.app.weather.WeatherEndpoints.WEATHER + cc.bridgit.app.adb.AdbModule.ENDPOINTS + listOf(cc.bridgit.app.judgment.JudgmentModule.JUDGMENT, cc.bridgit.app.judgment.JudgmentModule.IMAGE_GENERATE, cc.bridgit.app.judgment.JudgmentModule.IMAGE_USAGE) + cc.bridgit.app.judgment.ContextServices.ALL + cc.bridgit.app.watch.WatchModule.ALL + cc.bridgit.app.outbound.OutboundModule.ALL + cc.bridgit.app.portal.PortalEndpoints.ALL + cc.bridgit.app.satellite.SatelliteEndpoints.ALL + cc.bridgit.app.applets.AppletEndpoints.ALL + cc.bridgit.app.applets.BudgetEndpoints.ALL + cc.bridgit.app.applets.AppletPortalEndpoints.ALL + cc.bridgit.app.applets.AppletData.ALL + cc.bridgit.app.platform.ConnectionsApi.ALL + cc.bridgit.app.context.ContextApi.ALL + cc.bridgit.app.platform.ModesApi.ALL + cc.bridgit.app.vision.FindCropModule.ALL
}
