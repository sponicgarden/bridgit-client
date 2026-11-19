package cc.bridgit.app.headless

/** A TV without touch still has a display. Only an absent/1px display selects headless automatically. */
object HeadlessPolicy {
    val MODES = listOf("auto", "headless")
    fun active(mode: String, width: Int, height: Int): Boolean =
        mode == "headless" || width <= 1 || height <= 1

    /** USB forwarding is an additional transport, never an authentication exemption. */
    fun sourceAllowed(headless: Boolean, source: String, tailnet: Boolean): Boolean =
        tailnet || (headless && source in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"))
}
