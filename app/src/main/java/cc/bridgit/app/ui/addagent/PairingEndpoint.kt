package cc.bridgit.app.ui.addagent

import cc.bridgit.app.api.Address

/** Export only a live listener, never a configured fallback or an old prompt's address. */
internal object PairingEndpoint {
    data class Snapshot(val enabled: Boolean, val listening: Boolean, val address: Address, val configuredPort: Int) {
        val ready get() = enabled && listening && address.ip != null &&
            address.port in 1024..65535 && address.port == configuredPort
    }

    suspend fun verified(snapshot: () -> Snapshot, reachable: suspend (Address) -> Boolean): Address? {
        val before = snapshot()
        if (!before.ready || !reachable(before.address)) return null
        // The listener can rebind while the socket check is in flight.
        return before.address.takeIf { snapshot() == before }
    }
}
