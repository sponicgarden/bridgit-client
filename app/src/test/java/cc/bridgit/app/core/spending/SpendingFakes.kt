package cc.bridgit.app.core.spending

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [JournalKeys] with plain in-memory keys, for unit tests (Robolectric has no AndroidKeyStore). It lives in
 * the test tree so it can never be what a device falls back to. [forget] does what clearing the app's data
 * does to a Keystore key.
 */
class SoftwareJournalKeys(private val seed: String? = null) : JournalKeys {
    private val keys = HashMap<Namespace, Pair<String, ByteArray>>()
    private val random = SecureRandom()
    private var made = 0

    /** With a [seed] the epochs and keys are the same on every run, so a test can hold exact bytes. */
    private fun bytes(n: Int, what: String): ByteArray =
        if (seed == null) ByteArray(n).also(random::nextBytes) else java.security.MessageDigest.getInstance("SHA-256").digest("$seed:$what:$made".toByteArray()).copyOf(n)

    fun forget(namespace: Namespace) { keys.remove(namespace) }

    override fun epoch(namespace: Namespace) = keys[namespace]?.first

    override fun create(namespace: Namespace): String {
        made++
        val epoch = bytes(16, "epoch:${namespace.id}").joinToString("") { "%02x".format(it) }
        keys[namespace] = epoch to bytes(32, "key:${namespace.id}")
        return epoch
    }

    override fun mac(namespace: Namespace, epoch: String, data: ByteArray): ByteArray? {
        val (current, key) = keys[namespace] ?: return null
        if (current != epoch) return null
        return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    }
}

class FakeClock(var wall: Long = 1_794_000_000_000, var elapsed: Long = 5_000, var boots: Int = 7) : SpendClock {
    override fun wallMs() = wall
    override fun elapsedMs() = elapsed
    override fun bootCount() = boots
    fun advance(ms: Long) { wall += ms; elapsed += ms }
}

/** Stands in for a rule document that named this install an executor of [budgets]: the journal only records that one did. */
internal fun Journal.grant(vararg budgets: String) {
    transact { it.append(mapOf("kind" to "rules", "generation" to 1L, "digest" to "0".repeat(64), "budgets" to budgets.toList(), "made" to emptyMap<String, Any?>(), "unbar" to emptyList<Any?>())) }
}

/** Writes an accepted time anchor as [TimeAnchor] would, with none of its checks. */
internal fun Journal.anchor(t: Long, source: String = "provider_signed") { transact { it.append(mapOf("kind" to "anchor", "source" to source, "t" to t)) } }

/**
 * Opens a period for a journal-level test. When the journal asks for evidence it does not hold (a grant, or a
 * time inside the period), this supplies it the way the engine would have and tries once more.
 */
internal fun Journal.openP(rule: String, period: Journal.Period, basis: Journal.Basis, counted: Money): Journal.Entry =
    try {
        openPeriod(rule, period, basis, counted, if (basis == Journal.Basis.PROVIDER_READ || basis == Journal.Basis.CLOUD_SETTLEMENT) listOf(mapOf("source" to "test")) else emptyList())
    } catch (e: JournalRefused) {
        when (e.why) {
            "no_portion_received" -> grant(rule)
            "time_unanchored", "period_not_current", "full_period_not_elapsed" -> anchor(maxOf(period.startsMs, minOf(transact { it.clock.wallMs() }, period.endsMs - 1)))
            else -> throw e
        }
        openPeriod(rule, period, basis, counted)
    }
