package cc.bridgit.app.satellite

import cc.bridgit.app.api.EndpointSpec

/**
 * What may pass through a hub–satellite link, and how a forwarded call is reshaped. Pure, unit-tested.
 * Phase 1 (docs/spec-satellites.md §7): the capabilities below. Phase 2 widens this to every endpoint except an
 * exclusion list (SAT-19, SAT-40).
 */
object SatellitePolicy {
    /** Capabilities a satellite offers its hub in phase 1, and the grant set its hub-link agent gets. */
    val PHASE1_CAPS = setOf("camera", "mic", "speaker", "interactions", "location", "gallery", "sounds")

    /** Read-only status calls that also pass, so an agent can check a satellite before using it. */
    private val STATUS_GETS = setOf("/health", "/capabilities", "/device/profile")

    /** The only calls a satellite's return link may make on its hub (SatelliteHub.pushHubInfo). */
    val RETURN_PATHS = setOf("/satellite/hub", "/satellite/hub/photo", "/satellite/hub/portal-login", "/portal/home", "/portal/logs", "/portal/devices", "/satellite/hub/unlink",
        "/satellite/hub/agents", "/satellite/hub/agents/{id}/pause")
    /** The return link's grant: the satellite reads the hub's Home through two portal reads. */
    val RETURN_CAPS = setOf("portal")

    /** What a hub link may call on its satellite: what passes a link, plus the phone-to-phone calls. */
    fun hubLinkMay(spec: EndpointSpec): Boolean = forwardable(spec) || spec.path == "/satellite/manifest" || spec.path == "/satellite/hub-info" || spec.path == "/satellite/unlink"

    const val TARGET_HEADER = "X-Bridgit-Target"
    const val TARGET_QUERY = "target"
    const val ON_BEHALF_HEADER = "X-Bridgit-On-Behalf-Of"
    const val HUB_NAME_HEADER = "X-Bridgit-Hub-Name"
    const val RAN_ON_HEADER = "X-Bridgit-Ran-On"
    const val SAT_REQUEST_ID_HEADER = "X-Bridgit-Satellite-Request-Id"

    fun forwardable(spec: EndpointSpec): Boolean =
        spec.auth && spec.power == null && (spec.capability in PHASE1_CAPS || (spec.method == "GET" && spec.path in STATUS_GETS))

    /** The target an agent named: null = run here (absent, blank or "hub"). */
    fun target(header: String?, query: String?): String? =
        (header?.trim()?.takeIf { it.isNotEmpty() } ?: query?.trim()?.takeIf { it.isNotEmpty() })
            ?.lowercase()?.takeIf { it != "hub" }

    /**
     * The Idempotency-Key sent on to the satellite. The satellite sees one caller (the hub), so each hub agent's
     * keys get a prefix of their own and two agents can't collide. Stays within IdempotencyCache's 8-128 rule.
     */
    fun forwardedIdempotencyKey(agentId: String, key: String): String =
        ("h" + agentId.filter { it.isLetterOrDigit() }.take(40) + "-" + key).take(128)

    /** "<agent id>,<agent name>" for [ON_BEHALF_HEADER]; the name loses commas and control characters. */
    fun onBehalfOf(agentId: String, agentName: String): String =
        agentId + "," + agentName.filterNot { it.isISOControl() || it == ',' }.take(60)

    /** Parses [ON_BEHALF_HEADER]: (id, name), or null if malformed. */
    fun parseOnBehalfOf(v: String?): Pair<String, String>? {
        val s = v?.trim() ?: return null
        val i = s.indexOf(','); if (i <= 0) return null
        val id = s.substring(0, i).takeIf { Regex("[A-Za-z0-9_-]{1,64}").matches(it) } ?: return null
        val name = s.substring(i + 1).filterNot { it.isISOControl() }.trim().take(60).ifEmpty { return null }
        return id to name
    }

    /** Request headers never sent on: the caller's key, hop-by-hop headers, and the target itself. */
    val DROPPED_REQUEST_HEADERS = setOf("authorization", "host", "connection", "content-length", "transfer-encoding",
        "x-bridgit-target", "x-bridgit-on-behalf-of", "x-bridgit-hub-name", "idempotency-key", "keep-alive", "upgrade", "te",
        "accept-encoding")

    /** Response headers not copied back from the satellite (the hub sets its own). */
    val DROPPED_RESPONSE_HEADERS = setOf("x-request-id", "content-length", "transfer-encoding", "connection", "content-type",
        "keep-alive", "date", "server", "content-encoding", "x-bridgit-api-revision", "x-bridget-api-revision",
        "x-bridgit-whats-new", "x-bridget-whats-new")
}
