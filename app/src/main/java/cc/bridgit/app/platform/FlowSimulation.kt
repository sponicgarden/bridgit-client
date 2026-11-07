package cc.bridgit.app.platform

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.capability.isoAt
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** This boundary has no device, platform store, network client or interaction dispatcher. */
internal object FlowSimulation {
    private val supported = setOf("photo", "ask_model", "ask_owner", "notice", "alert", "ring", "event", "wait", "stop")
    private fun unsupported(message: String): Nothing = throw ApiError(422, "simulation_unsupported", message)
    private fun invalid(message: String): Nothing = throw ApiError(422, "invalid_fixture", message)

    private fun checkCondition(condition: JSONObject?) {
        if (condition == null || condition.has("step") || condition.has("trigger")) return
        val group = condition.optJSONArray("all") ?: condition.optJSONArray("any")
            ?: unsupported("Simulation supports only_if on trigger and step results")
        for (i in 0 until group.length()) checkCondition(group.getJSONObject(i))
    }

    private fun objectField(parent: JSONObject, name: String): JSONObject =
        if (!parent.has(name)) JSONObject() else parent.optJSONObject(name) ?: invalid("$name must be an object")

    fun run(flow: JSONObject, args: JSONObject): JSONObject {
        val steps = flow.getJSONArray("steps")
        checkCondition(flow.optJSONObject("only_if"))
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.getString("type") !in supported || step.optBoolean("keep"))
                unsupported("steps[$i] cannot be simulated yet. Supported steps: ${supported.joinToString()}; photo requires keep=false.")
            checkCondition(step.optJSONObject("only_if"))
        }
        val fixtures = objectField(args, "fixtures")
        fixtures.only("photos", "ask_model", "ask_owner")
        val trigger = JSONObject(objectField(args, "trigger").toString())
        val definition = flow.getJSONObject("trigger")
        if (definition.getString("type") == "called") {
            val with = FlowSteps.withValues(definition.optJSONObject("with"), objectField(trigger, "with"))
            trigger.put("with", with)
        }
        trigger.put("type", definition.getString("type"))
        if (!trigger.has("at")) trigger.put("at", isoAt(System.currentTimeMillis()))
        val run = FlowRun(steps, trigger)
        val would = JSONArray()
        val clock = FlowConditions.Clock(ZonedDateTime.now())
        if (flow.optJSONObject("only_if")?.let { !FlowSteps.holds(it, run.results, trigger, clock) } == true) {
            run.status = "skipped"
            run.notRun(0)
        } else {
            run.run(beforeStep = {}, facts = { clock }, checkpoint = { _, _ -> }, retry = {},
                effect = { step, id -> effect(flow, step, id, run, fixtures, would) })
        }
        return JSONObject().put("simulated", true).put("status", run.status).put("trigger", trigger)
            .put("steps", run.log).put("would", would).put("cost_usd", 0).apply { run.error?.let { put("error", it) } }
    }

    private fun effect(flow: JSONObject, step: JSONObject, id: String, run: FlowRun, fixtures: JSONObject, would: JSONArray): JSONObject {
        val type = step.getString("type")
        fun text(field: String, max: Int) = FlowSteps.textOf(step.opt(field), run.results, run.trigger, max)
        fun fixture(group: String): JSONObject {
            val path = "fixtures.$group.$id"
            val entries = objectField(fixtures, group)
            if (!entries.has(id)) throw ApiError(422, "no_fixture", "Provide $path")
            val value = entries.optJSONObject(id) ?: invalid("$path must be an object")
            if (value.has("error")) {
                val code = value.opt("error") as? String ?: invalid("$path.error must be an error code")
                if (!Regex("[a-z][a-z0-9_]{0,63}").matches(code) || code in WorkflowEngine.BLOCKING)
                    invalid("$path.error needs a device or service error code")
                val http = value.whole("http", if (code == "unknown_outcome") 502 else 409, 400, 599)
                throw ApiError(http, code, value.optString("message", "$path returned $code"))
            }
            return value
        }
        val action = JSONObject().put("id", id).put("type", type)
        return when (type) {
            "photo" -> {
                fixture("photos")
                JSONObject().put("photo", "photo:$id").put("taken_at", run.trigger.get("at")).put("camera", step.optString("camera", "rear"))
            }
            "ask_model" -> {
                if (step.has("photo") && FlowSteps.fillValue(step.get("photo"), run.results, run.trigger) == null)
                    throw ApiError(409, "camera_unavailable", "The photo step gave no photo")
                val result = JSONObject(fixture("ask_model").toString())
                val answers = result.optJSONObject("answers") ?: invalid("fixtures.ask_model.$id needs an answers object")
                step.getJSONObject("questions").keys().forEach { if (!answers.has(it)) invalid("fixtures.ask_model.$id.answers needs $it") }
                result.put("model", result.optString("model", step.getString("model")))
            }
            "ask_owner" -> {
                val choices = step.getJSONArray("choices")
                would.put(action.put("title", text("title", 120).ifBlank { flow.getString("name") }).put("text", text("text", 4096)).put("choices", choices))
                val result = fixture("ask_owner")
                if (result.opt("timed_out") == true) JSONObject().put("timed_out", true)
                else {
                    val choice = result.opt("choice") as? String
                    if ((0 until choices.length()).none { choices.getString(it) == choice }) invalid("fixtures.ask_owner.$id.choice must match one of the step's choices")
                    JSONObject().put("choice", choice).put("timed_out", false).put("interaction_id","simulated:$id")
                }
            }
            "notice" -> {
                would.put(action.put("title", text("title", 120).ifBlank { flow.getString("name") }).put("text", text("text", 4096)).put("priority", step.optString("priority", "normal")))
                JSONObject().put("interaction_id", "simulated:$id")
            }
            "alert" -> {
                would.put(action.put("destinations", step.getJSONArray("destinations")).put("text", text("text", 1024).ifBlank { flow.getString("name") }))
                JSONObject().put("alert_id", "simulated:$id")
            }
            "ring" -> { would.put(action.put("seconds", step.optInt("seconds", 10))); JSONObject() }
            "event" -> { would.put(action); JSONObject() }
            "wait", "stop" -> JSONObject()
            else -> unsupported("Simulation does not support $type")
        }
    }
}
