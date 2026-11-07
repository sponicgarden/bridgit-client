package cc.bridgit.app.relay
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class SealedTest {
    @Test fun authenticatedConstructionRejectsWrongSenderDeviceSequenceAndCiphertext() {
        val phone=Sealed.generate();val agent=Sealed.generate();val other=Sealed.generate()
        val aad=Sealed.aad("phone","agent","epoch","request01",123456,1,"request")
        val e=Sealed.seal(agent,Sealed.publicKey(phone),"{\"ok\":true}".toByteArray(),aad)
        assertEquals("{\"ok\":true}",String(Sealed.open(phone,Sealed.publicKey(agent),e,aad)))
        fun rejects(block:()->Unit){try{block();fail("must reject")}catch(_:org.bouncycastle.crypto.InvalidCipherTextException){}}
        rejects { Sealed.open(phone,Sealed.publicKey(other),e,aad) }
        rejects { Sealed.open(phone,Sealed.publicKey(agent),e,Sealed.aad("other","agent","epoch","request01",123456,1,"request")) }
        rejects { Sealed.open(phone,Sealed.publicKey(agent),e,Sealed.aad("phone","agent","epoch","request01",123456,2,"request")) }
        val mutated=JSONObject(e.toString());val ct=Sealed.un64(mutated.getString("ct"));ct[0]=(ct[0].toInt() xor 1).toByte();mutated.put("ct",Sealed.b64(ct));rejects { Sealed.open(phone,Sealed.publicKey(agent),mutated,aad) }
        val restored=Sealed.restore(Sealed.privateKey(phone),Sealed.publicKey(phone));assertEquals(Sealed.publicKey(phone),Sealed.publicKey(restored))
    }
    @Test fun opensMaintainedJsLibraryFixtureAndCreatesCompatibleResponse() {
        val f=JSONObject(javaClass.getResource("/relay-hpke-fixture.json")!!.readText())
        val phone=Sealed.restore(Sealed.un64(f.getString("phone_private")),f.getString("phone_public"))
        val e=f.getJSONObject("envelope");val aad=Sealed.aad("phone0001","agent001","gen1",e.getString("request_id"),e.getLong("deadline"),e.getLong("seq"),"request")
        val opened=JSONObject(String(Sealed.open(phone,f.getString("agent_public"),e,aad)))
        assertEquals("/v1/health",opened.getJSONObject("request").getString("path"))
        val r=Sealed.seal(phone,f.getString("agent_public"),"{\"verified\":true}".toByteArray(),Sealed.aad("phone0001","agent001","gen1",e.getString("request_id"),e.getLong("deadline"),e.getLong("seq"),"response")).put("request_id",e.getString("request_id")).put("deadline",e.getLong("deadline")).put("seq",e.getLong("seq"))
        val file=java.io.File("build/relay-hpke-response.json");file.parentFile.mkdirs();file.writeText(r.toString())
    }
}
