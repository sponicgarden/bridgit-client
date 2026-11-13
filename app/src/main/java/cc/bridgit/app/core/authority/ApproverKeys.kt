package cc.bridgit.app.core.authority

import cc.bridgit.app.feedback.InstallAuth
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import java.security.Signature

/**
 * The keys an approver device holds (architecture §4.7; spending authority §5.1 and G10.3 X3).
 *
 * - **Approver key**: P-256, signs one owner authorization per owner check. Signing is two steps so the
 *   check cannot be skipped: [beginSign] hands out a [Signing] whose [Signing.signature] goes to the
 *   platform prompt, and only [Signing.finish] after that prompt yields bytes. There is no one-step sign.
 * - **Sealing key**: what an overlay seals a copied secret to. It opens only under the owner's check.
 *   Only its public half is used so far.
 * - **Message key**: what approval messages are sealed to. It needs no owner check, so a request can be
 *   opened and shown before the owner decides, and it never seals a secret. Every device has one,
 *   approver or not, so it is made on first use and is not part of [state].
 *
 * The sealing and message public keys are in the form `relay/Sealed.kt` uses (base64 of the uncompressed
 * P-256 point), since both are HPKE keys of that suite. The approver key is SPKI, as [OwnerAuth] reads it.
 */
interface ApproverKeys {
    enum class State(val id: String) {
        /** Never made on this device. */
        ABSENT("absent"),
        READY("ready"),

        /**
         * Made, then lost: the platform invalidated it. Removing the screen lock always does. Adding a
         * fingerprint or face does only where the key takes a biometric alone (API 29); a key that also
         * takes the screen lock (API 30+) is exempt from that.
         */
        NEEDS_SETUP("needs_setup"),
    }

    /** Where a key lives, as the key store's own metadata says after it was made. */
    enum class Hardware(val id: String) { SOFTWARE("software"), TEE("tee"), STRONGBOX("strongbox") }

    /**
     * What the key store reports about one generated key. Read from the key, never assumed from what was
     * asked for: the Keystore provider can hand back a software key, or enforce the owner check in software.
     */
    data class Protection(val hardware: Hardware, val ownerCheckRequired: Boolean, val ownerCheckInHardware: Boolean, val everyUse: Boolean) {
        /** Fit for an approver or sealing-wrap key: in secure hardware, which itself enforces the owner check on every use. */
        val acceptable get() = hardware != Hardware.SOFTWARE && ownerCheckRequired && ownerCheckInHardware && everyUse
    }

    /**
     * The public result of [create]. [attestation] is DER certificates, leaf first; empty when the key store
     * gave none, and unverified evidence until the enrolment verifier checks it. [approverKey] and
     * [sealingWrapKey] are the protection the key store reports for the two owner-checked keys.
     */
    class Made(
        val approverKeySpki: ByteArray,
        val sealingPublic: String,
        val attestation: List<ByteArray>,
        val approverKey: Protection,
        val sealingWrapKey: Protection,
    ) {
        val strongBox get() = approverKey.hardware == Hardware.STRONGBOX
    }

    /** The keys were made and the platform has since invalidated them, or were never made. Enrol again. */
    class NeedsSetup : Exception("approver key needs set-up")

    /** This device cannot hold an approver key now; [readiness] is never READY. */
    class Unavailable(val readiness: ApproverReadiness) : Exception(readiness.id)

    /**
     * One signature in progress. [signature] is ready to sign and, on a hardware key, unusable until the
     * prompt passes. The bytes to sign are copied when this is made, before any prompt opens: the prompt
     * binds the key operation, not its message, so what the caller does to its buffer afterwards must not
     * change what is signed.
     */
    class Signing(val signature: Signature, message: ByteArray) {
        private val bytes = message.copyOf()

        /** Signs the bytes this was made with and returns r||s. [authorized] is the object the prompt gave back. */
        fun finish(authorized: Signature = signature): ByteArray =
            InstallAuth.derToP1363(authorized.apply { update(bytes) }.sign())
    }

    fun state(): State

    /**
     * Makes a fresh approver key and sealing key, replacing any older ones. [challenge] is the enrolment
     * transcript's fresh value from the other side; it becomes the attestation challenge, so the
     * attestation proves the key was made for this enrolment. Throws [Unavailable], also when the key store
     * made a key that is not [Protection.acceptable]; nothing is kept then.
     */
    fun create(challenge: ByteArray): Made

    fun approverKeySpki(): ByteArray?
    fun sealingPublic(): String?

    /**
     * Starts one signature over [message] (the [OwnerAuth.bytes], not a digest of them), which is copied
     * here. Throws [NeedsSetup] when there is no usable key; shows nothing.
     */
    fun beginSign(message: ByteArray): Signing

    /** This device's message key pair, made on first use. No owner check. */
    fun messageKey(): AsymmetricCipherKeyPair
    fun messagePublic(): String

    /** Drops the approver and sealing keys (removal, recovery). The message key stays until [resetMessageKey]. */
    fun clear()

    /** A new message key. Only with an approver add, remove or recovery, since the transcripts name it. */
    fun resetMessageKey()
}
