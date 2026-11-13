package cc.bridgit.app.core.authority

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.BigIntegers
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SPAKE2 on P-256 as RFC 9382 gives it: suite `SPAKE2-P256-SHA256-HKDF-HMAC`, with the RFC's M and N
 * (spending authority G10.3 X1). Side A sends first and uses M; in approver enrolment A is the device that
 * made the code and B the one that claims it. The contract pack's crypto/spake2.json holds the RFC's
 * Appendix B vectors and the shares that must be refused.
 *
 * One [Exchange] is one run: it makes a fresh scalar and can be finished once. A share that is not the
 * 65-byte uncompressed form of a point on the curve, or one that makes K the identity, ends the run
 * ([Exchange.finish] answers null). P-256 has cofactor 1, so a point on the curve is in the group. K itself
 * never leaves this file; [Keys.ke] is the shared secret and the confirmations prove both sides hold it.
 *
 * **Timing (RFC 9382 section 7).** Every multiplication by a secret scalar (w, x, y) is a Montgomery ladder
 * with a fixed number of steps over a freshly blinded scalar ([blindedMultiply]), and confirmations are
 * compared with [MessageDigest.isEqual]. So the sequence of point operations does not depend on the secret,
 * and what timing remains is tied to a value that changes on every call. That is all that is claimed. It
 * is not constant-time code: the field arithmetic underneath is BouncyCastle's on the JVM and
 * [BigInteger]'s for the blinding, neither of which promises input-independent timing; the ladder picks its
 * operands by a secret-dependent array index, not by a constant-time swap; the steps that meet the point at
 * infinity (the leading zero bits of the blinded scalar) are cheaper; and the garbage collector and the JIT
 * are outside anyone's control. Secrets held as [BigInteger] cannot be wiped.
 *
 * **What the caller owes** (not done here; this file is one run's arithmetic):
 * - **One run, used once, atomically.** [Exchange.finish] works once per object, but the link layer must
 *   also take the code's run in one atomic step, so the same run is never verified twice, on a retry or
 *   from two threads.
 * - **Expiry.** A run is only for a code that is still open: ten minutes, one use. A share or proof that
 *   arrives after that is refused before any of this is called.
 * - **Attempts.** A null from [Exchange.finish] or a failed key confirmation costs one of the code's five
 *   tries, counted before the answer is sent, and the fifth closes the code.
 * - **Keys.** The approver key signs only right after the owner's check; nothing here holds or asks for it.
 */
object Spake2 {
    const val SUITE = "SPAKE2-P256-SHA256-HKDF-HMAC"
    const val SHARE_BYTES = 65
    private const val SCALAR_BYTES = 32

    private val params = CustomNamedCurves.getByName("secp256r1")
    private val curve = params.curve
    private val fieldPrime: BigInteger = curve.field.characteristic

    /** The group order: the RFC calls it p. Not the field prime. */
    internal val order: BigInteger = params.n

    // RFC 9382 section 6, "For P-256", compressed (SEC1). The pack carries the same two values.
    internal const val M_HEX = "02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"
    internal const val N_HEX = "03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"
    private val pointM: ECPoint = curve.decodePoint(unhex(M_HEX))
    private val pointN: ECPoint = curve.decodePoint(unhex(N_HEX))

    enum class Side { A, B }

    /** Starts a run for approver enrolment: w comes from the enrolment secret and the target's registered name. */
    fun start(side: Side, secret: EnrolSecret, targetName: String, idA: ByteArray, idB: ByteArray, random: SecureRandom = SecureRandom()): Exchange =
        start(side, secret.w(targetName), idA, idB, BigIntegers.createRandomInRange(BigInteger.ONE, order.subtract(BigInteger.ONE), random))

    /** With the scalar given, for the vectors only. Everything else draws it from [SecureRandom]. */
    internal fun start(side: Side, w: BigInteger, idA: ByteArray, idB: ByteArray, scalar: BigInteger, aad: ByteArray = ByteArray(0)): Exchange {
        require(w.signum() >= 0 && w < order) { "w is reduced mod the group order" }
        require(scalar.signum() > 0 && scalar < order) { "The scalar is from 1 to n-1" }
        return Exchange(side, w, idA.copyOf(), idB.copyOf(), scalar, aad.copyOf())
    }

    internal val generator: ECPoint get() = params.g
    private const val BLIND_BITS = 64

    /** Enough ladder steps for any k + r*n with k below n and r below 2^64. */
    private val ladderBits = order.bitLength() + BLIND_BITS + 1
    private val blindRandom = SecureRandom()

    /**
     * [point] times a secret scalar ([k] below the group order): w, or a run's x or y. Never the provider's
     * default multiplier, whose window pattern follows the scalar's bits.
     */
    private fun secretMultiply(point: ECPoint, k: BigInteger): ECPoint = blindedMultiply(point, k, BigInteger(BLIND_BITS, blindRandom))

    /**
     * A Montgomery ladder over k + r*n, which is the same multiple of a point of order n. Every call does the
     * same [ladderBits] steps, one addition and one doubling each, whatever the scalar; and with a fresh [r]
     * the bits it walks are different on every call, so two runs with the same w do not repeat a pattern.
     * The result is not normalised: the caller does that once, at the end.
     */
    internal fun blindedMultiply(point: ECPoint, k: BigInteger, r: BigInteger): ECPoint {
        require(k.signum() >= 0 && k < order && r.signum() >= 0 && r.bitLength() <= BLIND_BITS)
        val blinded = k.add(r.multiply(order))
        val rung = arrayOf(curve.infinity, point)
        for (i in ladderBits - 1 downTo 0) {
            val b = if (blinded.testBit(i)) 1 else 0
            rung[1 - b] = rung[1 - b].add(rung[b])
            rung[b] = rung[b].twice()
        }
        return rung[0]
    }

    class Exchange internal constructor(
        private val side: Side, private val w: BigInteger, private val idA: ByteArray, private val idB: ByteArray,
        private var scalar: BigInteger?, private val aad: ByteArray,
    ) {
        /** pA for side A, pB for side B: what this side sends first. */
        val share: ByteArray = secretMultiply(if (side == Side.A) pointM else pointN, w).add(secretMultiply(params.g, scalar!!)).normalize().getEncoded(false)

        /**
         * Takes the other side's share and derives the keys, or answers null when the share must be refused.
         * Works once: the scalar is dropped, so a second run needs a new [Exchange].
         */
        @Synchronized fun finish(peerShare: ByteArray): Keys? {
            val x = checkNotNull(scalar) { "This exchange was already finished" }
            scalar = null
            val peer = point(peerShare) ?: return null
            val k = secretMultiply(peer.subtract(secretMultiply(if (side == Side.A) pointN else pointM, w)), x).normalize()
            if (k.isInfinity) return null
            val pA = if (side == Side.A) share else peerShare.copyOf()
            val pB = if (side == Side.A) peerShare.copyOf() else share
            return Keys(side, idA, idB, pA, pB, k.getEncoded(false), BigIntegers.asUnsignedByteArray(SCALAR_BYTES, w), aad)
        }
    }

    /** The outcome of one run. [ke] is the shared secret; Ka and the confirmation keys are used for nothing else. */
    class Keys internal constructor(
        val side: Side, internal val idA: ByteArray, internal val idB: ByteArray, internal val pA: ByteArray, internal val pB: ByteArray,
        internal val k: ByteArray, w: ByteArray, aad: ByteArray,
    ) {
        internal val tt: ByteArray = transcript(idA, idB, pA, pB, k, w)
        internal val hash: ByteArray = sha256(tt)
        val ke: ByteArray = hash.copyOfRange(0, 16)
        internal val ka: ByteArray = hash.copyOfRange(16, 32)
        private val confirmationKeys = hkdf(ka, "ConfirmationKeys".toByteArray(Charsets.US_ASCII) + aad, 32)
        internal val kcA: ByteArray = confirmationKeys.copyOfRange(0, 16)
        internal val kcB: ByteArray = confirmationKeys.copyOfRange(16, 32)

        /** This side's key confirmation (cA or cB): MAC of the transcript under its confirmation key. */
        val confirmation: ByteArray = hmac(if (side == Side.A) kcA else kcB, tt)
        private val peerConfirmation = hmac(if (side == Side.A) kcB else kcA, tt)

        /** Whether [received] is the other side's key confirmation. Compared in constant time. */
        fun peerConfirms(received: ByteArray): Boolean = MessageDigest.isEqual(peerConfirmation, received)

        override fun toString() = "Spake2.Keys(withheld)"
    }

    /**
     * A received share as a point, or null: exactly 65 bytes, the uncompressed form, both coordinates below
     * the field prime, on the curve and not the point at infinity. Checked here and not left to the provider.
     */
    internal fun point(share: ByteArray): ECPoint? {
        if (share.size != SHARE_BYTES || share[0] != 4.toByte()) return null
        val x = BigInteger(1, share.copyOfRange(1, 33))
        val y = BigInteger(1, share.copyOfRange(33, 65))
        if (x >= fieldPrime || y >= fieldPrime) return null
        val p = try {
            curve.validatePoint(x, y)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return p.takeIf { !it.isInfinity && it.isValid }
    }

    /** TT: each part behind its length as an 8-byte little-endian number (RFC 9382 section 3.3). */
    private fun transcript(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(p.size.toLong()).array())
            out.write(p)
        }
        return out.toByteArray()
    }

    internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    internal fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    /** HKDF-SHA256 (RFC 5869) with no salt. */
    internal fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, null, info)) }.generateBytes(out, 0, length)
        return out
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
