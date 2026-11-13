package cc.bridgit.app.core.spending.sandbox

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * The in-process copy of the sandbox provider host (spending authority G10.3 X8): the same answers the hosted
 * one will give, with no network. It moves no money. [now] is the true time; each key has its own shift.
 *
 * The app uses it while the `sandbox` instrument row names no host (`vocab/instruments.json`, `host: null`):
 * the hosted Worker does not exist yet. With a [store] file, what it billed and its signing key outlive the
 * process, so a lost answer can still be reconciled after the app restarts; without one (unit tests of the
 * adapter) everything is in memory.
 */
class InProcessSandboxHost(private val periodMs: Long = 24L * 60 * 60 * 1000, private val store: File? = null, private val now: () -> Long) : SandboxHost {
    private class Account(var capAtoms: Long? = null, var shiftMs: Long = 0, val charges: MutableList<SandboxHost.Charge> = mutableListOf())

    private val accounts = LinkedHashMap<String, Account>()
    private var seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var refs = 0

    /** The `/usage` switch: answer with one figure for the whole test account instead of this key's own. */
    @Volatile var accountWideUsage = false

    /** Calls the host actually ran, replays not counted. */
    var billedCalls = 0
        private set

    init { load() }

    private val signingKey get() = Ed25519PrivateKeyParameters(seed, 0)

    /** The host's raw Ed25519 public key, as the instrument row will carry it. */
    val timeKey: ByteArray get() = signingKey.generatePublicKey().encoded

    /** Makes [key]'s pending charges final. */
    @Synchronized fun settlePending(key: String) { account(key).charges.replaceAll { it.copy(pending = false) }; save() }

    /** Forgets [key] and what it was billed: the key was unlinked and nothing can ask about it again. */
    @Synchronized fun removeKey(key: String) { accounts.remove(key); save() }

    @Synchronized override fun createKey(): String {
        var n = accounts.size + 1
        while ("sbx_$n" in accounts) n++
        return "sbx_$n".also { accounts[it] = Account(); save() }
    }

    @Synchronized override fun call(key: String, call: SandboxHost.Call): SandboxHost.CallAnswer {
        val a = account(key)
        require(call.costAtoms >= 0 && call.case.billPastQuoteAtoms >= 0 && call.case.delayMs >= 0)
        if (call.case.honourSpendId) {
            a.charges.firstOrNull { it.spendId == call.spendId }?.let { return SandboxHost.CallAnswer.Billed(it.costAtoms, it.ref, true, time(key)) }
        }
        val cost = Math.addExact(call.costAtoms, call.case.billPastQuoteAtoms)
        a.capAtoms?.let { if (Math.addExact(total(current(key)), cost) > it) return SandboxHost.CallAnswer.Refused("limit_reached", time(key)) }
        val charge = SandboxHost.Charge(call.spendId, cost, "sbx-ref-${++refs}", pending = call.case.chargeLate, atMs = at(key))
        a.charges += charge
        billedCalls++
        a.shiftMs += call.case.delayMs
        save()
        if (call.case.loseAnswer) throw IOException("answer lost")
        return SandboxHost.CallAnswer.Billed(if (call.case.chargeLate) 0 else cost, charge.ref, false, time(key))
    }

    @Synchronized override fun usage(key: String): SandboxHost.Usage {
        val a = account(key)
        val start = at(key) / periodMs * periodMs
        val period = "period:${start / periodMs}"
        return if (accountWideUsage) SandboxHost.Usage("account", period, start, start + periodMs, accounts.keys.sumOf { total(current(it)) }, emptyList(), a.capAtoms, time(key))
        else SandboxHost.Usage("source", period, start, start + periodMs, total(current(key)), current(key), a.capAtoms, time(key))
    }

    @Synchronized override fun limit(key: String, capAtoms: Long?) { account(key).capAtoms = capAtoms; save() }

    @Synchronized override fun clock(key: String, shiftMs: Long) { account(key).shiftMs = shiftMs; save() }

    /** The cap and the clock shift a test put on [key]: what `POST /limit` and `POST /clock` last set. */
    @Synchronized fun controls(key: String): Pair<Long?, Long> = account(key).let { it.capAtoms to it.shiftMs }

    private fun account(key: String) = accounts[key] ?: throw IllegalArgumentException("Unknown sandbox key")
    private fun total(charges: List<SandboxHost.Charge>) = charges.sumOf { it.costAtoms }
    private fun at(key: String) = now() + account(key).shiftMs

    /** The key's charges of the host's current period; earlier ones are no longer shown. */
    private fun current(key: String): List<SandboxHost.Charge> {
        val start = at(key) / periodMs * periodMs
        return account(key).charges.filter { it.atMs >= start && it.atMs < start + periodMs }
    }

    private fun time(key: String): SandboxHost.SignedTime {
        val at = at(key)
        val bytes = SandboxHost.timeBytes(key, at)
        val sig = Ed25519Signer().run { init(true, signingKey); update(bytes, 0, bytes.size); generateSignature() }
        return SandboxHost.SignedTime(at, sig)
    }

    // ---- the host's own store: a provider remembers what it billed ----

    private fun load() {
        val text = store?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() } ?: return
        runCatching {
            val o = JSONObject(text)
            val s = o.getString("seed")
            seed = ByteArray(32) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            refs = o.getInt("refs")
            val all = o.getJSONObject("accounts")
            for (key in all.keys()) {
                val a = all.getJSONObject(key)
                val list = a.getJSONArray("charges")
                accounts[key] = Account(
                    if (a.isNull("cap")) null else a.getLong("cap"), a.getLong("shift"),
                    (0 until list.length()).map { list.getJSONObject(it) }
                        .map { SandboxHost.Charge(it.getString("spend_id"), it.getLong("cost"), it.getString("ref"), it.getBoolean("pending"), it.getLong("at")) }.toMutableList(),
                )
            }
        }.onFailure { accounts.clear() }
    }

    private fun save() {
        val file = store ?: return
        val all = JSONObject()
        for ((key, a) in accounts) all.put(
            key,
            JSONObject().put("cap", a.capAtoms ?: JSONObject.NULL).put("shift", a.shiftMs).put(
                "charges",
                JSONArray(a.charges.map { JSONObject().put("spend_id", it.spendId).put("cost", it.costAtoms).put("ref", it.ref).put("pending", it.pending).put("at", it.atMs) }),
            ),
        )
        val body = JSONObject().put("seed", seed.joinToString("") { "%02x".format(it) }).put("refs", refs).put("accounts", all).toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) { file.writeText(body); tmp.delete() }
    }
}
