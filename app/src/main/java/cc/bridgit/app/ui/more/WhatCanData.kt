package cc.bridgit.app.ui.more

import cc.bridgit.app.api.ApiDocs

/**
 * "What can Bridgit do?" (owner request, 1.4.2): one card per feature with a use case,
 * covering everything in the spec, marked built or with its planned phase (section 16).
 * Plain Kotlin so the list is easy to keep in step with the spec.
 */
object WhatCanData {
    /** Existing public anchors and bundled artwork survive display-copy changes. */
    fun stableTitle(title: String): String = when (title) {
        "Add a control agent in a minute" -> "Add an agent in a minute"
        "One key per control agent" -> "One key per agent"
        "Control agent notes" -> "Agent notes"
        "Control agents learn what’s new" -> "Agents learn what’s new"
        "Control agents learn what's new" -> "Agents learn what's new"
        "Share to a control agent" -> "Share to an agent"
        "Control agent permissions" -> "Per-agent permissions"
        else -> title
    }

    enum class Stage(val label: String) {
        NOW("Available now"), LOCAL("In this development build"), P2("Next: P2"), P3("Planned: P3"), P4("Planned: P4"), P5("Planned: P5"),
        P6("Planned: P6"), LATER("Later"), IDEA("Needs your OK"),
    }

    /** ABILITY = something an agent can do with the phone; MECHANIC = how Bridgit works and keeps you in control. */
    enum class Kind { ABILITY, MECHANIC }

    data class Feature(val stage: Stage, val title: String, val what: String, val useCase: String, val ref: String,
                       val kind: Kind = Kind.ABILITY)

    /**
     * The choices an agent has for each feature, shown as "Options" on its card (owner request).
     * Built features list what the API accepts today; planned ones list what the spec plans.
     */
    val options: Map<String, List<String>> = mapOf(
        "Find and interact with network devices" to listOf(
            "Find devices and services on this phone's local Wi-Fi or Ethernet network",
            "Enroll supported devices using credentials you authorize",
            "Read OpenWrt router information and DHCP leases; switch Shelly Gen1 relays",
            "Connect supported Tapo cameras through the separate camera permissions",
            "Discovery, enrollment and control each need your permission and a control agent grant",
            "Finding a device does not mean Bridgit can control it"),
        "Take a photo" to listOf(
            "Back or front camera",
            "Zoom: 0.5× (ultra-wide) up to the phone's maximum (telephoto)",
            "Flash on or off",
            "Size: sized for AI (1568 px), thumbnail (512 px), large (2560 px) or original",
            "Or an exact width or long edge, and JPEG quality",
            "Full resolution, up to the 50 MP sensor",
            "As JSON with the picture inside, or as plain JPEG bytes",
            "Never saved to your gallery"),
        "Record a short clip" to listOf(
            "Length: 1 to 120 whole seconds",
            "Comes back as an M4A (AAC) audio file, 44.1 kHz mono",
            "Not kept on the phone"),
        "Your latest screenshot or photo" to listOf(
            "Which: any image, the newest screenshot, or the newest camera photo",
            "Wait up to 55 seconds for the next one you take",
            "Size: sized for AI (default), thumbnail, large or original",
            "As JSON or as plain JPEG bytes, with when it was taken and the album"),
        "Find photos in your gallery" to listOf(
            "List the newest 1 to 100 images (no pictures, just details)",
            "Fetch one image at any of the sizes",
            "Works with full access or only the photos you picked"),
        "Questions and notices to you" to listOf(
            "A notice (one-way) or a question",
            "Buttons (up to 6), a text reply, a choice list, or a link",
            "An image with the message",
            "Normal or urgent; read aloud if you allow it",
            "Expires after 1 minute to 7 days",
            "The control agent waits for your answer or gets a callback"),
        "Rich HTML cards" to listOf(
            "A small HTML page with its own layout, charts and forms",
            "Runs on the phone with no internet access",
            "Sends one answer back to the control agent (up to 16 KB of data)",
            "Images inside the card only (data: images), up to 256 KB of HTML",
            "Bridgit's own header shows which control agent sent it; you can always dismiss it"),
        "Live text panel" to listOf(
            "Lower half of the screen, scrolls as text arrives",
            "Pause scrolling to read, then resume",
            "The control agent marks it finished; you close it whenever you like",
            "Keeps the last 200 KB of text"),
        "Use Bridgit over MCP" to listOf(
            "Same key, same switches, same log as the normal API",
            "Every endpoint the control agent may use becomes a tool, with its options",
            "Photos and screenshots come back as images"),
        "Send a screenshot" to listOf(
            "A private copy: nothing is saved and there's no flash",
            "Turn on Bridgit's screenshot service once (Hardware access), then Screenshots in Feature enablement",
            "Same size choices as photos",
            "At most one every 2 seconds and 60 an hour per control agent"),
        "Open a link or app" to listOf("Web links, maps, phone numbers (dialler only), email", "Or open an app by name", "Only when you tap it"),
        "Phone status and context" to listOf("Battery: level, charging and plug type, health, temperature, voltage, time to full, estimated time to empty while unplugged, battery saver", "Low, critical, charging and battery saver alerts to control agents", "Screen on/off and lock state", "Ringer, Do Not Disturb and media volume", "Wi-Fi/mobile transport and metered status", "Context: in use when screen is interactive and unlocked; otherwise unknown"),
        "Location details" to listOf(
            "Latitude and longitude (x, y)",
            "Altitude (z) in metres",
            "Accuracy in metres, speed and heading",
            "A fresh fix (up to 15 s) or the last known one at once",
            "High accuracy or battery-friendly",
            "Street address, if you turn on address lookup"),
        "Flash the light" to listOf("On for 1 second to 10 minutes; always turns itself off", "Turn off now", "Ask whether it's on"),
        "Phone controls" to listOf("DND: off, priority, alarms or none; none can mute alarms", "Ringer: normal, vibrate or silent; media/ring/alarm volumes", "Brightness level or automatic brightness", "Screen timeout, 15 seconds to 30 minutes", "Battery saver on or off, after a one-time USB grant", "Optional 1–1440 minute restoration; owner Undo", "Later manual changes are preserved"),
        "App settings" to listOf("Read every setting: feature switches, privacy, log, alerts, theme, speaker limit, notification channels, Android access and the phone's sound and display",
            "Each setting says its type, allowed values and whether you may change it, and why not",
            "Control agents with Super Powers change several at once once the owner allows it; one bad value changes nothing",
            "Never by control agents: the App settings switch, the listener and port, internet callbacks, alert address, wake words, control agent permissions"),
        "Find my phone" to listOf("Ring and vibrate for 1–60 seconds", "Respects Android sound and Do Not Disturb settings", "Stop from Files and Android access or the ongoing notification"),
        "Sensors" to listOf("Light, pressure and proximity where available", "Steps since reboot (Physical activity grant)", "Fresh rotation-vector orientation and accelerometer values", "Unavailable or stale sensors are reported explicitly"),
        "Videos and downloads" to listOf("List accessible videos", "Retrieve a video up to 40 MB, or a still frame", "Choose individual Downloads files on the phone for one control agent"),
        "Files to your phone" to listOf("25 MB per file; 100 files / 100 MB total", "Private storage, retained 30 days", "Owner opens, exports to a selected location, or deletes", "No automatic execution or automatic Downloads save"),
        "Share to a control agent" to listOf("Selected content files, text and links from Android sharing", "Choose which permitted control agent receives each share", "Control agent reads its own transfer history", "Up to ten selected files, each limited to 25 MB"),
        "Short video clips" to listOf("1 to 30 seconds", "Back or front camera", "With or without sound", "720p or 1080p"),
        "Read text on the phone" to listOf("Bundled Latin-script recognition", "From an accessible gallery image or supplied image bytes", "Returns recognized text; no cloud recognition"),
        "Transcribe clips on the phone" to listOf("Transcript with the audio, or the transcript only"),
        "Screen sharing as a fallback" to listOf("Allow once, for 1 hour or for 16 hours, or deny", "Stop any time from the status bar"),
        "App usage" to listOf("Foreground-time summaries for up to seven days", "Requires Android Usage access", "Android buckets may extend outside the requested interval"),
        "Health Connect" to listOf("Read permitted steps, sleep sessions or heart-rate samples", "No writes, background reads, workouts or retained health records", "At most 100 source records and seven days per request", "Source records may overlap; no deduplicated total claim"),
        "Read notifications" to listOf("Explicit feature switch and Android notification access", "At most 100 active notifications since listener connection", "RAM only; cleared when disabled, paused or disconnected", "No notification-history archive or long-poll in this build"),
        "Copy to clipboard" to listOf("Up to 10000 characters of text", "Every copy requires your foreground Copy tap", "Five-minute approval expiry", "Text is marked sensitive to suppress clipboard previews"),
        "Play an audio file" to listOf("An MP3, M4A, OGG, WAV or FLAC file (up to 25 MB), or a public https link", "Or text read aloud with the phone's own voice: language, speed and pitch",
            "Replace what's waiting, stop the current one, or queue", "Never louder than your volume ceiling (default 70 %)", "Silent when your phone is on silent, vibrate, Do Not Disturb or a call", "Stop clears everything"),
        "Weather" to listOf("Now: temperature, sky, rain, wind, humidity, cloud and pressure", "The next 0 to 48 hours, a 12-hour outlook and the next 24 hours' low and high",
            "Where the phone is (needs Location too), or any place the control agent names", "Your position is rounded to about 1 km before it's sent; forecast from MET Norway", "Can run on a schedule, e.g. every hour"),
        "Scheduled actions" to listOf("Any action the phone can take: photos, sounds, notices, downloads, and more", "Once, at a list of times, every N minutes (with a start and a count), daily, or right after another action",
            "Send results such as photos or recordings to a web address", "You approve each new list once; pause or remove any time", "Up to 100 actions; saved files 100 MB, 30 days"),
        "City tour" to listOf("History, architecture, environment, food, art, or anything interesting", "Updates every 30 s, 1, 2 or 5 minutes",
            "Location, heading (compass) and new photos each time", "Ask out loud or type a question", "Pause, skip, end; ends by itself after 4 hours"),
        "Everything is logged" to listOf("Filter by control agent, type, endpoint, result, level, time or text", "Follow it live", "Stats per control agent and endpoint", "Export as JSONL or CSV", "\"All entries\" or \"only their own\" for control agents"),
        "Report a bug or ask for a feature" to listOf("Last screen, up to 5 screenshots and the recent log attached", "Turn off \"Auto-fix\" to keep it for a person", "Statuses: new, seen, in progress, fixed, won't fix, duplicate", "You confirm a fix or say it's still broken"),
    )

    /**
     * The calls each card uses, for its section on the public API page (ApiDocs, card Share links).
     * Keys are card titles; ApiDocsTest checks every title and call exists.
     */
    val endpoints: Map<String, List<String>> = mapOf(
        "Find and interact with network devices" to listOf("POST /v1/lan/discover", "GET /v1/lan/devices", "POST /v1/lan/devices/enroll", "POST /v1/lan/devices/action", "POST /v1/lan/devices/remove", "GET /v1/app-devices", "POST /v1/app-devices", "POST /v1/lan-cameras/probe", "POST /v1/lan-cameras/audio/record", "POST /v1/lan-cameras/audio/play", "POST /v1/lan-cameras/talk-account"),
        "Add a control agent in a minute" to listOf("GET /v1/health", "POST /v1/pair", "GET /v1/tools/bridgit"),
        "One key per control agent" to listOf("GET /v1/health"),
        "Take a photo" to listOf("POST /v1/camera/snapshot"),
        "Record a short clip" to listOf("POST /v1/mic/record"),
        "Your latest screenshot or photo" to listOf("GET /v1/image/last"),
        "Send a screenshot" to listOf("POST /v1/screen/capture"),
        "Find photos in your gallery" to listOf("GET /v1/gallery/recent", "GET /v1/gallery/photo/{id}"),
        "Everything is logged" to listOf("GET /v1/log", "GET /v1/log/{id}", "GET /v1/log/stats", "GET /v1/log/export"),
        "Control agent notes" to listOf("POST /v1/log"),
        "The API explains itself" to listOf("GET /v1/guide", "GET /v1/openapi.json", "GET /v1/capabilities"),
        "Control agents learn what's new" to listOf("GET /v1/changes"),
        "Report a bug or ask for a feature" to listOf("GET /v1/issues", "GET /v1/issues/{id}", "POST /v1/issues/{id}/status", "POST /v1/issues", "GET /v1/issues/team-key"),
        "You stay in control" to listOf("GET /v1/capabilities"),
        "Questions and notices to you" to listOf("POST /v1/interactions", "GET /v1/interactions", "GET /v1/interactions/{id}", "DELETE /v1/interactions/{id}", "POST /v1/interactions/{id}/renotify", "GET /v1/events"),
        "Events and callbacks" to listOf("GET /v1/events", "POST /v1/callback/test"),
        "Rich HTML cards" to listOf("POST /v1/interactions", "GET /v1/interactions/{id}"),
        "Live text panel" to listOf("POST /v1/interactions", "POST /v1/text/stream", "DELETE /v1/interactions/{id}"),
        "Use Bridgit over MCP" to listOf("POST /v1/mcp"),
        "Open a link or app" to listOf("POST /v1/interactions"),
        "Location details" to listOf("GET /v1/location"),
        "Weather" to listOf("GET /v1/weather", "POST /v1/schedule"),
        "City tour" to listOf("GET /v1/tour", "DELETE /v1/tour", "GET /v1/events", "POST /v1/speaker/say", "POST /v1/speaker/play", "GET /v1/gallery/photo/{id}"),
        "Scheduled actions" to listOf("POST /v1/schedule", "GET /v1/schedule", "POST /v1/schedule/run/{id}", "GET /v1/schedule/assets/{name}", "DELETE /v1/schedule"),
        "Play an audio file" to listOf("POST /v1/speaker/play", "POST /v1/speaker/say", "GET /v1/speaker", "DELETE /v1/speaker"),
        "Inbox" to listOf("GET /v1/interactions", "GET /v1/files", "GET /v1/shares"),
        "Phone status and context" to listOf("GET /v1/phone/status", "GET /v1/phone/context"),
        "Flash the light" to listOf("GET /v1/flashlight", "POST /v1/flashlight"),
        "Phone controls" to listOf("POST /v1/phone/dnd", "POST /v1/phone/sound", "POST /v1/phone/brightness", "POST /v1/phone/power", "POST /v1/phone/undo"),
        "App settings" to listOf("GET /v1/settings", "POST /v1/settings"),
        "Find my phone" to listOf("POST /v1/phone/ring", "DELETE /v1/phone/ring"),
        "Sensors" to listOf("GET /v1/sensors"),
        "Videos and downloads" to listOf("GET /v1/gallery/videos/recent", "GET /v1/gallery/video/{id}", "GET /v1/downloads"),
        "Files to your phone" to listOf("POST /v1/files", "GET /v1/files"),
        "Share to a control agent" to listOf("GET /v1/shares", "GET /v1/shares/{id}"),
        "Listen for any sounds" to listOf("GET /v1/sounds", "GET /v1/sounds/clip/{id}", "GET /v1/events"),
        "Wake words" to listOf("GET /v1/listen", "GET /v1/listen/clip/{id}", "GET /v1/events"),
        "Short video clips" to listOf("POST /v1/camera/video"),
        "Read text on the phone" to listOf("POST /v1/ocr"),
        "Transcribe clips on the phone" to listOf("POST /v1/mic/record"),
        "Screen sharing as a fallback" to listOf("POST /v1/screen/grant", "GET /v1/screen/grants", "POST /v1/screen/share/capture", "DELETE /v1/screen/grant"),
        "App usage" to listOf("GET /v1/usage"),
        "Health Connect" to listOf("GET /v1/health/records"),
        "Read notifications" to listOf("GET /v1/notifications"),
        "Copy to clipboard" to listOf("POST /v1/clipboard", "GET /v1/clipboard/{id}"),
        "Control agent permissions" to listOf("GET /v1/capabilities"),
    )

    val all = listOf(
        Feature(Stage.NOW, "Find and interact with network devices",
            "Discover IP addresses and services on this Android device’s direct LAN, enroll supported devices, read OpenWrt router DHCP leases and switch Shelly Gen1 relays. Each permission stays under your control.",
            "Find a desk lamp on your home network and let your control agent switch its supported relay. Unknown devices stay observations; camera photos keep their separate permission.", "/v1/lan/devices"),
        // ---- Available now --------------------------------------------------------------------------
        Feature(Stage.NOW, "Add a control agent in a minute",
            "Bridgit makes a setup prompt with the control agent's own key, the address and the rules. Paste it into any AI control agent and it can use the phone straight away.",
            "You add Claude: name it, copy the prompt, paste it into Claude. The screen turns to \"Claude connected\" as soon as it calls.", "6.2", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "One key per control agent",
            "Every control agent has its own key. Rotate it or remove the control agent in one tap; the old key stops at once and the control agent is told why.",
            "You pasted Grok's prompt into the wrong chat. Rotate Grok's key; the leaked one is dead in a second.", "AGT-12", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Take a photo",
            "A control agent can take a photo with the front or back camera. Android's green dot and a Bridgit notification show every time.",
            "From your laptop: \"Is the stove off?\" Your control agent takes a photo of the kitchen and tells you.", "8.2"),
        Feature(Stage.NOW, "Record a short clip",
            "A control agent can record 1 to 120 seconds of audio and get it back as a file.",
            "\"Listen to the next 30 seconds of the meeting and give me the action items.\"", "8.4"),
        Feature(Stage.NOW, "Your latest screenshot or photo",
            "One call returns the newest screenshot or camera photo, sized for an AI to read. It can also wait for the next one you take.",
            "You screenshot an error on your phone; your control agent already has it and explains the fix.", "8.6"),
        Feature(Stage.NOW, "Find photos in your gallery",
            "Control agents can list recent images and fetch one at the size they need. Bridgit never edits or deletes anything.",
            "\"Find the receipt photo from this morning and add it to my expenses.\"", "8.5"),
        Feature(Stage.NOW, "Everything is logged",
            "Every request, switch change and control agent note is in Activity on the phone. Control agents can read and follow the log too, so they know what they did.",
            "Codex checks yesterday's log to see why a photo failed; you scroll Activity to see who did what.", "10", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Control agent notes",
            "Control agents can write short notes into the log.",
            "Your fridge-check control agent writes \"milk low\" so you see it in Activity.", "LOG-11", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "The API explains itself",
            "Control agents read a live guide and an OpenAPI file that list what's switched on, every call and every error.",
            "A brand-new control agent reads the guide and learns everything without a manual.", "API-09", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Control agents learn what's new",
            "Every reply says which API revision Bridgit speaks. After an update, control agents are told there's something new until they read the list of changes, so they pick up new features by themselves.",
            "Bridgit gains camera zoom; the next time Grok calls, it reads what's new and starts using zoom.", "API-14", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Report a bug or ask for a feature",
            "From any screen: the last screen is attached, plus screenshots and the recent log. Control agents in Bridgit Fixer Mode read reports and mark them fixed; you confirm or say it's still broken.",
            "The app misbehaves; you report it in 10 seconds. Claude picks it up, fixes it and marks it fixed.", "11", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Check for updates",
            "Bridgit finds a new version, checks it really is Bridgit, and lets Android install it after you confirm.",
            "A fix ships; your phone offers the update the next time you open Bridgit.", "12.14", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "You stay in control",
            "Every feature starts off and has its own switch. Pause listening in one tap. Access shows each Android permission with one button to the right page.",
            "Going into a meeting: turn off Camera and Microphone from Home in two taps.", "12.8", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Only over Tailscale",
            "Bridgit answers only on your private Tailscale network, never the open internet.",
            "Your control agents on your Mac and your server can reach the phone; nobody else can.", "ARC-04", kind = Kind.MECHANIC),
        // ---- P2 ----------------------------------------------------------------------------------------
        Feature(Stage.NOW, "Questions and notices to you",
            "Control agents send you a notice, or a question with buttons, choices or a text reply, and get your answer within a second of your tap. They can also read it aloud.",
            "\"Pay this invoice? $11.99\" [Pay] [Not now]. You tap Pay on the notification; the control agent pays.", "8.13"),
        Feature(Stage.NOW, "Rich HTML cards",
            "Instead of plain buttons, a control agent can send a small HTML page (charts, forms, choices) that runs safely on the phone with no internet access.",
            "Your travel control agent shows three flights as cards with prices and a map; you tap one to book.", "CAP-34"),
        Feature(Stage.NOW, "Live text panel",
            "A control agent streams text into the lower half of the screen and it scrolls as it arrives, like the transcript of an AI session. Pause the scrolling or close it any time.",
            "Claude Code is fixing a bug on your laptop; you watch what it's doing on the phone while you make coffee.", "D26"),
        Feature(Stage.NOW, "Use Bridgit over MCP",
            "Control agents that speak MCP (Claude Code, Codex and others) add Bridgit as a tool server with their own key, and every feature appears as a tool.",
            "Add Bridgit to Claude Code once; from then on \"take a photo of my desk\" just works.", "API-15", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Inbox",
            "One place for every question, notice, file and share between you and your control agents.",
            "See what Grok asked you this morning and what you answered.", "12.6", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Send a screenshot",
            "A control agent sees what's on your screen right now, with no prompt each time; a notification shows every capture.",
            "\"What does this error on my phone mean?\" The control agent takes a screenshot and reads it.", "8.10"),
        Feature(Stage.NOW, "Events and callbacks",
            "Control agents get told the moment something happens: a question answered, a new screenshot, a switch changed.",
            "Your control agent waits for your answer without asking again and again.", "9", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Open a link or app",
            "A control agent can suggest a link or app; it opens when you tap it.",
            "\"Here's the boarding pass\" [Open in Wallet].", "8.20"),
        Feature(Stage.NOW, "Quick Settings tile",
            "Pause or resume Bridgit from the notification shade.", "Pull down, tap Bridgit, and control agents can't reach the phone until you tap again.", "UI-23", kind = Kind.MECHANIC),
        // ---- P3 ----------------------------------------------------------------------------------------
        Feature(Stage.NOW, "Phone status and context",
            "Battery (level, charging, health, temperature, battery saver), network, ringer, Do Not Disturb and screen/lock status. Control agents hear when the battery runs low or starts charging. An unlocked interactive screen is reported as in use.",
            "Before calling you, a control agent checks whether your phone is in use or on silent.", "8.7"),
        Feature(Stage.NOW, "Location details",
            "Where the phone is, on request only: latitude and longitude (x, y), altitude (z), accuracy, speed and heading, and how old the fix is. Address lookup if you turn it on. No tracking unless you choose it.",
            "\"How long until I'm home?\" The control agent checks where you are and the traffic.", "8.8"),
        Feature(Stage.NOW, "Weather",
            "The forecast where the phone is, or anywhere the control agent names: now, the next hours and today's low and high, from MET Norway. Your position is rounded to about 1 km first. Control agents can check it on a schedule.",
            "Every morning at 7 your control agent checks the weather and tells you to take an umbrella.", "WTH-01"),
        Feature(Stage.NOW, "Flash the light",
            "Turn the torch on for up to ten minutes, with automatic off and an owner Stop button.",
            "Your door-camera control agent flashes the light so you know someone is at the door.", "CAP-21"),
        Feature(Stage.NOW, "Phone controls",
            "Do Not Disturb, ringer, volume, brightness, screen timeout and battery saver, put back as they were afterwards.",
            "Bedtime routine: your control agent turns on Do Not Disturb until 7 am.", "8.9"),
        Feature(Stage.NOW, "App settings",
            "Every Bridgit setting and the phone's sound and display settings in one place. Any control agent can read them; control agents with Agent Super Powers can change them once you allow it. Control agents can never stop the listener, change their own permissions or turn this switch back on.",
            "\"Switch Bridgit to dark mode, keep the log for 90 days and turn the media volume down to 30%.\"", "SET-01"),
        Feature(Stage.NOW, "City tour",
            "A guided walk: you pick the kind of tour, and every minute your control agent gets where you are, which way you face and the photos you just took. It tells you about what's around you, and you can ask it questions out loud.",
            "Pick \"Architecture\" and walk; when you photograph a church, your control agent tells you who built it and why it looks that way.", "8.25"),
        Feature(Stage.NOW, "Find my phone",
            "Ring and vibrate for up to a minute, respecting your sound and Do Not Disturb settings.", "Phone lost in the sofa: ask any control agent to ring it.", "8.14"),
        Feature(Stage.NOW, "Sensors",
            "Light, pressure, steps, motion and orientation, one reading each.", "A control agent notices it's dark and you're still, and holds non-urgent messages.", "8.21"),
        Feature(Stage.NOW, "Videos and downloads",
            "Find recent videos, take a frame from one, and pick a file from Downloads for a control agent.",
            "\"Get the frame at 0:12 from the video I just took.\"", "8.5"),
        // ---- P4 ----------------------------------------------------------------------------------------
        Feature(Stage.NOW, "Files to your phone",
            "Control agents send PDFs, images and other files; they land in the Inbox and can be saved to Downloads.",
            "Your accounting control agent sends this month's invoice PDF to your phone.", "8.11"),
        Feature(Stage.NOW, "Share to a control agent",
            "Share a photo, link or text from any app straight to Grok or Claude.",
            "Share a restaurant link to your control agent: \"Book this for Friday.\"", "8.12"),
        // ---- P5 ----------------------------------------------------------------------------------------
        Feature(Stage.NOW, "Listen for any sounds", "One control agent hears when the room gets loud, with up to 10 s of audio if you allow it. At most one every 30 s. Off by default.", "\"Tell me if the baby cries or someone knocks.\"", "8.23"),
        Feature(Stage.NOW, "Wake words", "Up to 10 words you choose, each sent to one control agent. Recognised offline on the phone; only what you say after one leaves it. Off by default.", "\"Hey bridget, remind me to water the plants.\"", "8.23"),
        Feature(Stage.NOW, "Short video clips", "1 to 30 seconds of 720p or 1080p MP4. Audio is off by default and needs a microphone grant.", "\"Record the dishwasher noise so I can describe it to the repair shop.\"", "8.3"),
        Feature(Stage.NOW, "Read text on the phone",
            "Bundled Latin-script text recognition runs on the phone; only the result leaves it.", "Read the serial number from a photo without uploading the picture.", "8.15"),
        Feature(Stage.NOW, "Transcribe clips on the phone",
            "Request transcribe=true on a microphone clip. Requires Android on-device recognition and an installed offline language; no cloud fallback.", "Dictate a note for 20 seconds; the control agent gets the words.", "CAP-08"),
        Feature(Stage.NOW, "Screen sharing as a fallback",
            "If you'd rather not turn on Accessibility, approve screen access once, for 1 hour or for 16 hours.", "Help your control agent debug an app for the next hour.", "CAP-26b"),
        // ---- P6 ----------------------------------------------------------------------------------------
        Feature(Stage.NOW, "App usage", "Read foreground-time summaries after granting Android Usage access. Android buckets can extend past the requested interval.", "Weekly review: \"You spent 6 hours in email this week.\"", "8.16"),
        Feature(Stage.NOW, "Health Connect", "Read permitted steps, sleep and heart-rate source records. No health writes; maximum 100 records per query.", "Your coach control agent adjusts tomorrow's plan to last night's sleep.", "8.17"),
        Feature(Stage.NOW, "Read notifications", "Read up to 100 active notification texts received since the listener connected. Explicit notification access required; memory only.", "\"Summarise everything that pinged me during the meeting.\"", "8.18"),
        Feature(Stage.NOW, "Copy to clipboard", "A control agent puts text on your clipboard after you approve it.", "Your control agent copies the 2-factor backup code so you can paste it.", "8.19"),
        // ---- Later / needs a decision -------------------------------------------------------------------
        Feature(Stage.NOW, "Control agent permissions", "Choose what each control agent may use, for example photos for Claude but not for Grok.",
            "Let your shopping control agent see the gallery but never the camera.", "15", kind = Kind.MECHANIC),
        Feature(Stage.IDEA, "Reach Bridgit without Tailscale (relay)",
            "Control agents that can't join Tailscale, like hosted assistants, leave jobs in an encrypted mailbox; a push wakes the phone.",
            "Muse, which runs on Meta's servers, takes a photo for you.", "4.4, Q10", kind = Kind.MECHANIC),
        Feature(Stage.NOW, "Play an audio file",
            "A control agent sends a sound, a voice message or text to read out, and the phone plays it on its speaker. Newer audio from the same control agent replaces what's waiting, so it keeps up with you.",
            "Your reminder control agent plays \"Time to leave for the airport\" in its own voice.", "new idea"),
        Feature(Stage.NOW, "Scheduled actions",
            "A control agent hands the phone a list of actions and when to run them, once. After you approve it, the phone runs them by itself, so the control agent doesn't have to wake up for each one.",
            "Every 7 minutes from 4:34, three times: take a photo and send it to the control agent's server.", "CAP-57"),
        Feature(Stage.IDEA, "Messages, contacts, calendar, calls",
            "Read and, with your approval each time, send.", "\"Text Sam I'm 10 minutes late\" — you approve on the phone.", "8.22, Q1"),
        Feature(Stage.IDEA, "Location history and places",
            "Optional history kept on the phone, and \"tell me when I get home\".", "Grok reminds you to water the plants when you arrive home.", "CAP-20a, Q14"),
    )

    /** The cards as the public API page (ApiDocs) shows them. */
    fun apiCards(): List<ApiDocs.Card> = all.map {
        ApiDocs.Card(it.title, it.stage.label, it.kind == Kind.MECHANIC, it.what, it.useCase,
            options[it.title].orEmpty(), endpoints[it.title].orEmpty(), it.ref)
    }
}
