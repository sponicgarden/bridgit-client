package cc.bridgit.app.api

import cc.bridgit.app.core.Agent
import org.json.JSONArray
import org.json.JSONObject

/**
 * API access (A0119/A0120): for now one permission governs the whole agent API. It is on by default for every agent,
 * all-or-nothing: with it an agent may call every API in the [registry] (each still applies its own grants, such as
 * Super Powers, control scopes and an issue's filer rules); without it every call is refused while the agent stays
 * registered and paired. Granular per-API permissions are future work, which is why every API group already has a
 * stable name and number.
 */
object ApiAccess {
    const val PERMISSION = "api_access"
    const val REASON = "api_access_off"

    fun refusal(agent: Agent): ApiError = ErrorCatalog.error("forbidden",
        "The owner turned off API access for ${agent.name} on this phone. Every API call is refused until they turn it back on; don't retry before then.",
        mapOf("reason" to REASON))

    /** Refuses [agent] when its API access is off; [agent] null (an unknown or removed id) is left to the caller. */
    fun require(agent: Agent?) { if (agent != null && !agent.apiAccess) throw refusal(agent) }

    /**
     * Registry numbers, frozen (A0120): an API group keeps its number for good. Add a new group at the END with the
     * next number; never renumber, reuse or remove a number (a removed group keeps its line, marked retired).
     * "platform" is the /v2 control API (tools over Tailscale, relay and MCP).
     */
    val NUMBERS: List<String> = listOf(
        // 1-56: numbered 9 Nov 2026 in alphabetical order of the groups that existed then. Append below.
        "adb", "app_device_access", "applet_data", "applet_install", "applet_portals", "budgets", "camera", "captures",
        "clipboard", "connections", "context", "controls", "downloads", "feedback", "files", "find_crop", "flashlight",
        "gallery", "gallery_video", "health_connect", "interactions", "judgment", "lan_camera", "lan_camera_audio",
        "lan_camera_speaker", "lan_control", "lan_discovery", "lan_enrollment", "location", "log", "mic", "modes",
        "notifications", "ocr", "outbound", "owner_features", "phone", "platform", "portal", "ring", "schedule",
        "screen", "screen_share", "sensors", "settings", "shares", "sounds", "speaker", "ssh", "status", "tour",
        "usage", "video", "wakewords", "watches", "weather",
    )
    const val PLATFORM = "platform"

    fun number(group: String): Int? = NUMBERS.indexOf(group).takeIf { it >= 0 }?.plus(1)

    /** The groups this build serves, with the module title where there is one. */
    data class Group(val number: Int, val id: String, val name: String, val calls: List<String>)

    fun groups(specs: List<EndpointSpec>, titles: Map<String, String>, platformTools: Collection<String>): List<Group> {
        val byGroup = specs.groupBy { it.capability }.mapValues { (_, s) -> s.map { "${it.method} ${it.fullPath}" }.distinct().sorted() }
            .toMutableMap()
        byGroup[PLATFORM] = platformTools.sorted().map { "tool $it" }
        return byGroup.map { (id, calls) ->
            Group(number(id) ?: error("API group $id has no registry number: append it to ApiAccess.NUMBERS"), id,
                titles[id] ?: if (id == PLATFORM) "Control platform (/v2 tools)" else id, calls)
        }.sortedBy { it.number }
    }

    /** GET /v1/registry: every API with its name, number, calls and permission, plus the caller's own API access. */
    fun registry(groups: List<Group>, caller: Agent?): JSONObject {
        val on = caller?.apiAccess ?: true
        return JSONObject()
            .put("permission", JSONObject().put("id", PERMISSION).put("name", "API access").put("default", true)
                .put("granular", false).put("you", on))
            .put("count", groups.size)
            .put("calls", groups.sumOf { it.calls.size })
            .put("apis", JSONArray(groups.map { g ->
                JSONObject().put("number", g.number).put("id", g.id).put("name", g.name)
                    .put("permission", PERMISSION).put("default", true).put("allowed", on)
                    .put("calls", JSONArray(g.calls))
            }))
    }
}
