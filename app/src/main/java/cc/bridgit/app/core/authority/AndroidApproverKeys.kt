package cc.bridgit.app.core.authority

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import cc.bridgit.app.relay.Sealed
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import java.security.InvalidAlgorithmParameterException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * [ApproverKeys] in the Android Keystore.
 *
 * - The approver key is P-256, made in StrongBox where the device has one and in the TEE otherwise, never
 *   exportable, and needs the owner's check for **every** use: from API 30 a strong biometric or the screen
 *   lock, on API 29 a strong biometric only (the platform offers no per-use key for the screen lock there).
 *   Attestation may be given up when the key store refuses it (recorded as an empty chain); the owner check
 *   is never given up, and there is no software fallback.
 * - The sealing key is a software HPKE key (the Keystore has no key agreement before Android 12). Its
 *   private half is wrapped by an RSA key with the same every-use owner check. RSA, so wrapping at set-up
 *   needs only the public half and no prompt, while unwrapping needs one.
 * - The message key is a software HPKE key wrapped by a Keystore key with no owner check, as
 *   `relay/RelayStore.kt` keeps the relay's, under its own alias and preferences.
 *
 * After each of the two owner-checked keys is made, the key store's own record of it is read back
 * ([protection]). A key that is software-backed, or whose owner check is not enforced by the secure
 * hardware on every use, is deleted and the device reports `platform_unsupported`.
 *
 * Android invalidates a key that needs the owner's check when the screen lock is removed. Adding a
 * fingerprint or face invalidates only a key that takes a biometric alone, which is the API 29 case; from
 * API 30 the key also takes the screen lock and Android exempts it. The public key stored at creation is
 * the marker that tells "made and since lost" ([ApproverKeys.State.NEEDS_SETUP]) from "never made".
 */
class AndroidApproverKeys(context: Context) : ApproverKeys {
    private val ctx = context.applicationContext
    private val prefs get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val messagePrefs get() = ctx.getSharedPreferences(MESSAGE_PREFS, Context.MODE_PRIVATE)
    private val keys get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun approverKeySpki(): ByteArray? = prefs.getString("approver_spki", null)?.let(Sealed::un64)
    override fun sealingPublic(): String? = prefs.getString("sealing_public", null)

    @Synchronized
    override fun state(): ApproverKeys.State {
        if (approverKeySpki() == null) return ApproverKeys.State.ABSENT
        return try {
            signer()
            unsealer()
            ApproverKeys.State.READY
        } catch (e: ApproverKeys.NeedsSetup) {
            ApproverKeys.State.NEEDS_SETUP
        }
    }

    @Synchronized
    override fun beginSign(message: ByteArray) = ApproverKeys.Signing(signer(), message)

    @Synchronized
    override fun create(challenge: ByteArray): ApproverKeys.Made {
        AndroidApproverProbe.readiness(ctx).let { if (it != ApproverReadiness.READY) throw ApproverKeys.Unavailable(it) }
        clear()
        val (approverKey, wrapKey) = try {
            val a = makeApproverKey(challenge)
            a to makeWrapKey(a.hardware == ApproverKeys.Hardware.STRONGBOX)
        } catch (e: Exception) {
            clear()
            if (e is ApproverKeys.Unavailable) throw e
            // The key store refuses an owner-checked key when the lock or the biometric went away meanwhile.
            if (e !is InvalidAlgorithmParameterException && e !is IllegalStateException && e !is ProviderException) throw e
            val now = AndroidApproverProbe.readiness(ctx)
            throw ApproverKeys.Unavailable(if (now == ApproverReadiness.READY) ApproverReadiness.PLATFORM_UNSUPPORTED else now)
        }
        val store = keys
        val chain = store.getCertificateChain(APPROVER)!!.map { it.encoded }
        val spki = store.getCertificate(APPROVER).publicKey.encoded
        val sealing = Sealed.generate()
        val secret = Sealed.privateKey(sealing)
        val wrapped = try {
            // The public half is used outside the Keystore, whose OAEP keeps MGF1 on SHA-1 whatever the main digest.
            val wrapKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(store.getCertificate(SEAL_WRAP).publicKey.encoded))
            Cipher.getInstance(OAEP).apply { init(Cipher.ENCRYPT_MODE, wrapKey, OAEP_SPEC) }.doFinal(secret)
        } finally {
            secret.fill(0)
        }
        val made = ApproverKeys.Made(spki, Sealed.publicKey(sealing), if (chain.size > 1) chain else emptyList(), approverKey, wrapKey)
        check(prefs.edit().putString("approver_spki", Sealed.b64(spki)).putString("sealing_public", made.sealingPublic)
            .putString("sealing_wrapped", Sealed.b64(wrapped)).putString("approver_hardware", approverKey.hardware.id).putString("sealing_wrap_hardware", wrapKey.hardware.id).commit())
        return made
    }

    @Synchronized
    override fun clear() {
        runCatching { keys.deleteEntry(APPROVER) }
        runCatching { keys.deleteEntry(SEAL_WRAP) }
        check(prefs.edit().clear().commit())
    }

    @Synchronized
    override fun messageKey(): AsymmetricCipherKeyPair {
        val store = keys
        if (!store.containsAlias(MESSAGE_WRAP)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(MESSAGE_WRAP, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        val wrap = store.getKey(MESSAGE_WRAP, null)
        val p = messagePrefs
        val saved = p.getString("secret", null)
        if (saved != null) {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, Sealed.un64(p.getString("iv", "")!!))) }
            return Sealed.restore(c.doFinal(Sealed.un64(saved)), p.getString("public", "")!!)
        }
        val pair = Sealed.generate()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrap) }
        val secret = Sealed.privateKey(pair)
        try {
            check(p.edit().putString("secret", Sealed.b64(c.doFinal(secret))).putString("iv", Sealed.b64(c.iv))
                .putString("public", Sealed.publicKey(pair)).commit())
        } finally {
            secret.fill(0)
        }
        return pair
    }

    @Synchronized
    override fun messagePublic(): String = messagePrefs.getString("public", null) ?: Sealed.publicKey(messageKey())

    @Synchronized
    override fun resetMessageKey() {
        runCatching { keys.deleteEntry(MESSAGE_WRAP) }
        check(messagePrefs.edit().clear().commit())
    }

    /** A signature object on the approver key, ready for the prompt. Starting one asks nothing of the owner. */
    private fun signer(): Signature = usable {
        val spki = approverKeySpki() ?: throw ApproverKeys.NeedsSetup()
        val store = keys
        val cert = store.getCertificate(APPROVER) ?: throw ApproverKeys.NeedsSetup()
        if (!cert.publicKey.encoded.contentEquals(spki)) throw ApproverKeys.NeedsSetup()
        val key = store.getKey(APPROVER, null) as? PrivateKey ?: throw ApproverKeys.NeedsSetup()
        Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
    }

    /** A cipher on the sealing key's wrap key, ready for the prompt. Used so far only to see that the key is still there. */
    private fun unsealer(): Cipher = usable {
        if (prefs.getString("sealing_wrapped", null) == null) throw ApproverKeys.NeedsSetup()
        val key = keys.getKey(SEAL_WRAP, null) as? PrivateKey ?: throw ApproverKeys.NeedsSetup()
        Cipher.getInstance(OAEP).apply { init(Cipher.DECRYPT_MODE, key, OAEP_SPEC) }
    }

    /**
     * Only positive evidence is "needs set-up": the key is gone, differs from the marker, or the platform says it
     * invalidated it. Any other key-store failure is an error and propagates, because set-up replaces the key
     * and starts the approver's wait again.
     */
    private fun <T> usable(block: () -> T): T = try {
        block()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw ApproverKeys.NeedsSetup()
    } catch (e: UnrecoverableKeyException) {
        if (e.cause is KeyPermanentlyInvalidatedException) throw ApproverKeys.NeedsSetup() else throw e
    }

    private fun KeyGenParameterSpec.Builder.ownerCheckEveryUse(strongBox: Boolean) = apply {
        setUserAuthenticationRequired(true)
        // API 29 keeps the default: every use, and a strong biometric is the only check the key store takes.
        if (Build.VERSION.SDK_INT >= ApproverCapability.CREDENTIAL_API) {
            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        }
        // Only has an effect on the biometric-only key of API 29; a key that takes the screen lock is exempt.
        setInvalidatedByBiometricEnrollment(true)
        if (strongBox) setIsStrongBoxBacked(true)
    }

    /**
     * What the key store says about the key it just made under [alias]. [askedStrongBox] is used only below
     * API 31, where the record says "secure hardware" without saying which; a StrongBox request that
     * succeeded there is StrongBox, since the key store refuses it otherwise.
     */
    private fun protection(alias: String, askedStrongBox: Boolean): ApproverKeys.Protection {
        val key = keys.getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        val hardware = if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> ApproverKeys.Hardware.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> ApproverKeys.Hardware.TEE
                else -> ApproverKeys.Hardware.SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            val secure = info.isInsideSecureHardware
            when {
                !secure -> ApproverKeys.Hardware.SOFTWARE
                askedStrongBox -> ApproverKeys.Hardware.STRONGBOX
                else -> ApproverKeys.Hardware.TEE
            }
        }
        return ApproverKeys.Protection(
            hardware, info.isUserAuthenticationRequired,
            info.isUserAuthenticationRequirementEnforcedBySecureHardware, info.userAuthenticationValidityDurationSeconds <= 0,
        )
    }

    /**
     * Runs [make] for each choice in turn and keeps the first key whose reported protection is acceptable.
     * A key that is not is deleted before the next try. Throws [ApproverKeys.Unavailable] when a key was made
     * and none was acceptable, and the key store's own error when none could be made.
     */
    private fun <C> firstAcceptable(alias: String, choices: List<C>, strongBox: (C) -> Boolean, make: (C) -> Unit): ApproverKeys.Protection {
        var last: ProviderException? = null
        var rejected = false
        for (choice in choices) {
            try {
                make(choice)
            } catch (e: ProviderException) { // StrongBoxUnavailableException is one
                last = e
                continue
            }
            val got = try {
                protection(alias, strongBox(choice))
            } catch (e: Exception) {
                runCatching { keys.deleteEntry(alias) }
                throw e
            }
            if (got.acceptable) return got
            keys.deleteEntry(alias)
            rejected = true
        }
        if (rejected) throw ApproverKeys.Unavailable(ApproverReadiness.PLATFORM_UNSUPPORTED)
        throw last!!
    }

    /** StrongBox with attestation, StrongBox without, then the TEE with and without: attestation is given up before the hardware is. */
    private fun makeApproverKey(challenge: ByteArray) =
        firstAcceptable(APPROVER, listOf(true to true, false to true, true to false, false to false), { it.second }) { (attest, strongBox) ->
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(APPROVER, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                    .apply { if (attest) setAttestationChallenge(challenge) }.ownerCheckEveryUse(strongBox).build())
            }.generateKeyPair()
        }

    /** The sealing key's wrap key, in the same place as the approver key where that works. */
    private fun makeWrapKey(preferStrongBox: Boolean) =
        firstAcceptable(SEAL_WRAP, if (preferStrongBox) listOf(true, false) else listOf(false), { it }) { strongBox ->
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(SEAL_WRAP, KeyProperties.PURPOSE_DECRYPT).setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .ownerCheckEveryUse(strongBox).build())
            }.generateKeyPair()
        }

    private companion object {
        const val PREFS = "approver_keys"
        const val MESSAGE_PREFS = "approver_message_key"
        const val APPROVER = "bridgit-approver-v1"
        const val SEAL_WRAP = "bridgit-approver-seal-wrap-v1"
        const val MESSAGE_WRAP = "bridgit-approver-message-wrap-v1"
        const val OAEP = "RSA/ECB/OAEPPadding"
        val OAEP_SPEC = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
