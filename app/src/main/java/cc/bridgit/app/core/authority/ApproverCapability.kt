package cc.bridgit.app.core.authority

/**
 * What this device offers an approver key (docs/plans/client-core/architecture.md §4.7; spending authority
 * §5.1, §5.2). Pure data, so the decision is tested without a device; [AndroidApproverProbe] reads it.
 *
 * [perUseCredentialKeys] says whether the key store can make a key that needs the owner's check for every
 * single use and accepts the screen lock (PIN, pattern, password) as that check. Android does from API 30.
 * On API 29 a per-use key accepts a strong biometric only, so [strongBiometric] decides there.
 * [strongBox] changes where the key is made, never whether the device can be an approver.
 */
data class ApproverCapabilities(
    val apiLevel: Int,
    val secureLockScreen: Boolean,
    val strongBox: Boolean,
    val strongBiometric: Boolean,
    val perUseCredentialKeys: Boolean,
)

/** Whether this device can hold an approver key. The ids are the contract's blocked reasons. */
enum class ApproverReadiness(val id: String) {
    READY("ready"),
    NO_SCREEN_LOCK("no_screen_lock"),
    PLATFORM_UNSUPPORTED("platform_unsupported"),
}

object ApproverCapability {
    /** The app's floor. Below it there is no per-use key bound to a prompt at all. */
    const val MIN_API = 29

    /** From this level a per-use key accepts the screen lock as well as a strong biometric. */
    const val CREDENTIAL_API = 30

    fun credentialAllowed(apiLevel: Int) = apiLevel >= CREDENTIAL_API

    /**
     * No screen lock is reported before anything else the owner can fix. A device that has a screen lock
     * but can only bind a per-use key to a strong biometric it does not have (API 29 with no enrolled
     * fingerprint or face) is `platform_unsupported`: the contract has no narrower reason.
     */
    fun decide(c: ApproverCapabilities): ApproverReadiness = when {
        c.apiLevel < MIN_API -> ApproverReadiness.PLATFORM_UNSUPPORTED
        !c.secureLockScreen -> ApproverReadiness.NO_SCREEN_LOCK
        c.perUseCredentialKeys || c.strongBiometric -> ApproverReadiness.READY
        else -> ApproverReadiness.PLATFORM_UNSUPPORTED
    }
}
