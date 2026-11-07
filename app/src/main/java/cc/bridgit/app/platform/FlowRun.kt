package cc.bridgit.app.platform

import cc.bridgit.app.api.ApiError
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Shared step sequencing. The caller supplies the effects, authority checks, checkpoints and retry clock. */
internal class FlowRun(val steps: JSONArray, val trigger: JSONObject) {
    val results = mutableMapOf<String, JSONObject>()
    val log = JSONArray()
    var status = "completed"
    var error: String? = null
    var cost = 0.0
    private var current: JSONObject? = null
    private var dispatchingEffect = false
    private val types = (0 until steps.length()).associate { index ->
        val step = steps.getJSONObject(index)
        step.optString("id").ifEmpty { "step${index + 1}" } to FlowSteps.hostStep(step).getString("type")
    }

    // New steps default to effectful until their retry behavior has been considered.
    private fun safeToRepeat(step: JSONObject): Boolean = when (step.getString("type")) {
        "photo" -> !step.optBoolean("keep")
        "lookup", "context", "wait", "stop" -> true
        else -> false
    }

    private fun ambiguous(error: ApiError): Boolean = when (error.code) {
        "unknown_outcome" -> true
        else -> error.extra["effect_started"] != false && (error.http >= 500 || error.http == 408)
    }

    private fun entry(index: Int): JSONObject {
        val step = steps.getJSONObject(index)
        return JSONObject().put("id", step.optString("id").ifEmpty { "step${index + 1}" }).put("type", step.getString("type")).apply {
            if (step.getString("type") == "photo") put("camera", step.optString("camera", "rear"))
        }
    }

    fun notRun(from: Int) {
        for (index in from until steps.length()) log.put(entry(index).put("status", "not_run"))
    }

    fun timedOut() {
        status = if (dispatchingEffect) "unknown_outcome" else "failed"
        error = "timed_out"
        current?.takeUnless { it.has("status") }?.let {
            log.put(it.put("status", status).put("error", error).put("message", "The run reached its time limit during this step"))
        }
        notRun(log.length())
    }

    inline fun run(
        beforeStep: () -> Unit,
        facts: () -> FlowConditions.Facts,
        checkpoint: (index: Int, state: String) -> Unit,
        retry: (attempt: Int) -> Unit,
        effect: (step: JSONObject, id: String) -> JSONObject
    ) {
        for (index in 0 until steps.length()) {
            beforeStep()
            val step = steps.getJSONObject(index)
            val entry = entry(index)
            current = entry
            val id = entry.getString("id")
            val started = System.currentTimeMillis()
            val condition = step.optJSONObject("only_if")
            if (condition != null && !FlowSteps.holds(condition, results, trigger, facts())) {
                log.put(entry.put("status", "skipped").put("condition", condition)
                    .put("reason", FlowSteps.skipReason(condition, types, results, trigger)))
                checkpoint(index + 1, "skipped")
                continue
            }
            checkpoint(index, "dispatching")
            val policy = step.optString("on_error", "stop")
            val retries = if (policy == "retry") step.optInt("retries", 1) else 0
            var failure: ApiError? = null
            var result = JSONObject()
            val safe = safeToRepeat(step)
            var unknown = false
            for (attempt in 0..retries) {
                if (attempt > 0) retry(attempt)
                try {
                    dispatchingEffect = !safe
                    result = effect(step, id)
                    dispatchingEffect = false
                    failure = null
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    if (e.code in WorkflowEngine.BLOCKING) throw e
                    failure = e
                } catch (e: Exception) {
                    failure = ApiError(500, "step_failed", e.message ?: e.javaClass.simpleName)
                }
                dispatchingEffect = false
                if (failure != null && !safe && ambiguous(failure)) {
                    unknown = true
                    break
                }
                // Only retry errors that a new attempt can plausibly clear.
                if (failure != null && failure.http !in setOf(408, 409, 429) && failure.http < 500) break
            }
            entry.put("ms", System.currentTimeMillis() - started)
            if (failure == null) {
                results[id] = result
                cost += result.optDouble("cost_usd", 0.0)
                log.put(entry.put("status", "ok").put("result", result))
            } else {
                log.put(entry.put("status", if (unknown) "unknown_outcome" else "failed").put("error", failure.code).put("message", failure.message))
                if (unknown || policy != "skip") {
                    status = if (unknown) "unknown_outcome" else "failed"
                    error = failure.code
                    notRun(index + 1)
                    break
                }
            }
            checkpoint(index + 1, "running")
            if (failure == null && step.getString("type") == "stop") {
                notRun(index + 1)
                break
            }
        }
    }
}
