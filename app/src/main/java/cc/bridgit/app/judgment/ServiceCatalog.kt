package cc.bridgit.app.judgment

import org.json.JSONArray
import org.json.JSONObject

/** General applet services; discovery never captures, calls a provider, or grants access. */
object ServiceCatalog {
    fun links(): JSONObject = JSONObject().put("catalog_url", "/v1/services").put("mcp_path", "/v1/mcp").put("mcp_tool", "services")
        .put("model_preferences", "/v1/services/models").put("model_preferences_docs", "https://bridgit.cc/learn/model-preferences.md")
        .put("general_applet_models", JSONArray(listOf("glm", "jev", "clef")))
        .put("image_generation", "Azure Sunburst default; FLUX 3 Image Klein via task_key flux_image_generation; POST /v1/images/generations, shared 40 images per account per month")
        .put("glm", "GLM 5.3 Flash via OpenRouter; any applet can select model glm through its granted judgment, watch or flow APIs")

    fun json(backendConfigured: Boolean, paused: Boolean): JSONObject {
        val question = JSONObject().put("match", JSONObject().put("type", "noul").put("instructions", "Does this state match the applet's condition?"))
        val services = listOf("glm", "jev", "clef").map { id ->
            val name = ServiceBudgets.NAMES.getValue(id)
            JSONObject().put("id", id).put("name", name).put("applet_compatible", true).put("paid", true)
                .put("description", "General typed questions and decisions for any applet; not tied to a template.")
                .put("provider", when(id) { "glm" -> "OpenRouter"; "jev" -> "TypeSafe"; else -> "Cloudflare" })
                .apply { if(id == "glm") put("provider_model", "z-ai/glm-5.3-flash") }
                .put("inputs", JSONArray(listOf("text", "json", "images") + if(id == "clef") listOf("audio", "video") else emptyList()))
                .put("availability", JSONObject().put("backend_configured", backendConfigured).put("external_calls_paused", paused)
                    .put("permission_check", "/v1/capabilities"))
                .put("call", JSONObject().put("method", "POST").put("path", "/v1/judgment").put("model", id)
                    .put("required_capability", "judgment").put("request", JSONObject().put("model", id).put("state", "The applet's text or JSON context").put("questions", question)))
                .put("applet", JSONObject().put("watch", JSONObject().put("path", "/v1/watches").put("model", id).put("required_capability", "watches"))
                    .put("flow_step", JSONObject().put("type", "ask_model").put("model", id).put("questions", question))
                    .put("flow_scope", "decisions").put("media", "For photos use a camera watch or a flow photo step; retained audio uses Clef with its sound grant."))
                .put("budget", JSONObject().put("service", id).put("read", "/v1/services/budgets").put("configure_action", "service.budget.set")
                    .put("configuration", "/v1/owner/configure").put("owner_approval", true).put("applet_limits_also_apply", true))
                .put("docs", "https://bridgit.cc/learn/decisions.md")
        }
        val images = JSONObject().put("id", "images").put("name", "GPT Image 2.5 Sunburst").put("provider", "Microsoft Azure")
            .put("provider_model", "gpt-image-2.5-sunburst").put("paid", true).put("applet_compatible", true)
            .put("inputs", JSONArray(listOf("text"))).put("outputs", JSONArray(listOf("images")))
            .put("description", "Generate images through Azure; 40 images per account per calendar month UTC, shared by all devices and applet wordmarks.")
            .put("availability", JSONObject().put("backend_configured", backendConfigured).put("external_calls_paused", paused).put("permission_check", "/v1/capabilities").put("linked_account_required", true))
            .put("call", JSONObject().put("method", "POST").put("path", "/v1/images/generations").put("required_capability", "judgment").put("idempotency_key_required", true)
                .put("request", JSONObject().put("prompt", "Describe the image")))
            .put("quota", JSONObject().put("images_per_month", 40).put("scope", "account").put("timezone", "UTC").put("read", "/v1/images/usage"))
            .put("budget", JSONObject().put("service", "images").put("read", "/v1/services/budgets").put("configure_action", "service.budget.set").put("configuration", "/v1/owner/configure").put("applet_limits_also_apply", true))
            .put("docs", "https://bridgit.cc/learn/images.md")
        val flux = JSONObject(images.toString()).put("id", "flux_images").put("name", "FLUX 3 Image Klein").put("provider", "OpenRouter")
            .put("provider_model", "black-forest-labs/flux-3-image-klein")
            .put("description", "Generate images with FLUX through OpenRouter; shares the same 40-image account allowance with Azure and wordmarks. PNG/JPEG, standard quality, opaque backgrounds.")
            .put("call", JSONObject().put("method", "POST").put("path", "/v1/images/generations").put("required_capability", "judgment").put("idempotency_key_required", true)
                .put("request", JSONObject().put("prompt", "Describe the image").put("task_key", "flux_image_generation")))
            .put("formats", JSONArray(listOf("png", "jpeg"))).put("backgrounds", JSONArray(listOf("auto", "opaque")))
        return JSONObject().put("schema", "bridgit-services/1").put("services", JSONArray(services + listOf(images, flux)).apply { val contexts=ContextCatalog.services(); for(i in 0 until contexts.length()) put(contexts.getJSONObject(i).put("applet_compatible",true).put("availability",JSONObject().put("backend_configured",backendConfigured).put("external_calls_paused",paused).put("provider_key_status","not_checked").put("permission_check","/v1/capabilities"))) })
            .put("scope", "Any installed applet or control agent may select these services through its granted APIs. Discovery grants no access and makes no paid call.")
            .put("budgets", "/v1/services/budgets").put("model_preferences", "/v1/services/models")
    }
}
