package cc.bridgit.app.ui.home

/**
 * Groups the features that lost a permission into one Home banner per permission
 * the owner sees. Microphone, wake words and "listen for any sounds" all need the
 * microphone, so they share one "Microphone permission was removed." banner, and
 * its Grant button asks for everything those features need. Pure, so it can be unit tested.
 */
object PermissionBanners {
    enum class Kind { CAMERA, MIC, PHOTOS, LOCATION }

    /** A feature that is on but blocked: the missing permission's short name (from "permission:X") and what Grant should ask for. */
    data class Blocked(val missing: String, val request: List<String>)

    data class Group(val kind: Kind, val request: List<String>)

    fun kindOf(permission: String): Kind = when (permission.substringAfterLast('.')) {
        "CAMERA" -> Kind.CAMERA
        "RECORD_AUDIO" -> Kind.MIC
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION" -> Kind.LOCATION
        else -> Kind.PHOTOS
    }

    /** One group per kind, in the order the kinds first appear; permissions to request are merged without repeats. */
    fun group(blocked: List<Blocked>): List<Group> {
        val byKind = LinkedHashMap<Kind, LinkedHashSet<String>>()
        for (b in blocked) byKind.getOrPut(kindOf(b.missing)) { LinkedHashSet() } += b.request
        return byKind.map { (k, p) -> Group(k, p.toList()) }
    }
}
