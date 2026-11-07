package cc.bridgit.app.relay

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.json.JSONObject
import java.util.Base64

/** RFC 9180 Auth mode, DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM. */
object Sealed {
    private fun suite()=HPKE(HPKE.mode_auth,HPKE.kem_P256_SHA256,HPKE.kdf_HKDF_SHA256,HPKE.aead_AES_GCM128)
    fun validatePublic(value:String) { suite().deserializePublicKey(un64(value)) }
    fun generate():AsymmetricCipherKeyPair=suite().generatePrivateKey()
    fun publicKey(pair:AsymmetricCipherKeyPair):String=b64(suite().serializePublicKey(pair.public))
    fun privateKey(pair:AsymmetricCipherKeyPair):ByteArray=suite().serializePrivateKey(pair.private)
    fun restore(secret:ByteArray,public:String):AsymmetricCipherKeyPair=suite().deserializePrivateKey(secret,un64(public))
    fun aad(device:String,agent:String,generation:String,id:String,deadline:Long,seq:Long,direction:String)=
        "bridgit-relay-v1\n$device\n$agent\n$generation\n$id\n$deadline\n$seq\n$direction".toByteArray(Charsets.UTF_8)
    fun open(pair:AsymmetricCipherKeyPair,peer:String,envelope:JSONObject,aad:ByteArray):ByteArray {
        val hpke=suite()
        return hpke.setupAuthR(un64(envelope.getString("enc")),pair,"bridgit-relay-v1".toByteArray(),hpke.deserializePublicKey(un64(peer)))
            .open(aad,un64(envelope.getString("ct")))
    }
    fun seal(pair:AsymmetricCipherKeyPair,peer:String,plaintext:ByteArray,aad:ByteArray):JSONObject {
        val hpke=suite();val ctx=hpke.setupAuthS(hpke.deserializePublicKey(un64(peer)),"bridgit-relay-v1".toByteArray(),pair)
        return JSONObject().put("enc",b64(ctx.encapsulation)).put("ct",b64(ctx.seal(aad,plaintext)))
    }
    fun b64(b:ByteArray):String=Base64.getEncoder().encodeToString(b)
    fun un64(s:String):ByteArray=Base64.getDecoder().decode(s)
}
