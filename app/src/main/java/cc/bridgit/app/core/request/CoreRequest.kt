package cc.bridgit.app.core.request

/**
 * One call as it arrives through a door (Client Core architecture 4.3, phase 1.6): the door's name from
 * contracts/client-core/services.json, the method and path, who sent it, and its headers. Each door translates
 * its own form into this before the shared checks run, so a later door can't skip one. Pure.
 *
 * Doors on it: http_v1 (ApiServer.admitHttp), http_v2 (PlatformApi.admit), relay_job (RelayDispatcher), and the
 * in-process doors that run a v1 handler through ApiServer.dispatch: mcp (tools/call and MCP Apps card actions),
 * portal_bridge (card actions and watch rules), applet_step (schedules) and the configuration tools (http_v2).
 */
data class CoreRequest(val door: String, val method: String, val path: String, val source: String, val header: (String) -> String?) {
    /** The bearer key, or null when the Authorization header is missing or isn't a bearer. */
    val bearer: String? get() = header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

    /** The Idempotency-Key the caller sent (a header on HTTP, a field of the job or tool call elsewhere). */
    val idempotencyKey: String? get() = header(IDEMPOTENCY_KEY)

    companion object {
        const val HTTP_V1 = "http_v1"
        const val HTTP_V2 = "http_v2"
        const val MCP = "mcp"
        const val RELAY_JOB = "relay_job"
        const val PORTAL_BRIDGE = "portal_bridge"
        const val APPLET_STEP = "applet_step"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"

        /**
         * An in-process call of [method] [path] from [source]: no headers but the JSON content type and the
         * [idempotencyKey] the door carried in its own form (a tool argument, a job field, a schedule slot).
         */
        fun inProcess(door: String, method: String, path: String, source: String, idempotencyKey: String?) =
            CoreRequest(door, method, path, source) { name ->
                when {
                    name.equals("Content-Type", ignoreCase = true) -> "application/json"
                    name.equals(IDEMPOTENCY_KEY, ignoreCase = true) -> idempotencyKey
                    else -> null
                }
            }

        /** The door an in-process call's log marker [via] belongs to (contracts/client-core/doors/authorization.json dispatch_callers). */
        fun doorOf(via: String): String = when {
            via == "mcp" || via == "mcp_app" -> MCP
            via.startsWith("relay") -> RELAY_JOB
            via == "schedule" -> APPLET_STEP
            via == "control-configuration" -> HTTP_V2
            via == "http" -> HTTP_V1
            else -> PORTAL_BRIDGE
        }
    }
}
