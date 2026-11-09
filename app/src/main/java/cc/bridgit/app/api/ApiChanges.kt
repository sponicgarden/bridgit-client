package cc.bridgit.app.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * API revisions (API-14, owner request 27 Oct 2026). The path stays /v1; each release that
 * adds to the API bumps [CURRENT] and adds an entry here. Every reply carries
 * `X-Bridgit-API-Revision`; agents that haven't caught up also get `X-Bridgit-Whats-New` (both also
 * under their pre-rename `X-Bridget-` names, see [BrandHeaders]). Old revisions keep the names they shipped with.
 * Pure Kotlin, unit-tested against [Endpoints].
 */
object ApiChanges {
    data class Item(val what: String, val endpoint: String? = null)
    data class Revision(val revision: Int, val appVersion: String, val date: String, val items: List<Item>)

    val revisions = listOf(
        Revision(1, "1.3.0", "2026-10-27", listOf(
            Item("Health, capabilities (enabled / ready / blocked_reason), this guide and OpenAPI", "/v1/health"),
            Item("Camera photo with size presets and raw=1", "/v1/camera/snapshot"),
            Item("Microphone clip, 1-120 s", "/v1/mic/record"),
            Item("Gallery list", "/v1/gallery/recent"),
            Item("One gallery photo, any size, raw=1", "/v1/gallery/photo/{id}"),
            Item("Newest screenshot or photo; since + wait for the next one", "/v1/image/last"),
            Item("Log: query, follow live, stats, export, notes; /v1/audit alias", "/v1/log"),
            Item("Idempotency-Key on POSTs; pending (202) schema", null),
            Item("The bridget helper download", "/v1/tools/bridget"),
        )),
        Revision(2, "1.4.0", "2026-10-27", listOf(
            Item("File a bug report or feature request (any agent)", "/v1/feedback"),
            Item("Developer agents: list and read reports, move their status", "/v1/feedback/{id}/status"),
        )),
        Revision(3, "1.4.4", "2026-10-27", listOf(
            Item("Camera zoom: \"zoom\": 0.5 (ultra-wide) to the phone's maximum; range in /v1/capabilities", "/v1/camera/snapshot"),
            Item("API revisions: X-Bridget-API-Revision on every reply, X-Bridget-Whats-New until you've caught up, and this list", "/v1/changes"),
        )),
        Revision(4, "1.5.0", "2026-10-27", listOf(
            Item("Interactions: send the owner a notice or a question with buttons, a text reply, a choice or links; read aloud if you ask", "/v1/interactions"),
            Item("Wait for their answer (wait=55), list or cancel your interactions", "/v1/interactions/{id}"),
            Item("Events: answers, closes and expiries for you, oldest first, with long-poll", "/v1/events"),
        )),
        Revision(5, "1.5.0", "2026-10-27", listOf(
            Item("Location with altitude, speed, heading and the compass", "/v1/location"),
            Item("Play audio or read text aloud on the phone; newer narration replaces what's queued", "/v1/speaker/play"),
            Item("Speak text with the phone's own voice", "/v1/speaker/say"),
            Item("What's playing; stop your audio", "/v1/speaker"),
            Item("City tour: your running tour (tour.started / tour.tick / tour.question / tour.ended events)", "/v1/tour"),
        )),
        Revision(6, "1.5.2", "2026-10-27", listOf(
            Item("Report your model and harness; the owner sees it on your agent card", "/v1/me"),
            Item("City tour brief in tour.started and GET /v1/tour: directions relative to them, nearest interesting places when nothing is left here", "/v1/tour"),
            Item("tour.photo: the owner took a photo for you on the tour; fetch it", "/v1/tour/photo/{n}"),
        )),
        Revision(7, "1.5.2", "2026-10-27", listOf(
            Item("HTML cards: kind=html draws your own card (offline, Bridget.respond(json) answers)", "/v1/interactions"),
            Item("Live text panel: kind=text, then stream text into it", "/v1/text/stream"),
            Item("Callbacks: the owner can set a callback URL for you; events are POSTed signed with HMAC-SHA256; send a test", "/v1/callback/test"),
            Item("MCP: use Bridgit as an MCP server (Streamable HTTP) with your key", "/v1/mcp"),
            Item("Speaker status: playing shows only your own sound; busy and paused added", "/v1/speaker"),
            Item("End tour: optional tour_id so you only end the tour you mean", "/v1/tour"),
            Item("Screenshot on request: what's on the owner's screen now, nothing saved (once they turn on Screenshots)", "/v1/screen/capture"),
            Item("Gallery with \"Select photos\" access: photo_not_found and /v1/gallery/recent say how the owner allows all", "/v1/gallery/recent"),
        )),
        Revision(8, "1.6.0", "2026-10-29", listOf(
            Item("Device context, sensors, flashlight, timed sound/display controls and find-phone ring", "/v1/phone/status"),
            Item("Private file transfers, owner-selected Downloads, Android sharing and clipboard approval", "/v1/files"),
            Item("On-device OCR and transcription; short video and accessible gallery videos", "/v1/ocr"),
            Item("Read-only Health Connect, app usage and opt-in notification text", "/v1/health/records"),
            Item("Owner-approved, per-agent MediaProjection screen-sharing grants", "/v1/screen/grant"),
            Item("Sensors", "/v1/sensors"), Item("Flashlight", "/v1/flashlight"),
            Item("Phone context", "/v1/phone/context"), Item("DND", "/v1/phone/dnd"), Item("Sound", "/v1/phone/sound"),
            Item("Brightness", "/v1/phone/brightness"), Item("Undo timed changes", "/v1/phone/undo"), Item("Ring", "/v1/phone/ring"),
            Item("Shares", "/v1/shares"), Item("Downloads", "/v1/downloads"), Item("Clipboard", "/v1/clipboard"),
            Item("Video recording", "/v1/camera/video"), Item("Gallery videos", "/v1/gallery/videos/recent"), Item("Gallery video bytes", "/v1/gallery/video/{id}"),
            Item("Notification reads", "/v1/notifications"), Item("Usage summaries", "/v1/usage"), Item("Shared-screen capture", "/v1/screen/share/capture"),
            Item("Per-agent capability grants, enforced for HTTP and MCP", "/v1/capabilities"),
        )),
        Revision(9, "1.6.0", "2026-10-30", listOf(
            Item("A demo script: offer the owner a hands-on tour of what you can do, tailored by a few questions", "/v1/demo"),
        )),
        Revision(10, "1.6.0", "2026-10-30", listOf(
            Item("Wake words: the owner maps words like \"hey bridget\" to you; what they say next arrives as a listen.heard event. Your wake words and whether the phone is listening", "/v1/listen"),
            Item("The audio after a wake word, when the owner chose to send it", "/v1/listen/clip/{id}"),
        )),
        Revision(11, "1.6.0", "2026-10-30", listOf(
            Item("Listen for any sounds: the owner can have the phone tell you when the room gets loud (sound.heard events), and how it's set", "/v1/sounds"),
            Item("The audio of a sound, when the owner lets sounds send audio", "/v1/sounds/clip/{id}"),
        )),
        Revision(12, "1.6.0", "2026-10-30", listOf(
            Item("Late answers: the owner can still answer a question after it expires; you get interaction.answered with late=true after interaction.expired", "/v1/events"),
        )),
        Revision(13, "1.6.0", "2026-10-31", listOf(
            Item("A five-minute first-run demo to offer right after pairing: ${Demos.SKILL_URL}", null),
            Item("speaker/say and speaker/play reply status \"silenced\" when the phone is on silent, Do Not Disturb or in a call", "/v1/speaker/say"),
            Item("Flashlight on a device without one: capabilities shows blocked_reason no_hardware and the call fails with not_supported", "/v1/flashlight"),
        )),
        Revision(14, "1.6.0", "2026-11-01", listOf(
            Item("The demo has one entry point: GET /v1/demo (MCP tool demo) now starts with the first-run tour, run from cards on the phone, then the menu. The menu asks its questions on a card and every demo ends with a card", "/v1/demo"),
        )),
        Revision(15, "1.6.0", "2026-11-02", listOf(
            Item("Reports go to the Bridgit team too (unless the owner turns it off), with the phone's details and yours: add agent_info {harness, harness_version, model} when you file one; replies say sent_to_team. Up to 50 reports an hour per agent", "/v1/feedback"),
        )),
        Revision(16, "1.6.0", "2026-11-02", listOf(
            Item("App settings: every Bridgit setting and the phone's sound and display settings, with type, allowed values and whether you may change each one (locked_reason, why)", "/v1/settings"),
            Item("Developer agents change settings in one call once the owner turns on App settings; locked ones fail with setting_locked", "/v1/settings"),
        )),
        Revision(17, "1.6.0", "2026-11-02", listOf(
            Item("Spelled Bridgit everywhere. Headers are now X-Bridgit-* (API revision, what's new, image and audio details, callback signatures); every one is still also sent as X-Bridget-*, so read either", null),
            Item("The helper is GET /v1/tools/bridgit (save it as ~/bin/bridgit; it reads BRIDGIT_* and ~/.config/bridgit/env, and still BRIDGET_* and ~/.config/bridget/env). /v1/tools/bridget still works", "/v1/tools/bridgit"),
            Item("HTML cards: call Bridgit.respond(json)", "/v1/interactions"),
            Item("health says \"app\": \"Bridgit\"; the MCP server name is bridgit; log filter agent=bridgit (agent=bridget still works); the pairing QR carries \"bridgit\": 1 as well as \"bridget\": 1", "/v1/health"),
        )),
        Revision(18, "1.6.0", "2026-11-02", listOf(
            Item("HTML cards act on the phone: Bridgit.act({method, path, body}) runs one of your endpoints at once, as you (your grants and the owner's switches apply), and resolves to {ok, status, body}", "/v1/interactions"),
            Item("action.executed event for every Bridgit.act call, so a card's action is reported even while you're offline", "/v1/events"),
        )),
        Revision(19, "1.6.0", "2026-11-02", listOf(
            Item("Interaction samples: GET /v1/samples has a complete, tested POST body for every kind, styled html cards included, and the exact answer each one produces", "/v1/samples"),
            Item("The demo's cards are styled html cards (step pill, icon, Do it / Skip / End demo); their pages are in GET /v1/samples", "/v1/demo"),
        )),
        Revision(20, "1.6.0", "2026-11-02", listOf(
            Item("Card stacks: post kind=html cards with the same \"stack\" id (optional stack_index) and the owner swipes through them, n of m with a peek of the next card. swipe_answer {left, right} lets a swipe answer a card (answer.data.swipe). Interactions and interaction.answered events carry stack, stack_index (and stack_size)", "/v1/interactions"),
        )),
        Revision(21, "1.6.0", "2026-11-02", listOf(
            Item("Arm the camera ahead of a photo: it opens and settles exposure for up to 10 s, so your next snapshot fires in about 0.3 s instead of 1.4-2 s; snapshot replies say warm", "/v1/camera/arm"),
        )),
        Revision(22, "1.6.0", "2026-11-02", listOf(
            Item("Scheduled actions: hand the phone an action manifest once (inline or a URL it polls) — any call you may make plus download, play, show, send and delete, each with its own schedule (at, times, every_minutes with count/until, daily, after). The owner approves it once; results arrive as schedule.* events, saved files at /v1/schedule/assets", "/v1/schedule"),
        )),
        Revision(23, "1.6.0", "2026-11-02", listOf(
            Item("Please report bugs and ask for features: the setup prompt, the guide (Report bugs, ask for features), the MCP instructions and health's links.feedback now point you to POST /v1/feedback. health's links also lists samples", "/v1/feedback"),
        )),
        Revision(24, "1.6.0", "2026-11-02", listOf(
            Item("Check on your own reports: every agent can list (GET /v1/feedback) and read the reports it filed, with status, history and the Bridgit team's note; the team's latest status is pulled first. Developer agents still see every report; mine=true narrows to yours", "/v1/feedback/{id}"),
            Item("Follow up on a report you filed: a note, and once it's fixed, won't fix or duplicate, outcome works or still_broken. Follow-ups reach the Bridgit team", "/v1/feedback/{id}/followup"),
            Item("Filing a report replies with url and followup_url so you can check back", "/v1/feedback"),
        )),
        Revision(25, "1.7.0", "2026-11-02", listOf(
            Item("Full battery report: status, plug type, health, temperature, voltage, cycles, current, time to full, battery saver, screen timeout and which power settings you may change. /v1/phone/status carries the same fields under battery", "/v1/phone/battery"),
            Item("Battery saver on or off (after the owner's one-time USB grant) and screen timeout, with optional restoration", "/v1/phone/power"),
            Item("Battery events on /v1/events for agents with the phone grant: battery.low (15%), battery.critical (5%), battery.okay (20%), battery.charging_started/stopped, battery.full, battery.saver_on/off, battery.hot (45 °C) and battery.cooled", "/v1/events"),
        )),
        Revision(26, "1.7.0", "2026-11-02", listOf(
            Item("Your own key for the Bridgit team's feedback backend: file reports, check their status and verify fixes there directly, so a broken phone doesn't stop you. rotate=true makes a new one", "/v1/feedback/team-key"),
            Item("The verify loop: follow your reports on the backend (GET /v1/agent/reports?waiting=true); when one is waiting_for_retest, install fixed_in or later, re-run the repro and POST /v1/agent/reports/{id}/verify works, still_broken or unverified. Never call something fixed until a re-test says works", "/v1/feedback/team-key"),
            Item("Send every bug and every feature request, however small, even if you doubt we can or will do it: we'd rather triage too many than miss one. POST /v1/feedback replies carry team_api {base_url, key_endpoint, docs}; uploads now carry your agent id", "/v1/feedback"),
        )),
        Revision(27, "1.7.0", "2026-11-02", listOf(
            Item("Weather where the phone is, or at a lat/lon you give: conditions now, the next hours and the next 24 hours, from MET Norway (free, CC BY 4.0). Coordinates are rounded to about 1 km before they leave the phone. Schedule it like any call: {\"do\":{\"call\":\"GET /v1/weather\"}}", "/v1/weather"),
            Item("New error upstream_failed (502): a service Bridgit asks for you, such as MET Norway, failed and nothing was cached", null),
        )),
        Revision(28, "1.7.0", "2026-11-02", listOf(
            Item("ADB control: how to help the owner set it up (on the phone, or with a USB cable and a laptop agent), and what to run once it's on (no key needed)", "/v1/adb/setup"),
            Item("ADB setup progress and which grants took effect", "/v1/adb/status"),
            Item("Run a shell command as Android's shell user (developer agents, owner switch)", "/v1/adb/shell"),
            Item("Give Bridgit every useful permission and access through ADB", "/v1/adb/grant"),
        )),
        Revision(29, "1.8.0", "2026-11-02", listOf(
            Item("Every agent can file and follow bug reports and feature requests, whatever permissions the owner gave it", "/v1/feedback"),
            Item("Every error reply carries report_problems: where to report, including the team server's no-phone route for when the phone itself is the problem", null),
        )),
        Revision(30, "1.8.0", "2026-11-02", listOf(
            Item("Learn Bridgit without the phone: ${Learn.INDEX_URL} lists the guide, OpenAPI, endpoint list, change log, samples, demo, errors and how to file bugs, always current with the latest code, no key. Also ${Learn.LLMS_URL}. health's links.learn and /v1/changes links.learn point there", null),
        )),
        Revision(31, "1.8.0", "2026-11-02", listOf(
            Item("The guide starts with a short Start here (capabilities first, one call, how to wait, retry and limits) and Good phone manners for cards and notices", "/v1/guide"),
            Item("The guide's capability table and endpoint list show what you are allowed to use (agent_allowed), not only what is switched on", "/v1/capabilities"),
            Item("The bridgit helper's wait-interaction keeps waiting while a question is only seen, holds 55 s at a time, and takes --idempotency-key", "/v1/tools/bridgit"),
        )),
        Revision(32, "1.8.0", "2026-11-02", listOf(
            Item("Decisions with Jev (TypeSafe): send a state and typed questions, get typed answers with calibrated probabilities, on the Bridgit team's account. Add conditional actions (bridgit-actions/1): a card, speech or a log line that runs only when an answer clears your threshold; a Jev failure runs nothing", "/v1/judgment"),
            Item("Agents with a phone-minted team key can also call the team backend directly: POST {base_url}/v1/jev, same body and reply (actions come back, not run)", "/v1/feedback/team-key"),
        )),
        Revision(33, "1.8.0", "2026-11-02", listOf(
            Item("Keep your captures where you want, in any combination: memory (for as long as you choose, up to the owner's limit), the phone's Downloads/Bridgit folder, or an upload to your URL. Set it once for all your captures or per kind", "/v1/captures/storage"),
            Item("store on photos, screenshots and recordings: [\"memory\",\"files\",\"upload\"] for this one capture; the reply's capture says where it went", "/v1/camera/snapshot"),
            Item("List, fetch and delete your kept captures; wake-word and sound clips are there too, and their events carry capture", "/v1/captures"),
            Item("Each agent still sees only its own captures, and only while it has the capability, unless the owner lets agents share them", null),
        )),
        Revision(34, "1.8.0", "2026-11-02", listOf(
            Item("Outbound log: every call the phone made to a server off the device (team backend, Jev, weather, relay, updates, Sentry, your callbacks), with destination, purpose, the agent that caused it, data categories, bytes, status and a redacted preview of what was sent. Filter by since, destination, agent, category; every agent may read the calls it caused", "/v1/outbound"),
            Item("Totals of what left the phone by destination, category, agent, purpose and day", "/v1/outbound/summary"),
            Item("One outbound entry with its previews", "/v1/outbound/{id}"),
        )),
        Revision(35, "1.8.0", "2026-11-02", listOf(
            Item("The owner's email: Bridgit works for 24 hours from the first agent call, then needs a verified address. See where it stands; health has the same as owner_email", "/v1/owner/email"),
            Item("Send the owner's address yourself: bridgit.cc emails them a link to tap (the same address gets email alerts)", "/v1/owner/email"),
            Item("New error email_required (403): after the 24 hours only status, feedback and /v1/owner/email work until the owner verifies", null),
        )),
        Revision(36, "1.8.0", "2026-11-02", listOf(
            Item("The outbound summary is now an analysis in one call: a plain-language headline (what left the phone and to whom), period=today|24h|7d|30d, trends by hour or day, the biggest destinations, agents and calls, destinations first seen in the window, failures by destination and error, and for each destination the data categories, purposes and agents", "/v1/outbound/summary"),
            Item("privacy.outbound_log turns the outbound log on or off (default on); the owner in Settings or an agent here, audited. While it is off nothing new is recorded, and /v1/outbound's recording says since when and who turned it off", "/v1/settings"),
            Item("period=today|24h|7d|30d on the outbound list too, and a recording block in every reply", "/v1/outbound"),
        )),
        Revision(37, "1.8.0", "2026-11-02", listOf(
            Item("The Developer switch is now two: Bridgit Fixer Mode (every bug report with screenshots, moving reports, attaching images) and Agent Super Powers (ADB shell and grants, changing settings, every agent's log). Agents that had Developer keep both", null),
            Item("Refusals say which one you need: forbidden with reason fixer_mode_off or super_powers_off (was developer_only). settings has agent.fixer_mode and agent.super_powers instead of agent.developer", "/v1/settings"),
            Item("The owner can tap Revoke Super Powers: Super Powers off for every agent, ADB control off, what ADB gave Bridgit taken back, the pairing forgotten and Wireless debugging off", "/v1/adb/status"),
        )),
        Revision(38, "1.8.0", "2026-11-02", listOf(
            Item("Reports have one team-wide number, ref: a letter for the source and the Bridgit team's id, e.g. M0042 (the owner, from the app), A0042 (an agent), C0042 (caught automatically, like crashes). The phone shows it everywhere instead of its own #0001", "/v1/feedback"),
            Item("number is now that same ref, and null until the phone has sent the report to the Bridgit team (it was the phone's own #0001). team_id is the plain id. Keep using id in /v1/feedback/{id} URLs", "/v1/feedback/{id}"),
        )),
        Revision(39, "1.8.0", "2026-11-02", listOf(
            Item("Watches: \"call me back when ...\". Pick data (location with speed, motion, steps, battery, ...) and an interval; the phone asks Jev (or Clef, model: clef) your question about the last few readings and calls you back (watch.matched) when the answer matches", "/v1/watches"),
            Item("Read one watch's last checks and the state Jev saw, or stop it", "/v1/watches/{id}"),
        )),
        Revision(40, "1.8.0", "2026-11-02", listOf(
            Item("Watches can read the camera (data: [\"camera\"], a 640 px photo each reading) and you choose what is paid and what stays on the phone: model clef (default with a photo), jev, or none (local only: called back when the picture changes, free)", "/v1/watches"),
            Item("local: a free change check before every paid call (on by default with camera): reuse the last answer while the picture stays the same, with sensitivity low/normal/high, learn_noise and max_reuse_s. max_paid_per_hour caps paid calls per watch", "/v1/watches"),
            Item("Each watch reports usage: answered_locally (free) vs paid_calls, paid_cost_usd and paid calls in the last hour; checks and watch.matched say paid or answered_locally with cost_usd. Defaults, presets and limits are in /v1/capabilities under watches", "/v1/watches/{id}"),
        )),
        Revision(41, "1.8.0", "2026-11-02", listOf(
            Item("Send X-Bridgit-Model: <model id> (and optionally X-Bridgit-Harness: <harness>) on any call and the owner's agent card follows it, so it stays right when you switch models. Easiest: add the line to the header file you send with every call. POST /v1/me still works", "/v1/me"),
        )),
        Revision(42, "1.8.0", "2026-11-02", listOf(
            Item("Interactions open automatically by default, subject to the owner's setting and Android overlay permission. Send no_takeover: true to keep a call in the Inbox and notifications without navigating, even while Bridgit is open; it is also excluded from automatic follow-up cards", "/v1/interactions"),
        )),
        Revision(43, "1.8.0", "2026-11-03", listOf(
            Item("Device inventory: the Android host plus owner-configured external devices, their action endpoints and permission state. Passive inventory distinguishes configured from confirmed online", "/v1/devices"),
            Item("Owner-enrolled LAN cameras: direct RTSP snapshots and ONVIF pan / tilt, separate lan_camera switch and agent grant. Add cameras and view video on the Devices tab", "/v1/lan-cameras"),
        )),
        Revision(44, "1.8.0", "2026-11-03", listOf(
            Item("Watches that remember: tally on POST /v1/watches counts answers on the phone (episodes, day totals, typical gaps) for up to 90 days, with no agent online; rules fire on the counts (none_for_s, fewer_than by a time, at_least within) and can call one of your endpoints on the phone; watch.rule event; 10 s readings with the camera change check", "/v1/watches"),
            Item("A tally watch's counts, days and episodes", "/v1/watches/{id}/tally"),
        )),
        Revision(45, "1.8.0", "2026-11-03", listOf(
            Item("Read and configure selective applet device assignments with a separate owner-controlled switch and agent grant; revocation stops active Dog Monitor use", "/v1/app-devices"),
            Item("Bounded mDNS (Android 13+), SSDP, ONVIF and optional TCP discovery on the host network", "/v1/lan/discover"),
            Item("Verify and enroll supported local devices with authorized credentials", "/v1/lan/devices/enroll"),
            Item("Forget this agent’s local device enrollment", "/v1/lan/devices/remove"),
            Item("Fixed supported device actions, never arbitrary RPC", "/v1/lan/devices/action"),
            Item("Direct-LAN discovery, supported-device enrollment and per-agent device actions. Separate owner-only switches and agent grants; Shelly Gen1 relays, read-only OpenWrt ubus, and existing Tapo ONVIF enrollment", "/v1/lan/devices"),
        )),
        Revision(46, "1.8.0", "2026-11-03", listOf(
            Item("Owner features and required downloads: resources inventory (Vosk voice model, translation, Android speech/TTS, bundled recognition and official APK updates), agent-scoped jobs with progress, errors and cancellation. All configuration/download/deletion requests need owner.configuration enabled/granted plus an immutable on-phone approval", "/v1/owner/features"),
            Item("Resource inventory, language readiness and your download/check/delete jobs", "/v1/resources"),
            Item("Follow and cancel one of your resource jobs", "/v1/resource-jobs"),
            Item("Native and agent-built applet catalog with Dog monitor status, history and existing frame", "/v1/applets"),
            Item("Remote viewing link and photo-sharing status", "/v1/remote-view"),
            Item("Own callback/alert delivery queue, retries and approved destination IDs without signing secrets", "/v1/delivery"),
            Item("Supplemental owner settings and model-key presence", "/v1/owner/settings"),
            Item("Native Dog monitor reads (state, totals/history and existing latest frame), applet catalog, device-to-app membership, remote sharing settings, callback/delivery diagnostics and model-key presence without secrets. Approved requests configure models/cameras, start/stop applets, set listening, open owner/Android setup", "/v1/owner/configure"),
            Item("Self-supplied profile now has main-MCP parity. Control MCP adds owner.configure and operation.cancel. Enrolled LAN cameras work in generic watches and bounded-JPEG control monitoring; each rechecks source grants", "/v1/agent/profile"),
            Item("Pause/resume your watch without losing its tally (expiry unchanged); read your exact approved/pending schedule manifest. Sound classification: judgment model clef with audio_clip_id sends an existing permitted clip to the decision backend, without starting a recording", "/v1/watches/{id}/pause"),
        )),
        Revision(47, "1.8.0", "2026-11-03", listOf(
            Item("Reference-image search, bounded direction calibration and centering, then hold aim; operation status, cancel, private final photo and Fixer defaults. Requires camera and judgment access; no patrol, tracking or zoom simulation", "/v1/lan-cameras/framing"),
            Item("Active-profile ONVIF control evidence. Absolute PTZ and zoom advertisement stay separate from implemented and verified controls", "/v1/lan-cameras/{id}/controls"),
        )),
        Revision(48, "1.8.0", "2026-11-03", listOf(
            Item("Passive battery time-to-empty estimate while unplugged: battery_runtime on health; estimated_runtime in phone battery/status, diagnostics and watch battery samples. Includes approximate range, drain rate and learning/plugged_in/unavailable states; no extra wakeups", "/v1/phone/battery"),
        )),
        Revision(49, "1.8.0", "2026-11-03", listOf(
            Item("Dog monitor personal backend is optional per applet per device. Owner-approved dog.configure accepts personal_backend, photo_sharing, backend_email and user_alias; enabling requires the requested email and alias and returns a verified-account linking code", "/v1/owner/configure"),
            Item("Dog monitor status includes backend consent, pending deletion, portal identity and estimated eating/drinking time", "/v1/applets/dog-monitor"),
        )),
        Revision(50, "1.8.0", "2026-11-03", listOf(
            Item("Pet monitor is the current display name across applets, device assignments and owner controls. Existing dog-monitor paths, dog actions and stored identifiers remain compatible; monitoring still uses the current dog classifier", "/v1/applets/dog-monitor"),
            Item("Public homepage applet library and Pet monitor capabilities are generated from published template 1.1.0, revision 2. Full template and instance configuration APIs remain authenticated; template saves do not start an applet. See https://bridgit.cc/applets/pet-monitor/", null),
        )),
        Revision(51,"1.8.0","2026-11-03",listOf(
            Item("Passive per-camera capability checks at enrollment and explicit refresh: advertised features, implementation, codec/direction, verification state; no automatic recording, playback or movement", "/v1/lan-cameras/probe"),
            Item("Explicit 1–30 second external camera microphone WAV clips, separate owner switch and agent permission; decodable silence is not proof of room pickup", "/v1/lan-cameras/audio/record"),
            Item("Bounded camera speaker audio through advertised ONVIF PCMA backchannel or authenticated Tapo adapter; separate owner speaker permission and private supplied PCM", "/v1/lan-cameras/audio/play"),
            Item("Separately verify/store or clear authorized Tapo cloud-password talk credentials; local camera passwords remain separate", "/v1/lan-cameras/talk-account"),
        )),
        Revision(52,"1.8.0","2026-11-03",listOf(
            Item("Pet monitor manual camera arrays take turns; Auto stays exclusive. Optional built-in and assigned camera microphone selections sample five-second clips with at least 60 seconds between rounds; Clef estimates barking/growling and reports unavailable captures separately", "/v1/owner/configure"),
            Item("Pet monitor status includes selected microphones, sample status and bounded audio observations; microphone permissions are required to read audio observations", "/v1/applets/dog-monitor"),
        )),
        Revision(53,"1.8.0","2026-11-03",listOf(
            Item("Pet monitor readings carry unchanged_since, a per-camera visual continuity marker. The private gallery groups consecutive matching samples into first/latest pictures and elapsed sampled time; changed scenes, camera/model changes and gaps stay separate", "/v1/applets/dog-monitor"),
            Item("Per-application, per-camera search delays on this device default to 60 seconds. Pet monitor Auto searches after every active view reaches its delay and retries without the former 5/10/20-minute backoff. Owner-approved dog.configure accepts camera_search_delays (10–3600 whole seconds; null resets); status exposes effective values", "/v1/owner/configure"),
        )),
        Revision(54, "1.8.0", "2026-11-04", listOf(
            Item("Unlimited history by default; zero disables each retention limit. Direct HTTP JSONL/CSV exports stream without a total-size cap", "/v1/log/export"),
            Item("Fixer Mode: app-UID logcat snapshots and Android exit/crash/ANR history; persistent exit summaries", "/v1/log/device"),
            Item("Android 11+ exit metadata and optional ANR/native traces", "/v1/log/exits"),
            Item("Super Powers and enabled ADB: system-wide logcat snapshots", "/v1/adb/logcat"),
        )),
        Revision(55, "1.8.0", "2026-11-04", listOf(
            Item("Bounded intent discovery for source-derived capabilities and recipes, with caller-scoped v1 readiness and explicit unknown v2 state; compact public starter and generated catalog, independently versioned documentation", "/v1/discover"),
            Item("Strict authenticated selection/relevance/self-reported attempt/outcome counters through existing opt-out-aware anonymous usage tallies; no query, content or verified-success events", "/v1/discover/report"),
        )),
        Revision(56, "1.8.0", "2026-11-04", listOf(
            Item("Remembered current-network discovery inventory with last-seen timestamps and connected/category totals; no scan", "/v1/lan/discovered"),
            Item("Versioned discovery and brand/logo catalog with control-agent instructions", "/v1/lan/catalog"),
            Item("Authenticated passive manufacturer logos, raw image or JSON/base64", "/v1/lan/brands/{id}/logo"),
            Item("Grouped human-readable device discovery, totals, suggested types/brands and useful categories first; broader mDNS, Tapo/TP-Link and WiiM coverage", "/v1/lan/discover"),
        )),
        Revision(57, "1.8.0", "2026-11-04", listOf(
            Item("Correct the watch data array's MCP/OpenAPI item type to string, matching documented examples and the existing runtime parser; no watch behavior change", "/v1/watches"),
        )),
        Revision(58, "1.8.0", "2026-11-04", listOf(
            Item("Applet terminology across UI, guides and schemas. Canonical applet.event command/trigger, applet_id and /v2/applets/events; legacy application names retain approval, event formats and idempotency compatibility", "/v2/applets/events"),
            Item("Canonical applet device assignment routes; /v1/app-devices and the existing grant remain compatible", "/v1/applet-devices"),
        )),
        Revision(59, "1.8.0", "2026-11-04", listOf(
            Item("SSH connections: pinned hosts, encrypted password/key login, granular commands and SFTP, owner management and scheduled remote work", "/v1/ssh/connections"),
            Item("Scheduled SSH commands treat nonzero/unknown exit status as failure; required idempotency keys supplied per run", "/v1/ssh/exec"),
            Item("Observe host fingerprints without credentials", "/v1/ssh/probe"),
            Item("Test saved SSH authentication without executing commands", "/v1/ssh/test"),
            Item("SFTP stat/list/read/write/mkdir/rename/remove/rmdir/chmod", "/v1/ssh/files"),
        )),
        Revision(60, "1.8.0", "2026-11-04", listOf(
            Item("Bridgit Portal (iPhone & iPad): read-only owner views over the relay, paired on More › Bridgit Portal. Home with feature states, connections and navigation", "/v1/portal/home"),
            Item("Portal snapshot: home, agents, applets and the newest log entries in one call", "/v1/portal/snapshot"),
            Item("Portal network devices: the remembered-devices payload, no scan", "/v1/portal/devices"),
            Item("Portal brand logos, as the LAN logo route", "/v1/portal/brands/{id}/logo"),
            Item("Portal activity with remote-view redaction, oldest first after since", "/v1/portal/logs"),
            Item("Portal agents with transport, mode and grants", "/v1/portal/agents"),
            Item("Portal applets", "/v1/portal/applets"),
            Item("Portal design tokens: light/dark colours, Home scene colours, corner radii, fonts, motion and signature animations", "/v1/portal/theme"),
        )),
        Revision(61, "1.8.0", "2026-11-04", listOf(
            Item("Portal endpoints take client=iPhone|iPad|Android: the phone's Home shows the device viewing it; relay calls now update an agent's last_used_at", "/v1/portal/home"),
        )),
        Revision(62, "1.8.0", "2026-11-04", listOf(
            Item("Portal home carries scene{options, paused, listening, home_status}: what the phone's Home feeds its scene; agents_count counts control agents only", "/v1/portal/home"),
            Item("Portal logs: until/has_older/oldest_id/latest_id paging and the Home scene's entry keys, still redacted", "/v1/portal/logs"),
            Item("Portal agents: portal flag on Bridgit Portal pairings", "/v1/portal/agents"),
        )),
        Revision(63, "1.8.0", "2026-11-04", listOf(
            Item("Portal snapshot: relay_daily_jobs, the pairing's relay allowance per day (20000 for Bridgit Portal pairings)", "/v1/portal/snapshot"),
        )),
        Revision(64, "1.8.0", "2026-11-04", listOf(
            Item("Limits are per account and can change: GET /v1/limits gives this phone's Jev and Clef calls an hour, monthly model budget and paid watch checks, with what is used, plus the limits the phone enforces itself. Watches use the account's value", "/v1/limits"),
        )),
        Revision(65, "1.8.0", "2026-11-04", listOf(
            Item("Device lock (on by default): your key works only from the Tailscale device(s) it is locked to; another device gets 403 wrong_device and the owner is asked. Settings security.* show the owner's security choices (read-only)", "/v1/settings"),
            Item("Bridgit Portal pairing links are single-use: the portal claims with a token it made (POST /v1/portal/claim through the relay); unclaimed links expire in 24 h (claim_required). With security.relay_signed_only on, connector relay agents other than portals get signed_requests_required", "/v1/portal/home"),
        )),
        Revision(66, "1.8.0", "2026-11-04", listOf(
            Item("Sound watches: data [\"sound\"] wakes a watch on each loud sound the phone hears (free loudness check on the phone, no interval) and asks Clef your question about that clip. sound {sensitivity, reuse_s}: a loud sound within reuse_s (default 300) that sounds like the last one checked (fingerprint compared on the phone) reuses that answer for free; a different sound is checked. listening shows whether the phone hears for it. Paid checks default to 6 an hour (max_paid_per_hour). Tally, rules and callbacks work as for any watch; needs the Sounds grant", "/v1/watches"),
            Item("Pet monitor barking is automatic, on the same sound core: the built-in microphone listens for loud sounds and Clef checks only those; barking episodes are counted today (owner status barking, portal tile)", "/v1/owner/configure"),
        )),
        Revision(67, "1.8.0", "2026-11-04", listOf(
            Item("Bridgit Satellites: other Bridgit phones linked to this one. GET /v1/satellites lists the ones the owner lets you use; they also appear in GET /v1/devices (kind satellite)", "/v1/satellites"),
            Item("Run a call on a satellite: add header X-Bridgit-Target: sat:<id> (or query target=sat:<id>) to camera, mic, speaker, interactions, location, gallery, sounds, /v1/health and /v1/capabilities calls. Replies carry X-Bridgit-Ran-On. New errors: unknown_target, target_not_granted, not_on_satellite, satellite_offline", "/v1/camera/snapshot"),
            Item("Phone to phone only (not for agents): a hub claims a satellite's one-time pairing code", "/v1/satellite/claim"),
            Item("Phone to phone only (not for agents): what a satellite offers its hub", "/v1/satellite/manifest"),
        )),
        Revision(68, "1.8.0", "2026-11-04", listOf(
            Item("Pet monitor readings carry dark: a near-black photo (lights off, camera covered) is not sent to a model and never reuses an earlier answer; it reads dog false, dog_p 0, dark true, and today's views count it as \"dark\". The first lit photo after it is checked fresh", "/v1/applets/dog-monitor"),
        )),
        Revision(69, "1.8.0", "2026-11-04", listOf(
            Item("Installable applets: POST /v1/applets/install takes one applet package (a bridgit-template/1 definition with runtime.adapter android.package.v1: typed blanks, watches, schedule, workflow, card and declared permissions), inline or by https://bridgit.cc URL or template registry key. The owner reviews it once on the phone; on Approve the phone creates every part under the applet's own agent and keeps it running with no agent (watches and card are renewed). POST /v1/applets/validate is the dry run with the owner's plain-words review", "/v1/applets/install"),
            Item("Installed applets you asked for: GET /v1/applets/installed, pause/resume, DELETE (removes every part and its permissions), and POST /v1/applets/installed/{id}/update, which recompiles and swaps a new revision in at once when it adds no permission (unchanged watches keep their history) and otherwise waits for the owner. Events applet.installed and applet.declined", "/v1/applets/installed"),
        )),
        Revision(70, "1.8.0", "2026-11-04", listOf(
            Item("Pet monitor sampling interval: one value for all cameras and microphones (default 20 s, was 60), 10-3600 s, with per-camera overrides. Auto checks the other cameras after this long without seeing the pet, and the food-view microphone samples this often. Read it as sampling {all_s, default_s, min_s, max_s, cameras, overrides, photo_interval_s}; the owner sets it with dog.configure sampling_all_s (camera_search_delays null now follows it) or from the web portal", "/v1/applets/dog-monitor"),
        )),
        Revision(71, "1.8.0", "2026-11-04", listOf(
            Item("Situations: POST /v1/watches takes situation instead of question, a common picture situation from the library (dog_licking_itself, dog_scratching, dog_on_couch, cat_on_counter, pot_boiling_over, smoke_in_room, water_on_floor, sunlight_in_room, lights_on, person_in_view) or your own words. The phone makes a camera watch with a tuned yes/no question that counts each episode on the phone (GET /v1/watches/{id}/tally) and, for alert situations, sends the owner an urgent notice when one starts. The model stays your pick (clef by default, or jev). GET /v1/situations lists the library", "/v1/situations"),
        )),
        Revision(72, "1.8.0", "2026-11-04", listOf(
            Item("Find things in a photo: POST /v1/images/find {query: \"the dog\" | \"each dirty dish\" | \"the face of each person\", capture_id | gallery_id | data_base64} returns boxes (x0, y0, x1, y1 fractions plus pixels). Faces are found on the phone, offline and free; anything else by a cheap cloud vision model on the Jev/Clef budget (mode auto, on_device or cloud)", "/v1/images/find"),
            Item("Zoom in: POST /v1/images/crop crops to what query finds (or a box you give) with a margin; which=first, all (one crop around every match) or each (one per match); pad, aspect, max_edge_px, raw=1", "/v1/images/crop"),
        )),
        Revision(73, "1.8.0", "2026-11-04", listOf(
            Item("Installed applets on the web portal: owner_configure action applet.configure {applet: package id, personal_backend, photo_sharing, backend_email, user_alias} shows an installed package applet with a counting (tally) watch as its own tab at bridgit.cc/<alias>/myapps: today's counts, the latest answer, the notices it sent, its pause state and (photo_sharing, default on) the photo of its latest check. The owner can pause it, rename it and share it from the portal. GET /v1/applets/installed reports web_portal", "/v1/owner/configure"),
        )),
        Revision(74, "1.8.0", "2026-11-05", listOf(
            Item("Owner photos: the phone's Camera tab and the Bridgit Portal's camera hand each photo the owner takes to the watches that check photos, as if the watch had taken it (no change-check training, the hourly paid cap still applies). The check shows in GET /v1/watches/{id} recent with source owner and counts in the tally. The Portal sends them with POST /v1/portal/photo {data_base64, camera, targets} after GET /v1/portal/photo/targets", "/v1/portal/photo"),
        )),
        Revision(75, "1.8.0", "2026-11-05", listOf(
            Item("Phone to phone only (not for agents): a hub tells its satellite its photo applets and portals and gives it a return key that can call only GET /v1/satellite/hub, POST /v1/satellite/hub/photo and POST /v1/satellite/hub/portal-login, so the satellite's Camera tab checks photos with the hub's applets and its Portals tab shows the hub's portals", "/v1/satellite/hub-info"),
            Item("Phone to phone only (not for agents): a satellite reads its hub's photo applets and portals with its return key", "/v1/satellite/hub"),
            Item("Phone to phone only (not for agents): a photo taken on a satellite's Camera tab, checked by the hub's photo applets", "/v1/satellite/hub/photo"),
            Item("Phone to phone only (not for agents): a one-use bridgit.cc sign-in code so a satellite opens its hub's portals signed in", "/v1/satellite/hub/portal-login"),
        )),
        Revision(76, "1.8.0", "2026-11-05", listOf(
            Item("A satellite's return key may also read its hub's GET /v1/portal/home and GET /v1/portal/logs, so the satellite's Home can switch to the hub's Home (read-only, as a Bridgit Portal does)", "/v1/portal/home"),
        )),
        Revision(77, "1.8.0", "2026-11-05", listOf(
            Item("Opt-in on-phone vision engines, free and offline, used only when you name them (the default routing is unchanged: faces on the phone, the rest in the cloud). POST /v1/images/find and /v1/images/crop take engine on_device_detector (Florence-2 base finds anything named in words; 7-16 s a photo on a mid-range phone, never falls back to the cloud) and outline true (MobileSAM: each item gets outline [[x, y], ...] fractions; crop returns a PNG cut out along it). Until a model is downloaded (Wi-Fi only) they answer 503 model_not_ready {engine, state, size_mb, retry_after_s} and start the download", "/v1/images/find"),
            Item("GET /v1/images/engines lists the engines: what each is good at, speed, memory, size, licence and whether its model is downloaded (installed, state); POST /v1/images/engines/download {engine} fetches one ahead of time", "/v1/images/engines"),
            Item("Applet packages can ask for engines by name: runtime.engines [\"on_device_detector\", \"on_device_outline\"] (needs find_crop in permissions.required). The owner's review says what downloads; approving starts the download, and the applet's card calls find/crop with engine or outline", "/v1/applets/install"),
        )),
        Revision(78, "1.8.0", "2026-11-05", listOf(
            Item("Card flows: send follow_up: true on a question, notice or html card when you will post another card once the owner answers or closes it. The card screen then shows \"Waiting for the next card…\" and stays up to 30 s; your next card replaces it in place (a card in the same stack comes up next), and Bridgit steps back to what the owner was doing only after the last card. interaction.answered and interaction.dismissed for such a card carry follow_up_seconds. Closing an html card now shows a \"Closed\" panel in the card, like an answer (no bridgit:answered event)", "/v1/interactions"),
        )),
        Revision(79, "1.8.0", "2026-11-05", listOf(
            Item("The on-phone engines' runtime (ONNX Runtime, 18 MB) is no longer in the app: it downloads once with the first engine, on Wi-Fi. GET /v1/images/engines shows it as runtime {model, size_mb, state}; an engine is installed only when its runtime is too, and model_not_ready says which part is still downloading", "/v1/images/engines"),
        )),
        Revision(80, "1.8.0", "2026-11-05", listOf(
            Item("Body tracking on the phone, offline and free: POST /v1/camera/pose/start {camera front|rear, seconds 5-120} streams the camera through ML Kit's pose detector and keeps 33 body points per frame (about 8-25 frames a second); no picture leaves the phone. Uses the camera grant", "/v1/camera/pose/start"),
            Item("GET /v1/camera/pose/{id}?since=&preview_after=&wait_ms= reads the frames as they come ([x, y, v] per point, fractions of the upright picture) with a small live preview picture; GET /v1/camera/pose/{id}/picture?t= gives the replay picture nearest a time (one every half second); POST /v1/camera/pose/{id}/stop ends it early", "/v1/camera/pose/{id}"),
        )),
        Revision(81, "1.8.0", "2026-11-05", listOf(
            Item("On-phone engines now come from the Bridgit team's signed software manifest, checked once a day while an engine is in use (and on request with POST /v1/images/engines/check), so new engines and new versions can appear without an app update. GET /v1/images/engines adds manifest {serial, published_at, source, checked_at, signed_by, last_error}, each engine's version and kept_up_to_date, items this app can't run (state unsupported with the reason), and update {model, version, state} while a newer version downloads; the old version keeps answering until the new one checks out", "/v1/images/engines"),
            Item("POST /v1/images/engines/check: read the software manifest now; engines in use update to their newest version (on Wi-Fi)", "/v1/images/engines/check"),
            Item("engine on find/crop and runtime.engines in applet packages accept any engine the manifest lists; one this phone can't run answers not_supported (and blocks an applet install) with the reason", "/v1/images/find"),
        )),
        Revision(82, "1.8.0", "2026-11-05", listOf(
            Item("Home inventory from a photo: POST /v1/images/appraise {capture_id | data_base64, room?} lists each belonging with a box, category, brand and model where readable, condition, estimated price new and used (USD), insurable or not with a reason, and confidence. One cloud vision call, about half a cent, on the Jev/Clef budget (find_crop permission)", "/v1/images/appraise"),
            Item("Your own records on the phone (applet_data permission): POST /v1/applet/records/{key} {value} saves a JSON object (40,000 characters, 1000 records), GET /v1/applet/records?prefix=&after= pages through them with values (about 48 KB a page), GET and DELETE one. Never uploaded; removing an applet deletes its records", "/v1/applet/records"),
            Item("Applet packages may be 128 KB, with assets up to 48 KB each (were 64 and 16 KB)", "/v1/applets/install"),
        )),
        Revision(83, "1.8.0", "2026-11-05", listOf(
            Item("Workflows are flows: new steps photo, ask_model (Jev/Clef, scope decisions), ask_owner (waits for an answer), wait and stop; later steps use earlier results with {\"\$step\":\"id.path\"} (text fields also take a list of texts and references); only_if conditions; on_error stop/skip/retry; a watch trigger {type: watch, watch, on: match|rule}. A failed run no longer blocks the workflow. workflow.validate returns summary, the flow as one plain sentence", "/v2/call"),
            Item("workflow.runs {id, limit}: the newest runs step by step (status, results, errors, cost); new event workflow.failed", "/v2/call"),
            Item("Applet packages: processing.workflow can name a package watch with {\"\$watch\":\"main\"}", "/v1/applets/install"),
        )),
        Revision(84, "1.8.0", "2026-11-05", listOf(
            Item("Phone to phone only (not for agents): a hub and its satellite keep their link in step. POST /v1/satellite/unlink (hub link key) and POST /v1/satellite/hub/unlink (return key) tell the other phone a link was removed; a phone whose key the other no longer knows (401) drops the link too. A test build adds its name to the phone name it shows, e.g. \"Pixel 2 XL (test applets)\"", "/v1/satellite/unlink"),
        )),
        Revision(85, "1.8.0", "2026-11-05", listOf(
            Item("Applet results on the web portal: an applet whose package declares ui.portal.records {kind, title} (e.g. workout) sends one record per result from its card with POST /v1/applet/portal/record {label?, metrics {up to 16 numbers}, items [{label, count?, seconds?}], answers?}. Words and numbers only, never free text or pictures; sent only while the owner has that applet's web portal on (portal on | not_linked | off in the reply). Needs the applet_data permission", "/v1/applet/portal/record"),
        )),
        Revision(86, "1.8.0", "2026-11-05", listOf(
            Item("One action vocabulary (reference: https://bridgit.cc/learn/builder/applet-packages.md): a watch tally rule's do and a schedule action's do may start a flow, {\"flow\": \"<workflow id>\", \"with\"?: {…}}, or run one step from the workflow menu, {\"step\": {\"type\": \"notice\" | \"speak\", …}} (text may use {\"\$trigger\":\"message\"}). The raw endpoint call keeps working; POST /v1/watches returns hints with the step that does the same", "/v1/watches"),
            Item("Schedule actions also accept the call as {method, path, query?, body?}, the same spelling as watch rules and cards; call \"POST /v1/…\" keeps working", "/v1/schedule"),
            Item("Workflows: a called trigger {type: called, with?: {name: text|number|boolean}} for a flow that only a watch rule, a schedule or workflow.run starts; steps read the values as {\"\$trigger\":\"with.<name>\"} and the caller as trigger caller (rule, schedule, button, agent). workflow.run takes with. The notice step takes priority normal|urgent. A rule's trigger data now also has of and today", "/v2/call"),
            Item("Applet packages: processing.flows (1-4 flows, each a workflow body plus id); rules and schedule actions start one with {\"flow\": {\"\$flow\": \"<id>\"}}, bound to the applet's own flow by name, so an update that changes a flow keeps the watches and their counts. GET /v1/applets/installed shows flows {name: workflow_id}", "/v1/applets/install"),
        )),
        Revision(87, "1.8.0", "2026-11-05", listOf(
            Item("Applet cards from pieces (reference: https://bridgit.cc/learn/builder/applet-packages.md): ui.card {title, sources, pieces} draws header, status, metrics, table, timeline, gallery, video, people, devices, draft and buttons in Bridgit's own style, with the card state (loading, empty, stale, paused, error) set by the phone; no HTML or CSS. Also POST /v1/interactions kind pieces", "/v1/interactions"),
            Item("On-phone tables: processing.tables, the workflow step record and photo keep: true, card buttons that save a row; the owner sees and deletes rows under Installed applets › Data. The installing agent reads and adds rows of a shared table (share_with_installer)", "/v1/applets/installed/{id}/tables/{table}/rows"),
            Item("Validate returns package: the complete file with observation_schema, metrics, lifecycle, migration and data_policy filled in (and a missing permissions.required and asset sha256), so agents stop copying them", "/v1/applets/validate"),
        )),
        Revision(88, "1.8.0", "2026-11-05", listOf(
            Item("Applet spending cap : a package may set budget {day_usd, month_usd} (0.01-100; default $1 a day, $10 a month). Every paid cloud call the applet makes (watch Jev/Clef checks, ask_model, find, locate, appraise, judgment) counts; over the cap they fail with the new error budget_reached (on_error decides in flows). The review states the cap, GET /v1/applets/installed shows budget {day_usd, month_usd, spent_today_usd, spent_month_usd, set_by}, and the owner can change it in Installed applets", "/v1/applets/install"),
            Item("POST /v1/applets/validate (and the install review) return warnings [{code, where, says, fix}] that never block an install: card_request_too_big, card_reply_too_big, card_form_submit, card_storage, permission_unused, data_policy_contradicts, paid_without_cap. workflow.validate warns paid_without_cap", "/v1/applets/validate"),
            Item("Bridgit.act: a request over 16 KB is refused with request_too_big, and a JSON reply over 64 KB comes back ok: false with error reply_too_big and a fix (it used to come back ok with only its size)", "/v1/interactions"),
        )),
        Revision(89, "1.8.0", "2026-11-05", listOf(
            Item("Workflow steps appraise (a kept photo → items[] with prices and boxes, scope decisions, paid like ask_model) and update (change columns of one row of the applet's table, row_not_found). record takes for_each (one row per element, at most 50 in one write) with {\"\$item\":\"<field>\"}", "/v2/call"),
            Item("Applet card buttons: do.flow starts one of the package's flows; with for_each (an array source or a table), only_if {item, …}, max 1-200 and progress it runs a batch, one run per element, that survives a restart. New card source {\"batch\": \"<button id or label>\"}. A run cut off by a restart no longer blocks its workflow", "/v1/applets/install"),
            Item("PATCH /v1/applets/installed/{id}/tables/{table}/rows/{row} {set} changes one row of a shared table; new error row_not_found", "/v1/applets/installed/{id}/tables/{table}/rows/{row}"),
        )),
        Revision(90, "1.8.0", "2026-11-05", listOf(
            Item("Workflow step share (only in a flow with a button trigger) and card button do.step {share}: the phone builds a PDF report or a CSV file of one of the applet's tables (columns, group_by, totals, photos thumbs, where) and opens Android's share sheet; the owner picks where it goes. At most 10,000 rows, 200 photos, 20 MB; new errors export_too_large and share_unavailable", "/v2/call"),
        )),
        Revision(91, "1.8.0", "2026-11-05", listOf(
            Item("Connections (webhooks out): the owner approves where applets may send in Applets › Connections (Home Assistant, n8n, Discord, Slack, Telegram, ntfy, Pushover, Zapier, Make, IFTTT or any JSON webhook). GET /v1/connections lists id, name, service and whether it takes photos, never the address or secrets", "/v1/connections"),
            Item("Workflow step send {to, data, text, photo, wait_reply} (scope connections.send) and do.step send: data (32 keys, 16 KiB) plus applet, flow, run_id, sent_at and phone; a photo step's result goes as a file (Discord, Telegram, ntfy, Pushover, Home Assistant, JSON); wait_reply waits up to 15 s and returns status and reply (JSON) or reply_text for only_if. Queued sends retry, keep their sent_at and go the moment the network is back", "/v2/call"),
            Item("POST /v1/connections/send: the same send from an agent or a single step, with a kept photo", "/v1/connections/send"),
            Item("New connections sign with Standard Webhooks (webhook-id, webhook-timestamp, webhook-signature v1 with a whsec_ secret); older destinations keep their X-Bridgit-Signature. On my private network allows plain http and any port, only to private addresses", null),
        )),
        Revision(92, "1.8.0", "2026-11-05", listOf(
            Item("Phone to phone only (not for agents): a satellite switched to its hub lists the hub's control agents, while the hub hasn't paused that satellite", "/v1/satellite/hub/agents"),
            Item("Phone to phone only (not for agents): a satellite pauses or resumes one of its hub's control agents", "/v1/satellite/hub/agents/{id}/pause"),
        )),
        Revision(93, "1.8.0", "2026-11-05", listOf(
            Item("The starter page (learn/start.md) is also served from the phone, with no key, for agents that can't reach the web; health links it as start", "/v1/start"),
            Item("GET /v1/events takes limit (1 to 100): the newest N without since, the first N after the cursor with it", "/v1/events"),
        )),
        Revision(94, "1.8.0", "2026-11-05", listOf(
            Item("Workflow trigger power {on: unplugged | plugged | restored, for_s 0-3600}: unplugged with for_s 600 fires once after 10 minutes off charge (a power cut); restored fires when charging comes back after at least for_s off, with off_at, on_at and off_minutes. Plugs and unplugs are saved as they happen, so an outage across a reboot is still reported. charging stays as the old name of power plugged, and no longer fires at once for a flow activated while the phone is charging", "/v2/call"),
            Item("Workflow triggers battery {on: low, critical, okay, full, hot, cooled, saver_on, saver_off} with percent, charging, battery_saver and temperature_c, and sun {on: sunrise | sunset, offset_min -180..180, optional latitude and longitude, else scope location and a rough position saved once}", "/v2/call"),
            Item("Workflow fields cooldown_s (0-86400) and max_runs_per_day (1-1000): starts too soon are skipped, counted in skipped_runs, refused with workflow_skipped and reported as skipped to rules and schedules; the review sentence says \"at most once an hour, at most 10 times a day\". An applet.event trigger's cooldown_seconds is now the old name of the flow's cooldown_s; on such a flow it still counts per device and event type (default 30)", "/v2/call"),
        )),
        Revision(95, "1.8.0", "2026-11-05", listOf(
            Item("Applet budgets are shared by all the owner's devices working on the applet and enforced by Bridgit's servers: every paid call an installed applet makes names it (package id; dog for the Pet monitor). Over a limit calls fail with the new error applet_budget (applet, limit, resets_at) and the applet's watches pause until resets_at (status paused: applet_budget, a watch.paused event with reason applet_budget, watch.resumed when it lifts). budget_forecast and budget_out events reach the agent that installed it", "/v1/events"),
            Item("GET /v1/applets/{id}/budget: limits with used, forecast and reset (all phones), state ok|warning|out, ceiling, agents_may_change, actions with every_s, min_s, max_s, places, paid share and projection, and the last changes", "/v1/applets/{id}/budget"),
            Item("New Budgets permission (master switch on by default; on for the agent that installed an applet): PUT /v1/applets/{id}/budget {limits}, POST /v1/applets/{id}/budget/top-up {limit_id, usd} and PUT /v1/applets/{id}/actions/{action} {every_s, dry_run}. New errors agents_may_not_change, over_ceiling and out_of_range; at most 10 changes a day per agent and applet (rate_limited)", "/v1/applets/{id}/budget"),
            Item("Packages: budget takes limits [{id, usd, per hour|day|week|month, check_at}] (day_usd and month_usd become l1 and l2), and actions {id: {every, min, max, per, priority}} that watches name with action. A watch's interval_s can now change in place (interval_s shows the current one, package_interval_s the package's) and keeps its tally", "/v1/applets/install"),
        )),
        Revision(96, "1.8.0", "2026-11-05", listOf(
            Item("Workflow only_if gains sources time {between [HH:MM, HH:MM] (may cross midnight; the early hours belong to the day it began), days}, sun {after | before: sunrise | sunset, offset_min} (today's, on the phone), phone (charging, battery at_least/below, network wifi/mobile/none), zone {latitude, longitude, radius_m} with is, mode {\"mode\": true, is | is_not | in}, and count / sum over the applet's own table {table, since today | this_week | all or hours N, where {column, is}, column} with at_least / below; all and any take 2-8 tests, one level. The review sentence says them: \"only between 22:00 and 07:00 and when the mode is away\"", "/v2/call"),
            Item("A flow-level only_if, checked before the first step: a start that doesn't hold is skipped, counted in skipped_runs and refused with workflow_skipped (also for the owner's own Run tap: a condition isn't a rate limit)", "/v2/call"),
            Item("Modes: one per phone (home, away, night, vacation; the owner can add). Step set_mode {mode} (new scope modes.set, risk action) and do.step set_mode; trigger {type: mode, to?, from?} with from, to and by; card source {\"mode\": true} (mode, since, set_by, modes, label); GET /v2/mode, GET /v1/mode and POST /v1/mode. Setting the mode it already is starts nothing; a flow or agent may change it at most 20 times in 10 minutes (mode_busy)", "/v2/mode"),
            Item("GET /v1/mode and POST /v1/mode {mode}: the phone's mode, since, set_by, modes and the newest changes, and setting it (what do.step set_mode calls); returns mode, from and changed", "/v1/mode"),
            Item("Workflow step lookup {table, where?, since? | hours?, limit 1-10}: the newest matching rows of the applet's own table, newest first, as rows, row (the newest) and count (all matching rows)", "/v2/call"),
        )),
        Revision(97, "1.8.0", "2026-11-05", listOf(
            Item("Satellites carry device_type (phone, tablet, computer, tv, watch or camera; null from an older satellite)", "/v1/satellites"),
            Item("Phone to phone: the satellite manifest, hub info and GET /v1/satellite/hub say what kind of device sent them (device_type)", "/v1/satellite/manifest"),
            Item("Portal home device gains device_type (phone, tablet, computer, tv, watch or camera) next to type; a portal may send client_type with snapshot and home", "/v1/portal/home"),
        )),
        Revision(98, "1.8.0", "2026-11-05", listOf(
            Item("Packages: ui.portal.include \"optional\" (default: the owner turns the applet's web portal on in Installed applets) or \"always\" (the portal comes with the applet: the install review says what it sends, it starts on Approve and stops only when the applet is removed). \"always\" needs something to send (ui.portal.records or a watch with a tally). GET /v1/applets/installed reports web_portal.include", "/v1/applets/install"),
            Item("An applet with no watches, no schedule and only flows a button or another flow starts is user-driven: it has no scheduled actions (actions is empty in its budget), and Installed applets shows Open for its card", "/v1/applets/installed"),
        )),
        Revision(99, "1.8.0", "2026-11-05", listOf(
            Item("Packages: ui.portal.tables [1-3 of the applet's table ids] makes the web portal the applet's interface: the phone copies those tables' rows and photos to the portal while it is on, the card stays off the Cards inbox, and the portal page presses the card buttons listed in ui.portal.actions [button ids] on the phone (in Applets › Portals) and edits or deletes rows.", "/v1/applets/install"),
        )),
        Revision(100, "1.8.0", "2026-11-05", listOf(
            Item("Applet portals (permission applet_portals): GET /v1/applets/portals lists every applet with a portal; POST /v1/applets/{id}/portal/open brings any applet's portal to the front of the screen; GET /v1/applets/{id}/portal returns its functions, published state, visible text and last input (owner or agent); POST …/portal/call runs a function the page exposes; POST …/portal/close closes it. Events portal.opened, portal.functions, portal.state and portal.closed", "/v1/applets/portals"),
            Item("Applet pages get Bridgit.portal: expose(name, {description, params}), setState(json) and on(\"call\", handler) inside a portal; Bridgit.portal.available is false in an Inbox card. Installed applets' Open shows the applet's portal", "/v1/applets/{id}/portal"),
        )),
        Revision(101, "1.8.0", "2026-11-05", listOf(
            Item("Personal applet development snapshots, durable template ancestry and tracking/paused/detached policy. Template upgrade preparation creates a local special task; conflicts and verification stay with the control agent. Agent updates now require change.expected_revision, intent, acceptance and evidence and wait for owner review", "/v1/applets/installed/{id}/development"),
            Item("Discover typed device context metadata without collecting readings", "/v1/applets/context"),
        )),
        Revision(102, "1.8.0", "2026-11-05", listOf(
            Item("Modify applet shares only the control-agent instructions link and installed instance ID. The owner-selected editor can read the development snapshot and propose updates to this applet; existing revision and owner-approval checks apply. It receives applet.modify_requested {instance_id, instructions}. Table rows, pause, removal and template-policy access remain with the assigned developer", "/v1/applets/installed/{id}/development"),
        )),
        Revision(103, "1.8.0", "2026-11-05", listOf(
            Item("Registry-selected bounded speech turns: hear (1-30 seconds), speak (1-600 characters), explicit cloud permission, offline defaults and no automatic cloud fallback", "/v1/speech/hear"),
            Item("Speech model selection and on-device readiness", "/v1/speech/models"),
            Item("Speak through the existing permission-checked speaker queue, volume ceiling and Stop", "/v1/speech/speak"),
        )),
        Revision(104, "1.8.0", "2026-11-05", listOf(
            Item("Automated Context: GET /v1/context, GET /v2/context and context.status read durable and temporary facts with source, confidence and expiry (context.read). A context flow step returns the same snapshot; reads never acquire GPS or call a model. Unknown/expired/deleted values are null; deleted automatic items stay deleted until restored. Exact positions stay internal. The owner can inspect, tag added facts Durable/Temporary with expiry, correct, delete or pause in More > Automated Context. Place and night are independent; no automatic mode changes.", "/v1/context"),
        )),
        Revision(105, "1.8.0", "2026-11-05", listOf(
            Item("Custom applet cards discover their own declared table schemas with GET /applet/tables and read/add/edit rows through /applet/tables/{table}/rows, bound to their own principal without an installed-instance id", "/v1/applet/tables"),
            Item("Applet persistence is restricted to declared processing.tables. New json columns require a description and hold bounded objects/arrays. Legacy free-form applet writes are refused; existing blobs remain readable for migration. Portal records declare a table/json column and save locally before queueing", "/v1/applets/validate"),
            Item("Owner control keys with applet_install can discover every installed applet and read/edit its tables without installer ownership, share_with_installer or another approval. Applet principals remain isolated; existing capability and key checks still apply", "/v1/applets/installed"),
            Item("Uniform runtime-generated bridgit-applet-data/2 discovery for existing and future templates: schemas, row and status counts, batch outcomes, paginated rows and saved-photo links", "/v1/applets/installed/{id}/data"),
            Item("Read-only JSON export or ZIP with manifest.json and original saved photos (20 MB); larger data uses paginated rows and authenticated individual photos. No capture, recognition or share sheet", "/v1/applets/installed/{id}/data/export"),
        )),
        Revision(106, "1.8.0", "2026-11-06", listOf(
            Item("Template upgrades for unchanged applets return an owner review (awaiting_owner) directly; customized copies retain three-way tasks. bundled selects a built-in template without a network fetch. Installed applets expose computed template_update availability; source, revision and approval checks still apply", "/v1/applets/installed/{id}/template-upgrade"),
        )),
        Revision(107, "1.8.0", "2026-11-06", listOf(
            Item("General external service discovery for any applet: GLM 5.3 Flash via OpenRouter, Jev and Clef; call formats, required grants, applet watch/flow usage and per-service budget configuration. Reading the catalog makes no paid call", "/v1/services"),
        )),
        Revision(108, "1.8.0", "2026-11-06", listOf(
            Item("Device name read/write/reset through settings device.name; existing App settings and Agent Super Powers required", "/v1/settings"),
            Item("Custom mode definitions: read and atomically replace, add, rename or clear unused custom modes; built-in and current modes are protected. Uses existing App settings and Agent Super Powers without a per-change dialog", "/v1/settings/modes"),
        )),
        Revision(109, "1.8.0", "2026-11-06", listOf(
            Item("workflow.simulate validates an inline flow and previews its steps with fixtures. Returns the run log and intended effects without capturing, calling a model or contacting the owner", "/v2/call"),
        )),
        Revision(110, "1.8.0", "2026-11-06", listOf(
            Item("Owner-paired control agents default to durable maximum grants; relay maximum retains explicit permissions. New scopes inherit maximum authority; narrowing or revoking it stops inheritance", "/v2/capabilities"),
            Item("App-owned owner.configure changes run under the existing configuration grant without repeated approval; OS consent and sign-in stay on device", "/v1/owner/configure"),
            Item("Super-powered settings writers can change listener and capture policy; security and initial-authority gates remain owner-only", "/v1/settings"),
            Item("Write-only saved AI key provisioning and removal; reads expose only presence", "/v1/settings/ai-keys"),
            Item("Create, replace, revoke and rotate connection configuration with owner.configuration plus App settings and Super Powers; existing credentials remain write-only", "/v1/connections/configure"),
            Item("Write-only owner capture upload configuration; null clears it", "/v1/settings/capture-upload"),
        )),
        Revision(111, "1.8.0", "2026-11-06", listOf(
            Item("Unattended app-owned configuration for explicitly owner-granted control agents: discover settings, captures/listener policy, native configuration actions, model keys, connection lifecycle, callback provisioning, notification selections, listening recipients, custom modes, alert enrollment and saved food view. Secrets are write-only; OS/account/biometric/security authority remains human", "/v1/owner/configuration"),
        )),
        Revision(112, "1.8.0", "2026-11-06", listOf(
            Item("Read the account image-generation allowance and usage before requesting a new applet illustration.", "/v1/images/usage"),
            Item("Azure GPT Image 2.5 Sunburst generation and usage discovery; shared 40 images per linked account per month, server-enforced idempotency and existing judgment authority", "/v1/images/generations"),
        )),
        Revision(113, "1.8.0", "2026-11-06", listOf(
            Item("Applet model preferences inherit live from account override, applet default, then shared task default. Account overrides apply to every instance of the package; clearing restores inheritance. Read choices and effective source; use existing owner.configuration for service.model.set", "/v1/services/models"),
            Item("General location-context adapters with discoverable requests, write-only Google Maps/Open-Meteo keys and service budgets", "/v1/services/context"),
            Item("GLM fairytales from kept panorama frames and a portrait, Aura narration and parallel Azure illustrations in HTML cards; presentation tokens prevent late speech and images after Stop", "/v1/story"),
            Item("Video frame extraction returns caller-owned capture ids; snapshot return_image:false returns compact metadata", "/v1/camera/video"),
        )),
        Revision(114, "1.8.0", "2026-11-06", listOf(
            Item("Person-started card/portal photos and videos require visible capture, camera flip, review and deletion; automatic monitoring is unchanged", "/v1/camera/snapshot"),
            Item("Replay or delete a kept video through its caller-owned capture id; image capture reads support bounded previews", "/v1/camera/video/review"),
        )),
        Revision(115, "1.8.0", "2026-11-06", listOf(
            Item("Search and page through the independent applet library. Each entry identifies an immutable registry key and revision to install.", "/v1/applets/catalog"),
            Item("Installing a catalog selection verifies its registry key and revision. Historical starter ids resolve to a pinned registry release.", "/v1/applets/install"),
        )),
        Revision(116, "1.8.0", "2026-11-06", listOf(
            Item("Generate a story from reviewed captures. Applets choose which results to save in their own tables.", "/v1/story/generate"),
        )),
        Revision(117, "1.8.0", "2026-11-06", listOf(
            Item("Read the enrolled devices for an approved applet edition from a paired portal.", "/v1/portal/applets/inventory"),
        )),
        Revision(118, "1.8.0", "2026-11-06", listOf(
            Item("Read watch state, counts and last check outcome for one paired applet edition.", "/v1/portal/applets/watches"),
        )),
        Revision(119, "1.8.0", "2026-11-06", listOf(
            Item("Watch cards may read scoped recent checks, today's tally and bounded episodes from the paired monitor.", "/v1/portal/applets/watches"),
        )),
        Revision(120, "1.8.0", "2026-11-06", listOf(
            Item("Read, save and clear owner-reviewed applet camera positions using a revision check.", "/v1/lan-cameras/applet-views/{instance}/{view}"),
        )),
        Revision(121, "1.8.0", "2026-11-06", listOf(
            Item("Delegated control agents can inspect and approve or decline exact pending applet installs/updates, schedules, workflows, actions and control sessions with their existing configuration and action authority. Changed requests conflict; native validation and OS consent remain in force", "/v1/owner/configuration/approvals"),
        )),
        Revision(122, "1.8.0", "2026-11-06", listOf(
            Item("FLUX 3 Image Klein is available through the image service with task_key flux_image_generation, sharing the existing 40-image account allowance and spending limits", "/v1/images/generations"),
        )),
        Revision(123, "1.8.0", "2026-11-06", listOf(
            Item("Existing Agent Super Powers authority can configure app-owned feature switches and paired control-agent capabilities, roles and durable scope grants, including configuration bootstrap, without another owner tap. Initial Super authority, key pairing and real Android/account consent remain protected", "/v1/owner/configuration/access"),
        )),
        Revision(124, "1.8.0", "2026-11-06", listOf(
            Item("Viewed card notifications stay cleared; the sending control agent can explicitly request another alert for its open card without changing its read status, expiry or answers", "/v1/interactions/{id}/renotify"),
            Item("Guided camera capture, a group voice shutter and explicit reveal for prepared story media", "/v1/story/reveal"),
        )),
        Revision(125, "1.8.0", "2026-11-07", listOf(
            Item("Applet practice catalog, creation/change review gates, scoped exceptions and structural compliance reports; shared photo viewer and truthful progress defaults", "/v1/applets/practices"),
        )),
        Revision(126, "1.8.0", "2026-11-07", listOf(
            Item("Applet run modes Autonomous and Passive; durable starting, running, pausing and paused task states with stop acknowledgments", "/v1/applets/installed"),
            Item("Pause waits for task-owned work and device Stop confirmation; explicit manual actions remain available after pause", "/v1/applets/installed/{id}/pause"),
        )),
        Revision(127, "1.8.0", "2026-11-07", listOf(
            Item("Owner-authorized agents can bring Bridgit itself forward with an audit reason and a device-wide cooldown; the result confirms visibility or an Open Bridgit notification fallback", "/v1/app/foreground"),
        )),
        Revision(128, "1.8.0", "2026-11-07", listOf(
            Item("Bridgit names: the first control call registers this Bridgit's Device Domain Name (bridgit_name in health and capabilities); label and visibility are configurable and a visible name sends a heartbeat", "/v1/owner/configuration/name"),
        )),
        Revision(129, "1.8.0", "2026-11-07", listOf(
            Item("Linked Bridgits: two-way links between the owner's Bridgits with a double opt-in per direction. Lists the ones you may use; target any call by the linked Bridgit's Device Domain Name (X-Bridgit-Target or target=); sat: ids keep working; GET /v1/satellites is the same list", "/v1/links"),
            Item("Link with a name and a one-time code: make a code on the Bridgit to be used, claim it on the other; both logs name who did it", "/v1/owner/configuration/links/claims"),
            Item("Change this Bridgit's half of a direction: the service list (in) or the agents that may use the other Bridgit (out), or pause it", "/v1/owner/configuration/links/{id}/directions/{direction}"),
            Item("Any control agent may disconnect or pause a link at once (Undo for 10 seconds); an offline other side gets link_revoked on its next call", "/v1/owner/configuration/links/{id}/disconnect"),
            Item("The grants log of both Bridgits: every link event with its granter (person, agent or migrated)", "/v1/owner/configuration/links/log"),
            Item("New errors: link_revoked, link_paused, link_waiting, not_on_target, target_offline, code_invalid, code_expired, code_used_up, name_required. A call is passed on once: a call that came over a link can't name another target", null),
            Item("Device to device (not for agents): a Bridgit claims a link code with the claim token derived from it", "/v1/link/claim"),
            Item("Device to device: grants-log events and each side's halves of the link", "/v1/link/events"),
            Item("Device to device: a Bridgit's other links, names and directions only", "/v1/link/peers"),
            Item("Device to device: the other Bridgit disconnected, so this one marks it off too", "/v1/link/disconnect"),
        )),
        Revision(130, "1.8.0", "2026-11-07", listOf(
            Item("Device packs: device-family support Bridgit downloads on first use and keeps updated (signed, versioned, never downgraded); list and check them", "/v1/lan/packs"),
            Item("Speakers (UPnP/DLNA: WiiM, Denon Home and others) enroll as adapter upnp_av with no credentials; actions status, play {url, title}, pause, resume, stop, volume_get, volume_set {level}, mute_set {muted} take params", "/v1/lan/devices/action"),
        )),
        Revision(131, "1.8.0", "2026-11-07", listOf(
            Item("Bridgit Portal update prompts (iPhone, iPad, Mac): read each paired Portal's prompt switch, build and newest TestFlight build in GET /v1/owner/settings portal_updates; change the switch with owner configuration action portal.update_prompts {portal_id, enabled} or ask it to prompt now with portal.update_now {portal_id}. The Portal applies a request when it next syncs", "/v1/owner/settings"),
            Item("Portal to phone (not for agents): the snapshot carries a pending update request and the Portal reports its update state", "/v1/portal/updates"),
        )),
        Revision(132, "1.8.0", "2026-11-08", listOf(
            Item("Device Diagnostics and Network Diagnostics replace Level 1 and Level 2; foreground screen device_diagnostics replays Device Diagnostics on Home (same as Rerun) and network_diagnostics opens Network Diagnostics", "/v1/app/foreground"),
        )),
        Revision(133, "1.8.0", "2026-11-08", listOf(
            Item("Camera photos and arm work while the phone is locked; with the owner-only captures.locked_camera_approval on, a locked photo first waits up to 30 s for the owner's Allow on the lock screen (approval_declined, reason declined or no_answer)", "/v1/camera/snapshot"),
            Item("captures.locked_camera_approval: read in GET /v1/settings, set by owner configuration agents with POST /v1/owner/configuration/settings", "/v1/owner/configuration/settings"),
        )),
        Revision(134, "1.8.0", "2026-11-08", listOf(
            Item("Bug reports are now Issues Tracking: /v1/issues, /v1/issues/{id}, /v1/issues/{id}/status, /v1/issues/{id}/followup and /v1/issues/team-key (MCP issues_list, issues_get, issues_status, issues_followup, issues_file, issues_team_key); the list key is issues. /v1/feedback… still answers as a deprecated alias with the old keys", "/v1/issues"),
            Item("Team backend: file and follow issues at /v1/agent/issues (client_issue_id)", "/v1/issues/team-key"),
            Item("links.issues is the issue list; links.feedback is deprecated", null),
        )),
        Revision(135, "1.8.0", "2026-11-08", listOf(
            Item("Full-screen cards: presentation \"full_screen\" on an html card shows it edge to edge with every bar hidden; a fixed-size page is scaled to fit whole, never cropped. Stored and returned with the interaction; unknown values are invalid_parameter", "/v1/interactions"),
            Item("The post reply's display block says the exact canvas the page gets (viewport {width_px, height_px, width_dp, height_dp, density}) and flags aspect_mismatch when content_aspect or the page's viewport meta differs by more than 10 %", "/v1/interactions"),
            Item("Display geometry: size, density, aspect ratio, rotation and the full-screen and standard card canvases; health carries a short display block", "/v1/device/display"),
            Item("Device specs: a named Bridgit stores its static spec sheet (display, full-screen card size, cameras, CPU, memory, storage, sensors, model, OS) with Bridgit, on by default; ask GET <team backend>/v1/names/{name}/specs first for static specs, the phone only for live state. Read, switch off (which deletes the stored sheet) or refresh it", "/v1/owner/configuration/specs"),
        )),
        Revision(136, "1.8.0", "2026-11-09", listOf(
            Item("Links are listed from both ends: a hub lists its satellites too, and every link is listed with role, reachable (whether you may target it) and directions {in, out} with state and services; the other device's cameras, capabilities and endpoints only when reachable. GET /v1/satellites is the same list", "/v1/links"),
        )),
        Revision(137, "1.8.0", "2026-11-09", listOf(
            Item("Health says which build answers: build_type, distribution, build_tag (null for the real app), package and app_label, the name Android lists it under. Test builds tag every name Android shows, e.g. \"Bridgit (debug)\" and \"Bridgit screenshots (debug)\"", "/v1/health"),
        )),
        Revision(138, "1.8.0", "2026-11-09", listOf(
            Item("API registry: every API group with its fixed name and number, its calls, and the permission that governs it", "/v1/registry"),
            Item("API access: one permission per agent over the whole API, on by default. Off refuses every call with forbidden, reason api_access_off, while the agent stays paired. Read it as api_access in owner configuration access; a Super agent changes it with api_access on access/agents/{agent}. Access changes made through the API are logged as the agent that made them", "/v1/owner/configuration/access/agents/{agent}"),
        )),
        Revision(139, "1.8.0", "2026-11-09", listOf(
            Item("Outlet flow log: every LAN device enroll, action (relay_on, relay_off, status, device-pack actions) and removal is logged as it happens: a started entry before anything reaches the device, any bind or retry step, and the outcome (ok, failed or unknown_outcome with the error), all sharing one op_id, with the trigger (agent, transport, request id) and the device. Read your own with GET /v1/lan/devices/log; the log grant also sees them in GET /v1/log?kind=lan", "/v1/lan/devices/log"),
        )),
        Revision(140, "1.8.0", "2026-11-09", listOf(
            Item("Health has a listener block: state (listening, rebinding, waiting_for_tailscale, bind_failed, stopped), what it is bound to and the Tailscale addresses the phone has now, so an agent can tell a network problem from Bridgit not listening", "/v1/health"),
            Item("The connection ladder: what to do when a call fails, layer by layer (network, transport, app, auth, capability), with a result per layer to send with a bug report as diagnosis: ${Learn.CONNECTION_URL}", null),
        )),
        Revision(141, "1.8.0", "2026-11-09", listOf(
            Item("LAN device actions take their parameters beside device and action ({\"action\":\"volume_set\",\"level\":25}) as well as in params; each device's actions in GET /v1/lan/devices list their params (name, type, required, bounds) with an example body", "/v1/lan/devices/action"),
            Item("LAN device actions have their own rate limit (${RateLimiter.DEVICE_ACTIONS_PER_AGENT} a minute per agent) and rate_limited message, separate from captures", "/v1/lan/devices/action"),
        )),
        Revision(142, "1.8.0", "2026-11-09", listOf(
            Item("API latency telemetry: every call on every transport is timed from receipt to response; count, median, p95 and max per API, and slow calls with the device context they ran in (network type and metered, battery, power saver, thermal, app build, screen, calls in flight)", "/v1/log/latency"),
        )),
        Revision(143, "1.8.0", "2026-11-09", listOf(
            Item("Applet requests: a third issue kind, applet, asks for a new applet with its portal page. File it with an optional applet object (name, what_it_does, devices, data_used, portal_shows); it is always held for a person. List them with kind=applet", "/v1/issues"),
        )),
        Revision(144, "1.8.0", "2026-11-09", listOf(
            Item("Monitor resources: Bridgit's own CPU, memory, network, disk, storage and context switches over time, with hourly and daily summaries per build, thresholds and a level (ok, elevated, high). Off by default; health carries a short resource_monitor block", "/v1/device/resources"),
            Item("device.resource_monitor (off by default) and device.resource_monitor_interval (1s, 10s, 1m default, 1h): read in GET /v1/settings, changed with POST /v1/settings or POST /v1/owner/configuration/settings", "/v1/settings"),
        )),
        Revision(145, "1.8.0", "2026-11-09", listOf(
            Item("Link trust tiers: every link is own (Your Other Devices), associate (Devices of Associates: a person named by email, view/interactions/speaker/sounds only, ends within 90 days) or public (Devices in the Wild: view or interactions only, ends within 24 h, the owner allows each change). links[].tier, tier_here, tier_there, expires_at, associate_email, registration; portals[] and tiers[]", "/v1/owner/configuration/links"),
            Item("Make a code for a tier: POST …/links/codes {tier, access_ms, associate, associate_email}; the claim takes the code's tier and may only narrow it ({tier})", "/v1/owner/configuration/links/codes"),
            Item("Change a link's or Portal pairing's tier, end date or associate (narrowing works at once; widening a link needs the other Bridgit too)", "/v1/owner/configuration/links/{id}/tier"),
            Item("GET /v1/links entries say their tier", "/v1/links"),
            Item("New errors: link_expired, tier_not_allowed, link_approval_declined. Associate and public links have per-tier rate limits", null),
        )),
        Revision(146, "1.8.0", "2026-11-09", listOf(
            Item("People view: view \"people\" on a screenshot or camera photo finds the people on the phone (offline) and returns one crop around all of them, each person's whole visible body plus a little padding (padding 0-0.5). aspect best_fit (default) or a shape like \"20:9\", \"1:1\", \"4:5\". The reply's people_view gives the crop box in the source frame, each person's box, the returned size and aspect_ratio, and aspect_met. Nobody found: no_person_found, and nothing is returned or kept", "/v1/screen/capture"),
            Item("The same view, aspect and padding on camera photos; the crop is cut from a larger photo, then sized as asked", "/v1/camera/snapshot"),
        )),
        Revision(147, "1.8.0", "2026-11-09", listOf(
            Item("The permission home: every control agent, this device and every linked device with granted/total and the layer that decided each permission (specific, default, superpowers, baseline, unavailable), as on the Agents and Linked Devices tab", "/v1/owner/configuration/permissions"),
            Item("This device's profile (purposes, device_type, model) and its own switches: true/false sets one specifically, \"default\" returns it to the default its (type, purpose) pairs give it", "/v1/owner/configuration/permissions/device"),
            Item("A linked device's permissions here (portal viewer, hub, satellite), within what its pairing gave it; Superpowers required", "/v1/owner/configuration/permissions/linked/{id}"),
        )),
        Revision(148, "1.8.0", "2026-11-09", listOf(
            Item("The Bridgit Interface Guide: this agent's full briefing, built live for this device; the setup prompt is now a few lines that point here", "/v1/welcome"),
        )),
    )

    val CURRENT: Int = revisions.maxOf { it.revision }

    /** Revisions after [since] (0 = everything). */
    fun since(since: Int): List<Revision> = revisions.filter { it.revision > since }

    fun json(since: Int, appVersion: String): JSONObject = JSONObject()
        .put("api_revision", CURRENT).put("app_version", appVersion).put("since", since)
        .put("changes", JSONArray(since(since).map { r ->
            JSONObject().put("revision", r.revision).put("app_version", r.appVersion).put("date", r.date)
                .put("items", JSONArray(r.items.map { i -> JSONObject().put("what", i.what).put("endpoint", i.endpoint ?: JSONObject.NULL) }))
        }))
        .put("links", links())

    /** Where an agent learns the current API (API-09). */
    fun links(): JSONObject = JSONObject().put("guide", "/v1/guide").put("demo", "/v1/demo").put("openapi", "/v1/openapi.json")
        .put("services", "/v1/services").put("discover", "/v1/discover").put("start", "/v1/start").put("starter", CapabilityCatalog.START_URL).put("catalog", CapabilityCatalog.INDEX_URL).put("catalog_bulk_export", CapabilityCatalog.URL).put("capabilities", "/v1/capabilities").put("changes", "/v1/changes").put("samples", "/v1/samples").put("issues", "/v1/issues").put("feedback", "/v1/feedback")
        .put("applet_practices", "/v1/applets/practices").put("owner_features", "/v1/owner/features").put("resources", "/v1/resources").put("control_capabilities", "/v2/capabilities").put("learn", Learn.INDEX_URL).put("llms_txt", Learn.LLMS_URL).put("connection", Learn.CONNECTION_URL)

    /** health's "whats_new": null when the agent is up to date. */
    fun whatsNew(known: Int): Any = if (known >= CURRENT) JSONObject.NULL else JSONObject()
        .put("since", known).put("new_revisions", since(known).size)
        .put("url", "/v1/changes?since=$known")
        .put("summary", since(known).flatMap { it.items }.take(5).joinToString("; ") { it.what })
}
