package cc.bridgit.app.core.authority

import cc.bridgit.app.relay.Sealed
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * [ApproverKeys] with plain in-memory keys, for unit tests (Robolectric has no AndroidKeyStore). It asks for
 * no owner check, so it lives in the test tree and can never be what a device falls back to.
 *
 * [readiness] stands in for the capability probe; [invalidate] does what Android does on a screen-lock or
 * fingerprint change. What it reports about its own keys is the truth: software, no owner check.
 */
class SoftwareApproverKeys(var readiness: ApproverReadiness = ApproverReadiness.READY) : ApproverKeys {
    private var approver: KeyPair? = null
    private var sealing: AsymmetricCipherKeyPair? = null
    private var made = false
    private var message: AsymmetricCipherKeyPair? = null

    /** The challenge the last [create] was given. */
    var challenge: ByteArray? = null
        private set

    fun invalidate() { approver = null; sealing = null }

    override fun state() = when {
        !made -> ApproverKeys.State.ABSENT
        approver == null -> ApproverKeys.State.NEEDS_SETUP
        else -> ApproverKeys.State.READY
    }

    override fun create(challenge: ByteArray): ApproverKeys.Made {
        if (readiness != ApproverReadiness.READY) throw ApproverKeys.Unavailable(readiness)
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val seal = Sealed.generate()
        approver = pair; sealing = seal; made = true; this.challenge = challenge.copyOf()
        val software = ApproverKeys.Protection(ApproverKeys.Hardware.SOFTWARE, ownerCheckRequired = false, ownerCheckInHardware = false, everyUse = false)
        return ApproverKeys.Made(pair.public.encoded, Sealed.publicKey(seal), emptyList(), software, software)
    }

    override fun approverKeySpki(): ByteArray? = approver?.public?.encoded
    override fun sealingPublic(): String? = sealing?.let(Sealed::publicKey)

    override fun beginSign(message: ByteArray): ApproverKeys.Signing {
        val key = approver ?: throw ApproverKeys.NeedsSetup()
        return ApproverKeys.Signing(Signature.getInstance("SHA256withECDSA").apply { initSign(key.private) }, message)
    }

    override fun messageKey(): AsymmetricCipherKeyPair = message ?: Sealed.generate().also { message = it }
    override fun messagePublic(): String = Sealed.publicKey(messageKey())
    override fun clear() { approver = null; sealing = null; made = false; challenge = null }
    override fun resetMessageKey() { message = null }
}
