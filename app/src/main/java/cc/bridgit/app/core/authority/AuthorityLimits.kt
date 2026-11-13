package cc.bridgit.app.core.authority

/** Waits of the approver contract (contracts/client-core/limits.json checks these values). Contract data, never a setting. */
object AuthorityLimits {
    /** Running time before an approver recovery clears the approver list (architecture §4.7, D16b). */
    const val RECOVERY_WAIT_MS = 72L * 60 * 60 * 1000

    /** Running time before an approver enrolled after first set-up, or after a recovery, can approve (D16c). */
    const val APPROVER_WAIT_MS = 24L * 60 * 60 * 1000

    /** How long a request for the owner's decision can be answered (architecture §4.7: a request waits, then expires). */
    const val REQUEST_LIFETIME_MS = 10L * 60 * 1000

    /** Bytes of the enrolment secret an approver link carries in `es` (128 bits; spending authority G10.3 X1). */
    const val ENROL_SECRET_BYTES = 16

    /** PBKDF2 rounds from the enrolment secret to SPAKE2's w. Small on purpose: the strength is in the secret. */
    const val ENROL_PBKDF2_ITERATIONS = 1000
}
