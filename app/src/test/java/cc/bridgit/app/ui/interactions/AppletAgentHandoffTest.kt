package cc.bridgit.app.ui.interactions

import org.junit.Assert.*
import org.junit.Test

class AppletAgentHandoffTest {
    @Test fun creationNamesExactDeviceAndStartsWithOwnersGoalAndCompactGuide() {
        val text = AppletAgentHandoff.prompt(null, device = "Pixel 10", deviceId = "target-123", agentName = "Grok Bot")
        assertTrue(text.contains("target-123"))
        assertTrue(text.contains("device_id"))
        assertTrue(text.contains("Grok Bot"))
        assertTrue(text.contains("Add control agent"))
        assertTrue(text.contains("https://bridgit.cc/learn/builder/quickstart.md"))
        assertTrue(text.contains("Ask what I want"))
        assertTrue(text.contains("Do not use another device"))
    }

    @Test fun modificationRetainsExactInstanceAndPreservationInstructions() {
        val text = AppletAgentHandoff.prompt("installed-123", "My kitchen")
        assertTrue(text.contains("/v1/applets/installed/installed-123/development"))
        assertTrue(text.contains("Preserve its existing data and behavior"))
        assertFalse(text.contains("quickstart.md"))
    }
}
