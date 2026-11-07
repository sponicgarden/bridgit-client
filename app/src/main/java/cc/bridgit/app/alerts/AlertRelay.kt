package cc.bridgit.app.alerts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cc.bridgit.app.core.SafeHttp
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Email alerts go through bridgit.cc (website/alerts.mjs in the private bridgit-cloud repo), which sends them with Cloudflare Email
 * Sending; no mail credentials are on the phone. This phone proves who it is with a random install token,
 * kept encrypted with a non-exportable Android Keystore key. The server only ever mails the
 * address this install confirmed by clicking the link in its confirmation email.
 */
class AlertRelay(context: Context, private val base: String = BASE) : AlertSender {
    private val prefs = context.getSharedPreferences("alerts_token", Context.MODE_PRIVATE)

    enum class Subscribe { PENDING, CONFIRMED, BAD_EMAIL, RATE_LIMITED, FAILED }
    enum class State { NONE, PENDING, CONFIRMED, UNKNOWN }

    fun subscribe(email: String): Subscribe = try {
        when (SafeHttp.post("$base/api/alerts/subscribe", SafeHttp.Policy.PUBLIC, headers(json = true),
            JSONObject().put("email", email).toString().toByteArray(), DEADLINE_MS)) {
            200 -> if (status(email) == State.CONFIRMED) Subscribe.CONFIRMED else Subscribe.PENDING
            400 -> Subscribe.BAD_EMAIL
            429 -> Subscribe.RATE_LIMITED
            else -> Subscribe.FAILED
        }
    } catch (e: Exception) { Subscribe.FAILED }

    fun status(expectedEmail: String? = null): State = try {
        val r = SafeHttp.get("$base/api/alerts/status", SafeHttp.Policy.PUBLIC, DEADLINE_MS, 4096, headers = headers())
        if (r.status != 200) State.UNKNOWN
        else JSONObject(String(r.bytes ?: ByteArray(0))).let {
            EmailVerificationRules.state(it.optString("state"), it.optString("email"), expectedEmail)
        }
    } catch (e: Exception) { State.UNKNOWN }

    /** Deletes this install's subscription on the server. Returns false if the server couldn't be reached. */
    fun unsubscribe(): Boolean = try {
        SafeHttp.delete("$base/api/alerts/subscription", SafeHttp.Policy.PUBLIC, headers(), DEADLINE_MS) == 200
    } catch (e: Exception) { false }

    override fun send(message: AlertMessage): AlertSender.Result = try {
        val body = JSONObject().put("subject", message.subject).put("text", message.text)
            .put("idempotency", message.idempotency.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64))
        resultFor(SafeHttp.post("$base/api/alerts/send", SafeHttp.Policy.PUBLIC, headers(json = true), body.toString().toByteArray(), DEADLINE_MS))
    } catch (e: Exception) { AlertSender.Result.RETRY }

    private fun headers(json: Boolean = false) = buildMap {
        put("Authorization", "Bearer ${installToken()}")
        put("Accept", "application/json")
        if (json) put("Content-Type", "application/json")
    }

    /** This install's token: 32 random bytes as hex, made on first use. */
    @Synchronized
    private fun installToken(): String {
        val iv = prefs.getString("iv", null); val data = prefs.getString("data", null)
        if (iv != null || data != null) {
            try {
                check(iv != null && data != null)
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.DECRYPT_MODE, secretKey(create = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                return String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
            } catch (_: Exception) {
                android.util.Log.w("BridgitEmail", "Stored email identity unavailable; preserving it instead of replacing it")
                throw IllegalStateException("Stored email identity unavailable; preserved for recovery")
            }
        }
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        prefs.edit().putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(c.doFinal(token.toByteArray()), Base64.NO_WRAP)).commit()
        return token
    }

    private fun secretKey(create: Boolean = true): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Stored email identity key unavailable" }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    companion object {
        const val BASE = "https://bridgit.cc"
        private const val DEADLINE_MS = 15_000L
        private const val ALIAS = "bridgit_alerts_install"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** How the server's reply maps onto what the dispatcher does next (the website's alerts.mjs). */
        fun resultFor(status: Int): AlertSender.Result = when (status) {
            200 -> AlertSender.Result.SENT
            409, 410, 401 -> AlertSender.Result.GONE
            400, 413 -> AlertSender.Result.REJECTED
            else -> AlertSender.Result.RETRY
        }
    }
}
