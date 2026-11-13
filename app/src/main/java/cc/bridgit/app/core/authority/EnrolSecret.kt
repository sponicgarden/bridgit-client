package cc.bridgit.app.core.authority

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * The enrolment secret of an approver link (spending authority G10.3 X1, SA-R4): 128 bits from
 * [SecureRandom], made with every code or link that carries `approver`. It is the SPAKE2 password of
 * approver enrolment; the 8-character link code authenticates nothing there.
 *
 * It exists only in the link form, as the field [FIELD] of the `bridgit://link#` fragment (and so in the QR):
 * 16 bytes, base64url, no padding, 22 characters. It is never sent to the names backend, the relay or any
 * other host, and neither is anything it could be checked against by itself: no hash of it, no w, no MAC
 * under a fixed key. Only the SPAKE2 shares and confirmations of one run may cross the relay. This class
 * therefore offers no hash and prints nothing; [text] is for the link form and a secret-bearing screen only.
 */
class EnrolSecret private constructor(private val bytes: ByteArray) {
    /** The value of the link's `es` field. */
    fun text(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * SPAKE2's w: PBKDF2-HMAC-SHA256 of the secret's bytes, salted with the enrolment label, a newline and the
     * target's registered name, 40 bytes out (64 bits more than the group order), reduced mod the order.
     */
    internal fun w(targetName: String): BigInteger {
        val salt = (EnrolTranscript.LABEL + "\n" + targetName).toByteArray(Charsets.UTF_8)
        val gen = PKCS5S2ParametersGenerator(SHA256Digest()).apply { init(bytes, salt, AuthorityLimits.ENROL_PBKDF2_ITERATIONS) }
        return BigInteger(1, (gen.generateDerivedParameters(W_BYTES * 8) as KeyParameter).key).mod(Spake2.order)
    }

    override fun toString() = "EnrolSecret(withheld)"

    companion object {
        const val FIELD = "es"
        const val BYTES = AuthorityLimits.ENROL_SECRET_BYTES
        const val TEXT_CHARS = 22
        private const val W_BYTES = 40
        private val form = Regex("^[A-Za-z0-9_-]{$TEXT_CHARS}$")

        fun generate(random: SecureRandom = SecureRandom()): EnrolSecret = EnrolSecret(ByteArray(BYTES).also { random.nextBytes(it) })

        /**
         * The secret an `es` field holds, or null. Only the one spelling [text] writes is accepted: 22
         * base64url characters, no padding, nothing trimmed, the last character's unused bits zero.
         */
        fun parse(text: String?): EnrolSecret? {
            if (text == null || !form.matches(text)) return null
            val bytes = try {
                Base64.getUrlDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (bytes.size != BYTES) return null
            return EnrolSecret(bytes).takeIf { it.text() == text }
        }
    }
}
