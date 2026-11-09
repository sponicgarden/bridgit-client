package cc.bridgit.app.link

/**
 * A0149: what the link header's two arrows show, derived from the two directions' [LinkModel.State] and service lists.
 * Pure (no Android), unit-tested. "out" points this device -> the other one (this device uses it), "in" the other way.
 */
object LinkDiagram {
    enum class Arrow { ON, PAUSED, WAITING, OFF }

    /**
     * [linked]: someone opted in (not [LinkModel.State.OFF]); [permitted]: both halves granted it (on or paused);
     * [enabled]: running right now (on, not paused). [services]: what may cross, in the canonical order; none while off.
     */
    data class Direction(val state: LinkModel.State, val arrow: Arrow, val linked: Boolean, val permitted: Boolean, val enabled: Boolean,
                         val services: List<String>)

    data class Model(val thisName: String, val farName: String, val out: Direction, val inn: Direction)

    fun direction(state: LinkModel.State, services: Set<String>): Direction {
        val arrow = when (state) {
            LinkModel.State.ON -> Arrow.ON
            LinkModel.State.PAUSED -> Arrow.PAUSED
            LinkModel.State.WAITING_FOR_TARGET, LinkModel.State.WAITING_FOR_USER -> Arrow.WAITING
            LinkModel.State.OFF -> Arrow.OFF
        }
        return Direction(state, arrow, linked = arrow != Arrow.OFF, permitted = arrow == Arrow.ON || arrow == Arrow.PAUSED, enabled = arrow == Arrow.ON,
            services = if (arrow == Arrow.OFF) emptyList() else ordered(services))
    }

    private val KNOWN = listOf("camera", "mic", "speaker", "interactions", "location", "gallery", "sounds", LinkModel.VIEW)

    /** Known services in a fixed order, then any other alphabetically, so a list never reshuffles. */
    fun ordered(services: Set<String>): List<String> = KNOWN.filter { it in services } + services.filter { it !in KNOWN }.sorted()

    /** [thisName] and [farName]: the friendly names (A0151); a domain name only where the caller found them confusable. */
    fun model(thisName: String, farName: String, outState: LinkModel.State, outServices: Set<String>, inState: LinkModel.State, inServices: Set<String>) =
        Model(thisName, farName, direction(outState, outServices), direction(inState, inServices))
}
