package cc.bridgit.app.lancamera

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** SDP is untrusted data. It can select only a track on the enrolled host, never another destination. */
object CameraAudio {
    data class Track(val payload: Int, val codec: String, val rate: Int, val channels: Int, val control: String, val direction: String, val protocol: String = "RTP/AVP") {
        val supported get() = codec in listOf("PCMA", "PCMU") && rate in listOf(8000,16000) && channels == 1 && protocol == "RTP/AVP"
        val outputSupported get() = codec == "PCMA" && rate == 8000 && channels == 1 && protocol == "RTP/AVP"
        fun json() = JSONObject().put("codec",codec).put("sample_rate_hz",rate).put("channels",channels).put("direction",direction).put("transport",protocol).put("implemented",if(direction=="sendonly")outputSupported else direction!="inactive" && supported)
    }
    fun tracks(sdp: String): List<Track> {
        require(sdp.length <= 65536)
        val result = mutableListOf<Track>()
        val session=sdp.replace("\r", "").substringBefore("\nm=").lines().firstOrNull { it in listOf("a=sendonly","a=recvonly","a=sendrecv","a=inactive") }?.substringAfter("a=") ?: "recvonly"
        for (section in sdp.replace("\r", "").split("\nm=").drop(1)) {
            val lines = section.lines()
            val m = lines.first().trim().split(Regex("\\s+"))
            if (m.size < 4 || m[0] != "audio") continue
            val control = lines.firstOrNull { it.startsWith("a=control:") }?.substringAfter("a=control:") ?: continue
            val direction = lines.firstOrNull { it in listOf("a=sendonly", "a=recvonly", "a=sendrecv", "a=inactive") }?.substringAfter("a=") ?: session
            for (pt in m.drop(3).mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..127 } }) {
                val explicit = lines.firstOrNull { it.startsWith("a=rtpmap:$pt ") }?.substringAfter(' ')?.split('/')
                val codec = explicit?.getOrNull(0)?.uppercase() ?: when(pt) { 0 -> "PCMU"; 8 -> "PCMA"; else -> "UNKNOWN" }
                val rate = explicit?.getOrNull(1)?.toIntOrNull() ?: 8000
                val channels = explicit?.getOrNull(2)?.toIntOrNull() ?: 1
                result.add(Track(pt,codec.take(64),rate,channels,control,direction,m[2].take(32)))
            }
        }
        return result.take(32)
    }
    fun control(base: String, value: String): String {
        require(value.isNotBlank() && value.length <= 1024 && value.none { it < ' ' || it == '\\' })
        val original = URI(base)
        val uri = if (value.startsWith("rtsp://")) URI(value) else URI(base.trimEnd('/') + "/").resolve(value)
        require(uri.scheme == "rtsp" && uri.host == original.host && (uri.port == -1 || uri.port == 554) && uri.rawUserInfo == null && uri.rawFragment == null)
        return uri.toASCIIString()
    }
    fun payload(packet: ByteArray, expected: Int): ByteArray? {
        if (packet.size < 12 || (packet[0].toInt() ushr 6 and 3) != 2 || packet[1].toInt() and 127 != expected) return null
        val b = packet[0].toInt() and 255
        var at = 12 + (b and 15) * 4
        if (at > packet.size) return null
        if (b and 16 != 0) {
            if (at + 4 > packet.size) return null
            val words = ((packet[at+2].toInt() and 255) shl 8) or (packet[at+3].toInt() and 255)
            at += 4 + words * 4
        }
        val end = if (b and 32 != 0) packet.size - (packet.last().toInt() and 255).also { if (it == 0) return null } else packet.size
        return if (at <= end && end <= packet.size) packet.copyOfRange(at,end) else null
    }
    fun pcm(bytes: ByteArray, codec: String): ByteArray {
        require(codec in listOf("PCMA", "PCMU"))
        val out = ByteBuffer.allocate(bytes.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.forEach { b ->
            val sample = if (codec == "PCMA") {
                val a = (b.toInt() and 255) xor 0x55
                val segment = (a and 0x70) shr 4
                var t = (a and 15) shl 4
                t += if (segment == 0) 8 else 0x108
                if (segment > 1) t = t shl (segment - 1)
                if (a and 128 != 0) t else -t
            } else {
                val u = b.toInt().inv() and 255
                val t = (((u and 15) shl 3) + 0x84) shl ((u and 0x70) shr 4)
                if (u and 128 != 0) 0x84-t else t-0x84
            }
            out.putShort(sample.toShort())
        }
        return out.array()
    }
    fun alaw(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0)
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return ByteArray(pcm.size/2) {
            var sample = b.short.toInt()
            val mask = if (sample >= 0) 0xd5 else { sample = -sample - 1; 0x55 }
            sample = sample.coerceAtMost(32767)
            val segment = if (sample < 256) 0 else (31 - Integer.numberOfLeadingZeros(sample)) - 7
            val quant = if (segment == 0) sample shr 4 else sample shr (segment+3)
            ((segment shl 4 or (quant and 15)) xor mask).toByte()
        }
    }
    fun wav(pcm: ByteArray, rate: Int = 8000): ByteArray = ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36+pcm.size); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(rate); putInt(rate*2); putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()
    fun signal(pcm: ByteArray): JSONObject {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0; var nonzero = 0; var energy = 0.0
        while(b.remaining() >= 2) { val x = b.short.toInt(); peak = maxOf(peak,kotlin.math.abs(x)); if(kotlin.math.abs(x)>16) nonzero++; energy += x.toDouble()*x }
        val n = pcm.size/2
        return JSONObject().put("peak_pcm",peak).put("rms_pcm",if(n==0)0.0 else kotlin.math.sqrt(energy/n))
            .put("above_silence_samples",nonzero).put("room_sound_verified",false)
            .put("note","Decodable audio is not proof of microphone pickup. Low-level G.711 silence and muted sources can decode successfully.")
    }
    fun manifest(tracks: List<Track>, back: List<Track>, checked: Long) = JSONObject().put("checked_at",checked).put("probe","RTSP DESCRIBE only; no recording or speaker playback")
        .put("audio_in", JSONObject().put("state",if(tracks.isEmpty())"not_advertised" else "advertised").put("tracks",JSONArray(tracks.map { it.json() }))
            .put("implemented",tracks.any { it.supported && it.direction in listOf("recvonly","sendrecv") }).put("verified_capture",false))
        .put("audio_out",JSONObject().put("state",if(back.any { it.direction == "sendonly" })"advertised" else "not_advertised")
            .put("tracks",JSONArray(back.filter { it.direction == "sendonly" }.map { it.json() })).put("implemented",back.any { it.outputSupported && it.direction == "sendonly" })
            .put("audibly_verified",false).put("tapo_proprietary","Requires separate Tapo talk enrollment with authorized cloud password; RTSP absence does not imply no physical speaker."))
}
