package cc.bridgit.app.security

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R

/**
 * The optional fingerprint check (Settings › Security › Confirm with fingerprint). An agent with Super Powers can
 * tap the screen through ADB, but it can't pass a fingerprint, face or screen-lock prompt, so the steps that would
 * let it raise its own power go through here. When the setting is off, [run] just runs the action.
 */
object OwnerCheck {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Runs [action] now when the setting is off; otherwise only after the owner passes the prompt. */
    fun run(f: Fragment, reason: String, onCancel: () -> Unit = {}, action: () -> Unit) {
        val app = f.requireContext().applicationContext as BridgeApp
        if (!app.settings.securityBiometric) action() else confirm(f.requireActivity(), reason, onCancel, action)
    }

    fun run(a: FragmentActivity, reason: String, onCancel: () -> Unit = {}, action: () -> Unit) {
        val app = a.applicationContext as BridgeApp
        if (!app.settings.securityBiometric) action() else confirm(a, reason, onCancel, action)
    }

    /** Always asks (used when the owner weakens a security setting while the check is on). */
    fun confirm(f: Fragment, reason: String, action: () -> Unit) = confirm(f.requireActivity(), reason, {}, action)

    fun confirm(a: FragmentActivity, reason: String, onCancel: () -> Unit, action: () -> Unit) {
        if (BiometricManager.from(a).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(a, R.string.biometric_unavailable, Toast.LENGTH_LONG).show()
            onCancel(); return
        }
        val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = action()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onCancel()
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(a.getString(R.string.biometric_title))
            .setSubtitle(reason).setAllowedAuthenticators(AUTHENTICATORS).build())
    }
}
