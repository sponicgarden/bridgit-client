package cc.bridgit.app.speech

import cc.bridgit.app.platform.FlowSteps
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechRulesTest {
    @Test fun wavHasCanonicalHeaderAndDerivesDurationFromPcm() {
        val pcm = ByteBuffer.allocate(48000 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { pcm.putShort(1200); pcm.putShort(800) }
        val wav = SpeechEngine.wav(pcm.array(),48000,2)
        val b=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32044,wav.size); assertEquals(32000,b.getInt(40)); assertEquals(16000,b.getInt(24)); assertEquals(1000,b.getShort(44).toInt())
    }
    @Test fun clipsCannotExceedThirtySecondsOrLieAboutChannels() {
        assertThrows(IllegalArgumentException::class.java) { SpeechEngine.wav(ByteArray(16000*2*31),16000,1) }
        assertThrows(IllegalArgumentException::class.java) { SpeechEngine.wav(ByteArray(9),16000,2) }
    }
    @Test fun hearingRequiresMicrophoneGrantAndCountsExplicitCloudWork() {
        val c=FlowSteps.check(JSONArray("""[{"id":"hear","type":"hear","duration_seconds":5,"allow_cloud":true}]"""))
        assertTrue("microphone.clip" in c.scopes); assertEquals(1,c.paidSteps)
        assertThrows(Exception::class.java) { FlowSteps.check(JSONArray("""[{"id":"hear","type":"hear","duration_seconds":31}]""")) }
        assertThrows(Exception::class.java) { FlowSteps.check(JSONArray("""[{"id":"hear","type":"hear","allow_cloud":true,"on_error":"retry"}]""")) }
    }
    @Test fun speakingUsesRegistryRouteAndCarriesExplicitCloudPermission() {
        val call=FlowSteps.inlineCall(JSONObject("""{"type":"speak","text":"Hello","allow_cloud":true}"""),JSONObject())
        assertEquals("/v1/speech/speak",call.getString("path")); assertTrue(call.getJSONObject("body").getBoolean("allow_cloud"))
    }
}
