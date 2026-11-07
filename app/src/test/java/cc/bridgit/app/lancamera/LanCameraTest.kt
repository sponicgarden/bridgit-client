package cc.bridgit.app.lancamera

import cc.bridgit.app.dog.DogCamera
import cc.bridgit.app.dog.Reading
import org.junit.Assert.*
import org.junit.Test

class LanCameraTest {
    @Test fun restrictsEnrollmentToLiteralPrivateIPv4WithoutRedirectOrUrlInput() {
        listOf("192.168.1.85", "10.1.2.3", "172.16.1.2", "172.31.2.3").forEach { assertTrue(it, LanCamera.localHost(it)) }
        listOf("127.0.0.1", "169.254.169.254", "100.83.1.1", "8.8.8.8", "172.32.1.2", "192.168.1.0", "192.168.1.255",
            "192.168.01.85", "192.168.1.256", "192.168.1.85:2020", "user@192.168.1.85", "camera.local", "https://192.168.1.85").forEach {
            assertFalse(it, LanCamera.localHost(it))
        }
    }
    @Test fun wifiSubnetMatchingWorksOnAndroidTenWithoutNewPlatformApis() {
        fun ip(s: String) = java.net.InetAddress.getByName(s).address
        assertTrue(LanNetwork.sameSubnet(ip("192.168.1.23"), ip("192.168.1.85"), 24))
        assertFalse(LanNetwork.sameSubnet(ip("192.168.2.23"), ip("192.168.1.85"), 24))
        assertTrue(LanNetwork.sameSubnet(ip("10.2.5.1"), ip("10.2.7.4"), 16))
        assertFalse(LanNetwork.sameSubnet(ip("10.2.5.1"), ip("10.2.7.4"), 32))
        assertTrue(LanNetwork.sameSubnet(ip("10.2.5.1"), ip("10.2.5.1"), 32))
        assertFalse(LanNetwork.sameSubnet(ip("::1"), ip("10.2.5.1"), 24))
    }
    @Test fun onvifPolicyDoesNotBroadenPublicOrTailnetHttpRules() {
        val policy = cc.bridgit.app.core.SafeHttp.Policy.LOCAL_CAMERA
        assertNull(cc.bridgit.app.core.SafeHttp.urlProblem("http://192.168.1.85:2020/onvif/service", policy))
        listOf("http://8.8.8.8:2020/onvif/service", "http://127.0.0.1:2020/onvif/service", "http://camera.local:2020/onvif/service",
            "http://192.168.1.85:80/onvif/service", "http://192.168.1.85:2020/other", "http://user@192.168.1.85:2020/onvif/service",
            "http://192.168.1.85:2020/onvif/service?x=1").forEach { assertNotNull(it, cc.bridgit.app.core.SafeHttp.urlProblem(it, policy)) }
        assertNotNull(cc.bridgit.app.core.SafeHttp.urlProblem("http://192.168.1.85:2020/onvif/service", cc.bridgit.app.core.SafeHttp.Policy.PUBLIC))
    }
    @Test fun publicDescriptionAndToStringCannotExposeCredentials() {
        val c = LanCamera("lan:dog", "Dog", "192.168.1.85", "private-user", "private-password")
        val public = c.publicJson().toString() + c.toString()
        assertTrue(c.publicJson().getBoolean("credentials_configured"))
        assertFalse(public.contains("private-user")); assertFalse(public.contains("private-password"))
        assertEquals(setOf("id", "name", "host", "model", "firmware", "relative_ptz", "verified_at","credentials_configured","capabilities","talk_credentials_configured"), c.publicJson().keys().asSequence().toSet())
    }
    @Test fun cameraSelectionAndHistoryPreserveNetworkCameraIdentity() {
        val id = "lan:dog"
        val choice = DogCamera.pick(mapOf("rear" to .1, "front" to null, id to .95), "rear", 1)
        assertEquals(listOf(id), choice.cameras)
        assertEquals(listOf(id), DogCamera.pick(mapOf(id to .1, "rear" to .2), id, 2).cameras)
        val reading = Reading(1, true, .9, "sleeping", .9, "calm", .9, camera = id)
        assertEquals(id, Reading.from(reading.json()).camera)
        assertTrue(DogCamera.isMode(id)); assertFalse(DogCamera.isMode("rtsp://secret@example.com"))
    }
}
