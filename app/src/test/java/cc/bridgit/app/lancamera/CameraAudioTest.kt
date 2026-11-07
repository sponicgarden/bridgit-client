package cc.bridgit.app.lancamera

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CameraAudioTest {
    private val sdp="""v=0
s=Camera
m=video 0 RTP/AVP 96
a=rtpmap:96 H264/90000
a=control:trackID=0
m=audio 0 RTP/AVP 8 0
a=control:trackID=1
a=rtpmap:8 PCMA/8000/1
a=recvonly
m=audio 0 RTP/AVP 8
a=control:trackID=2
a=sendonly
"""
    @Test fun discoversInputAndOutputWithoutClaimingAPhysicalMicrophoneOrSpeakerTest() {
        val t=CameraAudio.tracks(sdp)
        assertEquals(3,t.size);assertTrue(t.all{it.supported});assertEquals("PCMU",t[1].codec)
        val report=CameraAudio.manifest(t.filter{it.direction=="recvonly"},t,123)
        assertTrue(report.getJSONObject("audio_in").getBoolean("implemented"))
        assertTrue(report.getJSONObject("audio_out").getBoolean("implemented"))
        assertFalse(report.getJSONObject("audio_in").getBoolean("verified_capture"))
        assertFalse(report.getJSONObject("audio_out").getBoolean("audibly_verified"))
        assertEquals("not_advertised",CameraAudio.manifest(emptyList(),emptyList(),1).getJSONObject("audio_in").getString("state"))
        assertFalse(CameraAudio.tracks("v=0\nm=audio 0 RTP/SAVP 8\na=control:a").single().supported)
        assertTrue(CameraAudio.tracks("v=0\nm=audio 0 RTP/AVP 97\na=rtpmap:97 PCMU/16000\na=control:a").single().supported)
        assertFalse(CameraAudio.Track(0,"PCMU",8000,1,"a","sendonly").json().getBoolean("implemented"))
        assertEquals("inactive",CameraAudio.tracks("v=0\na=inactive\nm=audio 0 RTP/AVP 8\na=control:a").single().direction)
        assertFalse(CameraAudio.tracks("v=0\nm=audio 0 RTP/AVP 97\na=rtpmap:97 MPEG4-GENERIC/16000\na=control:a").single().supported)
    }
    @Test fun controlUrisCannotRedirectToAnotherHostPortSchemeOrEmbedCredentials() {
        val base="rtsp://192.168.1.85:554/stream2"
        assertEquals("$base/trackID=1",CameraAudio.control(base,"trackID=1"))
        assertEquals("rtsp://192.168.1.85:554/media/a",CameraAudio.control(base,"/media/a"))
        for(s in listOf("rtsp://8.8.8.8/a","rtsp://192.168.1.85:8800/a","rtsp://secret@192.168.1.85/a","http://192.168.1.85/a","//192.168.1.86/a","a\r\nAuthorization:evil"))
            assertThrows(s,IllegalArgumentException::class.java){CameraAudio.control(base,s)}
    }
    @Test fun parsesRtpHeaderExtensionsPaddingAndRejectsMalformedPackets() {
        val packet=byteArrayOf(0xb1.toByte(),8,0,1,0,0,0,0,1,2,3,4,0,0,0,1,0,1,0,1,5,6,7,8,0xd5.toByte(),0x55,0,2)
        assertArrayEquals(byteArrayOf(0xd5.toByte(),0x55),CameraAudio.payload(packet,8))
        assertNull(CameraAudio.payload(packet,0));assertNull(CameraAudio.payload(packet.copyOf(10),8))
        assertNull(CameraAudio.payload(packet.copyOf().also{it[it.lastIndex]=100.toByte()},8))
    }
    @Test fun g711SilenceIsNotMistakenForVerifiedRoomPickupAndWavHeadersAreExact() {
        assertArrayEquals(byteArrayOf(0,0,0,0),CameraAudio.pcm(byteArrayOf(0xff.toByte(),0x7f),"PCMU"))
        val pcm=CameraAudio.pcm(byteArrayOf(0xd5.toByte(),0x55),"PCMA")
        assertArrayEquals(byteArrayOf(8,0,-8,-1),pcm)
        assertArrayEquals(byteArrayOf(0xd5.toByte(),0x55),CameraAudio.alaw(pcm))
        val signal=CameraAudio.signal(pcm);assertEquals(0,signal.getInt("above_silence_samples"));assertFalse(signal.getBoolean("room_sound_verified"))
        val wav=CameraAudio.wav(pcm);assertEquals(48,wav.size)
        assertEquals("RIFF",String(wav.copyOfRange(0,4)));assertEquals(8000,ByteBuffer.wrap(wav,24,4).order(ByteOrder.LITTLE_ENDIAN).int)
    }
    @Test fun privateCloudCredentialRoundTripsWithoutPublicLeakAndLegacyCamerasStayUnknown() {
        val c=LanCamera("lan:test","Test","192.168.1.85","local-user","local-secret",talkPassword="cloud-secret")
        assertEquals("cloud-secret",LanCamera.fromJson(c.json()).talkPassword)
        assertFalse((c.publicJson().toString()+c.toString()).contains("cloud-secret"))
        assertFalse(c.publicJson().toString().contains("local-secret"))
        assertEquals("not_checked",LanCamera.fromJson(JSONObject(c.json().toString()).apply{remove("capabilities");remove("talk_password")}).publicJson().getJSONObject("capabilities").getString("state"))
        val report=CameraAudio.manifest(emptyList(),emptyList(),42)
        assertEquals("cloud-secret",c.verified(Onvif.Info("C210","v1",true,"p")).assessed(report).talkPassword)
    }
    @Test fun newApiSchemasHavePrivatePayloadsAndAllOwnerSwitchesStayOwnerOnly() {
        val specs=CameraAudioModule.ALL+LanCameraModule.PROBE
        assertTrue(cc.bridgit.app.api.Endpoints.all.containsAll(specs))
        assertTrue(cc.bridgit.app.api.McpTools.specs.containsAll(specs))
        assertTrue(CameraAudioModule.PLAY.privateFields.contains("pcm_s16le_base64"));assertTrue(CameraAudioModule.ACCOUNT.privateFields.contains("cloud_password"))
        for(key in listOf("lan_camera_audio","lan_camera_speaker"))
            org.junit.Assert.assertNull("Maximum-control configuration supports this feature switch", cc.bridgit.app.core.SettingsCatalog.features(listOf(key to key)).single().lock)
    }
    @Test fun mpegTsFramesContainValidPsiCrcAndContinuousAudioPes() {
        val mux=TalkMpegTs();val bytes=mux.header()+mux.audio(ByteArray(160){0xd5.toByte()},0)+mux.audio(ByteArray(160){0x55},1800)
        assertEquals(0,bytes.size%188);assertEquals(4,bytes.size/188)
        for(i in bytes.indices step 188)assertEquals(0x47,bytes[i].toInt() and 255)
        for(at in listOf(0,188)) {
            val offset=at+5+(bytes[at+4].toInt() and 255)
            assertEquals(0,bytes[offset].toInt())
            val section=offset+1;val size=3+(((bytes[section+1].toInt() and 15) shl 8) or (bytes[section+2].toInt() and 255))
            var crc=-1
            for(b in bytes.copyOfRange(section,section+size)){crc=crc xor ((b.toInt() and 255) shl 24);repeat(8){crc=if(crc<0)(crc shl 1)xor 0x04c11db7 else crc shl 1}}
            assertEquals(0,crc)
        }
    }
}
