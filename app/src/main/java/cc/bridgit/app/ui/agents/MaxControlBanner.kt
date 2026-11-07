package cc.bridgit.app.ui.agents

/**
 * The "Enable Max Agentic Control" card at the top of the Agents screen: the first thing to do until
 * Max Agentic Control is on. "On" is the same test as the switch in Settings (PowerAccess.everythingOn),
 * so the card goes away exactly when that switch reads on, and comes back if it is turned off.
 */
object MaxControlBanner {
    fun visible(maxControlOn: Boolean): Boolean = !maxControlOn
}
