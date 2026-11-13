package cc.bridgit.app.core.authority

import cc.bridgit.app.core.contract.ContractVocab
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.X509EncodedKeySpec

/**
 * Owner authorization, `bridgit-owner-auth-v1` (docs/plans/client-core/architecture.md §4.7): the bytes an
 * approver key signs for one request, and the check the operating device runs on an answer.
 *
 * The verifier builds the bytes from its own [Record] and [State]. From the [Answer] it takes the decision,
 * the approver id and the signature, and nothing else. An approval is only ever a signature by an enrolled
 * approver key that is past its wait; a decline is the same bytes with `decline` under that approver
 * device's install key. The contract pack's crypto/owner-auth.json holds the vectors.
 *
 * This is the signature check only, and it is checked against the authority generation stored in the
 * request's record, not the device's current one: an unrelated authority change since the request was made
 * does not void a signature. A signature that names any other generation than the record's fails.
 *
 * The caller owns the execution-time check (§4.7, review 3 C4). In the same atomic step as applying a
 * decision it must: confirm the request is still open and spend its nonce; run the request's whole
 * authorisation again against today's state (requester still granted, feature still on, the approver still
 * enrolled, limits still met) and refuse with `conflict` if it would no longer be allowed, including when a
 * relevant change was made without the generation counter rising; and refuse an owner-authorized setting
 * change that was made against an older generation than the current one. [State.nonceUsed] defaults to "never
 * used", which is no replay protection by itself.
 */
object OwnerAuth {
    const val LABEL = ContractVocab.OWNER_AUTH_LABEL
    const val APPROVE = "approve"
    const val DECLINE = "decline"
    private const val DIGEST_BYTES = 32

    private val purposes = ContractVocab.OWNER_AUTH_PURPOSES.associateBy { it.id }

    fun isPurpose(id: String) = id in purposes

    /** The request as the operating device stored it. [digest] is the SHA-256 of the record, [nonce] its one-use id. */
    class Record(val purpose: String, val digest: ByteArray, val nonce: String, val generation: Long, val expiresAtMs: Long)

    /** What came back. [label], [purpose] and [target] are what the sender says it signed; they can only cause a refusal. */
    class Answer(val label: String, val purpose: String, val decision: String, val target: String, val approver: String, val signatureP1363: ByteArray)

    /** An enrolled approver device: its id, its approver key and its install key (both SPKI), and whether its wait is over. */
    class Approver(val id: String, val approverKeySpki: ByteArray, val installKeySpki: ByteArray, val pastWait: Boolean)

    /**
     * The operating device now. [nonceUsed] says whether a request's one-use id was already spent. The
     * device's current authority generation is deliberately not here: see the class comment.
     */
    class State(
        val self: String,
        val approvers: List<Approver>,
        val nowMs: Long,
        val enabledProposed: Set<String> = emptySet(),
        val nonceUsed: (String) -> Boolean = { false },
    )

    enum class Reason(val id: String) {
        UNKNOWN_LABEL("unknown_label"), UNKNOWN_PURPOSE("unknown_purpose"), PURPOSE_NOT_ENABLED("purpose_not_enabled"),
        UNKNOWN_DECISION("unknown_decision"), PURPOSE_MISMATCH("purpose_mismatch"), WRONG_TARGET("wrong_target"),
        EXPIRED("expired"), NONCE_REUSED("nonce_reused"),
        UNKNOWN_APPROVER("unknown_approver"), APPROVER_WAITING("approver_waiting"), BAD_SIGNATURE("bad_signature"),
    }

    sealed interface Result {
        data class Approved(val approver: String) : Result
        data class Declined(val approver: String) : Result
        data class Refused(val reason: Reason) : Result
    }

    /**
     * The canonical bytes: label, purpose, decision, target, approver, digest, nonce, generation, expiry, in
     * that order, each behind a 4-byte big-endian length. Text is UTF-8; generation and expiry are 8-byte
     * big-endian. Never made from JSON.
     */
    fun bytes(purpose: String, decision: String, target: String, approver: String, digest: ByteArray, nonce: String, generation: Long, expiresAtMs: Long): ByteArray {
        require(isPurpose(purpose)) { "Unknown owner-authorization purpose" }
        require(decision in ContractVocab.OWNER_AUTH_DECISIONS) { "Unknown decision" }
        require(digest.size == DIGEST_BYTES) { "The request digest is SHA-256" }
        require(target.isNotEmpty() && approver.isNotEmpty() && nonce.isNotEmpty()) { "Empty field" }
        require(generation >= 0 && expiresAtMs >= 0) { "Negative number" }
        val out = ByteArrayOutputStream()
        fun field(b: ByteArray) { out.write(ByteBuffer.allocate(4).putInt(b.size).array()); out.write(b) }
        fun long(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
        for (text in listOf(LABEL, purpose, decision, target, approver)) field(text.toByteArray(Charsets.UTF_8))
        field(digest)
        field(nonce.toByteArray(Charsets.UTF_8))
        field(long(generation))
        field(long(expiresAtMs))
        return out.toByteArray()
    }

    fun verify(record: Record, answer: Answer, state: State): Result {
        fun refuse(reason: Reason) = Result.Refused(reason)
        if (answer.label != LABEL) return refuse(Reason.UNKNOWN_LABEL)
        val purpose = purposes[record.purpose]
        if (purpose == null || !isPurpose(answer.purpose)) return refuse(Reason.UNKNOWN_PURPOSE)
        if (purpose.proposed && purpose.id !in state.enabledProposed) return refuse(Reason.PURPOSE_NOT_ENABLED)
        if (answer.decision != APPROVE && answer.decision != DECLINE) return refuse(Reason.UNKNOWN_DECISION)
        if (answer.purpose != record.purpose) return refuse(Reason.PURPOSE_MISMATCH)
        if (answer.target != state.self) return refuse(Reason.WRONG_TARGET)
        if (state.nowMs >= record.expiresAtMs) return refuse(Reason.EXPIRED)
        if (state.nonceUsed(record.nonce)) return refuse(Reason.NONCE_REUSED)
        val approver = state.approvers.firstOrNull { it.id == answer.approver } ?: return refuse(Reason.UNKNOWN_APPROVER)
        val approve = answer.decision == APPROVE
        if (approve && !approver.pastWait) return refuse(Reason.APPROVER_WAITING)
        val signed = try {
            bytes(record.purpose, answer.decision, state.self, approver.id, record.digest, record.nonce, record.generation, record.expiresAtMs)
        } catch (e: IllegalArgumentException) {
            return refuse(Reason.BAD_SIGNATURE)
        }
        // An approval is only ever under the approver key; a decline only ever under that device's install key.
        val key = if (approve) approver.approverKeySpki else approver.installKeySpki
        if (!verifies(key, signed, answer.signatureP1363)) return refuse(Reason.BAD_SIGNATURE)
        return if (approve) Result.Approved(approver.id) else Result.Declined(approver.id)
    }

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * P-256 ECDSA with SHA-256 over [message]; [sigP1363] is r||s, 64 bytes. False for anything malformed.
     * The key's point and the signature's r and s are checked here and not left to the crypto provider.
     */
    internal fun verifies(spki: ByteArray, message: ByteArray, sigP1363: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
        validKey(key) && validScalars(sigP1363) &&
            Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(message) }.verify(p1363ToDer(sigP1363))
    } catch (e: Exception) {
        false
    }

    /** On P-256 itself (not another curve), not the point at infinity, both coordinates below p, and on the curve. */
    internal fun validKey(key: ECPublicKey): Boolean {
        val c = key.params.curve
        if (key.params.order != p256.order || c != p256.curve || key.params.generator != p256.generator || key.params.cofactor != 1) return false
        val w = key.w
        if (w == ECPoint.POINT_INFINITY) return false
        val p = (c.field as? ECFieldFp)?.p ?: return false
        val x = w.affineX
        val y = w.affineY
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        return y.multiply(y).mod(p) == x.multiply(x).multiply(x).add(c.a.multiply(x)).add(c.b).mod(p)
    }

    /** 64 bytes, with r and s each from 1 to n-1. */
    internal fun validScalars(sigP1363: ByteArray): Boolean {
        if (sigP1363.size != 64) return false
        val n = p256.order
        return listOf(0, 32).all { BigInteger(1, sigP1363.copyOfRange(it, it + 32)).let { v -> v.signum() > 0 && v < n } }
    }

    /** r||s to DER, which every provider's SHA256withECDSA verifies (the reverse of InstallAuth.derToP1363). */
    internal fun p1363ToDer(sig: ByteArray): ByteArray {
        require(sig.isNotEmpty() && sig.size % 2 == 0)
        val half = sig.size / 2
        fun int(from: Int): ByteArray {
            val v = BigInteger(1, sig.copyOfRange(from, from + half)).toByteArray()
            return byteArrayOf(0x02) + length(v.size) + v
        }
        val body = int(0) + int(half)
        return byteArrayOf(0x30) + length(body.size) + body
    }

    private fun length(n: Int): ByteArray = if (n < 0x80) byteArrayOf(n.toByte()) else byteArrayOf(0x81.toByte(), n.toByte())
}
