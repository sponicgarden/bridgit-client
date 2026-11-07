package cc.bridgit.app.lancamera

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

class CameraAudioProtocolTest {
    class Wire(data:ByteArray,private val beforeRead:(Int)->Unit = {}):Socket() {
        private val bytes=ByteArrayInputStream(data)
        var count=0;var ended=false
        val sent=ByteArrayOutputStream()
        override fun connect(address:SocketAddress,timeout:Int){}
        override fun setSoTimeout(ms:Int){}
        override fun getInputStream()=object:java.io.InputStream(){override fun read():Int{beforeRead(++count);return bytes.read()}}
        override fun getOutputStream()=sent
        override fun close(){ended=true}
    }
    private fun factory(w:Socket)=object:SocketFactory(){override fun createSocket()=w
        override fun createSocket(h:String,p:Int)=error("unused");override fun createSocket(h:String,p:Int,l:java.net.InetAddress,lp:Int)=error("unused")
        override fun createSocket(h:java.net.InetAddress,p:Int)=error("unused");override fun createSocket(h:java.net.InetAddress,p:Int,l:java.net.InetAddress,lp:Int)=error("unused")}
    private fun response(n:Int,status:Int=200,headers:String="",body:String="")="RTSP/1.0 $status Result\r\nCSeq: $n\r\nContent-Length: ${body.toByteArray().size}\r\n$headers\r\n$body".toByteArray()
    private val sdp="v=0\r\nm=audio 0 RTP/AVP 8\r\na=control:trackID=audio\r\n"
    private fun packet(seq:Int,timestamp:Int):ByteArray {
        val rtp=java.nio.ByteBuffer.allocate(172).put(0x80.toByte()).put(8).putShort(seq.toShort()).putInt(timestamp).putInt(123).put(ByteArray(160){0xd5.toByte()}).array()
        return byteArrayOf(36,0,0,172.toByte())+rtp
    }
    @Test fun authenticatedRtspCaptureUsesSameHostContentBaseAndNeverSendsRawPassword() {
        val wire=Wire(response(1,401,"WWW-Authenticate: Digest realm=\"cam\", nonce=\"n\", qop=\"auth\"\r\n")+
            response(2,headers="Content-Type: application/sdp\r\nContent-Base: rtsp://192.168.1.85:554/media/\r\n",body=sdp)+
            response(3,headers="Session: abc123\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")+response(4)+
            (0 until 50).fold(byteArrayOf()){a,i->a+packet(i,i*160)})
        CameraRtsp("192.168.1.85","owner","private-secret",factory(wire),5000){}.use {
            val track=it.describe().single();val pcm=it.record(track,1);assertEquals(16000,pcm.size)
        }
        val sent=wire.sent.toString("UTF-8")
        assertTrue(sent.contains("SETUP rtsp://192.168.1.85:554/media/trackID=audio"))
        assertTrue(sent.contains("nc=00000002"));assertFalse(sent.contains("private-secret"));assertTrue(wire.ended)
    }
    @Test fun excessiveRtpTimestampGapIsRefusedEvenWithContiguousSequenceNumbers() {
        val wire=Wire(response(1,headers="Content-Type: application/sdp\r\n",body=sdp)+response(2,headers="Session: abc\r\nTransport: RTP/AVP/TCP;interleaved=0-1\r\n")+response(3)+packet(0,0)+packet(1,3000))
        assertThrows(CameraFailure::class.java){CameraRtsp("192.168.1.85","owner","secret",factory(wire),2000){}.use{it.record(it.describe().single(),1)}}
        assertTrue(wire.ended)
    }
    @Test fun videoPacketsDoNotMultiplyExpensivePermissionChecks() {
        val media=ByteArrayOutputStream().apply {
            repeat(50) { i ->
                repeat(40) { write(byteArrayOf(36,2,0,1,0)) }
                write(packet(i,i*160))
            }
        }.toByteArray()
        val wire=Wire(response(1,headers="Content-Type: application/sdp\r\n",body=sdp)+response(2,headers="Session: abc\r\nTransport: RTP/AVP/TCP;interleaved=0-1\r\n")+response(3)+media)
        CameraRtsp("192.168.1.85","owner","secret",factory(wire),1500){Thread.sleep(5)}.use {
            assertEquals(16000,it.record(it.describe().single(),1).size)
        }
    }
    @Test fun revocationWhileMediaIsBufferedDiscardsTheClip() {
        var revoked=false
        val header=response(1,headers="Content-Type: application/sdp\r\n",body=sdp)+response(2,headers="Session: abc\r\nTransport: RTP/AVP/TCP;interleaved=0-1\r\n")+response(3)
        val media=(0 until 50).fold(byteArrayOf()){a,i->a+packet(i,i*160)}
        val wire=Wire(header+media){n->if(n>header.size)revoked=true}
        assertThrows(IllegalStateException::class.java){CameraRtsp("192.168.1.85","owner","secret",factory(wire),2000){check(!revoked)}.use{it.record(it.describe().single(),1)}}
        assertTrue(wire.ended)
    }
    @Test fun boundedClockGapIsPaddedAndReportedRatherThanCompressingAudio() {
        val rtp=(0 until 50).fold(byteArrayOf()){a,i->a+packet(i,i*160+if(i==0)0 else 16)}
        val wire=Wire(response(1,headers="Content-Type: application/sdp\r\n",body=sdp)+response(2,headers="Session: abc\r\nTransport: RTP/AVP/TCP;interleaved=0-1\r\n")+response(3)+rtp)
        CameraRtsp("192.168.1.85","owner","secret",factory(wire),2000){}.use{
            assertEquals(16000,it.record(it.describe().single(),1).size)
            assertEquals(16,it.diagnostics.getInt("inserted_silence_samples"))
        }
    }
    @Test fun revocationInterruptsAHeaderEvenWhenBytesKeepArriving() {
        var revoked=false
        val wire=Wire(response(1,headers="X-Long: ${"a".repeat(200)}\r\n")){n->Thread.sleep(3);if(n==20)revoked=true}
        assertThrows(IllegalStateException::class.java){CameraRtsp("192.168.1.85","owner","secret",factory(wire),2000){check(!revoked)}.use{it.describe()}}
        assertTrue(wire.count<100);assertTrue(wire.ended)
    }
    @Test fun cancellationClosesSocketEvenWhenAnOutputWriteBlocks() {
        val writing=java.util.concurrent.CountDownLatch(1)
        val closed=java.util.concurrent.CountDownLatch(1)
        val wire=object:Socket(){
            override fun connect(address:SocketAddress,timeout:Int){}
            override fun setSoTimeout(ms:Int){}
            override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream()=object:java.io.OutputStream(){override fun write(b:Int){writing.countDown();closed.await();throw java.net.SocketException("closed")}}
            override fun close(){closed.countDown()}
        }
        val job=kotlinx.coroutines.Job()
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future=executor.submit<Boolean>{
                try{CameraRtsp("192.168.1.85","owner","secret",factory(wire),3000,cancellationJob=job){}.use{it.describe()};false}
                catch(_:java.net.SocketException){true}
            }
            assertTrue(writing.await(1,java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            assertTrue(future.get(1,java.util.concurrent.TimeUnit.SECONDS))
        }finally{wire.close();executor.shutdownNow()}
    }
    @Test fun initialAuthorizationFailureNeverCreatesASocket() {
        var created=false
        val f=object:SocketFactory(){override fun createSocket():Socket {created=true;return Socket()}
            override fun createSocket(h:String,p:Int)=error("unused");override fun createSocket(h:String,p:Int,l:java.net.InetAddress,lp:Int)=error("unused")
            override fun createSocket(h:java.net.InetAddress,p:Int)=error("unused");override fun createSocket(h:java.net.InetAddress,p:Int,l:java.net.InetAddress,lp:Int)=error("unused")}
        assertThrows(IllegalStateException::class.java){CameraRtsp("192.168.1.85","u","p",f,1000){error("revoked")}}
        assertFalse(created)
    }
    @Test fun tapoTalkRequiresAuthenticatedDigestNeverNoneAndSendsBoundedMpegTs() {
        val reply="{\"params\":{\"session_id\":\"session123\"}}"
        val handshake="HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nWWW-Authenticate: Digest realm=\"Tapo\", nonce=\"n\", qop=\"auth\", encrypt_type=\"3\"\r\n\r\n"+
            "HTTP/1.1 200 OK\r\nContent-Type: multipart/mixed\r\nKey-Exchange: username=\"admin\"\r\n\r\n"
        val wire=Wire((handshake+"----device-stream-boundary--\r\nContent-Type: application/json\r\nContent-Length: ${reply.length}\r\n\r\n"+reply).toByteArray())
        TapoTalk("192.168.1.85","private-cloud-password",factory(wire),3000){}.use{it.play(ByteArray(320))}
        val text=wire.sent.toString("ISO-8859-1")
        assertTrue(text.contains("Authorization: Digest"));assertTrue(text.contains("Content-Type: audio/mp2t"));assertTrue(text.contains("X-Session-Id: session123"))
        assertFalse(text.contains("private-cloud-password"));assertTrue(wire.ended)
        val noauth=Wire("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
        assertThrows(IllegalArgumentException::class.java){TapoTalk("192.168.1.85","secret",factory(noauth),1000){}}
        assertTrue(noauth.ended)
    }
}
