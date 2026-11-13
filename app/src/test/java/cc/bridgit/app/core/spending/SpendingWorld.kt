package cc.bridgit.app.core.spending

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.time.Instant

/**
 * Several installs of one account in one test process: each with its own journal file, journal key, install
 * key and clock, and the `own`-tier links between them. Nothing here is a device; signatures are Ed25519 in
 * software, standing in for the install keys.
 */
class SpendingWorld(private val app: Context, val namespace: Namespace = Namespace.SANDBOX, private val seeded: Boolean = false) {
    private val random = SecureRandom()

    /** Seeded, every key is the hash of its name: Ed25519 is deterministic, so the same run gives the same signatures. */
    private fun key(name: String) = if (seeded) Ed25519PrivateKeyParameters(java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()), 0) else Ed25519PrivateKeyParameters(random)
    private val installKeys = HashMap<String, Ed25519PrivateKeyParameters>()
    private val approverKeys = HashMap<String, Ed25519PrivateKeyParameters>()
    private val links = HashSet<Set<String>>()
    val devices = LinkedHashMap<String, Device>()
    var approverEnrolled = false
    val cloudKey = key("cloud")

    /** Wednesday 11 November 2026, 10:00 UTC. */
    val start: Long = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()

    fun device(name: String, linked: Boolean = true): Device {
        installKeys.getOrPut(name) { key("install:$name") }
        if (linked) for (other in devices.keys) links += setOf(name, other)
        return Device(name).also { devices[name] = it }
    }

    fun unlink(a: String, b: String) { links -= setOf(a, b) }
    fun link(a: String, b: String) { links += setOf(a, b) }

    /** Time passes everywhere, with no reboot. */
    fun advance(ms: Long) { devices.values.forEach { it.clock.advance(ms) } }

    /**
     * Closes every journal. Before that, each one is checked whole, and every entry any test wrote is held to
     * the field names of the contract schema, so no entry kind can leave the pack behind.
     */
    fun close() {
        val file = java.io.File("../contracts/client-core/schemas/spend-entry.schema.json").takeIf { it.exists() } ?: java.io.File("contracts/client-core/schemas/spend-entry.schema.json")
        val schema = org.json.JSONObject(file.readText())
        val defs = schema.getJSONObject("\$defs")
        val top = schema.getJSONArray("required").let { r -> (0 until r.length()).map(r::getString) }
        for (d in devices.values) {
            check(d.journal.verify() == null) { "${d.name}: ${d.journal.verify()}" }
            for (epoch in d.journal.epochs()) for (e in d.journal.entries(epoch)) {
                val body = runCatching { org.json.JSONObject(e.json) }.getOrNull() ?: continue
                val def = defs.optJSONObject("${body.optString("kind")}_entry") ?: error("${d.name}: no schema for entry kind ${body.optString("kind")}")
                val allowed = def.getJSONObject("properties").keys().asSequence().toSet()
                val required = def.getJSONArray("required").let { r -> (0 until r.length()).map(r::getString) } + top
                check(allowed.containsAll(body.keys().asSequence().toSet())) { "${d.name} ${body.optString("kind")} ${e.seq}: fields the schema does not have: ${body.keys().asSequence().toSet() - allowed}" }
                check(required.all(body::has)) { "${d.name} ${body.optString("kind")} ${e.seq}: required fields missing: ${required.filterNot(body::has)}" }
            }
            d.db.close()
        }
    }

    fun signAs(install: String, bytes: ByteArray): ByteArray = sign(installKeys.getValue(install), bytes)
    fun signAsApprover(id: String, bytes: ByteArray): ByteArray = sign(approverKeys.getOrPut(id) { key("approver:$id") }, bytes)
    fun signAsCloud(bytes: ByteArray): ByteArray = sign(cloudKey, bytes)

    private fun sign(key: Ed25519PrivateKeyParameters, bytes: ByteArray) = Ed25519Signer().run { init(true, key); update(bytes, 0, bytes.size); generateSignature() }

    private fun verify(key: Ed25519PrivateKeyParameters?, bytes: ByteArray, signature: ByteArray): Boolean =
        key != null && Ed25519Signer().run { init(false, key.generatePublicKey()); update(bytes, 0, bytes.size); verifySignature(signature) }

    inner class Device(val name: String, private val file: String = "spending-${namespace.id}-$name.db") {
        val clock = FakeClock(wall = start, elapsed = 50_000, boots = 3)
        val journalKeys = SoftwareJournalKeys(name.takeIf { seeded })
        val events = ArrayList<SpendEvent>()
        val env = Env()
        lateinit var db: SpendingDb
        lateinit var journal: Journal
        lateinit var core: SpendingCore

        val signer = object : SpendSigner {
            override val installId = name
            override fun sign(bytes: ByteArray) = signAs(name, bytes)
        }

        val book = object : SignerBook {
            override fun standing(kind: String, id: String) = when {
                kind == "approver" -> if (id in approverKeys) SignerBook.Standing.APPROVER else SignerBook.Standing.UNKNOWN
                id == name -> SignerBook.Standing.OWN
                setOf(name, id) in links -> SignerBook.Standing.OWN_LINK
                else -> SignerBook.Standing.UNKNOWN
            }
            override fun verify(kind: String, id: String, bytes: ByteArray, signature: ByteArray) = verify(if (kind == "approver") approverKeys[id] else installKeys[id], bytes, signature)
            override fun approverEnrolled() = approverEnrolled
        }

        init { app.deleteDatabase(file); open() }

        /** Opens the journal as the app does when it starts. */
        fun open() {
            db = SpendingDb(app, namespace, file)
            journal = Journal.open(db, journalKeys, name, clock)
            core = SpendingCore(journal, signer, book, env, cloudKey.generatePublicKey().encoded, events::add)
        }

        /** The app is stopped and started again; the device did not reboot. */
        fun restartApp() { db.close(); open() }

        /** The device reboots: the monotonic clock starts again. [wallJump] moves the wall clock as well. */
        fun reboot(wallJump: Long = 0) { clock.boots++; clock.elapsed = 1_000; clock.wall += wallJump; restartApp() }

        /** The journal file is replaced by an empty one, with the keys kept (G5.3). The rule documents go with the file. */
        fun loseJournalFile() { db.close(); app.deleteDatabase(file); open() }

        /** The head row is removed: the journal is not whole, but the rows and the rule documents are still there. */
        fun loseHead() {
            db.close()
            SQLiteDatabase.openDatabase(app.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM head") }
            open()
        }

        fun raw(f: (SQLiteDatabase) -> Unit) {
            db.close()
            SQLiteDatabase.openDatabase(app.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READWRITE).use(f)
            open()
        }

        private var requests = 0

        fun spend(atoms: Long, source: String = "key-1", request: String = "req-${++requests}", applet: String? = null, principal: String = "a1"): Journal.HoldResult =
            core.engine.spend(RuleEngine.Request(SpendId.of(namespace, name, request), source, Money("USD-6", atoms), "agent", principal, applet = applet))

        fun held(atoms: Long, source: String = "key-1", applet: String? = null): Boolean = spend(atoms, source, applet = applet) is Journal.HoldResult.Held
        fun refusal(atoms: Long, source: String = "key-1", applet: String? = null): SpendRefusal = (spend(atoms, source, applet = applet) as Journal.HoldResult.Refused).refusal

        /** A time anchor from the sandbox provider, which signs its time. */
        fun anchor(t: Long = clock.wall, timeSource: String = "provider_signed", source: String = "key-1", verified: Boolean = true): TimeAnchor.Result =
            core.engine.anchor(source, timeSource, InstrumentAdapter.ProviderTime(t, verified))

        fun standing(budget: String) = core.standing(budget)!!
        fun portion(budget: String) = standing(budget).portion
        fun headroom(budget: String) = standing(budget).headroom
        fun used(budget: String) = standing(budget).used
    }

    class Env : RuleEngine.Environment {
        var online = true
        var paused = false
        var paymentsOn = true
        var mayAct = true
        var grants = emptySet<String>()
        var routeAllowed = true
        var bridgitRoom: RuleEngine.Room? = null
        var callsPerHour: Int? = null
        val sources = hashMapOf(
            "key-1" to RuleEngine.Source("key-1", "sandbox", "metered", "owner", "device", "core_only"),
            "cloud-1" to RuleEngine.Source("cloud-1", "sandbox", "metered", "bridgit", "cloud", "provider"),
        )

        override fun online() = online
        override fun outsideCallsPaused() = paused
        override fun paymentsOn() = paymentsOn
        override fun source(id: String) = sources[id]
        override fun mayAct(principalKind: String, principalId: String) = mayAct
        override fun grants(principalKind: String, principalId: String) = grants
        override fun routeAllowed(source: RuleEngine.Source, route: String?) = routeAllowed
        override fun bridgitRoom(source: RuleEngine.Source) = bridgitRoom
        override fun callsPerHour(source: RuleEngine.Source) = callsPerHour
    }

    // ---- shorthand for tests ----

    val owner = Caller("owner", "owner", "app")
    val agent = Caller("agent", "a1", "http")

    /** [by] makes the account's first document with [budgets], and every other device takes it. Returns its digest. */
    fun found(by: Device, budgets: Map<String, Map<String, Any?>>, caller: Caller = owner, edit: (Map<String, Any?>) -> Map<String, Any?> = { it }): String {
        val made = by.core.change(caller, true, null, "acct-1") { edit(it + ("budgets" to budgets)) } as RuleDocuments.Adopt.Adopted
        share(made.document, except = by)
        return made.current.digest
    }

    /** Carries a signed document to every other device. */
    fun share(document: RuleDocument, except: Device? = null): List<RuleDocuments.Adopt> = devices.values.filter { it != except }.map { it.core.adopt(document.json) }

    /** [by] changes the current document's budgets. */
    fun change(by: Device, caller: Caller = owner, capability: Boolean = true, edit: (Map<String, Any?>) -> Map<String, Any?>): RuleDocuments.Adopt =
        by.core.change(caller, capability, by.core.document()?.digest, "acct-1", edit)

    fun usd(dollars: Long) = dollars * 1_000_000
    fun money(dollars: Long) = Money("USD-6", usd(dollars))

    companion object {
        const val DAY = 24L * 60 * 60 * 1000

        /** A copy of [content] with one budget's fields replaced by [f]. */
        @Suppress("UNCHECKED_CAST")
        fun editBudget(content: Map<String, Any?>, id: String, f: (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> {
            val budgets = content["budgets"] as Map<String, Any?>
            return content + ("budgets" to (budgets + (id to f(budgets.getValue(id) as Map<String, Any?>))))
        }

        @Suppress("UNCHECKED_CAST")
        fun withGate(budget: Map<String, Any?>, agentsMayChange: Boolean? = null, ceiling: Long? = null, noCeiling: Boolean = false): Map<String, Any?> {
            val gate = HashMap(budget["gate"] as Map<String, Any?>)
            agentsMayChange?.let { gate["agents_may_change"] = it }
            if (ceiling != null) gate["ceiling"] = ceiling
            if (noCeiling) gate["ceiling"] = null
            return budget + ("gate" to gate)
        }
    }
}
