package cc.bridgit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleHelpTest {
    @Test fun tailnetNameFromMagicDns() {
        assertEquals("tail1234.ts.net", TailscaleHelp.tailnetName("pixel-2-xl.tail1234.ts.net."))
        assertNull(TailscaleHelp.tailnetName(null))
        assertNull(TailscaleHelp.tailnetName("100.64.0.10"))
    }

    @Test fun keepOnCommandsCoverVpnPromptAlwaysOnAndBattery() {
        val c = TailscaleHelp.keepOnCommands.joinToString("\n")
        listOf("ACTIVATE_VPN allow", "always_on_vpn_app com.tailscale.ipn", "always_on_vpn_lockdown 0",
            "deviceidle whitelist +com.tailscale.ipn", "RUN_ANY_IN_BACKGROUND allow").forEach { assertTrue("missing $it", it in c) }
        // Lockdown would cut the phone's internet whenever Tailscale stops.
        assertFalse("always_on_vpn_lockdown 1" in c)
    }

    @Test fun computerPromptExplainsSameAccountAndChecksThePhone() {
        val p = TailscaleHelp.computerPrompt("pixel-2-xl.tail1234.ts.net", 8734, "tail1234.ts.net")
        listOf("tailscale status", "tailscale up", "same account", "no network to create", "tail1234.ts.net",
            "tailscale ping pixel-2-xl.tail1234.ts.net", "http://pixel-2-xl.tail1234.ts.net:8734/v1/health", "401",
            "brew install --cask tailscale", "winget install tailscale.tailscale", "install.sh", "userspace").forEach {
            assertTrue("missing $it", it in p)
        }
        assertFalse("no secrets in the prompt", "Bearer" in p)
    }

    @Test fun computerPromptWithoutAddressStillWorks() {
        val p = TailscaleHelp.computerPrompt(null, 8734, null)
        assertTrue("tailscale up" in p && "same account" in p)
        assertFalse("ping" in p)
    }

    @Test fun usbStepsInstallConnectAndKeepOn() {
        val s = TailscaleHelp.usbPromptSteps().joinToString("\n")
        listOf("pm path com.tailscale.ipn", "market://details?id=com.tailscale.ipn", "com.tailscale.ipn.CONNECT_VPN", "100.x")
            .forEach { assertTrue("missing $it", it in s) }
        TailscaleHelp.keepOnCommands.forEach { assertTrue("missing $it", it in s) }
    }
}
