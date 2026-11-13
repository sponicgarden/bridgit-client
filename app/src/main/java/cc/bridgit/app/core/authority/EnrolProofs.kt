package cc.bridgit.app.core.authority

import cc.bridgit.app.feedback.InstallAuth
import java.security.MessageDigest

/**
 * The proofs of the approver enrolment handshake (architecture §4.7 step 3; spending authority G10.3 X1):
 * what each device sends over its transcript, and the check a device runs on what the other sent.
 *
 * Each device sends its SPAKE2 key confirmation, a confirmation of the enrolment transcript made with the
 * shared key, and its install key's signature over the transcript hash. The approver also sends the new
 * approver key's signature, which is the owner's check. All of it may cross the relay: every value depends
 * on the fresh values of one run. The enrolment secret, w, and anything made from them alone never do.
 *
 * [verify] takes only those values from the other side. It checks them against the device's own
 * [EnrolTranscript], so a key swapped on the way, a replayed run or a disagreement about who the approver
 * is cannot pass: the other side's proofs are then over a transcript this device does not hold. The
 * operating device stores the approver's keys, and the approver the operating device's, only on
 * [Result.Accepted]. A [Reason.KEY_CONFIRMATION_FAILED] costs one of the code's five tries.
 */
object EnrolProofs {
    internal const val CONFIRM_LABEL = "${EnrolTranscript.LABEL} confirm"
    internal const val PROOF_LABEL = "${EnrolTranscript.LABEL} proof"
    const val APPROVER_PURPOSE = "approver_enrol"
    private const val MAC_KEY_BYTES = 32

    /** What the other side sent. [approverSignature] only from the side that becomes the approver. */
    class Received(val keyConfirmation: ByteArray, val confirmation: ByteArray, val installSignature: ByteArray, val approverSignature: ByteArray?)

    /** In the order they are checked. [BAD_SHARE] is [Spake2.Exchange.finish] answering null, before there is anything to verify. */
    enum class Reason(val id: String) {
        BAD_SHARE("bad_share"), KEY_CONFIRMATION_FAILED("key_confirmation_failed"), INSTALL_ID_MISMATCH("install_id_mismatch"),
        BAD_CONFIRMATION("bad_confirmation"), BAD_INSTALL_SIGNATURE("bad_install_signature"), BAD_APPROVER_SIGNATURE("bad_approver_signature"),
    }

    sealed interface Result {
        data object Accepted : Result
        data class Refused(val reason: Reason) : Result
    }

    /**
     * [side]'s confirmation of the transcript: HMAC-SHA256 of [transcriptHash] under that side's half of
     * HKDF(Ke, "bridgit-approver-enrol-v1 confirm"), the maker's 32 bytes first. Each direction has its own
     * key, so a confirmation sent back to its sender proves nothing.
     */
    fun confirmation(kex: Spake2.Keys, side: EnrolTranscript.Side, transcriptHash: ByteArray): ByteArray {
        val keys = Spake2.hkdf(kex.ke, EnrolTranscript.utf8(CONFIRM_LABEL), MAC_KEY_BYTES * 2)
        val from = if (side == EnrolTranscript.Side.MAKER) 0 else MAC_KEY_BYTES
        return Spake2.hmac(keys.copyOfRange(from, from + MAC_KEY_BYTES), transcriptHash)
    }

    /** What [side]'s install key signs (P-256 ECDSA, SHA-256): a label, the side and the transcript hash, length-prefixed. */
    fun installProofBytes(side: EnrolTranscript.Side, transcriptHash: ByteArray): ByteArray =
        EnrolTranscript.prefixed(EnrolTranscript.utf8(PROOF_LABEL), EnrolTranscript.utf8(side.id), transcriptHash)

    /**
     * What the new approver key signs right after the owner's check: owner-authorization bytes for
     * `approver_enrol`. Target is the operating device, the digest is the transcript hash, the nonce is the
     * link id and the generation is the transcript's. The expiry is 0, so [OwnerAuth.verify] would call the
     * signature expired: it enrols, and can never pass as a live approval.
     */
    fun approverProofBytes(t: EnrolTranscript): ByteArray =
        OwnerAuth.bytes(APPROVER_PURPOSE, OwnerAuth.APPROVE, t.installId(t.approver.other), t.installId(t.approver), t.hash(), t.linkId, t.generation, 0)

    /**
     * [mine] is this device's side, [view] its own transcript and [kex] its own finished key exchange.
     * Accepted only if the other side holds the same key and the same transcript, signed it with the install
     * key the transcript names, and, when it is the approver, with the approver key the transcript names.
     */
    fun verify(mine: EnrolTranscript.Side, view: EnrolTranscript, kex: Spake2.Keys, received: Received): Result {
        require(kex.side == mine.spake) { "The maker is side A" }
        require(view.kexHash().contentEquals(EnrolTranscript.kexHash(kex))) { "The transcript names another key exchange" }
        fun refuse(reason: Reason) = Result.Refused(reason)
        val peer = mine.other
        if (!kex.peerConfirms(received.keyConfirmation)) return refuse(Reason.KEY_CONFIRMATION_FAILED)
        // An install id is the hash of its key. A key handed over under another key's id is refused by name.
        if (InstallAuth.installId(view.installKey(peer)) != view.installId(peer)) return refuse(Reason.INSTALL_ID_MISMATCH)
        val hash = view.hash()
        if (!MessageDigest.isEqual(confirmation(kex, peer, hash), received.confirmation)) return refuse(Reason.BAD_CONFIRMATION)
        if (!OwnerAuth.verifies(view.installKey(peer), installProofBytes(peer, hash), received.installSignature)) return refuse(Reason.BAD_INSTALL_SIGNATURE)
        if (view.approver == peer) {
            val sig = received.approverSignature ?: return refuse(Reason.BAD_APPROVER_SIGNATURE)
            if (!OwnerAuth.verifies(view.approverKey(), approverProofBytes(view), sig)) return refuse(Reason.BAD_APPROVER_SIGNATURE)
        }
        return Result.Accepted
    }
}
