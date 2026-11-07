package cc.bridgit.app.lancamera

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import org.json.JSONObject
import javax.net.SocketFactory
import cc.bridgit.app.outbound.Outbound

/** Authenticated Tapo /stream talk adapter. Never accepts unauthenticated/"none" authentication.
 * Protocol reference: https://github.com/AlexxIT/go2rtc/tree/master/pkg/tapo (MIT).
 * Uses only an explicitly supplied cloud password; it is never substituted for the local RTSP account.
 */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
class TapoTalk(host:String, cloudPassword:String, sockets:SocketFactory, maxMs:Long, cancellationJob:kotlinx.coroutines.Job?=null, private val authorized:()->Unit) : java.io.Closeable {
    private val socket=run {require(LanCamera.localHost(host) && cloudPassword.isNotEmpty() && cloudPassword.length<=256);authorized();sockets.createSocket()}
    private val cancellationHandle=cancellationJob?.invokeOnCompletion(onCancelling=true,invokeImmediately=true){cause->if(cause!=null)runCatching{socket.close()}}
    private val deadline=System.nanoTime()+maxMs*1_000_000
    private val watchdog:Thread
    private val input:java.io.InputStream
    private val output:java.io.OutputStream
    init {
        require(LanCamera.localHost(host) && cloudPassword.isNotEmpty() && cloudPassword.length<=256)
        try { authorized();socket.connect(InetSocketAddress(host,8800),3000);socket.soTimeout=500;input=socket.getInputStream();output=socket.getOutputStream() }
        catch(e:Exception){cancellationHandle?.dispose();socket.close();throw e}
        watchdog=Thread { try{Thread.sleep(maxMs);socket.close()}catch(_:InterruptedException){} }.apply { isDaemon=true;name="tapo-talk-deadline";start() }
        try {
            val started=System.currentTimeMillis()
            fun handshake(auth:String?=null):Pair<Int,Map<String,String>> {
                authorized()
                val text="POST /stream HTTP/1.1\r\nHost: $host:8800\r\nContent-Type: multipart/mixed; boundary=--client-stream-boundary--\r\n"+
                    (auth?.let{"Authorization: $it\r\n"} ?: "")+"\r\n"
                output.write(text.toByteArray());output.flush()
                val status=line().split(' ').getOrNull(1)?.toIntOrNull()?:throw CameraFailure("Invalid Tapo handshake.")
                val headers=headers()
                if(status!=200) { val size=headers["content-length"]?.toIntOrNull()?:0;require(size in 0..65536);exact(size) }
                return status to headers
            }
            val first=handshake();require(first.first==401){"Authenticated Tapo digest challenge required."}
            val challenge=first.second["www-authenticate"]?:throw CameraFailure("Tapo authentication unavailable.")
            val fields=CameraDigest.fields(challenge)
            require(fields["username"]!="none")
            val algorithm=when(fields["encrypt_type"]) { "3"->"SHA-256"; null,"1","2"->"MD5";else->throw CameraFailure("Unsupported Tapo password hashing mode.") }
            val hashed=MessageDigest.getInstance(algorithm).digest(cloudPassword.toByteArray()).joinToString(""){"%02X".format(it)}
            val second=handshake(CameraDigest.header(challenge,"POST","/stream","admin",hashed))
            Outbound.record("http://$host:8800/stream","AUTH","tapo",status=second.first,startedAt=started)
            require(second.first==200 && CameraDigest.fields(second.second["key-exchange"].orEmpty())["username"]!="none"){"Tapo cloud-password authentication failed (HTTP ${second.first})."}
        } catch(e:Exception) { close();throw e }
    }
    private fun check(){authorized();if(System.nanoTime()>=deadline)throw CameraFailure("Tapo talk deadline exceeded.")}
    private var lastGuard=0L
    private var receivedBytes=0
    private fun byte():Int {while(true){if(System.nanoTime()-lastGuard>100_000_000L){check();lastGuard=System.nanoTime()};if(System.nanoTime()>=deadline)throw CameraFailure("Tapo talk deadline exceeded.");try{val b=input.read();require(++receivedBytes<=256*1024);if(b<0)throw CameraFailure("Tapo closed its talk session.");return b}catch(_:java.net.SocketTimeoutException){check()}}}
    private fun exact(n:Int)=ByteArray(n){byte().toByte()}
    private fun line():String {val o=ByteArrayOutputStream();while(o.size()<8192){val b=byte();if(b==10)return o.toString("ISO-8859-1").trimEnd('\r');o.write(b)};throw CameraFailure("Oversized Tapo header.")}
    private fun headers():Map<String,String>{val m=mutableMapOf<String,String>();var size=0;while(true){val l=line();size+=l.length;require(size<=32768);if(l.isEmpty())return m;val i=l.indexOf(':');require(i>0);m[l.substring(0,i).lowercase()]=l.substring(i+1).trim()}}
    private fun part(type:String, bytes:ByteArray, session:String?=null){check();val h="----client-stream-boundary--\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n"+(session?.let{"X-If-Encrypt: 0\r\nX-Session-Id: $it\r\n"}?:"")+"\r\n";output.write(h.toByteArray());output.write(bytes);output.write("\r\n".toByteArray());output.flush()}
    fun play(pcm:ByteArray) {
        require(pcm.size in 320..480000 && pcm.size%2==0)
        part("application/json","""{"params":{"talk":{"mode":"aec"},"method":"get"},"seq":3,"type":"request"}""".toByteArray())
        var boundary=line();while(boundary.isEmpty())boundary=line()
        require(boundary=="----device-stream-boundary--")
        val h=headers();require(h["content-type"]?.startsWith("application/json")==true)
        val length=h["content-length"]?.toIntOrNull()?:throw CameraFailure("Missing Tapo reply length.");require(length in 1..8192)
        val reply=JSONObject(String(exact(length),Charsets.UTF_8))
        require(!reply.has("error_code") || reply.optInt("error_code")==0)
        val session=reply.optJSONObject("params")?.optString("session_id").orEmpty()
        require(session.matches(Regex("[a-zA-Z0-9_.-]{1,128}"))){"Tapo did not grant a talk session."}
        val mux=TalkMpegTs()
        part("audio/mp2t",mux.header(),session)
        val encoded=CameraAudio.alaw(pcm)
        for ((i, chunk) in encoded.toList().chunked(160).withIndex()) {
            check();part("audio/mp2t",mux.audio(chunk.toByteArray(),i*1800L),session);Thread.sleep(20)
        }
    }
    override fun close(){cancellationHandle?.dispose();watchdog.interrupt();runCatching{socket.close()}}
}

/** A single G.711 A-law elementary stream (Tapo stream type 0x90) in bounded MPEG-TS/PES. */
class TalkMpegTs {
    private val counters=mutableMapOf<Int,Int>()
    private fun crc(bytes:ByteArray):Int {var n=-1;for(b in bytes){n=n xor ((b.toInt() and 255) shl 24);repeat(8){n=if(n<0)(n shl 1) xor 0x04c11db7 else n shl 1}};return n}
    private fun psi(pid:Int, body:ByteArray):ByteArray {
        val c=crc(body);val payload=byteArrayOf(0)+body+java.nio.ByteBuffer.allocate(4).putInt(c).array()
        return packets(pid,payload,true)
    }
    fun header():ByteArray = psi(0,byteArrayOf(0,0xb0.toByte(),13,0,1,0xc1.toByte(),0,0,0,1,0xe0.toByte(),0x20))+
        psi(32,byteArrayOf(2,0xb0.toByte(),18,0,1,0xc1.toByte(),0,0,0xff.toByte(),0xff.toByte(),0xf0.toByte(),0,0x90.toByte(),0xe0.toByte(),0x40,0xf0.toByte(),0))
    fun audio(alaw:ByteArray, pts:Long):ByteArray {
        require(alaw.size in 1..8000)
        val n=alaw.size+8
        val time=byteArrayOf(((2 shl 4) or (((pts shr 30).toInt() and 7) shl 1) or 1).toByte(),(pts shr 22).toByte(),((((pts shr 15).toInt() and 127) shl 1) or 1).toByte(),(pts shr 7).toByte(),(((pts.toInt() and 127) shl 1) or 1).toByte())
        return packets(64,byteArrayOf(0,0,1,0xc0.toByte(),(n shr 8).toByte(),n.toByte(),0x80.toByte(),0x80.toByte(),5)+time+alaw,true)
    }
    private fun packets(pid:Int, payload:ByteArray, start:Boolean):ByteArray {
        val out=ByteArrayOutputStream();var at=0
        while(at<payload.size){val n=minOf(184,payload.size-at);val p=ByteArray(188){0xff.toByte()};val count=counters[pid]?:0;counters[pid]=(count+1) and 15
            p[0]=0x47;p[1]=((pid shr 8) or if(start&&at==0)0x40 else 0).toByte();p[2]=pid.toByte()
            val offset=if(n==184){p[3]=(0x10 or count).toByte();4}else{p[3]=(0x30 or count).toByte();p[4]=(183-n).toByte();if(n<183)p[5]=0;188-n}
            System.arraycopy(payload,at,p,offset,n);out.write(p);at+=n
        };return out.toByteArray()
    }
}
