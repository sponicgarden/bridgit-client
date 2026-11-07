package cc.bridgit.app.watch

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watches: "call me back when ...". The phone reads the data an agent picks on an interval, asks Jev the
 * agent's question about the last few readings, and calls the agent back when the answer matches.
 */
class WatchModule(app: BridgeApp) : Capability(app) {
    override val id = "watches"
    override val titleRes = R.string.cap_watches
    override val descriptionRes = R.string.cap_watches_desc
    override val group = "status"
    override val endpoints = ALL

    override fun details(): JSONObject = JSONObject().put("data", JSONArray(WatchRules.ELEMENTS.keys.toList()))
        .put("models", JSONArray(WatchRules.MODELS)).put("min_interval_s", WatchRules.MIN_INTERVAL_S)
        .put("max_per_agent", WatchRules.MAX_PER_AGENT).put("max_checks_per_hour", WatchRules.MAX_CHECKS_PER_HOUR)
        .put("paid", JSONObject()
            .put("models", JSONArray(WatchRules.PAID_MODELS))
            .put("default_model", JSONObject().put("with_camera", WatchRules.defaultModel(listOf("camera"))).put("without_camera", WatchRules.defaultModel(emptyList())))
            .put("max_paid_per_hour_range", JSONArray().put(1).put(WatchRules.MAX_CHECKS_PER_HOUR))
            .put("phone_wide_paid_checks_per_hour", JSONObject().put("attested", WatchRules.MAX_CHECKS_PER_HOUR).put("unverified", WatchRules.MAX_CHECKS_PER_HOUR_UNVERIFIED)
                .put("about", "Defaults. This phone's account may have a different value: GET /v1/limits"))
            .put("approx_cost_per_check_usd", JSONObject(WatchRules.COST_PER_CHECK_USD as Map<*, *>)))
        .put("local", cc.bridgit.app.vision.ChangeSettings.describe()
            .put("about", "With camera in data, each photo is compared on the phone with the last one a paid model saw. If nothing solid changed, that answer is reused for free. model none asks no model: you're called back when the picture changes.")
            .put("cameras", JSONArray(WatchRules.CAMERAS)))
        .put("sound", JSONObject()
            .put("about", "data [\"sound\"]: the phone listens for loud sounds (free, on the phone); each loud sound wakes the watch and only then is its clip sent to Clef with your question. A sound within reuse_s that sounds like the last one checked (a frequency fingerprint compared on the phone) reuses that answer (answered_locally), so a long bout of the same sound costs one check while a different sound is still checked. Tally rules (none_for_s ...) are checked every 30 s even in silence.")
            .put("sensitivities", JSONArray(cc.bridgit.app.sound.SoundRules.SENSITIVITIES)).put("min_gap_s", cc.bridgit.app.sound.SoundRules.MIN_GAP_S)
            .put("default_reuse_s", cc.bridgit.app.sound.SoundRules.DEFAULT_REUSE_MS / 1000).put("default_max_paid_per_hour", cc.bridgit.app.sound.SoundRules.DEFAULT_CHECKS_PER_HOUR))
        .put("situations", Situations.catalog())
        .put("tally", JSONObject()
            .put("about", "Add tally to a watch and the phone counts its answers itself: episodes (back-to-back readings with the same answer), day totals, typical gaps, kept for weeks. rules fire on those counts with you offline and can call one of your endpoints on the phone.")
            .put("min_interval_s_with_camera_change_check", WatchRules.MIN_INTERVAL_TALLY_S)
            .put("max_expires_in_s", WatchRules.MAX_EXPIRES_TALLY_S).put("default_expires_in_s", WatchRules.DEFAULT_EXPIRES_TALLY_S)
            .put("max_keep_days", WatchTally.MAX_KEEP_DAYS).put("max_rules", WatchTally.MAX_RULES)
            .put("rule_kinds", JSONArray(listOf("none_for_s", "fewer_than", "at_least"))))

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(CREATE) {
            val a = agent!!
            val specs = app.registry.modules.flatMap { it.endpoints }
            val spec = try {
                WatchRules.parse(body, checkFlow = { ref, at -> app.platform.workflows.startable(a.id, ref)?.let { throw WatchRules.Bad("$at: $it.") } }) { raw, at ->
                    // A rule's action must be a call this agent could make itself (the same rule as card actions).
                    val c = try { cc.bridgit.app.interact.CardActions.match(specs, cc.bridgit.app.interact.CardActions.parse(raw.toString())) }
                        catch (e: cc.bridgit.app.interact.CardActions.Refused) { throw WatchRules.Bad("$at: ${e.message}") }
                    if (c.first.privileged || c.first.capability == "watches") throw WatchRules.Bad("$at: ${c.first.method} ${c.first.fullPath} can't run from a watch rule.")
                    if (!a.allows(c.first.capability)) throw WatchRules.Bad("$at: you have no ${c.first.capability} access, so a rule can't call ${c.first.method} ${c.first.fullPath}.")
                }
            } catch (e: WatchRules.Bad) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad watch.") }
            // A rule's do.with names only values its flow takes (as a package install checks).
            spec.tally?.rules.orEmpty().forEach { r -> r.flow?.let { f -> app.platform.workflows.withProblem(a.id, f, r.with)?.let { throw ErrorCatalog.error("invalid_parameter", "rules.${r.id}.do.with: $it.") } } }
            // Faces (people) only come from an installed applet's own plan, never from this endpoint or a card action.
            if (spec.people != null) throw ErrorCatalog.error(Watches.PEOPLE_REFUSED_CODE, Watches.PEOPLE_REFUSED)
            if (spec.paid && !cc.bridgit.app.feedback.FeedbackUploader.configured) throw ErrorCatalog.error("team_unavailable", "This build of Bridgit has no team backend, so it can't ask Jev or Clef. model none (camera only) works without one.")
            val (w, secret) = try { app.watches.create(a.id, a.name, JSONObject(body.toString()), spec) }
                catch (e: Watches.Refused) { throw ErrorCatalog.error(e.code, e.message ?: "Refused.", e.extra) }
            message = "${a.name} created watch ${w.id}: ${spec.label}"
            data = JSONObject().put("watch_id", w.id).put("interval_s", spec.intervalS).put("data", JSONArray(spec.data))
            Reply.Json(w.json(full = false).put("spec", spec.json())
                .apply { WatchTally.hints(spec.tally).takeIf { it.isNotEmpty() }?.let { put("hints", JSONArray(it)) } }
                .put("callback_secret", secret ?: JSONObject.NULL)
                .put("first_check_in_s", if (w.userPaused) JSONObject.NULL else spec.intervalS.toLong() * (spec.minReadings - 1))
                .put("delivery", (if (spec.callbackUrl != null)
                    "watch.matched and watch.ended go to callback_url (signed with callback_secret, X-Bridgit-Signature), to your registered callback if you have one, and to GET /v1/events (those two need the interactions permission)"
                    else "watch.matched and watch.ended go to your registered callback if you have one, and to GET /v1/events (both need the interactions permission)") +
                    (if (spec.tally?.rules?.isNotEmpty() == true) "; watch.rule (a rule fired) goes the same way" else "")), 201)
        }
        endpoint(TALLY) {
            val days = query("days")?.let { it.toIntOrNull()?.takeIf { d -> d in 1..WatchTally.MAX_KEEP_DAYS } ?: throw ErrorCatalog.error("invalid_parameter", "days must be 1-${WatchTally.MAX_KEEP_DAYS}.") } ?: 1
            val t = app.watches.tallyOf(agent!!.id, path("id").orEmpty(), days, query("label")?.ifBlank { null })
                ?: throw ErrorCatalog.error("not_found", "You have no running watch with that id. An ended watch's last tally is in its watch.ended event.")
            if (t.length() == 0) throw ErrorCatalog.error("invalid_parameter", "This watch has no tally. Start one with tally {track} to count answers on the phone.")
            Reply.Json(t)
        }
        endpoint(SITUATIONS) { Reply.Json(Situations.catalog()) }
        endpoint(LIST) {
            val list = app.watches.list(agent!!.id)
            Reply.Json(JSONObject().put("watches", JSONArray(list.map { it.json(full = false) })))
        }
        endpoint(GET) {
            val w = app.watches.get(agent!!.id, path("id").orEmpty()) ?: throw ErrorCatalog.error("not_found", "You have no running watch with that id. Ended watches are gone; their watch.ended event is in /v1/events.")
            Reply.Json(w.json(full = true))
        }
        endpoint(PAUSE) {
            val paused=body.opt("paused") as? Boolean ?: throw ErrorCatalog.error("invalid_parameter","paused must be boolean.")
            Reply.Json(app.watches.setPaused(agent!!.id,path("id").orEmpty(),paused))
        }
        endpoint(DELETE) {
            val id = path("id").orEmpty()
            if (!app.watches.delete(agent!!.id, id)) throw ErrorCatalog.error("not_found", "You have no running watch with that id.")
            message = "${agentName} deleted watch $id"
            Reply.Json(JSONObject().put("deleted", id))
        }
    }

    companion object {
        val CREATE = EndpointSpec("POST", "/watches", "watches",
            "Call me back when ...: the phone reads the data you pick (location, motion, a camera photo, ...) on an interval, asks Clef or Jev (paid) your question about the last few readings, and calls you back when the answer matches. With a photo, a free change check on the phone skips the paid call while the picture stays the same; model none is local only. Designing one (which model, the change check, a worked door-camera example, cost): ${cc.bridgit.app.api.Learn.DECISIONS_URL}",
            privateFields = setOf("question", "questions", "context"),
            body = listOf(
                Param("situation", "string|object", "Instead of question: a common picture situation to spot and count on the camera, by library id (GET /v1/situations: dog_licking_itself, pot_boiling_over, sunlight_in_room, ...) or in your own words (\"the cat is on the kitchen counter\"), or {id | describe, alert?: true sends the owner an urgent notice when an episode starts (default: the library's choice; on for pot_boiling_over, smoke_in_room, water_on_floor), min?: 0-1 how likely to count, count_only_while?: a question like \"Is a dog visible?\"}. The phone fills in data [\"camera\"], the tuned question (id = the situation id, or \"situation\" for own words), a tally of episodes, the interval and the label; send model, camera, interval_s, max_paid_per_hour, expires_in_s, callback_when (on the situation id) or rules to change them, not question, questions or tally. Read the counts with GET /v1/watches/{id}/tally"),
                Param("question", "string", "A yes/no question in words, e.g. \"Is the phone on a bicycle?\". You're called back when Jev's probability of yes reaches min_probability. Send this or questions"),
                Param("min_probability", "number", "With question: how likely yes must be, 0-1 (default 0.8)"),
                Param("questions", "object", "Instead of question: 1-10 typed questions, {id: {type: choice|noul, instructions, criteria?}}, as https://docs.typesafe.ai/primitives"),
                Param("callback_when", "object|array", "With questions: the answer that calls you back, 1-5 conditions that must all hold: {answer, min?, max?} on a noul probability, or {answer, choice, min_confidence?} (default 0.8) on a choice. Jev's confidence is not the probability: 0.67 likely is about 0.34 confident"),
                Param("data", "array", "What to read each time (default [\"location\", \"motion\"]): location (with speed), motion (2 s accelerometer burst), steps, light, pressure, proximity, battery, device (screen, lock, headphones, network), camera (a 640 px photo; only the newest is sent to the model), sound (loud sounds: the watch runs on each loud sound instead of an interval and sends that clip to Clef; use it alone). location needs the location permission, the sensors ones the sensors permission, camera the camera permission and an unlocked phone. With only camera, window and min_readings default to 1"),
                Param("interval_s", "integer", "${WatchRules.INTERVAL_DESCRIPTION} A watch keeps the phone awake and reads location each time, so pick the longest interval and expiry that work. Phone-wide limit on paid watch checks, and the account's Jev and Clef calls an hour and monthly budget: read them with GET /v1/limits (they differ per account). A still, unplugged phone may pause checks until it wakes"),
                Param("window", "integer", "How many recent readings Jev sees, 1-20 (default 5)"),
                Param("min_readings", "integer", "Readings to collect before the first question (default 3, at most window)"),
                Param("expires_in_s", "integer", "Stop after this long, up to 86400 (default 7200). With a tally: up to 7776000 (90 days, default 30 days)"),
                Param("repeat", "boolean", "false (default): one callback, then the watch ends. true: keep watching, at most one callback per cooldown_s"),
                Param("cooldown_s", "integer", "With repeat: seconds between callbacks (default 600)"),
                Param("callback_url", "string", "Where to POST the result, as well as your registered callback and /v1/events. Same rules as callbacks: a tailnet address, or https on the internet if the owner allows internet callbacks. Signed with the callback_secret in the reply"),
                Param("context", "string", "Anything Jev should know, up to 2000 characters (\"the owner commutes by bike\")"),
                Param("label", "string", "A short name for logs and lists (default: the question)"),
                Param("model", "string", "Who answers, and whether you pay: clef (Cloudflare Clef-omni, paid; the default with camera, about \$0.000105 a photo), jev (TypeSafe, paid; the default without camera, about \$0.000065 a photo, \$0.000008 for text), glm (GLM 5.3 Flash through OpenRouter, paid), or none (local only, free: needs camera, takes no question, and calls you back when the picture changes). Paid models share the phone's hourly limit", enum = WatchRules.MODELS),
                Param("camera", "string", "With camera in data: rear (default), front or an enrolled lan:<id> (needs lan_camera access)", enum = null),
                Param("additional_cameras", "array", "Up to seven other enrolled camera ids or phone lenses. The watch rotates one per reading under its shared hourly cap; each source keeps its own change history. Excludes people watches."),
                Param("local", "object", "With camera in data: the free change check on the phone before any paid call. {change_check: true (default; false = pay for every photo), sensitivity: low | normal (default) | high (high notices smaller changes, so pays more often), learn_noise: true (default; when a paid call for a change gave the same answer, ignore those spots for 10 min), max_reuse_s: 30-3600 (default 300; ask again after this long even if nothing changed)}. A reused answer can still call you back (answered_locally: true). It looks at the photo only: other data you read doesn't make it pay"),
                Param("people", "object", PeopleRules.ABOUT),
                Param("sound", "object", "With data [\"sound\"]: {sensitivity: quiet | normal (default) | loud (how much louder than the room's quiet level a sound must be), reuse_s: 0-1800 (default 300; a loud sound this soon after the last paid check that sounds like it, compared on the phone by its frequency fingerprint, reuses that answer for free (answered_locally, similarity in last_check); a different-sounding one is checked; 0 = ask every time)}. No interval_s: the phone's loudness check wakes the watch, at most once every 30 s. Model must be clef. Paid checks default to 6 an hour; set max_paid_per_hour to change it. Needs the sounds permission and switch and the microphone permission. The watch shows listening true/false and not_listening_reason"),
                Param("start_paused", "boolean", "Create the watch paused until resumed. Default false. A paused watch takes no readings and fires no rules."),
                Param("max_paid_per_hour", "integer", "At most this many paid model calls an hour for this watch, 1-240 (default: only the phone-wide limit). Checks over it are skipped, not paid. Also lowers what the watch takes from the phone-wide limit"),
                Param("tally", "object", "Count on the phone, for weeks, with you offline (\"how many times did the dog eat today\"). {track: the question id to count (default: your only question; with question, \"match\"), labels?: the choice options to count (default all), min_confidence?: 0-1 for a choice (default 0.5), min?: 0-1 for a noul, which counts as its own id when that likely (default 0.5), seen?: one condition, e.g. {answer: \"dog_present\", min: 0.6}; readings count, and add to watched time, only while it holds, gap_s?: readings this far apart are one episode (default 3 x interval_s, at least 60), label_gap_s?: map tracked labels to individual episode gaps in seconds (1-21600); coverage still uses gap_s, min_episode_readings?: drop shorter episodes (default 1), keep_days?: 1-30 (default 30)}. callback_when becomes optional (with it, callbacks come at most once per cooldown_s, and the watch never ends on a match). Read it with GET /v1/watches/{id}/tally; a card reads it with Bridgit.act, on a button or a timer of 5 minutes or more (each call is an action.executed event, and events keep only your newest 1,000)"),
                Param("rules", "array", "With tally: up to 5 rules the phone checks after every reading. {id, when, do?, cooldown_s?}. when: {none_for_s, of, while_seen?: true} (no episode of that label for so long; while_seen counts only watched time), {fewer_than, of, by: \"10:00\"} (fewer episodes today by that local time; once a day), or {at_least, of, within_s}. A rule that fires sends you a watch.rule event {watch_id, rule, message, today, action} and runs do, exactly one of: {\"flow\":\"<workflow id>\", \"with\"?: {…}} starts one of your called or button workflows (its steps read the rule as {\"\$trigger\":\"message\"}, \"of\", \"today\", \"rule\", \"watch_id\"; action is {flow, status: started|dropped|flow_not_active|flow_not_found|bad_with, run_id}); {\"step\":{\"type\":\"notice\",\"title\":\"No meal seen yet\",\"text\":{\"\$trigger\":\"message\"},\"priority\"?:\"urgent\"}} or {\"step\":{\"type\":\"speak\",\"text\":…}} runs one step from the workflow menu (needs interactions or speaker); or one of your own endpoint calls {method, path, body?, query?}, as a card's Bridgit.act would ({message} and {label} in its text are filled in). cooldown_s: default the rule's own time span"),
            ),
            example = "POST /v1/watches {\"question\":\"Is a person at the front door?\",\"data\":[\"camera\"],\"interval_s\":30,\"model\":\"clef\",\"local\":{\"sensitivity\":\"normal\"},\"max_paid_per_hour\":30,\"repeat\":true,\"callback_url\":\"http://my-mac.your-tailnet.ts.net:8080/bridgit\"}",
            returns = "201: watch_id, label, created_at, expires_at, spec, callback_secret (only with callback_url; shown once), first_check_in_s (null while paused), delivery, usage. Each match is a watch.matched event {watch_id, label, question, answers, callback_when, model, answered_locally, cost_usd, checked_at, match, state (the readings the model saw), usage} (model none: change {largest_patch_share, sensitivity} instead of answers); the end is watch.ended {watch_id, reason: matched|expired|deleted|stopped_by_owner|access_revoked|budget|rejected|failing, message, checks, matches, usage, tally? (its last summary)}. A tally watch's rule firing is watch.rule {watch_id, label, rule, message, when, fired_at, today, action {method, path, ok, status, body} or null}. usage: {model, answered_locally (free checks), paid_calls, paid_cost_usd, paid_calls_last_hour, max_paid_per_hour, skipped_over_paid_cap, change_check}",
            errors = listOf("invalid_parameter", "agent_not_allowed", "capability_disabled", "too_many_open", "rate_limited", "team_unavailable"))
        val LIST = EndpointSpec("GET", "/watches", "watches", "Your running watches, with their last check",
            example = "GET /v1/watches",
            returns = "watches [{watch_id, label, created_at, expires_at, interval_s, data, checks, matches, last_match_at, readings_held, last_check, usage {model, answered_locally, paid_calls, paid_cost_usd, ...}}]")
        val GET = EndpointSpec("GET", "/watches/{id}", "watches", "One running watch: its spec, the last 10 checks with the model's answers, and the last state sent",
            example = "GET /v1/watches/w0123456789ab",
            returns = "watch_id, label, ..., usage, people? (a people watch: {state: on|battery_low|cap_reached|off, battery_floor, last_burst_at, faces_today, queued, embedder: ready|downloading|not_ready}), spec, recent_checks [{at, readings, model, paid, answers, matched, cost_usd (paid) or answered_locally (free)} or {at, skipped: max_paid_per_hour} or {at, error, message}], last_state",
            errors = listOf("not_found"))
        val DELETE = EndpointSpec("DELETE", "/watches/{id}", "watches", "Stop a watch (you get a watch.ended event with reason deleted)",
            example = "DELETE /v1/watches/w0123456789ab", returns = "deleted: the watch_id", errors = listOf("not_found"))
        val TALLY = EndpointSpec("GET", "/watches/{id}/tally", "watches", "A tally watch's counts: today, the last 7 days, what's going on now, typical gaps, rules, and the episodes",
            query = listOf(Param("days", "integer", "Episodes from the last this many days, 1-30 (default 1)"), Param("label", "string", "Only this label's episodes")),
            example = "GET /v1/watches/w0123456789ab/tally?days=7&label=eating",
            returns = "watch_id, label, track, labels, watching_since, last_seen_at, today {date, checks, watched_minutes, labels {<label>: {episodes, minutes, readings}}}, days [same, newest first, up to 7], ongoing {<label>: {since, minutes}}, last {<label>: when its last episode ended}, typical_gap_minutes {<label>: median minutes between starts, 7 days, 3+ episodes}, rules [{id, last_fired_at}], rules_fired, episodes [{label, start, end, minutes, readings, ongoing}]",
            errors = listOf("not_found", "invalid_parameter"))
        val PAUSE=EndpointSpec("POST","/watches/{id}/pause","watches","Pause or resume your watch without losing its tally; expiry is unchanged",idempotent=true,
            body=listOf(Param("paused","boolean","true pauses, false resumes",true)),example="POST /v1/watches/w123/pause {\"paused\":true}",returns="watch metadata including user_paused; watch.paused/watch.resumed events")
        val SITUATIONS = EndpointSpec("GET", "/situations", "watches", "The library of common picture situations a watch can count (dog licking itself, pot boiling over, sunlight in the room, ...): send one as situation in POST /v1/watches, or describe your own in words",
            example = "GET /v1/situations",
            returns = "about, situations [{id, title, question, min, interval_s, gap_s, alert, counts_only_while}], custom {example, defaults}")
        val ALL = listOf(CREATE, LIST, GET, DELETE, TALLY, PAUSE, SITUATIONS)
    }
}
