package cc.bridgit.app.core.request

/**
 * One call as it arrives through a door (Client Core architecture 4.3, phase 1.6): the door's name from
 * contracts/client-core/services.json, the method and path, who sent it, and its headers. Each door translates
 * its own form into this before the shared checks run, so a later door can't skip one. Pure.
 */
data class CoreRequest(val door: String, val method: String, val path: String, val source: String, val header: (String) -> String?) {
    /** The bearer key, or null when the Authorization header is missing or isn't a bearer. */
    val bearer: String? get() = header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
}
