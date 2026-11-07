package cc.bridgit.app.portal

import cc.bridgit.app.lancamera.LanCamera
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Bridgit Portal's "Added to Bridgit" list: names, addresses and checks, never credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortalAddedDevicesTest {
    @Test fun `added cameras are listed without credentials`() {
        val cam = (LanCamera("lan:door", "Front door", "192.168.1.85", "secret-user", "secret-password", model = "C210",
            verifiedAt = 1_700_000_000_000, capabilityReport = JSONObject().put("audio_in", JSONObject().put("state", "available")),
            talkPassword = "private-cloud-password"))
        val list = PortalData.addedDevices(listOf(cam), emptyList()) { listOf("Pet monitor") }
        assertEquals(1, list.length())
        val c = list.getJSONObject(0)
        assertEquals("lan:door", c.getString("id")); assertEquals("Front door", c.getString("name"))
        assertEquals("192.168.1.85", c.getString("host")); assertEquals("camera", c.getString("kind")); assertEquals("on", c.getString("state"))
        assertEquals("Pet monitor", c.getJSONArray("applets").getString(0))
        assertEquals("available", c.getJSONObject("checks").getString("audio_in"))
        assertEquals("not_checked", c.getJSONObject("checks").getString("tapo_talk"))
        val text = list.toString()
        listOf("secret-user", "secret-password", "private-cloud-password").forEach { assertFalse(it, text.contains(it)) }
    }
}
