package cc.bridgit.app.core.authority

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG

/** Reads [ApproverCapabilities] from this device. Asks nothing of the owner and makes no key. */
object AndroidApproverProbe {
    fun read(ctx: Context): ApproverCapabilities {
        val keyguard = ctx.getSystemService(KeyguardManager::class.java)
        return ApproverCapabilities(
            apiLevel = Build.VERSION.SDK_INT,
            secureLockScreen = keyguard?.isDeviceSecure == true,
            strongBox = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
            strongBiometric = BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS,
            perUseCredentialKeys = ApproverCapability.credentialAllowed(Build.VERSION.SDK_INT),
        )
    }

    fun readiness(ctx: Context): ApproverReadiness = ApproverCapability.decide(read(ctx))
}
