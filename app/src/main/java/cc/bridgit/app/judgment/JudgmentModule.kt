package cc.bridgit.app.judgment

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * POST /v1/judgment: an agent asks TypeSafe's Jev typed questions through this phone and the Bridgit
 * team's account, and the phone runs the conditional actions whose conditions held (cards, speech,
 * log lines, from a closed list). The agent sends the state itself; the phone adds nothing to it.
 */
class JudgmentModule(app: BridgeApp) : Capability(app) {
    override val id = "judgment"
    override val titleRes = R.string.cap_judgment
    override val descriptionRes = R.string.cap_judgment_desc
    override val group = "status"
    override val endpoints = listOf(JUDGMENT, IMAGE_GENERATE, IMAGE_USAGE) + ContextServices.ALL

    override fun Route.install(api: ApiServer) = with(api) {
        with(ContextServices) { install(api,app) }
        endpoint(IMAGE_GENERATE) {
            val result = withContext(Dispatchers.IO) { ImageGeneration.call(app, body, header("Idempotency-Key"), agent!!.id) }
            message = "${agentName} generated one image with ${result.optString("model", "the image service")}"
            Reply.Json(result)
        }
        endpoint(IMAGE_USAGE) {
            Reply.Json(withContext(Dispatchers.IO) { ImageGeneration.call(app, null, null, agent!!.id) })
        }
        endpoint(JUDGMENT) {
            val a = agent!!
            val state = body.opt("state")?.takeIf { it is String || it is JSONObject || it is JSONArray }
                ?: throw ErrorCatalog.error("invalid_parameter", "state is required: text, a JSON object or a JSON array.")
            val questions = body.optJSONObject("questions")?.takeIf { it.length() > 0 }
                ?: throw ErrorCatalog.error("invalid_parameter", "questions must be a non-empty object: {id: {type, instructions, criteria?}}.")
            if (body.has("actions") && body.optJSONArray("actions") == null)
                throw ErrorCatalog.error("invalid_parameter", "actions must be an array of {id, when, do}.")
            val model=body.optString("model","jev")
            if(model !in setOf("jev","clef","glm")) throw ErrorCatalog.error("invalid_parameter","model must be jev, clef or glm (GLM 5.3 Flash).")
            val audio=if(body.has("audio_clip_id")) {
                if(model!="clef") throw ErrorCatalog.error("invalid_parameter","Audio classification requires model clef.")
                val id=body.opt("audio_clip_id") as? String ?: throw ErrorCatalog.error("invalid_parameter","audio_clip_id must be text.")
                val clip=listOf("sounds","wakewords").filter { a.allows(it) && app.settings.isOn(if(it=="wakewords") cc.bridgit.app.listen.WakeListener.SWITCH else cc.bridgit.app.listen.WakeListener.SOUND_SWITCH) }
                    .firstNotNullOfOrNull { cap -> app.wake.clip(id,a.id,cap) }
                    ?: throw ErrorCatalog.error("not_found","No retained audio clip is available under your sound/wake-word access.")
                listOf(Jev.dataUrl(clip.wav,"audio/wav"))
            } else null
            val answer = try {
                withContext(Dispatchers.IO) { Jev.ask(app, state, questions, body.optJSONArray("actions"),route=cc.bridgit.app.watch.WatchRules.route(model),audio=audio) }
            } catch (e: Jev.Failure) { failure(e) }
            val ran = JevActions.run(app, a.id, a.name, answer.actionsSchema, answer.actions)
            message = "${a.name} asked Jev ${questions.length()} question(s); ${(0 until ran.length()).count { ran.getJSONObject(it).optBoolean("ran") }} action(s) ran"
            data = JSONObject().put("model", answer.model).put("input_tokens", answer.inputTokens).put("cost_usd", answer.costUsd)
            Reply.Json(JSONObject().put("model", answer.model).put("answers", answer.answers)
                .put("usage", JSONObject().put("input_tokens", answer.inputTokens)).put("cost_usd", answer.costUsd)
                .put("elapsed_ms", answer.elapsedMs).put("actions_schema", answer.actionsSchema.ifBlank { JevActions.SCHEMA })
                .put("actions", ran).put("skipped", answer.skipped))
        }
    }

    private fun budgetExtra(e: Jev.Failure): Map<String, Any?> = mapOf("applet" to e.applet, "resets_at" to e.extra?.opt("resets_at"), "limit" to e.extra?.opt("limit"))

    private fun failure(e: Jev.Failure): Nothing {
        val extra = mapOf("reason" to e.reason, "service" to "jev", "backend_http" to e.http.takeIf { it > 0 })
        throw when (e.reason) {
            "budget_reached" -> ErrorCatalog.error("budget_reached", e.message ?: "This applet's spending limit is used up.", extra)
            "service_budget" -> ErrorCatalog.error("rate_limited", e.message ?: "Service budget used up.", extra + mapOf("service" to e.extra?.opt("service"), "resets_at" to e.extra?.opt("resets_at"), "limit" to e.extra?.opt("limit")))
            "applet_budget" -> ErrorCatalog.error("applet_budget", e.message ?: "This applet's budget is used up.", extra + budgetExtra(e))
            "rate_limited", "spend_cap" -> ErrorCatalog.error("rate_limited", e.message ?: "Over the Jev limit.", extra + ("retry_after_s" to 3600))
            "jev_rejected", "invalid_actions", "invalid_parameter", "invalid_json", "images_off", "header_not_allowed", "payload_too_large" ->
                ErrorCatalog.error("invalid_parameter", e.message ?: "Jev refused the request.", extra)
            "jev_busy", "jev_timeout", "jev_unreachable", "jev_malformed" -> ErrorCatalog.error("upstream_failed", e.message ?: "Jev failed.", extra)
            else -> ErrorCatalog.error("team_unavailable", e.message ?: "The Bridgit team backend can't be reached.", extra + ("retryable" to e.retryable))
        }
    }

    companion object {
        val IMAGE_GENERATE = EndpointSpec("POST", "/images/generations", "judgment",
            "Generate one image through Azure Sunburst or OpenRouter FLUX 3 Image Klein; 40 per linked account per calendar month UTC, shared across devices and applet wordmarks",
            idempotent = true, idempotencyRequired = true, backendIdempotency = true, media = true, privateFields = setOf("*"), maxBody = 140_000,
            body = listOf(Param("prompt", "string", "Image description, 1-32000 characters", required = true),
                Param("task_key", "string", "Registered applet image task; image_generation by default. Resolves live applet/account model preferences; Azure Sunburst by default; flux_image_generation selects FLUX 3 Image Klein", enum = listOf("image_generation", "make_fairytale_image", "flux_image_generation")),
                Param("size", "string", "Image dimensions", enum = listOf("1024x1024", "1536x1024", "1024x1536")),
                Param("quality", "string", "Quality; medium by default, and the only supported FLUX quality", enum = listOf("low", "medium", "high")),
                Param("output_format", "string", "Image format; png by default", enum = listOf("png", "jpeg")),
                Param("background", "string", "Transparent requires png and the Azure service", enum = listOf("auto", "opaque", "transparent"))),
            example = "POST /v1/images/generations {\"prompt\":\"An otter in a purple scarf\"}",
            returns = "provider, model, request_id, data[{b64_json}], size, quality, output_format, usage, cost_usd, quota{limit,used,remaining,resets_at}. Idempotency-Key required; retry unknown outcomes only with the same key; results kept privately 24 hours",
            errors = listOf("invalid_parameter", "account_required", "image_quota_exceeded", "image_in_progress", "image_provider_failed", "image_outcome_unknown", "images_unavailable"))
        val IMAGE_USAGE = EndpointSpec("GET", "/images/usage", "judgment", "Read this linked account's shared monthly image allowance without generating an image",
            example = "GET /v1/images/usage", returns = "limit (40), used, remaining, period, timezone (UTC), resets_at")

        val JUDGMENT = EndpointSpec("POST", "/judgment", "judgment",
            "Ask Jev typed questions about text/JSON, or Clef about one retained sound/wake-word clip (audio is sent to the decision backend); run matching conditional actions",
            privateFields = setOf("state"), maxBody = 512 * 1024,
            body = listOf(
                Param("model","string","jev (default), clef (retained audio), or glm (GLM 5.3 Flash through OpenRouter)",enum=listOf("jev","clef","glm")),
                Param("audio_clip_id","string","With model clef: one retained clip id from sound.heard or listen.heard; needs its enabled sound/wake-word grant. Sends the clip to Clef; no new recording or local sound-model download."),
                Param("state", "string|object|array", "What the questions are about: text or JSON, up to about 512 KB. Text/JSON context; use model clef plus audio_clip_id to classify a retained audio clip", required = true),
                Param("questions", "object", "1-50 typed questions, {id: {type: choice|noul|..., instructions, criteria?}}, as https://docs.typesafe.ai/primitives", required = true),
                Param("actions", "array", "Up to 10 rules, bridgit-actions/1: {id, when: condition or 1-5 conditions (all must hold), do: action}. Condition: {answer, choice, min_confidence?} or {answer, min?, max?} for a probability. Action: {type: notice, title, text?} (a card from you), {type: speak, text} or {type: log, message}; plain text only, no links"),
            ),
            example = "POST /v1/judgment {\"state\":{\"battery_percent\":12,\"charging\":false},\"questions\":{\"charge\":{\"type\":\"choice\",\"instructions\":\"Should the owner plug in before a two-hour call?\",\"criteria\":{\"yes\":\"Plug in first\",\"no\":\"Fine as it is\"}}},\"actions\":[{\"id\":\"plug_in\",\"when\":{\"answer\":\"charge\",\"choice\":\"yes\",\"min_confidence\":0.8},\"do\":{\"type\":\"notice\",\"title\":\"Plug in before your call\",\"text\":\"Battery is at 12%.\"}}]}",
            returns = "model, answers {id: {type, choice+confidence | noul}}, usage, cost_usd, elapsed_ms, actions_schema (bridgit-actions/1), actions [{id, type, reason, ran, error?}] (only rules whose conditions held), skipped [{id, reason}]. When Jev fails you get an error and no action runs",
            errors = listOf("invalid_parameter", "rate_limited", "upstream_failed", "team_unavailable", "payload_too_large"))
    }
}
