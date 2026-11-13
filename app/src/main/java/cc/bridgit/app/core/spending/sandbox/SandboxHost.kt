package cc.bridgit.app.core.spending.sandbox

import java.io.IOException

/**
 * The sandbox provider host as the device sees it (spending authority G10.3 X8): a provider that moves no
 * money and does exactly what a test asks. The hosted one is its own Worker and does not exist yet; unit
 * tests use an in-process copy. Amounts are atoms of the sandbox instrument's unit.
 *
 * Every answer carries the host's time, signed with the host's own Ed25519 key over [timeBytes].
 */
interface SandboxHost {
    /** `POST /keys`: a new sandbox key, linked like any own key. */
    fun createKey(): String

    /**
     * `POST /call`: the paid call. Throws [IOException] when the answer is lost, whether or not the host
     * billed it; that is the unknown outcome.
     */
    @Throws(IOException::class)
    fun call(key: String, call: Call): CallAnswer

    /** `GET /usage`: what this key was billed in the host's **current** period only, pending charges included. Earlier periods are not in it. */
    @Throws(IOException::class)
    fun usage(key: String): Usage

    /** `POST /limit`: a provider-side cap on the key for a period, or none. */
    fun limit(key: String, capAtoms: Long?)

    /** `POST /clock`: shifts this key's time ahead or behind. */
    fun clock(key: String, shiftMs: Long)

    /** What a test asks the host to do with one call. */
    data class Case(
        /** The host takes this long, by its own clock, before it answers. */
        val delayMs: Long = 0,
        /** The host bills, then the answer is lost. */
        val loseAnswer: Boolean = false,
        /** The host bills this much more than the call's cost. */
        val billPastQuoteAtoms: Long = 0,
        /** The answer says nothing was billed; the charge shows in the usage read afterwards. */
        val chargeLate: Boolean = false,
        /** False: the host ignores the spend id, so a retry is billed again. */
        val honourSpendId: Boolean = true,
    )

    data class Call(val spendId: String, val costAtoms: Long, val case: Case = Case())

    sealed class CallAnswer {
        abstract val time: SignedTime

        /** [replayed]: the host had this spend id already and answered from its record without billing again. */
        data class Billed(val costAtoms: Long, val ref: String, val replayed: Boolean, override val time: SignedTime) : CallAnswer()

        /** Not run and not billed, for example because the key's cap is reached. */
        data class Refused(val reason: String, override val time: SignedTime) : CallAnswer()
    }

    data class Charge(val spendId: String, val costAtoms: Long, val ref: String, val pending: Boolean, val atMs: Long)

    /**
     * [scope] is `source` (this key alone, with its [charges]) or `account` (one figure for the whole test
     * account, which frees nothing, G5.3). [period] runs from [periodStartMs] up to but not including
     * [periodEndMs], in the host's time. [capAtoms] is the key's cap, when it has one.
     */
    data class Usage(
        val scope: String, val period: String, val periodStartMs: Long, val periodEndMs: Long,
        val totalAtoms: Long, val charges: List<Charge>, val capAtoms: Long?, val time: SignedTime,
    )

    class SignedTime(val epochMs: Long, val signature: ByteArray)

    companion object {
        const val TIME_LABEL = "bridgit-sandbox-time-v1"

        /** The bytes a host signs for a time it gives to [key]. */
        fun timeBytes(key: String, epochMs: Long): ByteArray = "$TIME_LABEL\n$key\n$epochMs".toByteArray(Charsets.UTF_8)
    }
}
