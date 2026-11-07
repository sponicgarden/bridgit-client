package cc.bridgit.app.lancamera

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortalCameraFeaturesTest {
    private fun camera(report: JSONObject? = null) = LanCamera("lan:dog", "Dog", "192.168.1.85", "secret-user", "secret-password", model = "C210", capabilityReport = report, talkPassword = "private-cloud-password")
    @Test fun modelAndCredentialsDoNotProveSupport() {
        val result = PortalCameraFeatures.json(camera(), true, true, true)
        for (key in listOf("photo", "video", "microphone", "speaker", "pan_tilt")) assertEquals("not_checked", result.getString(key))
        assertEquals("not_supported", result.getString("record_video"))
    }
    @Test fun reportsDetectedSupportSeparatelyFromSwitchesAndTalkSetup() {
        val report = JSONObject("""{"checked_at":123,"host":"private","video":{"advertised":true,"implemented":true},"audio_in":{"state":"advertised","implemented":true},"audio_out":{"state":"not_advertised","implemented":false},"tapo_talk":{"state":"credentials_required","implemented":true},"pan_tilt":{"advertised":true,"implemented":true}}""")
        val result = PortalCameraFeatures.json(camera(report), true, false, true)
        assertEquals("available", result.getString("photo")); assertEquals("available", result.getString("video"))
        assertEquals("off", result.getString("microphone")); assertEquals("needs_setup", result.getString("speaker"))
        assertEquals("available", result.getString("pan_tilt"))
        report.getJSONObject("tapo_talk").put("state", "authenticated")
        assertEquals("available", PortalCameraFeatures.json(camera(report), true, true, true).getString("speaker"))
        val off = PortalCameraFeatures.json(camera(report), false, true, true)
        for (key in listOf("photo", "video", "microphone", "speaker", "pan_tilt")) assertEquals("off", off.getString(key))
        assertFalse(result.toString().contains("private")); assertFalse(result.toString().contains("secret"))
    }
    @Test fun failedDiscoveryAndUnsupportedCodecsStayExplicit() {
        val report = JSONObject("""{"audio_in":{"state":"advertised","implemented":false},"audio_out":{"state":"unverified","implemented":false},"pan_tilt":{"advertised":false,"implemented":false}}""")
        val result = PortalCameraFeatures.json(camera(report), true, true, true)
        assertEquals("not_supported", result.getString("microphone"))
        assertEquals("unverified", result.getString("speaker"))
        assertEquals("not_advertised", result.getString("pan_tilt"))
    }
}
