package cc.bridgit.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AGT-05a: until the owner copies the prompt or leaves the setup screen, the new key is
 * kept encrypted with a non-exportable Android Keystore AES-GCM key, for at most
 * 30 minutes. If the app is killed meanwhile, reopening it returns to the prompt.
 * The plaintext key is never written to disk or saved instance state.
 */
class PendingKeyVault(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("pending_key", Context.MODE_PRIVATE)

    data class Pending(val agentId: String, val key: String, val expiresAt: Long, val rotated: Boolean)

    @Synchronized
    fun put(agentId: String, key: String, rotated: Boolean) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(key.toByteArray())
        prefs.edit()
            .putString("agent_id", agentId)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putLong("expires_at", clock() + TTL_MS)
            .putBoolean("rotated", rotated)
            .commit()
    }

    /** The pending key, or null if none, expired or unreadable (an expired or broken entry is wiped). */
    @Synchronized
    fun get(): Pending? {
        val id = prefs.getString("agent_id", null) ?: return null
        val exp = prefs.getLong("expires_at", 0)
        if (clock() > exp) { wipe(); return null }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            val key = String(cipher.doFinal(Base64.decode(prefs.getString("data", ""), Base64.NO_WRAP)))
            Pending(id, key, exp, prefs.getBoolean("rotated", false))
        }.getOrElse { wipe(); null }
    }

    /** Called on Copy, Done or Leave (acknowledgement), and on expiry. */
    @Synchronized
    fun wipe() { prefs.edit().clear().commit() }

    fun hasPending(): Boolean = prefs.contains("agent_id") && clock() <= prefs.getLong("expires_at", 0)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    companion object {
        const val TTL_MS = 30 * 60 * 1000L
        private const val ALIAS = "bridget_pending_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
