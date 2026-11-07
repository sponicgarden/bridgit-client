package cc.bridgit.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Agent keys (SEC-09): "brg_" + 43 base64url characters (256 random bits).
 * Only a salted SHA-256 hash is stored (SEC-02). Old 1.2 keys (64 hex) are
 * not accepted (D20). No android.* here, so it is unit-tested on the JVM.
 */
object Keys {
    const val PREFIX = "brg_"
    const val LENGTH = 47 // "brg_" + 43
    val PATTERN = Regex("brg_[A-Za-z0-9_-]{43}")
    private val WHOLE = Regex("^brg_[A-Za-z0-9_-]{43}$")

    fun generate(rnd: SecureRandom = SecureRandom()): String {
        val b = ByteArray(32).also { rnd.nextBytes(it) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun newSalt(rnd: SecureRandom = SecureRandom()): String =
        ByteArray(16).also { rnd.nextBytes(it) }.toHex()

    fun isWellFormed(key: String?): Boolean = key != null && WHOLE.matches(key)

    fun hash(salt: String, key: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$key".toByteArray()).toHex()

    /** Constant-time comparison of [key] against a stored salt and hash. */
    fun matches(salt: String, storedHash: String, key: String): Boolean =
        MessageDigest.isEqual(storedHash.toByteArray(), hash(salt, key).toByteArray())

    /** First 8 characters, for display only (AGT-01), e.g. "brg_Xk3f". */
    fun displayPrefix(key: String): String = key.take(8)

    /** Replaces anything that looks like a key with a placeholder. Used by every log and export path. */
    fun scrub(text: String): String = PATTERN.replace(text, "brg_[redacted]")
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
