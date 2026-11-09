package cc.bridgit.app.link

import cc.bridgit.app.api.EndpointSpec

/**
 * Link trust tiers (owner, 8 Nov 2026; docs/spec-linked-bridgits.md §16). Every link is in exactly one tier; the wire
 * field is `tier`, shared with the backend's link registration.
 *
 * - [OWN] "Your Other Devices": the owner's own Bridgits. Everything a link already allowed.
 * - [ASSOCIATE] "Devices of Associates": a device of another person, named by email. Only per-capability grants from
 *   [ASSOCIATE_SERVICES], with an end date at most [ASSOCIATE_MAX_MS] away; revocable from either side; never
 *   `/v1/satellite/…` (hub and satellite control is for the owner's own devices).
 * - [PUBLIC] "Devices in the Wild": unknown devices. At most one service from [PUBLIC_SERVICES], an expiry no more than
 *   [PUBLIC_MAX_MS] away (so its key never becomes a lasting credential), a low rate limit, and the owner's Allow for
 *   every request that isn't a read.
 *
 * Unknown or garbled tier values read as [PUBLIC] (the most restrictive). Records from before tiers existed were the
 * owner's own links by definition (LNK-01) and read as [OWN]; Portal pairings, whose one-time link can be handed to
 * anyone, read as [ASSOCIATE]. Pure: unit-tested.
 */
enum class LinkTier(val wire: String, val rank: Int) {
    OWN("own", 0), ASSOCIATE("associate", 1), PUBLIC("public", 2);

    companion object {
        /** Display order on Linked devices. */
        val ORDER = listOf(OWN, ASSOCIATE, PUBLIC)

        fun parse(s: String?): LinkTier? = values().firstOrNull { it.wire == s }

        /** A value that is present but not understood is treated as the most restrictive tier. */
        fun parseOrPublic(s: String?): LinkTier = parse(s) ?: PUBLIC

        /** Both devices must agree to widen: the tier that applies is the narrower of the two. */
        fun narrowest(a: LinkTier, b: LinkTier?): LinkTier = if (b != null && b.rank > a.rank) b else a
    }
}

object LinkTierPolicy {
    /** View status and Home, send cards (interactions), play a sound or speak: no sensors that watch or listen. */
    val ASSOCIATE_SERVICES = setOf(LinkModel.VIEW, "interactions", "speaker", "sounds")
    /** Read-only (view) or send one card. */
    val PUBLIC_SERVICES = setOf(LinkModel.VIEW, "interactions")

    const val DAY_MS = 86_400_000L
    const val ASSOCIATE_DEFAULT_MS = 30 * DAY_MS
    /** The backend's limit for associates (bridgit-cloud device-links: 90 days, 30 by default). */
    const val ASSOCIATE_MAX_MS = 90 * DAY_MS
    /** A migrated Portal pairing gets a review date, never a cut-off: it keeps working until the owner decides. */
    const val MIGRATED_REVIEW_MS = 90 * DAY_MS
    const val PUBLIC_DEFAULT_MS = 3600_000L
    const val PUBLIC_MAX_MS = DAY_MS

    fun allowedServices(t: LinkTier): Set<String> = when (t) {
        LinkTier.OWN -> LinkModel.SERVICES
        LinkTier.ASSOCIATE -> ASSOCIATE_SERVICES
        LinkTier.PUBLIC -> PUBLIC_SERVICES
    }

    /** Throws IllegalArgumentException with the reason when [services] can't be granted in tier [t]. */
    fun checkServices(t: LinkTier, services: Set<String>) {
        val extra = services - allowedServices(t)
        require(extra.isEmpty()) { "${extra.sorted().joinToString()} can't be granted to ${label(t)}. Allowed: ${allowedServices(t).sorted().joinToString()}" }
        if (t == LinkTier.PUBLIC) require(services.size <= 1) { "A device in the wild gets at most one service: ${PUBLIC_SERVICES.sorted().joinToString(" or ")}" }
    }

    /**
     * What a link agent of tier [t] may actually use, whatever its grants say: grants outside the tier never apply,
     * and a public link with more than one service keeps only view.
     */
    fun effectiveServices(t: LinkTier, services: Set<String>): Set<String> {
        val s = services.intersect(allowedServices(t))
        return if (t == LinkTier.PUBLIC && s.size > 1) s.intersect(setOf(LinkModel.VIEW)) else s
    }

    /**
     * The expiry a new grant of tier [t] gets from a requested lifetime [requestedMs] (null = the default).
     * Own links never expire (null). Throws IllegalArgumentException when the request is out of range.
     */
    fun expiry(t: LinkTier, requestedMs: Long?, now: Long): Long? = when (t) {
        LinkTier.OWN -> { require(requestedMs == null) { "Links between your own devices don't expire" }; null }
        LinkTier.ASSOCIATE -> { val ms = requestedMs ?: ASSOCIATE_DEFAULT_MS; require(ms in 60_000L..ASSOCIATE_MAX_MS) { "An associate's access lasts 1 minute to 90 days" }; now + ms }
        LinkTier.PUBLIC -> { val ms = requestedMs ?: PUBLIC_DEFAULT_MS; require(ms in 60_000L..PUBLIC_MAX_MS) { "A device in the wild gets 1 minute to 24 hours" }; now + ms }
    }

    /** Whether a key of this tier may outlive [PUBLIC_MAX_MS]: never for public links. */
    fun mayHoldLastingKey(t: LinkTier) = t != LinkTier.PUBLIC

    /** Link calls per minute from the other device. Own links keep the API's normal limits. */
    fun perMinute(t: LinkTier): Int? = when (t) { LinkTier.OWN -> null; LinkTier.ASSOCIATE -> 60; LinkTier.PUBLIC -> 10 }

    /**
     * Whether the link agent of tier [t] may call [spec] with [services] (its grants, already narrowed by
     * [effectiveServices]). On top of [LinkPolicy.mayCall]: only own links reach the `/v1/satellite/…` calls (hub and
     * satellite control), and the other tiers get their own link's calls (events, peers, disconnect) and the services.
     */
    fun mayCall(t: LinkTier, spec: EndpointSpec, services: Set<String>): Boolean {
        if (!LinkPolicy.mayCall(spec, services)) return false
        if (t == LinkTier.OWN) return true
        if (LinkPolicy.isPlumbing(spec)) return spec.path.startsWith("/link/")
        // The view return paths (hub Home, applets, photo submission) belong to the owner's own satellites.
        if (spec.path.startsWith("/satellite/")) return false
        return true
    }

    /** A public link's request that changes anything waits for the owner's Allow (reads and its own link calls don't). */
    fun needsApproval(t: LinkTier, spec: EndpointSpec): Boolean = t == LinkTier.PUBLIC && spec.method != "GET" && !LinkPolicy.isPlumbing(spec)

    private val EMAIL = Regex("[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,}")
    /** An associate's email as the backend takes it (lower case), or null when it isn't one. */
    fun email(s: String?): String? = s?.trim()?.lowercase()?.takeIf { it.length <= 254 && EMAIL.matches(it) }

    fun label(t: LinkTier) = when (t) { LinkTier.OWN -> "your other devices"; LinkTier.ASSOCIATE -> "an associate's device"; LinkTier.PUBLIC -> "a device in the wild" }

    /** Plain words for what the other side can do, as the API and the UI say them. */
    fun about(t: LinkTier) = when (t) {
        LinkTier.OWN -> "Your own device: uses what you share with it, with no end date."
        LinkTier.ASSOCIATE -> "Someone you name by email: only what you allow (view, cards, sounds), until the date you pick (90 days at most). Either side can disconnect."
        LinkTier.PUBLIC -> "Anyone with the code: view only or one thing, for up to a day. You approve each request."
    }
}
