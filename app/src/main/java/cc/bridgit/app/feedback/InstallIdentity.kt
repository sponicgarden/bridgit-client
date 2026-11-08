package cc.bridgit.app.feedback

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import cc.bridgit.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * The install's identity for the Bridgit team's backend (docs/spec-client-identity.md).
 *
 * A P-256 key made in the Android Keystore (never exportable) is the credential, and its hash is
 * the install ID (`bgi_…`). The key is made with key attestation, so the backend can check it
 * came from a genuine Bridgit build on real hardware. Every request is signed; no bearer secret
 * travels. Installs that uploaded with the older random key move their issues over once.
 */
object InstallIdentity {
    private const val ALIAS = "bridgit-install-v1"
    private const val PREFS = "install_identity"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** What the backend decided about this install (CID-13). */
    data class Status(val installId: String, val trust: String, val reason: String?)

    class Refused(val code: Int, message: String) : Exception(message)

    private val keys get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The registered install, or null if this phone hasn't registered yet. */
    fun status(ctx: Context): Status? {
        val p = prefs(ctx)
        val id = p.getString("install_id", null) ?: return null
        if (!keys.containsAlias(ALIAS)) return null
        return Status(id, p.getString("trust", "unverified")!!, p.getString("reason", null))
    }

    /**
     * Registers this install if needed and returns its status. Null means the backend doesn't
     * offer registration (an older deploy): the caller keeps using the older bearer key.
     * Throws IOException to retry later, [Refused] if the backend turned registration down.
     */
    @Synchronized
    fun ensure(ctx: Context, base: String): Status? {
        status(ctx)?.let { return it }
        val p = prefs(ctx)
        // A refused registration is retried at most once a day, with the older key used meanwhile.
        if (System.currentTimeMillis() - p.getLong("refused_at", 0L) < 86_400_000L) return null
        repeat(2) { attempt ->
            val challenge = p.getString("challenge", null)?.takeIf { keys.containsAlias(ALIAS) }
                ?: newChallenge(base)?.also { c -> makeKey(Base64.getDecoder().decode(c)); p.edit().putString("challenge", c).commit() }
                ?: return null
            try {
                return register(ctx, base, challenge)
            } catch (e: Refused) {
                // The challenge is baked into the key: an expired one needs a new key (CID-11).
                if (e.code == 410 && attempt == 0) { forget(ctx); return@repeat }
                p.edit().putLong("refused_at", System.currentTimeMillis()).commit()
                throw e
            }
        }
        return null
    }

    /** Signed headers for one request (CID-20). Only after [ensure] returned a status. */
    fun headers(ctx: Context, method: String, pathAndQuery: String, body: ByteArray): Map<String, String> {
        val id = status(ctx)?.installId ?: error("install not registered")
        return signedHeaders(id, method, pathAndQuery, body)
    }

    /** The backend lost this install (or blocked it): start over with a fresh key next time. */
    @Synchronized
    fun forget(ctx: Context) {
        runCatching { keys.deleteEntry(ALIAS) }
        prefs(ctx).edit().clear().commit()
    }

    /** Base64 DER certificates, leaf first, for the debug hook. */
    fun chain(): List<String> = keys.getCertificateChain(ALIAS)?.map { Base64.getEncoder().encodeToString(it.encoded) }.orEmpty()

    /** Makes the key with a given challenge (debug hook only: captures a chain for backend tests). */
    fun makeKeyForTest(challenge: ByteArray) { runCatching { keys.deleteEntry(ALIAS) }; makeKey(challenge) }

    private fun publicKey(): ByteArray = keys.getCertificate(ALIAS).publicKey.encoded

    private fun signedHeaders(id: String, method: String, pathAndQuery: String, body: ByteArray): Map<String, String> {
        val at = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()
        val text = InstallAuth.signingText(id, method, pathAndQuery, at, nonce, body)
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keys.getKey(ALIAS, null) as PrivateKey); update(text.toByteArray())
        }.sign()
        return mapOf("X-Bridgit-Install" to id, "X-Bridgit-Signature" to InstallAuth.signatureHeader(at, nonce, InstallAuth.derToP1363(der)))
    }

    /** StrongBox with attestation, then the TEE with attestation, then the TEE without (CID-01, CID-11). */
    private fun makeKey(challenge: ByteArray) {
        fun spec(attest: Boolean, strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (attest) setAttestationChallenge(challenge); if (strongBox) setIsStrongBoxBacked(true) }.build()
        for ((attest, strongBox) in listOf(true to true, true to false, false to false)) {
            try {
                KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(spec(attest, strongBox)) }.generateKeyPair()
                return
            } catch (e: StrongBoxUnavailableException) {
            } catch (e: ProviderException) {
            }
        }
        error("The Keystore couldn't make an install key.")
    }

    private fun post(url: String, body: ByteArray, headers: Map<String, String>): Pair<Int, String> {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(body.toRequestBody(JSON)).build()
        return FeedbackUploader.client.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    /**
     * POST /v1/install/challenge → base64 challenge; null when the backend predates registration
     * (it answers 401 or 404 for an endpoint it doesn't have).
     */
    private fun newChallenge(base: String): String? {
        val (code, body) = post("$base/v1/install/challenge", "{}".toByteArray(), emptyMap())
        if (code in 400..499 && code != 429) return null
        if (code != 200) throw IOException("challenge HTTP $code")
        return JSONObject(body).getString("challenge")
    }

    private fun register(ctx: Context, base: String, challenge: String): Status {
        val spki = publicKey()
        val id = InstallAuth.installId(spki)
        val body = JSONObject().put("public_key", Base64.getEncoder().encodeToString(spki))
            .put("attestation_chain", JSONArray(chain())).put("challenge", challenge)
            .put("app_version", BuildConfig.VERSION_NAME)
        // Issues sent with the older random key move to this install (and that key stops working).
        FeedbackUploader.existingIdentity(ctx)?.let { (legacyId, key) -> body.put("legacy", JSONObject().put("install_id", legacyId).put("key", key)) }
        val bytes = body.toString().toByteArray()
        val path = "/v1/install/register"
        val (code, text) = post(base + path, bytes, signedHeaders(id, "POST", path, bytes))
        if (code == 429 || code >= 500) throw IOException("register HTTP $code")
        val o = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
        // A wrong phone clock isn't a refusal: keep the key and challenge, try again later.
        if (code == 401 && o.optString("error") == "clock_skew") throw IOException("register: phone clock is off")
        if (code != 200 && code != 201) throw Refused(code, o.optString("message", "HTTP $code"))
        val status = Status(o.optString("install_id", id), o.optString("trust", "unverified"), o.optString("reason", "").ifBlank { null })
        prefs(ctx).edit().putString("install_id", status.installId).putString("trust", status.trust)
            .putString("reason", status.reason).remove("challenge").commit()
        FeedbackUploader.forgetLegacy(ctx)
        return status
    }
}
