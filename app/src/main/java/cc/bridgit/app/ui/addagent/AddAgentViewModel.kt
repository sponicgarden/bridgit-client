package cc.bridgit.app.ui.addagent

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.Pairing
import cc.bridgit.app.R
import cc.bridgit.app.api.Address
import cc.bridgit.app.api.SetupPrompt
import cc.bridgit.app.capability.address
import cc.bridgit.app.core.Connection
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.LogQuery
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Add agent state machine (6.2, AGT-03 … AGT-08, AGT-05a). The key lives only in
 * this ViewModel's memory and in [BridgeApp.vault]; never in a Bundle or saved state.
 */
class AddAgentViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<BridgeApp>()

    enum class Check { PENDING, OK, FAIL, WARN }
    data class Checks(val listener: Check = Check.PENDING, val tailscale: Check = Check.PENDING,
                      val port: Check = Check.PENDING, val ip: String? = null, val portNumber: Int = 0) {
        val canContinue get() = listener == Check.OK && tailscale == Check.OK && (port == Check.OK || port == Check.WARN)
        val allPassed get() = listener == Check.OK && tailscale == Check.OK && port == Check.OK
        val running get() = listOf(listener, tailscale, port).any { it == Check.PENDING }
    }

    enum class WaitKind { WAITING, CONNECTED, NO_CALL }
    data class Wait(val kind: WaitKind = WaitKind.WAITING, val entry: LogEntry? = null)

    /** 1 Name, 2 Connection, 3 Check, 4 Setup prompt (and waiting, step 5). */
    val step = MutableStateFlow(1)
    var promptExpanded = false
    var powersOpen = false
    var connectionAdvanced = false
    var name = ""
    var connection = Connection.DIRECT
    /** Relay setup for a hosted chat app: a connector-mode agent added there as a custom connector. Null: not one. */
    var connector: cc.bridgit.app.relay.ConnectorClient? = null
    var nameError: String? = null

    var agentId: String? = null; private set
    /** The plaintext key. Memory only. */
    var key: String? = null; private set
    var prompt: String? = null; private set
    /** Copied, shared or otherwise acknowledged (AGT-05): leaving needs no dialog and the vault is wiped. */
    var acknowledged = false; private set

    /** The trust warning was accepted for the current key; a rotated key asks again. */
    var trustWarned = false
    /** Opened for an existing agent whose key is no longer held (AGT-05a after expiry). */
    val keyGone = MutableStateFlow(false)
    /** Minutes left on a resumed key; read once by the fragment for its Snackbar. */
    var resumedMinutes: Int? = null
    var pairWindow: Pairing.Window? = null

    private var initialised = false
    var step0Done = false

    private val _checks = MutableStateFlow(Checks())
    val checks: StateFlow<Checks> get() = _checks
    private var checkJob: Job? = null

    private val _wait = MutableStateFlow(Wait())
    val wait: StateFlow<Wait> get() = _wait
    private var waitJob: Job? = null

    /** Called once with the fragment's arguments. */
    fun init(argAgentId: String?, fromRotate: Boolean) {
        if (initialised) return
        initialised = true
        // Restored after the process was killed mid-add: the arguments carry no agentId, but the vault does (AGT-05a).
        val id = argAgentId ?: app.vault.get()?.agentId?.takeIf { app.agents.get(it) != null } ?: return
        val agent = app.agents.get(id) ?: return
        agentId = agent.id
        name = agent.name
        connection = agent.connection
        val pending = app.vault.get()
        if (pending != null && pending.agentId == agent.id) {
            key = pending.key
            buildPrompt()
            if (!fromRotate) resumedMinutes =
                ((pending.expiresAt - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(1)
        } else {
            keyGone.value = true
        }
        step.value = 4
        startWaiting()
    }

    val agentExists get() = agentId?.let { app.agents.get(it) } != null

    // ---- Step 1

    fun validateName(): Boolean {
        val n = name.trim()
        nameError = app.agents.checkName(n)
        return nameError == null
    }

    // ---- Step 3

    fun runChecks() {
        checkJob?.cancel()
        _checks.value = Checks(portNumber = app.listener.value.port.takeIf { it > 0 } ?: app.settings.port)
        checkJob = viewModelScope.launch {
            // Give the listener up to ~5 s to start and bind after step 0.
            var waited = 0
            while (!app.listener.value.listening && waited < 5_000) { delay(250); waited += 250 }
            val s = app.listener.value
            val ip = s.boundTo.firstOrNull { !it.contains(':') } ?: s.boundTo.firstOrNull()
            val port = if (s.port > 0) s.port else app.settings.port
            val listener = if (s.running) Check.OK else Check.FAIL
            val tailscale = if (s.boundTo.isNotEmpty()) Check.OK else Check.FAIL
            _checks.value = Checks(listener, tailscale, Check.PENDING, ip, port)
            // Self-connection over the tailnet address (6.2 step 3). A raw socket: no plain-HTTP call (ARC-12a).
            val reach = if (ip == null || !s.running) Check.FAIL else withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(ip, port), 2_000) }
                    Check.OK
                }.getOrDefault(Check.WARN)
            }
            _checks.value = _checks.value.copy(port = reach)
        }
    }

    // ---- Step 4

    /** Creates the agent and its key once (a config change must never create a second one). */
    fun createAgent(): Boolean {
        if (agentId != null) return true
        val (agent, k) = try {
            app.agents.add(name.trim(), connection)
        } catch (e: IllegalArgumentException) {
            nameError = e.message; return false
        }
        cc.bridgit.app.power.PowerAccess(app).apply { allSwitchesOn(); grantAll(agent.id, superPowers = false) }
        app.vault.put(agent.id, k, rotated = false)
        app.log.system("audit", "agent", app.getString(R.string.addagent_added_log, agent.name))
        agentId = agent.id
        name = agent.name
        key = k
        buildPrompt()
        startWaiting()
        return true
    }

    /** Rotate from the "key can't be shown again" state (SEC-10). */
    fun rotate() {
        val id = agentId ?: return
        val (agent, k) = app.agents.rotate(id)
        app.vault.put(id, k, rotated = true)
        app.log.system("audit", "agent", app.getString(cc.bridgit.app.R.string.agents_rotated_log, agent.name))
        key = k
        acknowledged = false
        trustWarned = false
        buildPrompt()
        keyGone.value = false
        _wait.value = Wait()
        startWaiting()
    }

    private fun buildPrompt(address: Address = app.address()) {
        val k = key ?: return
        val helper = if (connection == Connection.USERSPACE)
            runCatching { app.assets.open("bridgit-helper.py").bufferedReader().use { it.readText() } }.getOrNull()
        else null
        // A callback set for this agent: the prompt carries its existing secret (EVT-06), never a new one.
        val callback = agentId?.let { id -> app.callbacks.get(id)?.let { app.callbacks.secret(id) } }
        val agent = agentId?.let { app.agents.get(it) }
        // Direct and relay agents get the short prompt and fetch the full briefing (GET /v1/welcome); userspace needs the helper inline.
        prompt = if (connection == Connection.USERSPACE)
            SetupPrompt.build(name, k, connection, address, deviceModel(), app.registry.caps(), helper, callback, identity(app)) { agent?.allows(it) == true }
        else SetupPrompt.short(name, k, address, identity(app))
    }

    /** Rebuilds the prompt (for example once the Tailscale address is known). */
    fun refreshPrompt() { if (key != null) buildPrompt() }

    data class Export(val prompt: String, val qr: String)

    /** Called after the trust dialog, immediately before Copy, Share or QR. */
    suspend fun prepareExport(): Export? {
        val k = key ?: return null
        val address = PairingEndpoint.verified(snapshot = {
            val s = app.listener.value
            PairingEndpoint.Snapshot(app.settings.listenerEnabled, s.listening, app.address(), app.settings.port)
        }, reachable = { addr ->
            withContext(Dispatchers.IO) {
                runCatching { Socket().use { it.connect(InetSocketAddress(addr.ip, addr.port), 2_000) }; true }
                    .getOrDefault(false)
            }
        }) ?: return null
        // No cached domain name (never named, or settings cleared): ask Bridgit once so the prompt names a named device.
        if (cc.bridgit.app.feedback.DeviceNames.cached(app) == null)
            withContext(Dispatchers.IO) { runCatching { cc.bridgit.app.feedback.DeviceNames.refresh(app) } }
        buildPrompt(address)
        return Export(prompt ?: return null, SetupPrompt.qrPayload(name, k, address))
    }

    /** Copy, Copy key only, Share, Done and Leave all acknowledge the key: the encrypted copy goes (AGT-05a). */
    fun acknowledge() {
        acknowledged = true
        app.vault.wipe()
    }

    // ---- Step 5

    private fun startWaiting() {
        if (waitJob != null) waitJob?.cancel()
        val id = agentId ?: return
        waitJob = viewModelScope.launch {
            var emptyTicks = 0
            while (isActive) {
                val agent = app.agents.get(id) ?: return@launch
                if (agent.lastUsedAt > 0) {
                    // The audit row is written on the log's executor, so it can lag lastUsedAt by a moment.
                    val first = withContext(Dispatchers.IO) {
                        runCatching {
                            app.log.query(LogQuery(agentIds = listOf(id), channels = listOf("audit"), kinds = listOf("request"),
                                sinceTs = agent.keyCreatedAt, ascending = true, limit = 1)).firstOrNull()
                        }.getOrNull()
                    }
                    if (first != null || ++emptyTicks >= 5) {
                        _wait.value = Wait(WaitKind.CONNECTED, first); return@launch
                    }
                } else if (System.currentTimeMillis() - agent.keyCreatedAt > NO_CALL_MS) {
                    if (_wait.value.kind != WaitKind.NO_CALL) _wait.value = Wait(WaitKind.NO_CALL)
                }
                delay(1_000)
            }
        }
    }

    companion object {
        const val NO_CALL_MS = 10 * 60 * 1000L

        /**
         * The prompt's IDENTITY block (A0213): the Device Domain Name the phone has cached from Bridgit, the friendly
         * name (the name record's label, else the phone's own name), the model and the owner email as health shows it.
         */
        fun identity(app: BridgeApp): cc.bridgit.app.api.DeviceIdentity {
            val own = cc.bridgit.app.feedback.DeviceNames.cached(app)
            val domain = own?.optString("name")?.takeIf { it.isNotBlank() }
            val label = own?.optString("label")?.takeIf { own.opt("label") is String && it.isNotBlank() }
            val email = cc.bridgit.app.alerts.EmailGate.mask(app.settings.alertEmail)
            val verified = runCatching { app.alerts.gate().status == cc.bridgit.app.alerts.EmailGate.Status.VERIFIED }.getOrDefault(false)
            return cc.bridgit.app.api.DeviceIdentity(domain, label ?: cc.bridgit.app.platform.DeviceName.of(app), deviceModel(),
                email?.let { "$it (${if (verified) "verified" else "not verified yet"})" })
        }

        /** "Google Pixel 10 Pro XL"; just the model if it already names the maker. */
        fun deviceModel(): String {
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        }
    }
}
