package cc.bridgit.app.core.authority

import cc.bridgit.app.feedback.InstallAuth
import java.nio.ByteBuffer

/**
 * A device enrolling itself as its own approver (architecture §4.7; spending authority G10.3 X5): the
 * enrolment transcript `bridgit-approver-enrol-v1` in its one-device form, and the two proofs over it.
 *
 * The sixteen fields of [EnrolTranscript], in the same order and encoding, filled for one install:
 * no link id (zero bytes), the install id and install key in both the maker's and the claimer's place,
 * `self` as the approver, the device's message key in both places, no key-exchange hash (zero bytes), and
 * the one 32-byte random value in both places. There is no channel to authenticate, so no SPAKE2 and no
 * confirmation. [EnrolTranscript] itself refuses equal install ids, so the two forms cannot be confused.
 *
 * The install key signs the transcript hash as side `self`. The new approver key signs owner-authorization
 * bytes for `approver_enrol` right after the owner's check: target and approver are both this install, the
 * digest is the transcript hash, the nonce is the random value in lowercase hex (there is no link id) and
 * the expiry is 0, so the signature enrols and can never pass as a live approval. The contract pack's
 * crypto/spake2.json holds the vectors (`enrol.self`).
 */
class SelfEnrol(
    val installId: String,
    installKey: ByteArray,
    approverKey: ByteArray,
    sealingKey: ByteArray,
    attestation: ByteArray,
    messageKey: ByteArray,
    val generation: Long,
    nonce: ByteArray,
) {
    private val installKey = installKey.copyOf()
    private val approverKey = approverKey.copyOf()
    private val sealingKey = sealingKey.copyOf()
    private val attestation = attestation.copyOf()
    private val messageKey = messageKey.copyOf()
    private val nonce = nonce.copyOf()

    init {
        require(installId.isNotEmpty()) { "Empty id" }
        require(listOf(installKey, approverKey, sealingKey, messageKey).all { it.isNotEmpty() }) { "Empty key" }
        require(nonce.size == EnrolTranscript.NONCE_BYTES) { "The random value is ${EnrolTranscript.NONCE_BYTES} bytes" }
        require(generation >= 0) { "Negative generation" }
    }

    fun installKey(): ByteArray = installKey.copyOf()
    fun approverKey(): ByteArray = approverKey.copyOf()

    /** The request's one-use id: the random value in lowercase hex. */
    fun nonceText(): String = hex(nonce)

    fun bytes(): ByteArray = EnrolTranscript.prefixed(
        EnrolTranscript.utf8(EnrolTranscript.LABEL), ByteArray(0), EnrolTranscript.utf8(installId), installKey, EnrolTranscript.utf8(installId), installKey,
        EnrolTranscript.utf8(SIDE), approverKey, sealingKey, attestation, messageKey, messageKey, ByteArray(0),
        ByteBuffer.allocate(8).putLong(generation).array(), nonce, nonce,
    )

    fun hash(): ByteArray = Spake2.sha256(bytes())

    /** What the install key signs. */
    fun installProofBytes(): ByteArray = installProofBytes(hash())

    /** What the new approver key signs after the owner's check. */
    fun approverProofBytes(): ByteArray =
        OwnerAuth.bytes(EnrolProofs.APPROVER_PURPOSE, OwnerAuth.APPROVE, installId, installId, hash(), nonceText(), generation, 0)

    /**
     * The check before the approver is stored: the id is this install key's, the install key signed the
     * transcript, and the approver key the transcript names signed it for `approver_enrol`.
     */
    fun verify(installSignature: ByteArray, approverSignature: ByteArray?): EnrolProofs.Result {
        fun refuse(reason: EnrolProofs.Reason) = EnrolProofs.Result.Refused(reason)
        if (InstallAuth.installId(installKey) != installId) return refuse(EnrolProofs.Reason.INSTALL_ID_MISMATCH)
        if (!OwnerAuth.verifies(installKey, installProofBytes(), installSignature)) return refuse(EnrolProofs.Reason.BAD_INSTALL_SIGNATURE)
        if (approverSignature == null || !OwnerAuth.verifies(approverKey, approverProofBytes(), approverSignature)) return refuse(EnrolProofs.Reason.BAD_APPROVER_SIGNATURE)
        return EnrolProofs.Result.Accepted
    }

    companion object {
        const val SIDE = "self"

        fun installProofBytes(transcriptHash: ByteArray): ByteArray =
            EnrolTranscript.prefixed(EnrolTranscript.utf8(EnrolProofs.PROOF_LABEL), EnrolTranscript.utf8(SIDE), transcriptHash)

        internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
