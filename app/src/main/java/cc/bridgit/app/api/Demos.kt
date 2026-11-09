package cc.bridgit.app.api

/**
 * `GET /v1/demo`: the one demo entry point. Part 1 is the fixed first-run tour (site-content/public/demo/SKILL.md,
 * copied into the APK at build time), part 2 is a menu generated live like the guide: a few questions on a card,
 * then the demos that fit the answers. Every demo ends with a card on the phone, and each is marked with
 * whether it works right now.
 */
object Demos {
    /** The same first-run tour, hosted on the website for agents that install skills from a URL. */
    const val SKILL_URL = "https://bridgit.cc/demo/SKILL.md"

    /** The first-run tour's file name in the APK's assets (copied from site-content/public/demo/SKILL.md). */
    const val TOUR_ASSET = "demo-first-run.md"

    /** One demo: [capability] decides whether it can run now, [calls] are the endpoints it uses. */
    data class Demo(val title: String, val capability: String, val goodFor: Set<String>, val pitch: String,
                    val steps: List<String>, val calls: List<String>)

    // Interests the owner can pick in question 2; demos are tagged with the ones they suit.
    private const val DECIDE = "decide"
    private const val SEE = "see"
    private const val VOICE = "voice"
    private const val PHONE = "phone"

    val all = listOf(
        Demo("Ask me with buttons", "interactions", setOf(DECIDE), "A question pops up on the phone; one tap answers it and you get the answer back.",
            listOf("POST /v1/interactions {\"kind\":\"question\",\"title\":\"<a real yes/no from their day>\",\"controls\":[{\"type\":\"button\",\"id\":\"yes\",\"label\":\"Yes\",\"style\":\"primary\"},{\"type\":\"button\",\"id\":\"no\",\"label\":\"Not now\"}]}",
                "GET /v1/interactions/{id}?wait=55 returns the answer; a line back to the owner shows what arrived.",
                "The owner can reopen it from Interactions and change the answer: interaction.answered arrives again with resubmission=true."),
            listOf("POST /v1/interactions", "GET /v1/interactions/{id}")),
        Demo("Pick from a list, or type a reply", "interactions", setOf(DECIDE), "Choices and free-text answers, for when yes/no isn't enough.",
            listOf("POST /v1/interactions with a choice control (2-8 options) and a text control, e.g. \"Where should we eat?\" with three real options and a \"Something else?\" box.",
                "The answer leads to a notice card that acts on the pick: \"Booked for 7 at <place>?\" or the next step."),
            listOf("POST /v1/interactions", "GET /v1/interactions/{id}")),
        Demo("A card you design", "interactions", setOf(DECIDE, SEE), "Your own HTML on their phone: a slider, a mini form, a chart, a checklist.",
            listOf("POST /v1/interactions {\"kind\":\"html\",\"title\":\"...\",\"html\":\"<a full page>\"}. GET /v1/samples?id=html-checklist is a complete one (checkboxes, a slider, a send button): change its CARD object and post it.",
                "The page runs offline and answers once with Bridgit.respond(JSON.stringify({...})); the answer arrives as answer.data, and bridgit:answered on document lets the page confirm.",
                "Ideas that fit the owner: a packing checklist for their trip, a 1-10 mood slider, a chart of shared work."),
            listOf("POST /v1/interactions")),
        Demo("Live text panel", "interactions", setOf(DECIDE, SEE), "Watch you work: text scrolls on the phone as you write it.",
            listOf("POST /v1/interactions {\"kind\":\"text\",\"title\":\"<what you're doing>\"}",
                "POST /v1/text/stream {\"interaction_id\":\"<id>\",\"append\":\"...\"} a line at a time during a real task, then {\"done\":true}."),
            listOf("POST /v1/interactions", "POST /v1/text/stream")),
        Demo("Talk out loud", "speaker", setOf(VOICE), "The phone speaks your words, so they can hear you from across the room.",
            listOf("POST /v1/speaker/say {\"text\":\"<a short, friendly line>\"}. A reply with \"status\":\"silenced\" means the phone is on silent; a card says so.",
                "Then a question card with \"speak\":true, read aloud as it arrives: \"Did you hear me?\" with Yes / No buttons."),
            listOf("POST /v1/speaker/say", "POST /v1/interactions")),
        Demo("Camera control", "camera", setOf(SEE), "You take the photo yourself: pick the lens, zoom in, fire the flash, then show them what you saw.",
            listOf("The owner points the phone at something (a shelf, the view, their desk).",
                "POST /v1/camera/snapshot {\"camera\":\"rear\",\"zoom\":0.5}, then {\"zoom\":1}, then {\"zoom\":2} (or 5 on a telephoto phone). The zoom range is in /v1/capabilities after the first photo.",
                "The most zoomed shot goes on the html photo card (GET /v1/samples?id=html-photo-check) or a question card (\"image\":{\"data_base64\":...}) with one line on what changed between shots, and buttons Zoom more / Front camera / Done; the tap picks the next shot."),
            listOf("POST /v1/camera/snapshot", "POST /v1/interactions")),
        Demo("Tell me when something changes", "camera", setOf(SEE, PHONE), "The phone keeps an eye on a spot (a door, a desk, the dog's bed) and tells you when the picture changes, checked on the phone for free.",
            listOf("The owner props the phone up, screen on and unlocked, facing something that will change: a doorway, their desk, a pet bed.",
                "POST /v1/watches {\"data\":[\"camera\"],\"model\":\"none\",\"interval_s\":30,\"expires_in_s\":600,\"label\":\"Demo: something changed\"}. The first photo is the reference; then they wave a hand or walk past (allow up to a minute).",
                "The watch.matched event (change.largest_patch_share) arrives in GET /v1/events?wait=55. POST /v1/camera/snapshot and show it on a question card with one line on what changed. Tell them: with \"model\":\"clef\" and a question (\"is someone at the door?\") the phone asks Clef only when the picture changes (${Learn.DECISIONS_URL}). The watch ends after one callback."),
            listOf("POST /v1/watches", "GET /v1/events", "POST /v1/camera/snapshot", "POST /v1/interactions")),
        Demo("Show me something", "gallery", setOf(SEE), "They take a photo or screenshot the usual way; you see it the moment it's saved.",
            listOf("The newest image id comes from GET /v1/image/last?type=any; then the owner snaps a photo or screenshot of anything.",
                "GET /v1/image/last?type=any&since=<that id>&wait=55 returns it as soon as it lands.",
                "A question card shows it (\"image\":{\"gallery_id\":<id>}) with a read of it (a receipt, a plant, an error message) and two buttons for possible next steps."),
            listOf("GET /v1/image/last", "POST /v1/interactions")),
        Demo("Read the text in a photo", "ocr", setOf(SEE), "Text recognised on the phone itself; the image never leaves it for a cloud service.",
            listOf("After a photo of a sign, label or page: POST /v1/ocr {\"gallery_id\":<id>} (or data_base64).",
                "A question card shows what it says, in their language, with buttons Translate / Summarise / Done."),
            listOf("POST /v1/ocr", "POST /v1/interactions")),
        Demo("A short video", "video", setOf(SEE), "A few seconds of video from either camera.",
            listOf("POST /v1/camera/video {\"camera\":\"rear\",\"duration_seconds\":5}.",
                "A notice card says what moved, second by second."),
            listOf("POST /v1/camera/video", "POST /v1/interactions")),
        Demo("Listen and transcribe", "mic", setOf(VOICE), "You hear the room, transcribed on the phone with no cloud speech service.",
            listOf("The owner hears first that the phone will listen, and for how long. POST /v1/mic/record {\"duration_seconds\":10,\"transcribe\":true}.",
                "The owner says a reminder or a to-do. The transcript goes on a question card with buttons Remind me / Add to my list / Not quite, and the tap decides what happens."),
            listOf("POST /v1/mic/record", "POST /v1/interactions")),
        Demo("Say the wake word", "wakewords", setOf(VOICE), "Hands-free: they say their wake word and what follows reaches you, recognised on the phone.",
            listOf("GET /v1/listen. An empty wake_words means the owner adds one in Bridgit (Features > Wake words), e.g. \"hey bridget\"; it shows up in the list.",
                "The owner says it and then a request, e.g. \"hey bridget, remind me to water the plants\". GET /v1/events?wait=55 delivers it.",
                "listen.heard carries the request; the answer goes out loud (POST /v1/speaker/say), then a notice card \"I heard: <transcript>\". The green mic dot and the Mute button in the notification show listening is on."),
            listOf("GET /v1/listen", "GET /v1/events", "POST /v1/interactions")),
        Demo("Hear the room", "sounds", setOf(VOICE, PHONE), "The phone tells you when something makes a noise, with a few seconds of audio, so you can say what it was.",
            listOf("GET /v1/sounds. for_you false: the owner picks the agent under Features > Wake words > Any sounds.",
                "The owner claps, knocks on the table or drops their keys. GET /v1/events?wait=55 delivers it.",
                "sound.heard has clip_url; a question card shows the guess and how loud it was (peak_dbfs), with buttons Right / Not quite and a text box. Sounds are at least 30 s apart."),
            listOf("GET /v1/sounds", "GET /v1/sounds/clip/{id}", "GET /v1/events", "POST /v1/interactions")),
        Demo("What's on my screen", "screen", setOf(SEE), "One look at what they're looking at; not added to their gallery (your capture storage setting decides whether a copy is kept).",
            listOf("The owner opens something they want help with (an email, a settings page, a map). POST /v1/screen/capture.",
                "The help goes on a question card as text (no screenshot on it), with buttons Do it / Explain more."),
            listOf("POST /v1/screen/capture", "POST /v1/interactions")),
        Demo("Phone at a glance", "phone", setOf(PHONE), "One card with battery, network, sound mode and where they are, and buttons to act on it.",
            listOf("GET /v1/phone/status, plus GET /v1/location if location is ready for you.",
                "One question card titled \"Your phone right now\": battery, network, sound mode, and which way they're facing and how high up. " +
                    "Buttons: Quiet it for a minute (POST /v1/phone/dnd {\"mode\":\"priority\",\"duration_minutes\":1}), Flash the light (POST /v1/flashlight {\"on\":true,\"auto_off_seconds\":5}), All good.",
                "The tap picks the action, and a line says what happened; the phone puts Do Not Disturb back on its own (POST /v1/phone/undo restores it early)."),
            listOf("GET /v1/phone/status", "GET /v1/location", "POST /v1/interactions", "POST /v1/phone/dnd", "POST /v1/flashlight")),
        Demo("Find my phone", "ring", setOf(PHONE, VOICE), "Ring the phone when it's lost in the sofa.",
            listOf("For when the owner is nearby and expects it: POST /v1/phone/ring {\"duration_seconds\":5}.",
                "Alongside, a question card \"Found it?\" with a Stop ringing button; its tap means DELETE /v1/phone/ring."),
            listOf("POST /v1/phone/ring", "POST /v1/interactions")),
    )

    /** The four menu questions on one card: three choices, a text box and a button. */
    const val QUESTIONS_CARD = "POST /v1/interactions {\"kind\":\"question\",\"title\":\"A few quick questions\",\"controls\":[" +
        "{\"type\":\"choice\",\"id\":\"where\",\"label\":\"Where's the phone?\",\"options\":[\"In my hand\",\"On the desk next to me\",\"Somewhere else in the room\"]}," +
        "{\"type\":\"choice\",\"id\":\"help\",\"label\":\"What would help you most?\",\"options\":[\"Quick decisions and approvals\",\"Seeing and reading things\",\"Hands-free voice and sound\",\"Knowing what the phone is doing\"]}," +
        "{\"type\":\"choice\",\"id\":\"length\",\"label\":\"How long?\",\"options\":[\"A quick taste (3 demos)\",\"The full tour\"]}," +
        "{\"type\":\"text\",\"id\":\"today\",\"label\":\"Anything on your plate today?\",\"placeholder\":\"A decision, a trip, a task\"}," +
        "{\"type\":\"button\",\"id\":\"go\",\"label\":\"Show me\",\"style\":\"primary\"}]}"

    /** [tour] is the first-run script (SKILL.md, frontmatter is dropped); [allowed] says whether the owner lets this agent use a capability. */
    /** [server] = the copy at [Learn.DEMO_URL]: no phone, so every demo is listed without a ready/not-ready state. */
    fun build(agentName: String, caps: List<CapInfo>, tour: String? = null, server: Boolean = false, allowed: (String) -> Boolean): String {
        val byId = caps.associateBy { it.id }
        fun state(d: Demo): String? {
            if (server) return null
            val c = byId[d.capability] ?: return "not on this phone's version of Bridgit"
            return when {
                !allowed(d.capability) -> "the owner hasn't allowed you to use ${c.title}; they can in your agent page in Bridgit"
                !c.enabled -> "${c.title} is switched off; the owner can switch it on in Bridgit"
                !c.ready -> "${c.title} isn't ready: ${c.blockedReason}"
                else -> null
            }
        }
        val cards = server || (byId["interactions"]?.usable == true && allowed("interactions"))
        val servedBy = if (server) "Reference published at ${Learn.DEMO_URL} (API revision ${ApiChanges.CURRENT}), the same as `GET /v1/demo` on a phone with everything switched on"
            else "Reference served by Bridgit on the owner's phone to agent $agentName"
        val ready = all.filter { state(it) == null }
        val sb = StringBuilder()
        val body = tour?.let { stripFrontmatter(it) }?.takeIf { it.isNotBlank() }
        if (body != null) {
            sb.appendLine("$servedBy: Bridgit's demo, for when the owner asks for one. " +
                "Part 1 is the first-run tour for a new owner; part 2 is the menu, for when they want more. It describes cards and calls and asks nothing of the reader.")
            sb.appendLine()
            sb.appendLine(body.trim())
            sb.appendLine()
            sb.appendLine("# Part 2: the menu")
            sb.appendLine()
        } else {
            sb.appendLine("# Bridgit demos")
            sb.appendLine()
            sb.appendLine("$servedBy. For a new owner the five-minute first-run tour at $SKILL_URL comes first; this menu is for afterwards.")
            sb.appendLine()
        }
        sb.appendLine("More demos, built from what's switched on right now: for when the owner taps **Show me more** at the end of the tour, " +
            "or asks later. The goal: the owner finishes knowing the handful of things an agent can do through their phone that matter to *them*.")
        sb.appendLine()
        sb.appendLine("## 1. A few questions first")
        if (cards) {
            sb.appendLine("All four go on one styled HTML form card (start from `GET /v1/samples?id=html-form`: choice fields `where`, `help`, `length`, a text field `today`, submit \"Show me\"; the answers are in `data.values`); `GET /v1/interactions/{id}?wait=55` returns the answer. The native card below is the fallback if HTML cards are unavailable. " +
                "A control whose answer is already known can be left out.")
            sb.appendLine()
            sb.appendLine("```")
            sb.appendLine(QUESTIONS_CARD)
            sb.appendLine("```")
        } else {
            sb.appendLine("Cards are off, so these go in chat, one at a time, leaving out any whose answer is already known.")
            sb.appendLine("1. **Where's the phone?** In your hand / on the desk next to you / somewhere else in the room.")
            sb.appendLine("2. **What would help you most?** Quick decisions and approvals / seeing and reading things / hands-free voice and sound / knowing what the phone is doing.")
            sb.appendLine("3. **How long?** A quick taste (3 demos) / the full tour.")
            sb.appendLine("4. **Anything on your plate today?** A decision, a trip, a task.")
        }
        sb.appendLine("The answer to question 4 is the content of the demos, so they're about the owner's life, not placeholders.")
        sb.appendLine()
        sb.appendLine("## 2. Pick the demos")
        sb.appendLine("- In their hand: anything. On the desk: camera, screen, speaker and cards work best. Elsewhere in the room: speaker, find my phone and the phone at a glance first.")
        sb.appendLine("- Question 2 matches each demo's **Good for**. Quick taste: three demos. Full tour: every ready demo that fits, best first.")
        sb.appendLine("- Before each demo send a step card: the html action card from the first-run tour (`GET /v1/samples?id=html-step`) with the demo's title, an icon and \"Step <i> of <n>\". Do it is their OK for that demo's captures; every capture also shows on the phone and is logged under your name.")
        sb.appendLine("- Results with a photo go on the html photo card (`GET /v1/samples?id=html-photo-check`); one-way results can be notices.")
        sb.appendLine("- Every demo ends with a card on the phone showing the result: the photo, the transcript, the guess. The owner sees it where they're looking, not only in chat.")
        sb.appendLine("- Every POST carries an `Idempotency-Key` header. A failed call becomes a notice card explaining the error in plain words, and the demo moves on.")
        sb.appendLine()
        sb.appendLine("## 3. Wrap up")
        sb.appendLine("A notice card with two or three ideas for using what they saw in their own routine, based on their answers, and which switch turns on any demo they'd like that is off. " +
            "Then an action card (`GET /v1/samples?id=html-wrapup`) offering to set one of those ideas up for real.")
        sb.appendLine()
        sb.appendLine("## Demos")
        sb.appendLine()
        if (server) sb.appendLine("Which of these are ready depends on the owner's switches: the phone's `GET /v1/demo` marks the ones that aren't.")
        else sb.appendLine("Ready now: " + (if (ready.isEmpty()) "none yet; the owner switches features on in Bridgit." else ready.joinToString("; ") { it.title }) + ".")
        for (d in all) {
            sb.appendLine()
            val st = state(d)
            sb.appendLine("### ${d.title}" + if (st == null) "" else " (not available: $st)")
            sb.appendLine(d.pitch)
            sb.appendLine("Good for: " + d.goodFor.joinToString(", ") { INTEREST_LABELS.getValue(it) } + ".")
            d.steps.forEach { sb.appendLine("- $it") }
        }
        return sb.toString()
    }

    /** Drops a leading `---` YAML block (the skill's name and description). */
    fun stripFrontmatter(md: String): String =
        Regex("\\A\\s*---\\R.*?\\R---\\R", RegexOption.DOT_MATCHES_ALL).replace(md, "")

    private val INTEREST_LABELS = mapOf(DECIDE to "decisions and approvals", SEE to "seeing and reading",
        VOICE to "voice and sound", PHONE to "the phone itself")
}
