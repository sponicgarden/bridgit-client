package cc.bridgit.app.security

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cc.bridgit.app.core.authority.ApproverCapability
import cc.bridgit.app.core.authority.ApproverKeys

/**
 * The owner's check bound to one approver-key signature (architecture §4.7; spending authority §5.2, P5).
 *
 * The prompt is given the signature operation itself. The approver key needs the owner's check for every
 * use, so the key store will not finish that signature unless this prompt passed for it: the proof of
 * approval is the signature, not a callback. [OwnerCheck] only runs a callback and accepts weak
 * biometrics; it is not an approval and is not used here.
 *
 * One prompt gives one signature. From API 30 the owner may use a strong biometric or the screen lock; on
 * API 29 a strong biometric only, because the key store offers no per-use key for the screen lock there.
 */
object BoundOwnerCheck {
    sealed interface Outcome {
        /** r||s over the message, made by the approver key after the prompt passed. */
        class Signed(val signatureP1363: ByteArray) : Outcome

        /** The owner closed the prompt. Nothing was signed. */
        object Cancelled : Outcome

        /** No usable approver key: never made, or invalidated by the platform (see [ApproverKeys.State.NEEDS_SETUP]). */
        object NeedsSetup : Outcome

        /** The prompt could not run or the key store refused; [code] is a BiometricPrompt error, or 0. */
        class Failed(val code: Int) : Outcome
    }

    fun authenticators(apiLevel: Int = Build.VERSION.SDK_INT) =
        if (ApproverCapability.credentialAllowed(apiLevel)) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

    /**
     * Shows the prompt and signs [message] (the canonical bytes, e.g. [cc.bridgit.app.core.authority.OwnerAuth.bytes])
     * with the approver key. The bytes are copied before the prompt opens, so a change to [message] while the
     * owner is deciding does not change what is signed. Call on the main thread; [done] runs there once.
     */
    fun sign(a: FragmentActivity, keys: ApproverKeys, title: String, reason: String, message: ByteArray, done: (Outcome) -> Unit) {
        val signing = try {
            keys.beginSign(message)
        } catch (e: ApproverKeys.NeedsSetup) {
            done(Outcome.NeedsSetup); return
        } catch (e: Exception) {
            done(Outcome.Failed(0)); return
        }
        val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Only the object the prompt authorized can sign; a fresh one would be refused by the key store.
                val authorized = result.cryptoObject?.signature
                done(if (authorized == null) Outcome.Failed(0) else try {
                    Outcome.Signed(signing.finish(authorized))
                } catch (e: Exception) {
                    Outcome.Failed(0)
                })
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(when (errorCode) {
                BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> Outcome.Cancelled
                else -> Outcome.Failed(errorCode)
            })
        })
        val allowed = authenticators()
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(reason).setAllowedAuthenticators(allowed)
            .setConfirmationRequired(true)
            // The screen lock takes the place of the cancel button; without it the platform requires one.
            .apply { if (allowed and DEVICE_CREDENTIAL == 0) setNegativeButtonText(a.getString(android.R.string.cancel)) }.build()
        try {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(signing.signature))
        } catch (e: IllegalArgumentException) {
            done(Outcome.Failed(0))
        }
    }
}
