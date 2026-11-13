package cc.bridgit.app.core.spending.sandbox

import cc.bridgit.app.core.contract.ContractVocab
import cc.bridgit.app.core.spending.InstrumentAdapter
import cc.bridgit.app.core.spending.InstrumentAdapter.Charge
import cc.bridgit.app.core.spending.InstrumentAdapter.Coverage
import cc.bridgit.app.core.spending.InstrumentAdapter.Description
import cc.bridgit.app.core.spending.InstrumentAdapter.Execution
import cc.bridgit.app.core.spending.InstrumentAdapter.ProviderTime
import cc.bridgit.app.core.spending.InstrumentAdapter.Quote
import cc.bridgit.app.core.spending.InstrumentAdapter.Reconciliation
import cc.bridgit.app.core.spending.InstrumentAdapter.SpendRequest
import cc.bridgit.app.core.spending.InstrumentAdapter.UsageFacts
import cc.bridgit.app.core.spending.Money
import cc.bridgit.app.core.spending.Namespace
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException

/**
 * The device-held sandbox instrument (spending authority G10.1 row 1, G10.3 X8): metered, the owner's own
 * key, synthetic money in the sandbox namespace. It is an ordinary adapter; only its provider is a test
 * double. A request's `detail` may carry what the host should do with the call (the names in [Detail]).
 *
 * [keyOf] gives the sandbox key linked as a source, or null. [hostTimeKey] is the host's raw Ed25519 public
 * key; a time whose signature does not verify under it is passed on as unverified. [cap] is `provider` when
 * the test put a limit on the key and `core_only` otherwise.
 */
class SandboxAdapter(
    private val host: SandboxHost,
    private val keyOf: (source: String) -> String?,
    private val hostTimeKey: ByteArray,
    private val cap: String,
    private val nowMs: () -> Long,
    private val quoteLifeMs: Long,
) : InstrumentAdapter {
    private val row = ContractVocab.INSTRUMENTS.first { it.id == ID }

    init {
        require(cap in row.caps) { "Unknown cap" }
        require(quoteLifeMs > 0) { "A quote needs a lifetime" }
    }

    /** Request details the sandbox host understands. */
    object Detail {
        const val DELAY_MS = "sandbox.delay_ms"
        const val LOSE_ANSWER = "sandbox.lose_answer"
        const val BILL_PAST_QUOTE_ATOMS = "sandbox.bill_past_quote_atoms"
        const val CHARGE_LATE = "sandbox.charge_late"
        const val IGNORE_SPEND_ID = "sandbox.ignore_spend_id"
    }

    override fun describe() = Description(
        instrument = ID, instrumentClass = row.instrumentClass, funder = FUNDER, custody = CUSTODY, namespace = Namespace.of(ID), unit = row.unit,
        cap = cap, honoursSpendId = true, holdReleasable = false, refunds = false, timeSource = row.timeSource,
        usageRead = UsageFacts(row.usageRead.scope, row.usageRead.perPeriod, row.usageRead.pendingIncluded),
    )

    override fun available(source: String): Money? {
        val key = keyOf(source) ?: return null
        val usage = try { host.usage(key) } catch (e: IOException) { return null }
        val capAtoms = usage.capAtoms ?: return null
        return Money(row.unit, capAtoms) - Money(row.unit, usage.totalAtoms)
    }

    /** The sandbox has no price list of its own: the most a call can cost is what the request says it costs. */
    override fun quote(request: SpendRequest): Quote {
        require(request.maxCost.unit == row.unit) { "The sandbox is priced in ${row.unit}" }
        return Quote(request.maxCost.requireAmount(), Math.addExact(nowMs(), quoteLifeMs))
    }

    override fun execute(spendId: String, request: SpendRequest, quote: Quote): Execution {
        val key = keyOf(request.source) ?: return Execution.NotBilled("source_not_linked", null)
        if (nowMs() >= quote.expiresAtMs) return Execution.NotBilled("quote_expired", null)
        val d = request.detail
        val case = SandboxHost.Case(
            delayMs = d[Detail.DELAY_MS]?.toLong() ?: 0,
            loseAnswer = d[Detail.LOSE_ANSWER] == "true",
            billPastQuoteAtoms = d[Detail.BILL_PAST_QUOTE_ATOMS]?.toLong() ?: 0,
            chargeLate = d[Detail.CHARGE_LATE] == "true",
            honourSpendId = d[Detail.IGNORE_SPEND_ID] != "true",
        )
        val answer = try {
            host.call(key, SandboxHost.Call(spendId, quote.max.atoms, case))
        } catch (e: IOException) {
            return Execution.Unknown("no_answer")
        }
        return when (answer) {
            is SandboxHost.CallAnswer.Billed -> Execution.Done(Money(row.unit, answer.costAtoms).requireAmount(), answer.ref, time(key, answer.time))
            is SandboxHost.CallAnswer.Refused -> Execution.NotBilled(answer.reason, time(key, answer.time))
        }
    }

    /** The provider's reported cost, else the hold. Never cut down to the hold: a bill past the quote is an overshoot (G5.4). */
    override fun settle(quote: Quote, done: Execution.Done): Money = done.cost ?: quote.max

    override fun giveBack(quote: Quote, execution: Execution): Money? = when (execution) {
        is Execution.NotBilled -> quote.max
        is Execution.Done -> (quote.max - settle(quote, execution)).takeIf { !it.isNegative && !it.isZero }
        is Execution.Unknown -> null
    }

    override fun reconcile(source: String, spendIds: Collection<String>): Reconciliation {
        val key = keyOf(source) ?: return Reconciliation(source, emptyList(), null, null)
        val usage = try { host.usage(key) } catch (e: IOException) { return Reconciliation(source, emptyList(), null, null) }
        val asked = spendIds.toSet()
        val charges = usage.charges.filter { it.spendId in asked }.map { Charge(it.spendId, Money(row.unit, it.costAtoms).requireAmount(), it.ref, it.pending) }
        // The read is of the host's current period only, and the host can be switched to answer for a whole account:
        // what it covers goes back with it, so an empty answer for another period or scope proves nothing (G5.3 step 4).
        val coverage = Coverage(
            source, usage.scope, usage.period, usage.periodStartMs, usage.periodEndMs,
            pendingIncluded = row.usageRead.pendingIncluded, complete = usage.scope == "source" && row.usageRead.perPeriod,
        )
        return Reconciliation(source, charges, coverage, time(key, usage.time))
    }

    private fun time(key: String, t: SandboxHost.SignedTime): ProviderTime {
        val verified = runCatching {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(hostTimeKey, 0))
                val bytes = SandboxHost.timeBytes(key, t.epochMs)
                update(bytes, 0, bytes.size)
                verifySignature(t.signature)
            }
        }.getOrDefault(false)
        return ProviderTime(t.epochMs, verified)
    }

    companion object {
        const val ID = "sandbox"
        const val FUNDER = "owner"
        const val CUSTODY = "device"
    }
}
