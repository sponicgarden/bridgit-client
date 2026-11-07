package cc.bridgit.app.ui.addagent

import org.junit.Assert.assertEquals
import org.junit.Test

class AddAgentChecksTest {
    @Test fun onlyThreeSuccessfulChecksAdvanceAutomatically() {
        val states = AddAgentViewModel.Check.values()
        for (listener in states) for (tailscale in states) for (port in states) {
            val checks = AddAgentViewModel.Checks(listener, tailscale, port)
            assertEquals(
                "Automatic advance: $listener / $tailscale / $port",
                listOf(listener, tailscale, port).all { it == AddAgentViewModel.Check.OK },
                checks.allPassed
            )
            assertEquals(
                "Manual continue: $listener / $tailscale / $port",
                listener == AddAgentViewModel.Check.OK &&
                    tailscale == AddAgentViewModel.Check.OK &&
                    port in listOf(AddAgentViewModel.Check.OK, AddAgentViewModel.Check.WARN),
                checks.canContinue
            )
        }
    }
}
