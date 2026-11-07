package cc.bridgit.app.api

import cc.bridgit.app.BuildConfig
import cc.bridgit.app.adb.AdbSetupHelp
import cc.bridgit.app.core.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbGuidanceTest {
    @Test fun agentsDiscoverRecoveryThroughPromptMcpAndGuide() {
        val address = Address("phone.example", null, 8734)
        val prompt = SetupPrompt.build("Agent", "test-key", Connection.USERSPACE, address, "Phone", emptyList(), "helper")
        assertEquals(BuildConfig.PRIVATE_CONTROL, prompt.contains(AdbSetupHelp.promptHint))
        assertEquals(BuildConfig.PRIVATE_CONTROL, McpProtocol.instructions("Agent").contains(AdbSetupHelp.agentHint))
        assertEquals(BuildConfig.PRIVATE_CONTROL, cc.bridgit.app.platform.PlatformApi.GUIDE.contains(AdbSetupHelp.agentHint))
        val guide = Guide.build("Agent", "test", address, emptyList(), "own", false, server = true)
        assertTrue(guide.contains(AdbSetupHelp.guide()))
    }
}
