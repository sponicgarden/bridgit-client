package cc.bridgit.app.applets

import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.interact.CardActions
import cc.bridgit.app.capability.SpeakerRules
import cc.bridgit.app.platform.WorkflowDefinition
import cc.bridgit.app.schedule.ScheduleRules
import java.time.ZoneOffset
import cc.bridgit.app.watch.WatchRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Offline authoring tool: parses and checks; never installs, captures or calls a provider. */
class OfflineAppletValidator(private val endpointInventory: () -> String) {
    private val endpoints by lazy {
        val catalog = JSONObject(endpointInventory()).getJSONArray("endpoints")
        (0 until catalog.length()).map { index ->
            val op = catalog.getJSONObject(index)
            EndpointSpec(op.getString("method"), op.getString("path").removePrefix("/v1"), op.getString("capability"),
                op.getString("summary"), auth = op.getBoolean("auth"), example = "", returns = "")
        }
    }

    private fun endpoint(raw: JSONObject) = CardActions.match(endpoints, CardActions.parse(raw.toString())).first

    fun validatePackage(source: String): JSONObject {
        val p = AppletPackage.parse(source)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        val startable = plan.flows.filter { (_, flow) ->
            flow.getJSONObject("trigger").optString("type") in setOf("called", "button")
        }.map { it.first }.toSet()
        val watches = plan.watches.map { (_, request) ->
            WatchRules.parse(request, checkFlow = { ref, at ->
                require(ref in startable) { "$at: no startable package flow named $ref" }
            }, checkCall = { raw, _ -> endpoint(raw) })
        }
        plan.schedule?.let { manifest ->
            ScheduleRules.parse(manifest, ZoneOffset.UTC, System.currentTimeMillis(), SpeakerRules::urlAllowed,
                routeExists = { method, path ->
                    runCatching { endpoint(JSONObject().put("method", method).put("path", path)) }.exceptionOrNull()?.message
                }, flowProblem = { ref -> if (ref in startable) null else "No startable package flow named $ref" })
        }
        val watchIds = plan.watches.associate { (name, _) -> name to name }
        val scopes = AppletPackage.derived(plan, WatchRules::grantsFor, callCap = { call ->
            endpoint(JSONObject().put("method", call.substringBefore(' ')).put("path", call.substringAfter(' '))).capability
        }) { flow ->
            val resolved = AppletPackage.resolve(flow, null, watchIds) as JSONObject
            val check = WorkflowDefinition.validate(resolved, validateDestinations = {}, validateSend = {})
            AppletPackage.strings(check.getJSONArray("required_scopes")).toSet()
        }.let { if ("context" in it) it + "context.read" else it }
        val missing = AppletPackage.undeclared(p, scopes)
        require(missing.isEmpty()) { "Add to permissions.required: ${missing.sorted().joinToString()}" }
        val warnings = JSONArray()
        val usesModel = plan.flows.any { (_, flow) ->
            val steps = flow.getJSONArray("steps")
            (0 until steps.length()).any { steps.getJSONObject(it).optString("type") in setOf("ask_model", "appraise", "kitchen") }
        }
        if (usesModel || watches.any { it.paid }) {
            val document = JSONObject(source)
            val remote = document.optJSONObject("permissions")?.optJSONArray("remote")
            val policy = document.optJSONObject("data_policy")?.optJSONObject("device_only")
            if (remote == null || (0 until remote.length()).none { remote.optString(it).isNotBlank() } ||
                policy?.optBoolean("remote_models", false) != true) {
                warnings.put("For a hosted model, declare permissions.remote and set data_policy.device_only.remote_models to true. The library defaults remote_models to false.")
            }
        }
        return JSONObject().put("practice_compliance",AppletPractices.assess(p.definition)).put("valid", true).put("id", p.id).put("version", p.version)
            .put("warnings", warnings).put("sha256", p.sha256).put("required_scopes", JSONArray(scopes.sorted()))
            .put("checks", JSONArray(listOf("package", "default_config", "watch_syntax", "workflow_trigger_syntax", "flow_steps",
                "flow_limits_and_conditions", "schedule_syntax", "declared_permissions", "declarative_endpoint_and_flow_references")))
            .put("not_checked", JSONArray(listOf("live_service_bindings", "owner_grants", "platform_availability", "approved_destinations",
                "detached_media_bytes", "live_endpoint_authorization", "html_and_dynamic_actions", "external_effects", "upgrade_safety")))
    }


    companion object {
        @JvmStatic fun main(args: Array<String>) {
            if (args.size != 1) {
                System.err.println("Usage: java -jar validate-applet.jar applet.json")
                kotlin.system.exitProcess(64)
            }
            val validator = OfflineAppletValidator {
                checkNotNull(OfflineAppletValidator::class.java.getResourceAsStream("/endpoints.json"))
                    .bufferedReader().use { it.readText() }
            }
            val report = try {
                val source = File(args[0]).inputStream().use { it.readNBytes(131073) }
                require(source.size <= 131072) { "Package exceeds 128 KiB" }
                validator.validatePackage(source.decodeToString(throwOnInvalidSequence = true))
            } catch (e: Exception) {
                JSONObject().put("valid", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            println(report.toString(2))
            if (!report.getBoolean("valid")) kotlin.system.exitProcess(1)
        }
    }
}
