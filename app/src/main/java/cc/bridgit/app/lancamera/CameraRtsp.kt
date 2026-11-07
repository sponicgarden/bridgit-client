package cc.bridgit.app.lancamera

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.io.ByteArrayOutputStream
import javax.net.SocketFactory
import cc.bridgit.app.outbound.Outbound

/** Fixed enrolled-host RTSP, TCP-interleaved RTP, bounded messages, no redirects or UDP endpoints. */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
class CameraRtsp(private val host: String, private val username: String, private val password: String,
    sockets: SocketFactory, maxMs: Long, cancellationJob:kotlinx.coroutines.Job?=null,
    /** Bytes received (video included) before the connection gives up; long streams pass a bigger budget. */
    private val maxBytes: Int = 16*1024*1024, private val authorized: () -> Unit) : java.io.Closeable {
    private val socket: Socket = run { require(LanCamera.localHost(host));authorized();sockets.createSocket() }
    private val cancellationHandle=cancellationJob?.invokeOnCompletion(onCancelling=true,invokeImmediately=true){cause->if(cause!=null)runCatching{socket.close()}}
    private val end = System.nanoTime() + maxMs*1_000_000
    private val watchdog: Thread
    private var input: java.io.InputStream
    private val output: java.io.OutputStream
    private var cseq = 0
    private var nonceCount = 0
    private var challenge: String? = null
    private var session: String? = null
    val base = "rtsp://$host:554/stream2"
    private var trackBase=base
    private var videoControl:String?=null
    private var audioChannel=0
    internal val diagnostics=org.json.JSONObject().put("packets",0)
    data class Response(val status: Int, val headers: Map<String,String>, val body: ByteArray)
    init {
        require(LanCamera.localHost(host))
        try { authorized();socket.connect(InetSocketAddress(host,554),3000); socket.soTimeout=500; input=socket.getInputStream(); output=socket.getOutputStream() }
        catch(e: Exception) { cancellationHandle?.dispose();socket.close(); throw e }
        watchdog=Thread { try { Thread.sleep(maxMs); socket.close() } catch(_:InterruptedException) {} }.apply { isDaemon=true; name="camera-rtsp-deadline"; start() }
    }
    private fun check() { authorized(); if(System.nanoTime()>=end) throw CameraFailure("Camera audio deadline exceeded.") }
    private var lastGuard=0L
    private var receivedBytes=0
    private fun byte(): Int {
        while(true) {
            if(System.nanoTime()-lastGuard>100_000_000L){check();lastGuard=System.nanoTime()}
            if(System.nanoTime()>=end) throw CameraFailure("Camera audio deadline exceeded.")
            try { val b=input.read(); if(++receivedBytes>maxBytes)throw CameraFailure("Camera stream exceeded the bounded transfer budget."); if(b<0) throw CameraFailure("Camera closed its audio stream."); return b }
            catch(_:java.net.SocketTimeoutException) { check() }
        }
    }
    private fun exact(size:Int):ByteArray { require(size in 0..65536); return ByteArray(size) { byte().toByte() } }
    private fun line(first:Int?=null):String {
        val out=ByteArrayOutputStream(); if(first!=null)out.write(first)
        while(out.size()<8192) { val b=byte(); if(b==10)return out.toString("ISO-8859-1").trimEnd('\r'); out.write(b) }
        throw CameraFailure("Oversized camera header.")
    }
    private fun response():Response {
        var first=byte(); var skipped=0
        while(first==36) { byte(); val n=(byte() shl 8) or byte(); exact(n); require(++skipped<256); check(); first=byte() }
        return response(first)
    }
    private fun response(first:Int):Response {
        val status=line(first).split(' ').getOrNull(1)?.toIntOrNull() ?: throw CameraFailure("Invalid RTSP response.")
        val headers=mutableMapOf<String,String>(); var total=0
        while(true) { val s=line(); total+=s.length; require(total<=32768); if(s.isEmpty())break; val i=s.indexOf(':'); require(i>0); headers[s.substring(0,i).lowercase()]=s.substring(i+1).trim() }
        val length=headers["content-length"]?.toIntOrNull() ?: 0; require(length in 0..65536)
        return Response(status,headers,exact(length))
    }
    fun request(method:String, uri:String=base, backchannel:Boolean=false, transport:String?=null):Response {
        check(); CameraAudio.control(base,uri)
        fun send():Response {
            write(method,uri,backchannel,transport)
            return response().also { require(it.headers["cseq"]?.toIntOrNull()==cseq){"Unexpected RTSP response sequence."} }
        }
        val started=System.currentTimeMillis()
        var r=send()
        if(r.status==401 && challenge==null) { challenge=r.headers["www-authenticate"] ?: throw CameraFailure("Camera authentication unavailable."); check(); r=send() }
        Outbound.record("rtsp://$host:554/stream2",method,"rtsp",status=r.status,startedAt=started)
        if(r.status==200) r.headers["session"]?.substringBefore(';')?.also { require(it.matches(Regex("[A-Za-z0-9_.-]{1,128}"))); session=it }
        return r
    }
    private fun write(method:String, uri:String, backchannel:Boolean=false, transport:String?=null) {
        val text=StringBuilder("$method $uri RTSP/1.0\r\nCSeq: ${++cseq}\r\nUser-Agent: Bridgit\r\n")
        if(method=="PLAY")text.append("Range: npt=0.000-\r\n")
        if(method=="DESCRIBE")text.append("Accept: application/sdp\r\n")
        if(backchannel)text.append("Require: www.onvif.org/ver20/backchannel\r\n")
        session?.let { text.append("Session: $it\r\n") }; transport?.let { text.append("Transport: $it\r\n") }
        challenge?.let { text.append("Authorization: ${CameraDigest.header(it,method,uri,username,password,++nonceCount)}\r\n") }
        output.write(text.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)); output.flush()
    }
    fun describe(backchannel:Boolean=false):List<CameraAudio.Track> {
        val r=request("DESCRIBE",backchannel=backchannel)
        if(backchannel && r.status in listOf(400,404,405,501,551)) return emptyList()
        if(r.status!=200)throw CameraFailure("Camera RTSP authentication or discovery failed.")
        require(r.headers["content-type"]?.substringBefore(';')?.trim()=="application/sdp")
        trackBase=r.headers["content-base"]?.let{CameraAudio.control(base,it)} ?: base
        val sdp=String(r.body,Charsets.UTF_8)
        videoControl=sdp.replace("\r","").split("\nm=").drop(1).firstOrNull{it.startsWith("video ")}?.lines()?.firstOrNull{it.startsWith("a=control:")}?.substringAfter("a=control:")
        return CameraAudio.tracks(sdp)
    }
    fun setup(track:CameraAudio.Track, backchannel:Boolean=false) {
        // Tapo C210 firmware stalls audio-only PLAY: negotiate its video track too, then discard video RTP without decoding/storing it.
        audioChannel=0
        if(!backchannel && videoControl!=null) {
            val video=request("SETUP",CameraAudio.control(trackBase,videoControl!!),false,"RTP/AVP/TCP;unicast;interleaved=0-1")
            if(video.status!=200)throw CameraFailure("Camera refused its required combined media session.")
            audioChannel=2
        }
        val channel=if(backchannel)0 else audioChannel
        val r=request("SETUP",CameraAudio.control(trackBase,track.control),backchannel,"RTP/AVP/TCP;unicast;interleaved=$channel-${channel+1}")
        if(r.status!=200 || session==null || r.headers["transport"]?.contains(Regex("interleaved=$channel-${channel+1}(?:;|$)"))!=true)throw CameraFailure("Camera refused TCP audio transport.")
        if(request("PLAY",trackBase,backchannel=backchannel).status!=200)throw CameraFailure("Camera refused audio playback.")
    }
    fun record(track:CameraAudio.Track, seconds:Int):ByteArray {
        require(track.supported && seconds in 1..30)
        setup(track)
        // Keep control headers interruptible byte-by-byte; buffer only high-volume interleaved media.
        if(input !is java.io.BufferedInputStream) input=input.buffered(64*1024)
        val pcm=ByteArrayOutputStream(); val max=seconds*track.rate*2
        var previous:Int?=null; var expectedTimestamp:Long?=null; var ssrc:List<Byte>?=null
        while(pcm.size()<max) {
            // byte() checks authorization at least every 100 ms while consuming media.
            // Rechecking Android permissions for every discarded video packet can starve audio.
            if(byte()!=36)throw CameraFailure("Unexpected camera stream data.")
            val channel=byte(); val length=(byte() shl 8) or byte(); val packet=exact(length)
            diagnostics.put("packets",diagnostics.optInt("packets")+1).put("last_channel",channel).put("last_payload",packet.getOrNull(1)?.toInt()?.and(127))
            if(channel!=audioChannel)continue
            val data=CameraAudio.payload(packet,track.payload) ?: continue
            val source=packet.slice(8..11)
            if(ssrc!=null && ssrc!=source)throw CameraFailure("Audio source changed mid-capture.")
            ssrc=source
            val seq=((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255)
            if(previous!=null && seq != (previous+1 and 65535))throw CameraFailure("Camera audio packet loss; clip was discarded.")
            previous=seq
            val timestamp=java.nio.ByteBuffer.wrap(packet,4,4).int.toLong() and 0xffffffffL
            diagnostics.put("rtp_timestamp",timestamp).put("expected_timestamp",expectedTimestamp).put("payload_bytes",data.size)
            var trim=0
            if(expectedTimestamp!=null && timestamp!=expectedTimestamp) {
                val raw=(timestamp-expectedTimestamp) and 0xffffffffL
                val delta=if(raw>0x7fffffffL)raw-0x100000000L else raw
                if(kotlin.math.abs(delta)>track.rate/4 || delta<0 && -delta>=data.size)throw CameraFailure("Camera audio timing discontinuity exceeds the bounded jitter window; clip was discarded.")
                if(delta>0) {
                    val inserted=diagnostics.optInt("inserted_silence_samples")+delta.toInt()
                    if(inserted>max/8)throw CameraFailure("Camera audio timing gaps exceed 25 percent; clip was discarded.")
                    diagnostics.put("inserted_silence_samples",inserted)
                    pcm.write(ByteArray(minOf(delta.toInt()*2,max-pcm.size())))
                } else { trim=(-delta).toInt();diagnostics.put("trimmed_overlap_samples",diagnostics.optInt("trimmed_overlap_samples")+trim) }
            }
            expectedTimestamp=(timestamp+data.size) and 0xffffffffL
            val bytes=CameraAudio.pcm(if(trim==0)data else data.copyOfRange(trim,data.size),track.codec)
            pcm.write(bytes,0,minOf(bytes.size,max-pcm.size()))
        }
        check()
        return pcm.toByteArray()
    }
    /**
     * Long-running microphone audio for the loudness stream (CameraMics): each audio packet's source, RTP timestamp
     * and decoded PCM go to [onPcm] (false stops) until [forMs] has passed. Packet loss doesn't end it (the caller's
     * CameraMicAudio.Join fills or restarts); video is read and dropped, never decoded or kept. An OPTIONS request
     * every [keepaliveMs] keeps the RTSP session alive; its answer is read and ignored between packets.
     */
    fun stream(track:CameraAudio.Track, forMs:Long, keepaliveMs:Long=30_000, onPcm:(ssrc:Int, timestamp:Long, pcm:ByteArray)->Boolean) {
        require(track.supported && track.direction in listOf("recvonly","sendrecv") && forMs>0)
        setup(track)
        if(input !is java.io.BufferedInputStream) input=input.buffered(64*1024)
        val until=System.nanoTime()+forMs*1_000_000
        var keepalive=System.nanoTime()+keepaliveMs*1_000_000
        while(System.nanoTime()<until) {
            if(System.nanoTime()>=keepalive) { write("OPTIONS",base); keepalive=System.nanoTime()+keepaliveMs*1_000_000 }
            val first=byte()
            if(first!=36) { val r=response(first); if(r.status!=200)diagnostics.put("keepalive_status",r.status); continue }
            val channel=byte(); val length=(byte() shl 8) or byte(); val packet=exact(length)
            diagnostics.put("packets",diagnostics.optInt("packets")+1)
            if(channel!=audioChannel)continue
            val data=CameraAudio.payload(packet,track.payload) ?: continue
            val ssrc=java.nio.ByteBuffer.wrap(packet,8,4).int
            val timestamp=java.nio.ByteBuffer.wrap(packet,4,4).int.toLong() and 0xffffffffL
            if(!onPcm(ssrc,timestamp,CameraAudio.pcm(data,track.codec)))return
        }
    }
    fun talk(track:CameraAudio.Track, pcm:ByteArray) {
        require(track.supported && pcm.size in 320..480000 && pcm.size%2==0)
        setup(track,true)
        val audio=if(track.codec=="PCMA")CameraAudio.alaw(pcm) else throw CameraFailure("Backchannel codec not implemented.")
        val ssrc=java.security.SecureRandom().nextInt(); var seq=0; var timestamp=0
        for(data in audio.toList().chunked(160)) {
            check(); val rtp=java.nio.ByteBuffer.allocate(12+data.size).put(0x80.toByte()).put(track.payload.toByte()).putShort((seq++).toShort()).putInt(timestamp).putInt(ssrc).put(data.toByteArray()).array()
            output.write(byteArrayOf(36,0,(rtp.size shr 8).toByte(),rtp.size.toByte())); output.write(rtp); output.flush(); timestamp+=data.size
            Thread.sleep(20)
        }
    }
    override fun close() { cancellationHandle?.dispose();watchdog.interrupt(); runCatching { socket.close() } }
}

/** RFC Digest with a supplied account only; no fallbacks, credential searches or bypasses. */
object CameraDigest {
    fun fields(challenge:String):Map<String,String> = Regex("([a-zA-Z_]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").findAll(challenge)
        .associate { it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groupValues[3]) }
    fun md5(s:String)=MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun header(challenge:String, method:String, uri:String, user:String, pass:String, nonceCount:Int=1):String {
        require(challenge.startsWith("Digest ",true) && challenge.length<=4096)
        val f=fields(challenge); val realm=f["realm"] ?: throw CameraFailure("Missing authentication realm."); val nonce=f["nonce"] ?: throw CameraFailure("Missing authentication nonce.")
        require((f["algorithm"] ?: "MD5").equals("MD5",true))
        require(listOf(realm,nonce,user,uri).all { v -> v.none { it<' ' || it=='"' || it=='\\' } })
        val ha1=md5("$user:$realm:$pass"); val ha2=md5("$method:$uri")
        val qop=f["qop"]?.split(',')?.map { it.trim() }; require(qop==null || "auth" in qop)
        val nc="%08x".format(nonceCount)
        val cnonce=UUID.randomUUID().toString().replace("-",""); val response=if(qop==null)md5("$ha1:$nonce:$ha2")else md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"" +
            (if(qop==null)"" else ", qop=auth, nc=$nc, cnonce=\"$cnonce\"") + (f["opaque"]?.let { require(it.none { c -> c<' ' || c=='"' || c=='\\' }); ", opaque=\"$it\"" } ?: "")
    }
}
