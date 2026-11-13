package cc.bridgit.app.core.spending

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.authority.InstallSigner
import cc.bridgit.app.core.authority.OwnerAuth
import cc.bridgit.app.core.contract.ContractVocab
import cc.bridgit.app.core.spending.sandbox.InProcessSandboxHost
import cc.bridgit.app.core.spending.sandbox.SandboxAdapter
import cc.bridgit.app.feedback.InstallAuth
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.link.LinkTier
import org.json.JSONObject
import java.io.File

/**
 * The spending core in the app (spending authority G10.2 SA2.5 device part, SA3.1): the journal, the rule
 * engine and the instrument adapters, wired to this device's keys, switches and agents.
 *
 * Everything is behind the owner setting `spending.core_enabled`, off by default. While it is off nothing
 * here runs: no journal file is opened and no key is made. **This build has the sandbox namespace only.**
 * The live namespace answers "not available yet", makes no file and no key, and today's budgets and paid
 * routes do not pass through here.
 *
 * - The journal key is in the Android Keystore ([AndroidJournalKeys]); the epoch is that key's alias.
 * - Rule documents and increments are signed with the install key (P-256), which the install id is the
 *   hash of. An unregistered install has no id, so the core does not open.
 * - The sandbox provider is the in-process host while the `sandbox` instrument row names no host
 *   (`vocab/instruments.json`): the hosted one is a later backend step.
 */
class Spending(private val app: BridgeApp) {
    val enabled: Boolean get() = app.settings.spendingCoreEnabled

    // Seams: unit tests have no key store, their own install key, clock and network. Set before first use.
    var journalKeys: JournalKeys? = null
    var install: InstallSigner = DeviceInstall()
    var clock: SpendClock = DeviceClock()
    var online: () -> Boolean = ::reachesANetwork

    /** Back to what the device itself says about the network, after a test told the core otherwise. */
    fun useDeviceNetwork() { online = ::reachesANetwork }

    /** Test seam: another journal file, so tests never share one. */
    internal var dbFile: String? = null

    /**
     * Own-key calls per hour (G6 rule 7). The contract has no value for it yet, so there is no limit; only a
     * debug build's test hook and unit tests set one. Never a setting.
     */
    @Volatile var callsPerHourForTests: Int? = null

    @Volatile private var opened: Space? = null
    val isOpen: Boolean get() = opened != null

    fun requireEnabled() {
        if (!enabled) throw ErrorCatalog.error("capability_disabled", "The spending core is off on this device", mapOf("setting" to SETTING))
    }

    /** Turns the core on or off. Off closes the journal; its file, its key and the linked sources are kept. */
    fun setEnabled(on: Boolean, by: String) {
        if (enabled == on) return
        app.settings.spendingCoreEnabled = on
        app.log.system("audit", "spending", "$by turned the spending core ${if (on) "on" else "off"}")
        if (!on) close()
    }

    /**
     * The namespace a call names. Reads and making writes carry `namespace`, `live` by default (G10.3 X10),
     * and only `sandbox` exists in this build: `live` is refused before anything is opened.
     */
    fun space(namespace: String?): Space {
        requireEnabled()
        return when (namespace ?: ContractVocab.SPEND_DEFAULT_NAMESPACE) {
            Namespace.SANDBOX.id -> sandbox()
            Namespace.LIVE.id -> throw notBuilt("The live spending namespace is not available on this build yet; today's budgets are unchanged. Send namespace=sandbox", Namespace.LIVE.id)
            else -> throw ErrorCatalog.error("invalid_parameter", "namespace must be live or sandbox")
        }
    }

    @Synchronized fun sandbox(): Space {
        requireEnabled()
        opened?.let { return it }
        val id = install.id() ?: run { runCatching { install.ensure() }; install.id() }
            ?: throw ErrorCatalog.error("team_unavailable", "This install is not registered yet, so it has no install key to sign with; retry when the Bridgit backend can be reached")
        return Space(id).also {
            opened = it
            it.journal.reset?.let { r -> event(SpendEvent("spend.journal_reset", mapOf("reason" to r.reason, "previous_epoch" to r.previousEpoch.orEmpty()))) }
        }
    }

    @Synchronized fun close() { opened?.db?.close(); opened = null }

    /** Runs [f]; when the journal turns out not to be whole, closes it so the next call opens a new epoch (G5.3 rule 3). */
    fun <T> guarded(f: () -> T): T = try { f() } catch (e: JournalNotWhole) {
        close()
        throw ErrorCatalog.error("conflict", "The spend journal did not verify (${e.why}), so a new epoch begins and every current period counts as fully used; read again before retrying", mapOf("reason" to "journal_unverified"))
    }

    private fun event(e: SpendEvent) {
        val warn = e.name in setOf(SpendEvent.SECURITY, SpendEvent.OVERSHOOT, SpendEvent.TRANSFER_CONFLICT, "spend.journal_reset")
        // Sandbox events are marked and go to the log only: no owner notification and no email (G10.3 X10).
        app.log.system("audit", "spending", "${e.name} (sandbox)", level = if (warn) "warn" else "info", data = JSONObject(e.detail as Map<*, *>).put("namespace", Namespace.SANDBOX.id))
    }

    /** One linked source. [key] is its secret, write-only: it is never returned by a read. */
    class Source(val id: String, val instrument: String, val key: String, val linkedMs: Long)

    /** Everything of one namespace on this device. Only the sandbox has one in this build. */
    inner class Space internal constructor(val installId: String) {
        val namespace = Namespace.SANDBOX
        internal val db = dbFile?.let { SpendingDb(app, namespace, it) } ?: SpendingDb(app, namespace)
        val journal: Journal = Journal.open(db, journalKeys ?: AndroidJournalKeys().also { journalKeys = it }, installId, clock)
        private val prefs = app.getSharedPreferences("spending_${namespace.id}", Context.MODE_PRIVATE)
        val host = InProcessSandboxHost(store = File(app.filesDir, "spending-sandbox-host.json")) { clock.wallMs() }
        private val row = ContractVocab.INSTRUMENTS.first { it.id == SandboxAdapter.ID }

        private val signer = object : SpendSigner {
            override val installId = this@Space.installId
            override fun sign(bytes: ByteArray): ByteArray = install.sign(bytes) ?: throw IllegalStateException("The install key is not available")
        }

        val core = SpendingCore(journal, signer, Book(), Env(), ::event)
        val runner = SpendRunner(core, { source(it)?.let(::engineSource) }, ::adapter, ::event)

        /** The account a first rule document names. One device stands for its own account until it is linked to one (the cloud step). */
        val account: String get() = installId

        /** Where the sandbox provider is: the host the instrument row names, or the in-process copy while it names none. */
        val hostName: String get() = row.host ?: "in_process"

        // ---- sources ----

        @Synchronized fun sources(): List<Source> = prefs.all.entries.filter { it.key.startsWith(SOURCE) }.mapNotNull { (_, v) ->
            runCatching { JSONObject(v as String).let { Source(it.getString("id"), it.getString("instrument"), it.getString("key"), it.getLong("linked")) } }.getOrNull()
        }.sortedBy { it.linkedMs }

        fun source(id: String): Source? = sources().firstOrNull { it.id == id }

        /** Links a sandbox source: asks the host for a key and stores it as an own key. Null when [id] is taken. */
        @Synchronized fun link(id: String): Source? {
            if (row.host != null) throw notBuilt("The sandbox provider is hosted at ${row.host}, and this build has no client for it yet", namespace.id)
            if (!free(id)) return null
            val s = Source(id, SandboxAdapter.ID, host.createKey(), clock.wallMs())
            prefs.edit().putString(SOURCE + id, JSONObject().put("id", s.id).put("instrument", s.instrument).put("key", s.key).put("linked", s.linkedMs).toString()).commit()
            return s
        }

        /**
         * Whether [id] was ever a source here. **A source id is one credential, for good**: the journal names a
         * spend's source by this id alone, so a new credential under an old id would be asked about its
         * predecessor's spends, and its empty record would "prove" an unknown hold unspent. An id that was
         * unlinked stays retired (a tombstone), and so does one the journal names in any epoch.
         */
        @Synchronized fun retired(id: String): Boolean = source(id) == null &&
            (prefs.contains(RETIRED + id) || journal.epochs().any { epoch -> journal.entries(epoch).any { it.kind == "spend" && it.source == id } })

        @Synchronized fun free(id: String): Boolean = source(id) == null && !retired(id)

        /** Removes the source and its key, and retires its id. What it spent stays in the journal; an unknown hold of it can no longer be reconciled and keeps its hold. */
        @Synchronized fun unlink(id: String): Boolean {
            val s = source(id) ?: return false
            prefs.edit().putLong(RETIRED + id, clock.wallMs()).remove(SOURCE + id).commit()
            runCatching { host.removeKey(s.key) }
            return true
        }

        /** `provider` when the provider enforces a limit on the key, `core_only` otherwise (G2.2 `describe`). */
        fun cap(s: Source): String = if (runCatching { host.controls(s.key).first }.getOrNull() != null) "provider" else "core_only"

        fun engineSource(s: Source) = RuleEngine.Source(s.id, s.instrument, row.instrumentClass, SandboxAdapter.FUNDER, SandboxAdapter.CUSTODY, cap(s), row.valueTransfer)

        /**
         * The adapter of a source, or null where this build has none. Once the instrument row names the hosted
         * sandbox provider, the in-process copy is no longer the provider, and there is no client for the
         * hosted one yet: closed, not quietly answered in-process.
         */
        fun adapter(s: RuleEngine.Source): InstrumentAdapter? =
            if (s.instrument != SandboxAdapter.ID || row.host != null) null else SandboxAdapter(host, { source(it)?.key }, host.timeKey, s.cap, clock::wallMs, QUOTE_LIFE_MS)

        fun approverEnrolled(): Boolean = Book().approverEnrolled()

        private inner class Env : RuleEngine.Environment {
            override fun online() = this@Spending.online()
            override fun outsideCallsPaused() = app.settings.externalCallsPaused

            /** Payments are not built (Part B): off. */
            override fun paymentsOn() = false
            override fun source(id: String) = this@Space.source(id)?.let(::engineSource)

            /** Whoever the device already lets act: an agent that is not paused or switched off, an installed applet, the owner. */
            override fun mayAct(principalKind: String, principalId: String) = when (principalKind) {
                "owner" -> true
                "agent" -> app.agents.get(principalId)?.let { !it.suspended } ?: false
                "applet" -> app.applets.get(principalId) != null
                else -> false
            }

            /** `payments.request` is given only by an owner authorization, which is not built: nobody holds it. */
            override fun grants(principalKind: String, principalId: String) = emptySet<String>()

            /** The sandbox instrument has no routes: a spend that names one is not on its list. */
            override fun routeAllowed(source: RuleEngine.Source, route: String?) = route == null

            /** No Bridgit-funded source exists on the device-held path, so nothing is mirrored. */
            override fun bridgitRoom(source: RuleEngine.Source): RuleEngine.Room? = null
            override fun callsPerHour(source: RuleEngine.Source) = callsPerHourForTests
        }
    }

    /**
     * Whose signature this device takes (G6.1). Its own install key always. An approver key, and another
     * install's key, only from the approver list, and only while approver devices are on: the authority store
     * is never opened while that setting is off. A link record holds the other device's install id but not
     * its key, so a linked device that is not an approver is not accepted yet: closed.
     */
    private inner class Book : SignerBook {
        private fun approvers() = if (app.authority.enabled) runCatching { app.authority.store.approvers() }.getOrDefault(emptyList()) else emptyList()

        private fun keyOf(installId: String): ByteArray? =
            if (installId == install.id()) install.spki() else approvers().firstOrNull { InstallAuth.installId(it.installKey) == installId }?.installKey

        private fun ownLink(installId: String) = app.links.all().any { it.peerInstall == installId && it.effectiveTier == LinkTier.OWN && !it.provisional && !it.inRevoked && !it.outRevoked }

        override fun standing(kind: String, id: String): SignerBook.Standing = when {
            kind == "approver" -> if (approvers().any { it.id == id }) SignerBook.Standing.APPROVER else SignerBook.Standing.UNKNOWN
            kind != "install" -> SignerBook.Standing.UNKNOWN
            id == install.id() -> SignerBook.Standing.OWN
            ownLink(id) && keyOf(id) != null -> SignerBook.Standing.OWN_LINK
            else -> SignerBook.Standing.UNKNOWN
        }

        override fun verify(kind: String, id: String, bytes: ByteArray, signature: ByteArray): Boolean {
            val key = (if (kind == "approver") approvers().firstOrNull { it.id == id }?.approverKey else if (kind == "install") keyOf(id) else null) ?: return false
            return OwnerAuth.verifies(key, bytes, signature)
        }

        override fun approverEnrolled(): Boolean = approvers().isNotEmpty()
    }

    private inner class DeviceInstall : InstallSigner {
        override fun id(): String? = InstallIdentity.status(app)?.installId
        override fun spki(): ByteArray? = InstallIdentity.publicKeySpki(app)
        override fun sign(message: ByteArray): ByteArray? = InstallIdentity.sign(app, message)
        override fun ensure() { InstallIdentity.ensure(app, cc.bridgit.app.feedback.TeamKeys.base) }
    }

    private inner class DeviceClock : SpendClock {
        override fun wallMs() = System.currentTimeMillis()
        override fun elapsedMs() = SystemClock.elapsedRealtime()

        /** Android's own count of boots. Where a device does not keep one, a reboot still shows as the monotonic clock going back. */
        override fun bootCount() = runCatching { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(0)
    }

    /** False only when the device knows it reaches nothing (G7.2): no network at all, or none that offers the internet. */
    private fun reachesANetwork(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching {
            // A VPN is not a network of its own: a tunnel that is still up in flight mode reaches nothing.
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) } == true
            }
        }.getOrDefault(true)
    }

    companion object {
        const val SETTING = "spending.core_enabled"
        private const val SOURCE = "source:"
        private const val RETIRED = "retired:"
        private const val QUOTE_LIFE_MS = 60_000L

        fun notBuilt(message: String, namespace: String) =
            ErrorCatalog.error("platform_unsupported", message, mapOf("namespace" to namespace, "reason" to "not_built", "available_namespaces" to org.json.JSONArray(listOf(Namespace.SANDBOX.id))))
    }
}
