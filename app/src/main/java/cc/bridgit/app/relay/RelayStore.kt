package cc.bridgit.app.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** No agent plaintext token is retained. HPKE private material is wrapped by a Keystore AES key. */
class RelayStore(private val app:BridgeApp) {
    private val prefs=app.getSharedPreferences("relay_v1",Context.MODE_PRIVATE)
    private val keys get()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias="bridgit-relay-identity-v1"
    var enabled:Boolean get()=prefs.getBoolean("enabled",false);set(v){check(prefs.edit().putBoolean("enabled",v).commit())}
    var base:String get()=prefs.getString("base","") ?: "";set(v){
        val u=java.net.URI(v);require(u.scheme=="https"&&u.host!=null&&u.userInfo==null&&u.query==null&&u.fragment==null&&u.path.matches(Regex("/d/[A-Za-z0-9_-]{8,128}")))
        if(v!=base)check(prefs.edit().putString("base",v).putBoolean("enrolled",false).commit())
    }
    val device get()=app.platform.store.deviceId
    private fun identity(){if(!keys.containsAlias(alias))KeyPairGenerator.getInstance("EC","AndroidKeyStore").apply { initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build()) }.generateKeyPair()}
    fun identityPublic():String { identity();return Sealed.b64(keys.getCertificate(alias).publicKey.encoded) }
    fun challengeProof():JSONObject {
        val nonce=java.util.UUID.randomUUID().toString();val at=System.currentTimeMillis()
        return JSONObject().put("nonce",nonce).put("at",at).put("signature",signText("bridgit-relay-challenge-v1\n$device\n$nonce\n$at"))
    }
    /** Self-enrolment proof: this phone holds the identity key it registers (relays with SELF_ENROLL on). */
    fun selfProof():JSONObject {
        val nonce=java.util.UUID.randomUUID().toString();val at=System.currentTimeMillis()
        return JSONObject().put("nonce",nonce).put("at",at).put("signature",signText("bridgit-relay-enroll-v1\n$device\n$nonce\n$at"))
    }
    /** This build's relay for this phone, or "" when the build has none. */
    fun defaultBase():String=cc.bridgit.app.BuildConfig.RELAY_URL.takeIf { it.isNotEmpty() }?.let { "$it/d/$device" } ?: ""
    /** Registered with the relay at [base]. Phones enrolled before this flag existed count once they were enabled. */
    var enrolled:Boolean get()=prefs.getBoolean("enrolled",enabled);set(v){check(prefs.edit().putBoolean("enrolled",v).commit())}
    fun sign(nonce:String,until:Long):String=signText("bridgit-relay-v1\n$device\n$nonce\n$until")
    private fun signText(message:String):String {
        identity();val der=Signature.getInstance("SHA256withECDSA").apply { initSign(keys.getKey(alias,null) as java.security.PrivateKey);update(message.toByteArray()) }.sign()
        // WebCrypto uses IEEE P1363 r||s, Android Keystore emits DER.
        val seq=org.bouncycastle.asn1.ASN1Sequence.getInstance(der)
        return Sealed.b64(ByteArray(64).also { out -> for(i in 0..1){val n=org.bouncycastle.asn1.ASN1Integer.getInstance(seq.getObjectAt(i)).positiveValue.toByteArray().takeLast(32).toByteArray();n.copyInto(out,i*32+32-n.size)} })
    }
    @Synchronized fun hpke():org.bouncycastle.crypto.AsymmetricCipherKeyPair {
        val wrap="bridgit-relay-wrap-v1"
        if(!keys.containsAlias(wrap))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(wrap,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
        val secret=keys.getKey(wrap,null)
        val saved=prefs.getString("hpke_secret",null)
        if(saved!=null){val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,secret,GCMParameterSpec(128,Sealed.un64(prefs.getString("hpke_iv","")!!)));return Sealed.restore(c.doFinal(Sealed.un64(saved)),prefs.getString("hpke_public","")!!)}
        val pair=Sealed.generate();val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secret)
        check(prefs.edit().putString("hpke_secret",Sealed.b64(c.doFinal(Sealed.privateKey(pair)))).putString("hpke_iv",Sealed.b64(c.iv)).putString("hpke_public",Sealed.publicKey(pair)).commit());return pair
    }
    @Synchronized fun agents():List<JSONObject> = storedAgents().map { cc.bridgit.app.ownerapi.ConfigurationDelegation.effective(app, it) }
    @Synchronized private fun storedAgents():List<JSONObject> {
        val all=JSONArray(prefs.getString("agents","[]"));return (0 until all.length()).map { all.getJSONObject(it) }.filter { c -> app.agents.get(c.optString("id"))?.keyHash==c.optString("generation") }
    }
    /**
     * Single-use portal link: the portal swaps the link's token for one it made on the device ([tokenHash] = SHA-256 hex).
     * Returns the HTTP status and JSON body the portal gets. Once claimed (or past [CLAIM_MS]) the link's token is dead:
     * the relay drops it with the next agents frame, within about a second.
     */
    @Synchronized fun claimPortal(id:String,tokenHash:String):Pair<Int,JSONObject> {
        val all=storedAgents();val c=all.firstOrNull { it.optString("id")==id } ?: return 404 to JSONObject().put("error","not_found")
        val until=c.optLong("claim_until",0)
        if(until<=0)return 409 to JSONObject().put("error","already_claimed")
        if(System.currentTimeMillis()>until)return 410 to JSONObject().put("error","link_expired")
        if(!Regex("[a-f0-9]{64}").matches(tokenHash)||tokenHash==c.optString("token_hash"))return 400 to JSONObject().put("error","invalid_token_hash")
        val next=JSONObject(c.toString()).put("token_hash",tokenHash).put("claimed_at",System.currentTimeMillis()).apply { remove("claim_until") }
        check(prefs.edit().putString("agents",JSONArray(all.map { if(it.optString("id")==id) next else it }).toString()).commit())
        return 200 to JSONObject().put("claimed",true)
    }
    /** Portal pairings whose link was never claimed and has expired: their agents are removed. Returns how many. */
    fun sweepExpiredClaims():Int {
        val now=System.currentTimeMillis()
        val expired=storedAgents().filter { it.optLong("claim_until",0) in 1 until now }.map { it.getString("id") }
        expired.forEach { remove(it);app.agents.remove(it) }
        return expired.size
    }
    /**
     * A relay agent whose token the other side made ([tokenHash] = SHA-256 hex): a link code's claim-only agent, or the
     * link agent of a Bridgit that claimed over the relay (docs/spec-linked-bridgits.md §4.1). [claimUntil] 0 = no expiry.
     */
    @Synchronized fun addAgentHash(id:String,routes:Set<String>,tokenHash:String,claimUntil:Long=0) {
        val a=app.agents.get(id) ?: error("No such agent");require(a.caps!=null) { "Relay agents need explicit capability grants" }
        require(Regex("[a-f0-9]{64}").matches(tokenHash))
        val config=JSONObject().put("id",id).put("generation",a.keyHash).put("mode","connector").put("token_hash",tokenHash).put("peer","").put("routes",JSONArray(routes.sorted())).put("tools",JSONArray())
        if(claimUntil>0)config.put("claim_until",claimUntil)
        check(prefs.edit().putString("agents",JSONArray(storedAgents().filter { it.optString("id")!=id }+config).toString()).commit())
    }
    /** Replaces a relay agent's route allowlist in place (same token and generation). */
    @Synchronized fun setRoutes(id:String,routes:Set<String>) {
        val all=storedAgents(); if(all.none { it.optString("id")==id }) return
        check(prefs.edit().putString("agents",JSONArray(all.map { if(it.optString("id")==id) JSONObject(it.toString()).put("routes",JSONArray(routes.sorted())) else it }).toString()).commit())
    }
    @Synchronized fun addAgent(id:String,mode:String,peer:String?,routes:Set<String>,tools:Set<String>,claimMs:Long=0):String {
        val a=app.agents.get(id) ?: error("No such agent");require(mode in setOf("sealed","connector"));require(a.caps!=null) { "Relay agents need explicit capability grants" }
        if(mode=="sealed")Sealed.validatePublic(peer ?: "")
        val token="rly_"+java.util.UUID.randomUUID()+java.util.UUID.randomUUID()
        val config=JSONObject().put("id",id).put("generation",a.keyHash).put("mode",mode).put("token_hash",hash(token)).put("peer",peer ?: "").put("routes",JSONArray(routes.sorted())).put("tools",JSONArray(tools.sorted()))
        if(claimMs>0)config.put("claim_until",System.currentTimeMillis()+claimMs)
        check(prefs.edit().putString("agents",JSONArray(storedAgents().filter { it.optString("id")!=id }+config).toString()).commit());return token
    }
    /**
     * Brings every Bridgit Portal pairing's route allowlist up to [cc.bridgit.app.portal.PortalData.ROUTES] in place:
     * same token, generation and mode, so a paired iPhone/iPad keeps working and gains new read-only routes.
     */
    @Synchronized fun upgradePortalRoutes():Int {
        var n=0
        val all=storedAgents().map { c ->
            val routes=RelayDispatcher.strings(c,"routes");val tools=RelayDispatcher.strings(c,"tools")
            val target=cc.bridgit.app.portal.PortalData.ROUTES.toSet()
            if(cc.bridgit.app.portal.PortalData.isLegacyPortalConfig(routes,tools)&&routes!=target){n++;JSONObject(c.toString()).put("routes",JSONArray(target.sorted()))}else c
        }
        if(n>0)check(prefs.edit().putString("agents",JSONArray(all).toString()).commit())
        return n
    }
    @Synchronized fun remove(id:String){check(prefs.edit().putString("agents",JSONArray(storedAgents().filter { it.optString("id")!=id }).toString()).commit());app.platform.invalidate(id)}
    /** Strict monotonic sequence per pinned agent generation. Persist BEFORE execution; survives process death. */
    @Synchronized fun claim(id:String,generation:String,seq:Long,requestId:String?=null):Boolean {
        val key="seq:$id:$generation";if(seq<=prefs.getLong(key,0)||seq<=0)return false
        val now=System.currentTimeMillis();val edit=prefs.edit()
        if(requestId!=null){
            val prefix="rid:$id:$generation:";val rid=prefix+requestId
            if(prefs.getLong(rid,0)>now)return false
            val recent=prefs.all.filter { it.key.startsWith(prefix)&&(it.value as? Long ?: 0)>now };if(recent.size>=400)return false
            prefs.all.filter { it.key.startsWith("rid:")&&(it.value as? Long ?: 0)<=now }.keys.forEach { edit.remove(it) }
            edit.putLong(rid,now+24*3600000L)
        }
        check(edit.putLong(key,seq).commit());return true
    }
    fun enrollment()=JSONObject().put("device_id",device).put("identity_spki",identityPublic()).put("hpke_public",Sealed.publicKey(hpke()))
    companion object {
        /** How long a portal pairing link may wait to be claimed. */
        const val CLAIM_MS=24*3600_000L
        fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
}
