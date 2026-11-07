package cc.bridgit.app.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class UsbSetupTest {
    @Test fun stepsCoverCableDeveloperOptionsUsbDebuggingAllowAndPrompt() {
        val text = UsbSetup.steps.joinToString("\n") { it.title + " " + it.how }
        assertEquals(5, UsbSetup.steps.size)
        listOf("USB cable", "Claude Code", "Codex", "Cursor", "Build number 7 times", "USB debugging", "Allow", "Share prompt")
            .forEach { assertTrue("missing $it", it in text) }
    }

    @Test fun promptNamesPackageAndEveryCommand() {
        val cmds = listOf("pm grant cc.bridgit.app android.permission.WRITE_SECURE_SETTINGS", "appops set cc.bridgit.app GET_USAGE_STATS allow")
        val p = UsbSetup.prompt("cc.bridgit.app", cmds)
        assertTrue("cc.bridgit.app" in p)
        cmds.forEach { assertTrue(it in p) }
        listOf("adb devices", "unauthorized", "-s <serial>", "platform-tools", "adb shell \"<command>\"").forEach { assertTrue("missing $it", it in p) }
        assertTrue("checks Tailscale too", "com.tailscale.ipn.CONNECT_VPN" in p && "always_on_vpn_app" in p)
    }

    @Test fun exportedAccessibilityCommandsRequireLiveMergeAndVerifiedOutcome() {
        val p = UsbSetup.prompt("cc.bridgit.app.test", listOf(
            "settings put secure enabled_accessibility_services 'other/.Service:cc.bridgit.app.test/.Screen'"))
        assertTrue(p.indexOf("LIVE") < p.indexOf("settings put secure enabled_accessibility_services"))
        assertTrue("Do not overwrite other services" in p)
        assertFalse("other/.Service" in p)
        assertTrue("Add only these Bridgit components: cc.bridgit.app.test/.Screen" in p)
        assertTrue("zero exit code alone is not proof" in p)
        assertTrue("package:cc.bridgit.app.test" in p)
        assertTrue("Never grant yourself Agent Super Powers" in p)
        assertTrue("Max Agentic Control" in p)
        assertTrue("Allow restricted settings" in p)
    }
}
