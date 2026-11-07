package cc.bridgit.app.applets

import cc.bridgit.app.platform.FlowSteps
import cc.bridgit.app.judgment.ServiceCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

class AppletAuthoringValidationTest {
    private val validator = OfflineAppletValidator { File("../site-content/public/learn/endpoints.json").readText() }
    @Test fun validatePublishedPackagesAndFreshAuthorTrials() {
        val trials = File("../docs/applet-authoring-trials/2026-11-06")
        val packages = listOf(File("../applets"), File("../site-content/public/recipes")).flatMap { dir ->
            dir.listFiles()!!.map { File(it, "applet.json") }.filter(File::isFile)
        } + listOf("terra", "sol", "luna/final").map { File(trials, "$it/applet.json") }
        val failures = packages.mapNotNull { file ->
            runCatching { validator.validatePackage(file.readText()) }.exceptionOrNull()?.let { "$file: ${it.message}" }
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }

    @Test fun rejectInvalidTriggerScopeAndScheduleBeforeInstallation() {
        val source = File("../docs/applet-authoring-trials/2026-11-06/terra/applet.json").readText()
        fun rejected(reason: String, change: (JSONObject) -> Unit) {
            val doc = JSONObject(source).also(change)
            val error = runCatching { validator.validatePackage(doc.toString()) }.exceptionOrNull()
            check(error?.message?.contains(reason) == true) { "Expected $reason, got $error" }
        }
        rejected("seconds must be") { doc ->
            doc.getJSONObject("ui").remove("card")
            doc.getJSONObject("processing").getJSONObject("workflow")
                .put("trigger", JSONObject().put("type", "interval").put("seconds", 2))
        }
        rejected("decisions") { doc ->
            doc.getJSONObject("permissions").put("required", JSONArray(listOf("workflows", "interactions", "camera.snapshot")))
        }
        rejected("unknown_table") { doc ->
            val steps = JSONArray().put(JSONObject("""{"type":"share","table":"unknown_table","as":"csv"}"""))
            doc.getJSONObject("processing").getJSONObject("workflow").put("steps", steps)
        }
        rejected("after") { doc ->
            val action = JSONObject("""{"id":"loop","when":{"after":"loop"},"do":{"step":{"type":"notice","title":"Notice","text":"Loop"}}}""")
            doc.getJSONObject("processing").put("schedule", JSONObject().put("manifest", JSONObject().put("actions", JSONArray().put(action))))
        }
    }

    @Test fun eachDocumentedStepExamplePassesTheRuntimeChecker() {
        val schema = JSONObject(File("../site-content/public/applets/authoring/workflow-step.schema.json").readText())
        val alternatives = schema.getJSONArray("oneOf")
        val checked = mutableSetOf<String>()
        for (i in 0 until alternatives.length()) {
            val shape = alternatives.getJSONObject(i)
            val type = shape.getJSONObject("properties").getJSONObject("type").getString("const")
            check(checked.add(type))
            val examples = shape.getJSONArray("examples")
            for (index in 0 until examples.length()) {
                val step = examples.getJSONObject(index)
                check(step.getString("type") == type)
                FlowSteps.check(JSONArray().put(step), setOf("photo", "row"))
            }
        }
        check(checked == FlowSteps.STEPS.keys)
    }

    @Test fun offlineTriggerSchemaExamplesMatchRuntimeValidation() {
        val schema = JSONObject(File("../site-content/public/applets/authoring/workflow-trigger.schema.json").readText())
        val alternatives = schema.getJSONArray("oneOf")
        val documented = mutableSetOf<String>()
        for (i in 0 until alternatives.length()) {
            val alternative = alternatives.getJSONObject(i)
            val type = alternative.getJSONObject("properties").getJSONObject("type").getString("const")
            check(documented.add(type))
            val trigger = alternative.getJSONArray("examples").getJSONObject(0)
            check(trigger.getString("type") == type)
            if (type == "watch") {
                check(trigger.getJSONObject("watch").getString("\$watch") == "main")
                trigger.put("watch","installedWatch")
            }
            val flow = JSONObject().put("name","Trigger example").put("trigger",trigger)
                .put("steps",JSONArray().put(JSONObject().put("type","stop")))
            fun validate() = cc.bridgit.app.platform.WorkflowDefinition.validate(flow, {}, {})
            check(validate().getBoolean("valid"))
            trigger.put("unrecognized_example_field",true)
            check(runCatching { validate() }.isFailure) { "$type accepted an undocumented field" }
        }
        check(documented == setOf("button","called","charging","power","battery","sun","interval","geofence",
            "watch","mode","applet.event","application.event"))
    }

    @Test fun validateAndExportContract() {
        val input = System.getProperty("bridgit.applet.file").orEmpty()
        val out = File(System.getProperty("bridgit.applet.output") ?: "build/applet-authoring").apply { mkdirs() }
        val report = File(out, "validation.json")
        val result = try {
            validator.validatePackage(File(input.ifEmpty { "../site-content/public/recipes/room-change-watcher/applet.json" }).readText())
        } catch (e: Exception) {
            report.writeText(JSONObject().put("valid", false).put("error", e.message ?: e.javaClass.simpleName).toString(2))
            throw e
        }
        val steps = JSONObject()
        FlowSteps.STEPS.forEach { (type, definition) ->
            steps.put(type, JSONObject().put("fields", JSONArray(definition.first.toList().sorted()))
                .put("scope", definition.second ?: JSONObject.NULL))
        }
        File(out, "flow-contract.json").writeText(JSONObject().put("format", "bridgit-flow-authoring/1")
            .put("conformance", "field_inventory_not_complete_schema").put("steps", steps).put("common_fields", JSONArray(listOf("type", "id", "only_if", "on_error", "retries")))
            .put("max_steps", 32).put("max_paid_steps", FlowSteps.MAX_PAID_STEPS)
            .put("max_paid_per_day", FlowSteps.MAX_PAID_PER_DAY)
            .put("retry_delays_ms", JSONArray(FlowSteps.RETRY_DELAYS_MS)).toString(2))
        val services = ServiceCatalog.json(false, false)
        services.put("availability_is_live", false)
        val entries = services.getJSONArray("services")
        for (i in 0 until entries.length()) entries.getJSONObject(i).put("availability", "runtime_check_required")
        services.put("flow_authoring", JSONObject()
            .put("ask_model", JSONObject()
                .put("android",JSONArray(listOf("clef","jev","glm")))
                .put("ios_foreground",JSONArray(listOf("clef"))).put("macos_foreground",JSONArray(listOf("clef")))
                .put("apple_question_types",JSONArray(listOf("choice","score","noul")))
                .put("request_schema","workflow-step.schema.json")
                .put("result","answers.<question-id> holds the typed answer. See decisions.md for choice, score and probability representations.")
                .put("max_questions",10).put("max_paid_steps_per_run",FlowSteps.MAX_PAID_STEPS)
                .put("max_paid_per_day",FlowSteps.MAX_PAID_PER_DAY)
                .put("privacy","The hosted model receives the declared questions, state and referenced photo. Declare the transfer in permissions.remote and enable data_policy.device_only.remote_models.")
                .put("apple_prerequisites","Linked owner, network, monetary budget and consent to each submission. Use a paired Android host for sound watches.")
                .put("failure","An unavailable model, missing grant or exhausted budget stops a step under the default error policy. Inspect the run error and honor its retry policy. No implicit model substitution."))
            .put("signed_editions",JSONObject()
                .put("chef.signed-edition-budget.v2",JSONArray(listOf("scan","observe")))
                .put("appraise.signed-edition-budget.v2",JSONArray(listOf("appraise")))
                .put("reference","workflow-steps.md describes host support, output fields, edition proof and the data sent for each operation.")))
        File(out, "services.json").writeText(services.toString(2))
        report.writeText(result.toString(2))
    }
}
