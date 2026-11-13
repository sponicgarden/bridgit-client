package cc.bridgit.app.planned

import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.Param

/** Shared declarations used by routes, OpenAPI, MCP, and the in-app API reference. */
object PlannedEndpoints {
    private fun get(path: String, cap: String, summary: String, query: List<Param> = emptyList(), media: Boolean = false) = EndpointSpec("GET", path, cap, summary,
        example = "GET /v1$path", returns = summary, query = query, media = media)
    private fun post(path: String, cap: String, summary: String, body: List<Param> = emptyList(), media: Boolean = false, capture: Boolean = false) = EndpointSpec("POST", path, cap, summary,
        example = "POST /v1$path", returns = summary, body = body, media = media, capture = capture, idempotent = true,
        privateFields = setOf("text", "data_base64"))
    private fun delete(path: String, cap: String, summary: String) = EndpointSpec("DELETE", path, cap, summary, example = "DELETE /v1$path", returns = summary)
    /** A0270: list rows still arriving too, as complete:false. */
    val INCLUDE_PENDING = Param("include_pending", "boolean", "true also lists items still arriving (recording, downloading, syncing) with complete:false; default false")
    private val duration = Param("duration_minutes", "integer", "Optional timed restoration, 1–1440 minutes; null leaves the change in place")
    val STATUS = get("/phone/status", "phone", "Battery, network, screen and sound status, with as_of")
    val BATTERY = get("/phone/battery", "phone", "Full battery report: percent, status, plug type, health, temperature, voltage, cycles, current, time to full, estimated_runtime (time to empty at recent usage while unplugged; status, remaining/lower/upper seconds, observation window and drain rate; learning until 10 minutes and 2% drain), battery saver, which power settings agents may change, and the battery.* events")
    val CONTEXT = get("/phone/context", "phone", "Screen and lock context; likely=in_use or unknown, with the rule")
    val DND = post("/phone/dnd", "controls", "Set Do Not Disturb, with optional restoration", listOf(Param("mode", "string", "off, priority, alarms or none (none mutes alarms)", true), duration))
    val SOUND = post("/phone/sound", "controls", "Change ringer or volume", listOf(Param("ringer_mode", "string", "normal, vibrate or silent"), Param("volume", "object", "media/ring/alarm percentages, 0–100"), duration))
    val BRIGHTNESS = post("/phone/brightness", "controls", "Set brightness or automatic brightness", listOf(Param("level", "number", "0–1"), Param("auto", "boolean", "true enables automatic brightness"), duration))
    val POWER = post("/phone/power", "controls", "Turn battery saver on or off (needs a one-time USB grant, see /phone/battery controls) or set the screen timeout, with optional restoration",
        listOf(Param("battery_saver", "boolean", "true turns battery saver on; Android keeps it off while charging"), Param("screen_timeout_seconds", "integer", "15–1800"), duration))
    val UNDO = post("/phone/undo", "controls", "Restore pending timed phone changes, preserving subsequent manual changes")
    val TORCH_GET = get("/flashlight", "flashlight", "Whether the torch is on")
    val TORCH = post("/flashlight", "flashlight", "Set torch with mandatory auto-off", listOf(Param("on", "boolean", "true or false", true), Param("auto_off_seconds", "integer", "1–600, default 60")), capture = true)
    val RING = post("/phone/ring", "ring", "Ring and vibrate within Android sound restrictions", listOf(Param("duration_seconds", "integer", "1–60, default 15")), capture = true)
    val RING_STOP = delete("/phone/ring", "ring", "Stop ringing and vibration")
    val SENSORS = get("/sensors", "sensors", "Supported fresh sensor values or explicit unavailable status", listOf(Param("which", "string", "Comma separated: light,pressure,proximity,steps,orientation,motion")))
    val NOTIFICATIONS = get("/notifications", "notifications", "Up to 100 active notification texts since service connected; RAM only")
    val USAGE = get("/usage", "usage", "App foreground time from Android usage buckets (maximum seven days)", listOf(Param("since", "string", "ISO-8601 UTC"), Param("until", "string", "ISO-8601 UTC")))
    val HEALTH_DATA = get("/health/records", "health_connect", "Read-only steps, sleep or heart-rate source records (maximum 100)", listOf(Param("type", "string", "steps, sleep, heart_rate"), Param("since", "string", "ISO-8601 UTC"), Param("until", "string", "ISO-8601 UTC")))
    val CLIPBOARD = post("/clipboard", "clipboard", "Queue a five-minute owner approval; never copies until owner taps Copy", listOf(Param("text", "string", "1–10000 characters", true)))
    val CLIPBOARD_GET = get("/clipboard/{id}", "clipboard", "Your clipboard request: pending, copied, denied or expired")
    val VIDEO = post("/camera/video", "video", "Record an MP4, returned raw (HTTP: 40 MB maximum; MCP: 6 MB, may stop early)", listOf(Param("camera", "string", "rear or front"), Param("duration_seconds", "integer", "1–30, default 10"), Param("audio", "boolean", "false by default; true also requires microphone grant"), Param("resolution", "string", "720p or 1080p"), Param("viewfinder","boolean","Opt into visible capture and review; always enforced for card/portal/MCP-App actions"), Param("instructions","string","Short framing instructions above the live preview, up to 240 characters"), Param("countdown_seconds","integer","0–10, starts after the owner presses the visible shutter"), Param("extract_frames","integer","0 (raw video, default) or 1–8: record then extract spaced JPEG frames into caller-owned capture memory and return JSON frames[{capture_id,...}]; person-started captures retain video_capture_id for optional replay/delete; automatic captures delete the video")), media = true, capture = true)
    val VIDEO_REVIEW = post("/camera/video/review","video","Review your kept video without recording anything; Play/Pause, Done and Delete",listOf(Param("capture_id","string","Caller-accessible video/mp4 capture id",true)))
    val VIDEO_STOP = EndpointSpec("DELETE","/camera/video","video","Stop this caller's active video recording",example="DELETE /v1/camera/video",returns="stopped")
    val VIDEOS = get("/gallery/videos/recent", "gallery_video", "Accessible videos and metadata", listOf(Param("limit", "integer", "1–100, default 20"), INCLUDE_PENDING))
        .copy(returns = "access_mode, items[id, name, duration_ms, size_bytes, mime, added_at, complete, source{kind, app, folder} (source needs the gallery grant)]")
    val GALLERY_VIDEO = get("/gallery/video/{id}", "gallery_video", "Accessible video raw, or a JPEG frame", listOf(Param("frame_at_s", "number", "Optional frame position in seconds; a video still arriving answers 409 media_incomplete")), media = true)
        .copy(errors = listOf("capability_disabled", "permission_denied", "invalid_parameter", "not_found", "payload_too_large", "media_incomplete"))
    val OCR = post("/ocr", "ocr", "Recognize Latin-script text locally; no image upload", listOf(Param("gallery_id", "integer", "Accessible gallery image (also requires gallery grant); a photo still arriving answers 409 media_incomplete"), Param("data_base64", "string", "Image bytes instead of gallery_id")))
        .copy(errors = listOf("capability_disabled", "permission_denied", "invalid_parameter", "photo_not_found", "payload_too_large", "media_incomplete"))
    val transfers = listOf("files", "shares", "downloads").flatMap { cap -> buildList {
        add(get("/$cap", cap, "Your $cap transfers, retained 30 days on the phone"))
        add(get("/$cap/{id}", cap, "Read your selected transfer bytes", media = true))
        add(delete("/$cap/{id}", cap, "Delete your transfer"))
        if (cap == "files") add(post("/files", cap, "Receive a file on the phone (25 MB maximum)", listOf(Param("name", "string", "Filename, no path", true), Param("mime", "string", "MIME type"), Param("data_base64", "string", "File bytes; or send raw body with ?name=..."))).copy(maxBody = 25 * 1024 * 1024, query = listOf(Param("filename", "string", "Filename for raw-body uploads (or name= for HTTP compatibility)"))))
    } }
    val SCREEN_GRANT = post("/screen/grant", "screen_share", "Request owner-approved MediaProjection screen access", listOf(Param("duration_seconds", "integer", "0=once, 3600=one hour, 57600=sixteen hours")))
    val SCREEN_GRANTS = get("/screen/grants", "screen_share", "Your screen requests and active grant")
    val SHARE_CAPTURE = post("/screen/share/capture", "screen_share", "Capture a JPEG using your active owner-approved grant", media = true, capture = true)
    val SHARE_STOP = delete("/screen/grant", "screen_share", "End your screen-sharing grant")
    val all = listOf(SCREEN_GRANT, SCREEN_GRANTS, SHARE_CAPTURE, SHARE_STOP, STATUS, BATTERY, CONTEXT, DND, SOUND, BRIGHTNESS, POWER, UNDO, TORCH_GET, TORCH, RING, RING_STOP, SENSORS, NOTIFICATIONS,
        USAGE, HEALTH_DATA, CLIPBOARD, CLIPBOARD_GET, VIDEO, VIDEO_STOP, VIDEO_REVIEW, VIDEOS, GALLERY_VIDEO, OCR) + transfers
}
