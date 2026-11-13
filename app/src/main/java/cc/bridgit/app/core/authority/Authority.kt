package cc.bridgit.app.core.authority

import android.app.Activity
import android.app.Application
import android.os.Bundle
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Agent
import cc.bridgit.app.core.authority.AuthorityStore.Outcome
import cc.bridgit.app.core.authority.AuthorityStore.Refusal
import cc.bridgit.app.feedback.InstallIdentity
import cc.bridgit.app.relay.Sealed
import cc.bridgit.app.security.BoundOwnerCheck
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The install key as the approver code needs it. Behind an interface because unit tests have no key store. */
interface InstallSigner {
    /** The registered install id, or null before registration. */
    fun id(): String?
    fun spki(): ByteArray?

    /** r||s over [message] by the install key, or null when there is no key. Never an approval. */
    fun sign(message: ByteArray): ByteArray?

    /** Registers the install if it is not yet (a network call). */
    fun ensure() {}
}

/** One owner check that is waiting: what the OS prompt says and the bytes the approver key signs. */
class OwnerCheckRequest(val id: String, val title: String, val reason: String, val message: ByteArray)

/** Shows the owner's check for a waiting proposal; the outcome goes to [Authority.answered]. False when it could not be shown now. */
fun interface OwnerPrompt {
    fun show(proposal: String): Boolean
}

/**
 * What an approved request does. [check] is the request's whole authorisation run again against today's
 * state (architecture §4.7, the execution-time check): null when it would still be allowed, else why not.
 * Both run inside the store's transaction: they must not call [AuthorityStore] except through the [AuthorityStore.Tx].
 */
interface AuthorityEffect {
    fun check(tx: AuthorityStore.Tx, r: AuthorityStore.Request): String?
    fun apply(tx: AuthorityStore.Tx, r: AuthorityStore.Request): JSONObject

    /** The owner declined on an approver: the request is closed; undo what was waiting for it. Outside the transaction. */
    fun declined(r: AuthorityStore.Request) {}

    /** An owner-authorized setting change: refused when it was asked against an older authority generation (§4.7, racing changes). */
    val settingChange: Boolean get() = false
}

/**
 * Approver devices in the app (architecture §4.7; spending authority G10.2 SA1): the one [AuthorityStore] and
 * [RecoveryMachine], the hooks that raise the authority generation, the alert outbox's local and email delivery, a
 * device enrolling itself, and owner checks answered on this device.
 *
 * Everything is behind the owner setting `security.approvers_enabled`, off by default. While it is off no
 * hook does anything and authority.db is never opened.
 *
 * Threads: the store is never called on the main thread. Hooks and prompt outcomes go to one worker
 * thread, so a hook that fires while a store transaction runs (an approval's effect adds an agent) waits
 * for it instead of calling the store from inside it.
 */
class Authority(private val app: BridgeApp) : Application.ActivityLifecycleCallbacks {
    val enabled: Boolean get() = app.settings.approversEnabled

    // Seams: unit tests have no key store, no prompt and their own clock. Set before first use.
    private var keysOrNull: ApproverKeys? = null
    var keys: ApproverKeys
        get() = keysOrNull ?: synchronized(this) { keysOrNull ?: AndroidApproverKeys(app).also { keysOrNull = it } }
        set(v) { keysOrNull = v }
    var capabilities: () -> ApproverCapabilities = { AndroidApproverProbe.read(app) }
    var install: InstallSigner = AndroidInstall(app)
    var prompt: OwnerPrompt = OwnerPrompt { id -> cc.bridgit.app.security.OwnerCheckActivity.launch(app, id) }
    var time: AuthorityTime = AuthorityTime.Device
    var dbName: String = AuthorityDb.NAME
    /** How a security mail request reaches bridgit.cc: the alert relay, with this install's token. */
    var mailTransport: AuthoritySecurityMail.Transport = AuthoritySecurityMail.Transport { body ->
        app.alerts.relay.security(body).let { AuthoritySecurityMail.Reply(it.status, String(it.bytes ?: ByteArray(0)), it.header("retry-after")) }
    }
    /**
     * The published build. Approver devices show the owner's check prompt and security notifications, and no
     * screen ships without a design pass (A0300), so a published build can't turn them on yet. Debug and
     * test_claude builds (all of them the debug build type) can.
     */
    var releaseBuild: Boolean = !cc.bridgit.app.BuildConfig.DEBUG

    /** Why approver devices can't be turned on in this build, or null. Turning them off is never blocked by this. */
    fun enableBlocker(): String? = if (releaseBuild) ENABLE_NOT_IN_BUILD else null

    /** Refuses turning approver devices on where [enableBlocker] names a reason. */
    fun requireCanEnable() {
        val reason = enableBlocker() ?: return
        throw ErrorCatalog.error("platform_unsupported", "Approver devices can't be turned on in this build yet: their screens wait for a design pass",
            mapOf("reason" to reason, "remaining_step" to ENABLE_REMAINING_STEP, "setting" to SETTING))
    }

    private val opened = lazy { AuthorityDb(app, dbName) }
    val store: AuthorityStore by lazy {
        AuthorityStore(opened.value, time, events = { e ->
            // The change is kept in authority.db; now the setting follows it, and its mail is owed even though the switch is off.
            if (e is AuthorityEvent.ApproversOff) { mailOwed = true; if (app.settings.approversEnabled) app.settings.approversEnabled = false }
            post { drain(); mailSoon() }
        })
    }
    val recovery: RecoveryMachine by lazy { RecoveryMachine(store) }
    /** Email delivery of the outbox. Its requests run on their own thread, never on the authority thread. */
    val mail: AuthoritySecurityMail by lazy {
        AuthoritySecurityMail(store, time, { mailTransport }, ::emailVerified) { line -> runCatching { app.log.system("diagnostic", "authority", line, level = "warn") } }
    }
    private val mailer = Executors.newSingleThreadExecutor { r -> Thread(r, "bridgit-authority-mail").apply { isDaemon = true } }
    /**
     * What must survive a stop of the app while the switch changes (stored at once, in its own small file):
     * `pending`, the change that was begun and whose alert may not be stored yet, and `mail_owed`, set while the
     * switch is off and security mail still waits. authority.db is opened with the switch off only for these two.
     */
    private val switchPrefs by lazy { app.getSharedPreferences("authority_switch", android.content.Context.MODE_PRIVATE) }
    var mailOwed: Boolean
        get() = switchPrefs.getBoolean("mail_owed", false)
        private set(v) { if (v != mailOwed) switchPrefs.edit().putBoolean("mail_owed", v).commit() }

    private class Transition(val id: String, val on: Boolean, val by: String)

    private fun pendingSwitch(): Transition? = switchPrefs.getString("pending", null)?.let { stored ->
        runCatching { JSONObject(stored).let { Transition(it.getString("id"), it.getBoolean("on"), it.getString("by")) } }.getOrNull()
    }

    /** The first half of a switch change: the change is written down, then the setting moves. [reconcile] does the rest. */
    internal fun beginSwitch(on: Boolean, by: String) {
        val edit = switchPrefs.edit().putString("pending", JSONObject().put("id", UUID.randomUUID().toString()).put("on", on).put("by", by).toString())
        if (!on) edit.putBoolean("mail_owed", true)
        check(edit.commit()) { "The switch change could not be stored" }
        app.settings.approversEnabled = on
    }

    /**
     * Finishes a switch change that was begun, and brings the setting in line with authority.db. Run after every
     * change, when the app starts and from the checkpoint; doing it twice changes nothing.
     *
     * A change begun by [setEnabled] is written down before the setting moves, so a stop at any point leaves a
     * note: the generation is raised and the alert stored exactly once (the alert carries the note's id), then
     * the note is removed. The owner turning the switch off on an approver device is kept in authority.db first
     * and the setting follows; if the app stopped in between, the newest switch alert says "off" while the
     * setting says "on", and the setting is corrected here.
     */
    internal fun reconcile() {
        pendingSwitch()?.let { t ->
            if (app.settings.approversEnabled != t.on) app.settings.approversEnabled = t.on
            val closed = store.tx { tx ->
                if (AuthorityOutbox.hasTransition(tx.db, t.id)) return@tx emptyList()
                tx.raise(AuthorityGeneration.Reason.MASTER_SWITCH, "approvers ${if (t.on) "on" else "off"}")
                tx.emit(if (t.on) AuthorityEvent.ApproversOn(t.by, t.id) else AuthorityEvent.ApproversOff(t.by, t.id))
                if (t.on) emptyList() else closeWaiting(tx)
            }
            // An enrolment that was waiting for the owner never finished: its keys guard nothing.
            if (closed.any { it.kind == KIND_SELF_ENROL }) runCatching { keys.clear() }
            if (!t.on) mailOwed = true
            if (pendingSwitch()?.id == t.id) switchPrefs.edit().remove("pending").commit()
        }
        if (enabled && pendingSwitch() == null && store.tx { AuthorityOutbox.lastSwitch(it.db) } == "approvers_off") {
            mailOwed = true
            app.settings.approversEnabled = false
            app.log.system("audit", "security", "Approver devices: the setting was brought in line with the stored change (off)")
        }
    }
    private val effects = HashMap<String, AuthorityEffect>()
    /** Making the approver key replaces any older one, so two starts never run side by side. */
    private val enrolLock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bridgit-authority").apply { isDaemon = true } }
    @Volatile private var agentView: Map<String, AgentView> = emptyMap()
    @Volatile private var showing: String? = null

    /**
     * Not offered yet (A0300 review, 12 Nov 2026). Starting a recovery waits for
     * a cancel action on the approver device (its alerts are mailed now: [AuthoritySecurityMail]); asking the owner to decide an approval waits for a native review
     * screen that shows what is signed. The machinery is built and tested; only unit tests turn these on.
     */
    @Volatile internal var recoveryStartBuilt = false
    @Volatile internal var ownerReviewBuilt = false

    // Prompt pacing, per asker (the agent whose call raises the owner's prompt). Kept in memory: a restart starts it over.
    private val presented = HashMap<String, ArrayDeque<Long>>()
    private val quietUntil = HashMap<String, Long>()

    /** Seconds until [asker] may raise the owner's prompt again, or 0 now. */
    fun promptWaitS(asker: String): Long = synchronized(presented) {
        val now = time.elapsedMs()
        val recent = presented[asker]?.apply { while (isNotEmpty() && (now - first() >= PROMPT_WINDOW_MS || first() > now)) removeFirst() }
        val quiet = (quietUntil[asker] ?: 0L).let { if (it - now > DISMISS_QUIET_MS) 0L else it } // a restart of the device starts the clock over
        val window = if (recent != null && recent.size >= PROMPTS_PER_WINDOW) recent.first() + PROMPT_WINDOW_MS else 0L
        (maxOf(quiet, window) - now).coerceAtLeast(0).let { if (it == 0L) 0L else (it + 999) / 1000 }
    }

    /** Refuses a call that would raise the prompt too often, or soon after the owner closed one from the same asker. Reads are never refused. */
    private fun requirePrompt(asker: String) {
        val wait = promptWaitS(asker)
        if (wait > 0) throw ErrorCatalog.error("rate_limited", "The owner was asked recently; ask again later", mapOf("retry_after_s" to wait))
    }

    private fun presentedBy(asker: String) = synchronized(presented) { presented.getOrPut(asker) { ArrayDeque() }.addLast(time.elapsedMs()) }
    private fun closedBy(asker: String) = synchronized(presented) { quietUntil[asker] = time.elapsedMs() + DISMISS_QUIET_MS }
    private fun asker(p: AuthorityStore.Proposal) = runCatching { JSONObject(p.body).optString("asker") }.getOrDefault("")

    /** The fields of an agent that are "who may do what" (not its use counters, which change on every call). */
    private data class AgentView(val keyHash: String, val suspended: Boolean, val caps: Set<String>?, val fixer: Boolean, val superPowers: Boolean, val maximumControl: Boolean)

    private fun view(a: Agent) = AgentView(a.keyHash, a.suspended, a.caps, a.fixer, a.superPowers, a.maximumControl)

    /** Called once from BridgeApp.onCreate. Registers the hooks; each does nothing while the setting is off. */
    fun attach() {
        if (enabled) agentView = app.agents.all().associate { it.id to view(it) }
        app.agents.addListener(::agentsChanged)
        app.registerActivityLifecycleCallbacks(this)
        register("recovery_cancel", RecoveryCancelEffect)
        register(KIND_SWITCH_OFF, SwitchOffEffect())
        // A switch change the last run of the app did not finish.
        if (enabled || pendingSwitch() != null) post { reconcile(); mailSoon() }
    }

    /** Whether authority.db was opened in this process. False for as long as the setting has been off. */
    val isOpen: Boolean get() = opened.isInitialized()

    fun register(kind: String, effect: AuthorityEffect) = synchronized(effects) { effects[kind] = effect }
    private fun effect(kind: String) = synchronized(effects) { effects[kind] }

    /** Runs [work] on the authority thread. */
    fun post(work: () -> Unit): Future<*> = worker.submit {
        try { work() } catch (e: Exception) { runCatching { app.log.system("diagnostic", "authority", "Authority work failed: ${e.javaClass.simpleName}", level = "warn") } }
    }

    // ---- The switch

    /** Whether turning approver devices off now is the owner's decision: an approver is enrolled (as removing one, §4.7). */
    fun offNeedsOwner(): Boolean = enabled && store.approvers().isNotEmpty()

    /**
     * Turns approver devices on, or off while no approver is enrolled. Off cancels what was waiting; the
     * keys are kept. With an approver enrolled, off happens only through [requestOff] and the owner's check.
     */
    fun setEnabled(on: Boolean, by: String) {
        if (enabled == on) return
        if (on) requireCanEnable()
        check(on || !offNeedsOwner()) { "Turning approver devices off needs an owner authorization" }
        // The view is taken before the hooks wake up, so the first change after is compared with today.
        if (on) agentView = app.agents.all().associate { it.id to view(it) }
        beginSwitch(on, by)
        app.log.system("audit", "security", "$by turned approver devices ${if (on) "on" else "off"}")
        post { reconcile() }
    }

    private fun closeWaiting(tx: AuthorityStore.Tx): List<AuthorityStore.Proposal> {
        tx.cancelRequests("approvers_off")
        return tx.proposals(WAITING).onEach { tx.putProposal(it.copy(state = CANCELLED, remainingHumanStep = "")) }
    }

    /** Asks the owner, on an enrolled approver, to turn approver devices off (purpose `setting`). [asker] is the calling agent. */
    fun requestOff(asker: String): AuthorityStore.Proposal = requestOwner(KIND_SWITCH_OFF, "setting", "", JSONObject().put("setting", SETTING).put("value", false),
        JSONObject(), app.getString(cc.bridgit.app.R.string.approver_check_off), asker)

    /** The owner approved turning approver devices off. The list and the keys are kept; everything that waited is cancelled. */
    private inner class SwitchOffEffect : AuthorityEffect {
        override val settingChange get() = true
        override fun check(tx: AuthorityStore.Tx, r: AuthorityStore.Request): String? = null
        override fun apply(tx: AuthorityStore.Tx, r: AuthorityStore.Request): JSONObject {
            // The setting moves when this transaction is kept (the store's events hook), never before: see [reconcile].
            tx.raise(AuthorityGeneration.Reason.MASTER_SWITCH, "approvers off")
            tx.emit(AuthorityEvent.ApproversOff(r.approver.ifEmpty { "owner" }, r.id))
            closeWaiting(tx)
            app.log.system("audit", "security", "The owner turned approver devices off on an approver device")
            return JSONObject().put("setting", SETTING).put("value", false)
        }
    }

    fun requireEnabled() {
        if (!enabled) throw ErrorCatalog.error("capability_disabled", "Approver devices are off on this device", mapOf("setting" to SETTING))
    }

    // ---- Hooks: the authority generation rises on every change to who may do what (§4.7)

    fun raise(reason: AuthorityGeneration.Reason, detail: String) {
        if (!enabled) return
        post { if (enabled) store.raise(reason, detail) }
    }

    private fun agentsChanged() {
        if (!enabled) return // the listener fires on every agent call; turning the switch on takes a fresh view
        val now = app.agents.all().associate { it.id to view(it) }
        val was = agentView
        if (now == was) return
        agentView = now
        for (id in (was.keys + now.keys)) {
            val a = was[id]; val b = now[id]
            when {
                a == b -> Unit
                a == null -> raise(AuthorityGeneration.Reason.GRANT, "agent $id added")
                b == null -> raise(AuthorityGeneration.Reason.REVOKE, "agent $id removed")
                a.keyHash != b.keyHash -> raise(AuthorityGeneration.Reason.REKEY, "agent $id")
                a.suspended != b.suspended -> raise(AuthorityGeneration.Reason.PAUSE, "agent $id ${if (b.suspended) "paused" else "resumed"}")
                else -> raise(AuthorityGeneration.Reason.GRANT, "agent $id permissions")
            }
        }
    }

    fun grantChanged(agent: String, scope: String?, granted: Boolean) =
        raise(if (granted) AuthorityGeneration.Reason.GRANT else AuthorityGeneration.Reason.REVOKE, "agent $agent ${scope ?: "all"}")

    fun switchChanged(key: String, on: Boolean) = raise(AuthorityGeneration.Reason.MASTER_SWITCH, "$key ${if (on) "on" else "off"}")

    /**
     * On service start and about once a minute: counts running time to storage, does a due recovery clear, sends
     * the daily reminder of a waiting recovery, delivers waiting alerts here and by email. Called off the main thread.
     */
    fun checkpoint() {
        if (pendingSwitch() != null) reconcile()
        if (!enabled) {
            if (mailOwed) mailNow()
            return
        }
        reconcile()
        store.tx { tx ->
            tx.proposals(WAITING).filterNot { waits(tx, it) }.forEach { tx.putProposal(it.copy(state = EXPIRED, remainingHumanStep = "")) }
            RecoveryMachine.daily(tx)
        }
        drain()
        mailNow()
    }

    /** Whether [p] still has something to ask: an enrolment always, a check only while its request is open and in time. */
    fun waits(tx: AuthorityStore.Tx, p: AuthorityStore.Proposal): Boolean = p.state == WAITING &&
        (p.kind != KIND_OWNER_CHECK || tx.request(p.request)?.let { it.state == AuthorityStore.OPEN && tx.nowMs < it.expiresMs } == true)

    // ---- Alerts

    /**
     * One email pass over the outbox, on the calling thread (never the main or the authority thread). With the
     * switch off nothing is sent, with one exception: the mail that says approver devices were turned off, and
     * whatever waited before it. That pass runs when the switch goes off and again from every checkpoint until
     * no mail waits, also after the app was stopped and started: [mailOwed] is stored. Once nothing waits,
     * authority.db stays closed again.
     */
    fun mailNow(): AuthoritySecurityMail.Pass {
        val on = enabled
        if (!on && !mailOwed) return AuthoritySecurityMail.Pass.OFF
        val pass = mail.run(on, afterOff = !on)
        // Looked at again inside the store, so a change stored during the pass keeps its mail owed.
        if (!on && (pass == AuthoritySecurityMail.Pass.IDLE || pass == AuthoritySecurityMail.Pass.DONE))
            store.tx { tx -> if (AuthorityOutbox.undelivered(tx.db, AuthorityOutbox.Way.EMAIL).isEmpty()) mailOwed = false }
        return pass
    }

    /** Done once every email pass queued so far has run. */
    internal fun mailIdle(): Future<*> = mailer.submit { }

    /** Queues an email pass on the mail thread, after a change stored an alert. */
    fun mailSoon(): Future<*> = mailer.submit { runCatching { mailNow() } }

    /** Delivers stored alerts on this device. Approver delivery has no sender yet; those rows wait. */
    fun drain() {
        for (alert in store.undelivered(AuthorityOutbox.Way.LOCAL)) {
            runCatching { cc.bridgit.app.security.AuthorityAlerts.show(app, alert) }
            store.delivered(alert.id, AuthorityOutbox.Way.LOCAL)
        }
    }

    // ---- Conditions

    /** The owner alert email is set and that address is confirmed (review 3, C5). One predicate for enrolment and recovery. */
    fun emailVerified(): Boolean = app.settings.alertEmail != null && app.settings.alertState == cc.bridgit.app.alerts.Alerts.STATE_CONFIRMED

    fun requireEmail() {
        if (!emailVerified()) throw ErrorCatalog.error("owner_email_required", "Set and verify the owner alert email first: POST /v1/owner/configuration/email, then the owner opens the link in that email")
    }

    fun readiness(): ApproverReadiness = ApproverCapability.decide(capabilities())
    fun selfId(): String? = install.id()

    /** Why this device cannot enrol itself as the first approver now, or null. The ids are API values. */
    fun enrolBlocker(): String? = when {
        !enabled -> "approvers_disabled"
        !emailVerified() -> "owner_email_required"
        readiness() != ApproverReadiness.READY -> readiness().id
        else -> store.tx { tx ->
            when {
                tx.recovery().state == RecoveryMachine.PENDING -> "recovery_pending"
                tx.approvers().isNotEmpty() -> "approver_listed"
                else -> null
            }
        }
    }

    // ---- A device as its own approver (X5)

    /**
     * Starts this device's own enrolment: makes the approver key, builds and install-signs the transcript and
     * asks for the owner's check. The approver is stored only when that check produced the approver key's
     * signature ([answered]). Asking again while one waits offers the same check again and makes no new key.
     * Blocking; never on the main thread.
     */
    fun startSelfEnrol(by: String, asker: String = by): AuthorityStore.Proposal = synchronized(enrolLock) {
        requireEnabled()
        requirePrompt(asker)
        store.proposals(WAITING).firstOrNull { it.kind == KIND_SELF_ENROL }?.let { presentedBy(asker); offer(it.id); return@synchronized it }
        requireEmail()
        when (readiness()) {
            ApproverReadiness.READY -> Unit
            ApproverReadiness.NO_SCREEN_LOCK -> throw ErrorCatalog.error("no_screen_lock", "This device has no screen lock, so it can't be an approver device")
            ApproverReadiness.PLATFORM_UNSUPPORTED -> throw ErrorCatalog.error("platform_unsupported", "This device can't hold an approver key")
        }
        store.tx { tx ->
            when {
                tx.recovery().state == RecoveryMachine.PENDING -> Refusal.NOT_OPEN to "An approver recovery is running; cancel it or wait for it to finish"
                tx.approvers().isNotEmpty() -> Refusal.LIST_NOT_EMPTY to "An approver is already enrolled; a further approver is added from an existing one"
                else -> null
            }
        }?.let { (refusal, why) -> throw ErrorCatalog.error(refusal.code, why) }
        runCatching { install.ensure() }
        val id = install.id() ?: throw ErrorCatalog.error("upstream_failed", "This install is not registered with Bridgit yet; retry when the device is online")
        val installKey = install.spki() ?: throw ErrorCatalog.error("upstream_failed", "This install has no key yet; retry when the device is online")
        val nonce = ByteArray(EnrolTranscript.NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val made = try { keys.create(nonce) } catch (e: ApproverKeys.Unavailable) {
            throw ErrorCatalog.error(if (e.readiness == ApproverReadiness.NO_SCREEN_LOCK) "no_screen_lock" else "platform_unsupported", "This device can't hold an approver key now")
        }
        val t = SelfEnrol(id, installKey, made.approverKeySpki, Sealed.un64(made.sealingPublic), made.attestation.fold(ByteArray(0)) { all, c -> all + c },
            Sealed.un64(keys.messagePublic()), store.generation(), nonce)
        val signature = install.sign(t.installProofBytes()) ?: run {
            runCatching { keys.clear() }
            throw ErrorCatalog.error("upstream_failed", "The install key could not sign; retry")
        }
        val b64 = Base64.getEncoder()
        val body = JSONObject().put("by", by).put("asker", asker).put("install_id", id).put("generation", t.generation).put("nonce", b64.encodeToString(nonce))
            .put("install_key", b64.encodeToString(installKey)).put("approver_key", b64.encodeToString(made.approverKeySpki))
            .put("sealing_key", made.sealingPublic).put("attestation", b64.encodeToString(made.attestation.fold(ByteArray(0)) { all, c -> all + c }))
            .put("message_key", keys.messagePublic()).put("install_signature", b64.encodeToString(signature))
            .put("name", cc.bridgit.app.platform.DeviceName.of(app)).put("strongbox", made.strongBox)
        val p = store.putProposal(AuthorityStore.Proposal("enrol_${t.nonceText()}", "", KIND_SELF_ENROL, body.toString(), STEP_OWNER_CHECK, WAITING))
        app.log.system("audit", "security", "$by started enrolling this device as its own approver; waiting for the owner's check")
        presentedBy(asker)
        offer(p.id)
        p
    }

    private fun selfEnrol(p: AuthorityStore.Proposal): Pair<SelfEnrol, ByteArray> {
        val o = JSONObject(p.body)
        val d = Base64.getDecoder()
        return SelfEnrol(o.getString("install_id"), d.decode(o.getString("install_key")), d.decode(o.getString("approver_key")), Sealed.un64(o.getString("sealing_key")),
            d.decode(o.getString("attestation")), Sealed.un64(o.getString("message_key")), o.getLong("generation"), d.decode(o.getString("nonce"))) to d.decode(o.getString("install_signature"))
    }

    // ---- Requests decided by an approver's answer (SA1.5)

    /**
     * Stores a request for the owner's decision and, when this device is its own approver, asks for the
     * owner's check here. [digestOf] is the exact change; the stored digest also covers the kind, the
     * requester, this device, the expiry, the one-use id and the authority generation (§4.7, flow step 1).
     */
    fun requestOwner(kind: String, purpose: String, requester: String, digestOf: JSONObject, body: JSONObject, what: String, asker: String): AuthorityStore.Proposal {
        requireEnabled()
        requirePrompt(asker)
        val self = selfId() ?: throw ErrorCatalog.error("no_approver", "No approver device that can decide this is enrolled")
        val stale = effect(kind)?.settingChange == true
        val p = store.tx { tx ->
            val mine = tx.approver(self)
            if (mine == null || !tx.pastWait(mine)) return@tx null
            val change = cc.bridgit.app.platform.CanonicalJson.canonical(digestOf)
            val check = JSONObject().put("what", what).put("asker", asker).toString()
            // Asking again for the same change offers the waiting request again; it makes no second one.
            val same = tx.openRequests().filter { it.kind == kind && it.requester == requester && tx.nowMs < it.expiresMs && JSONObject(it.body).optString(CHANGE) == change }
            // A setting change asked against an older generation could only be refused: it is replaced.
            if (stale && same.any { it.generation != tx.generation() }) tx.cancelRequests("superseded", requester, kind)
            else same.firstOrNull()?.let { open ->
                return@tx tx.putProposal(AuthorityStore.Proposal("check_${open.id}", open.id, KIND_OWNER_CHECK, check, STEP_OWNER_CHECK, WAITING))
            }
            val id = UUID.randomUUID().toString()
            val expires = tx.nowMs + AuthorityLimits.REQUEST_LIFETIME_MS
            val record = JSONObject().put("kind", kind).put("purpose", purpose).put("requester", requester).put("target", self)
                .put("expires_at", expires).put("nonce", id).put("generation", tx.generation()).put("change", digestOf)
            val digest = MessageDigest.getInstance("SHA-256").digest(cc.bridgit.app.platform.CanonicalJson.canonical(record).toByteArray())
            when (val made = tx.createRequest(AuthorityStore.NewRequest(id, digest, kind, purpose, requester, expires, JSONObject(body.toString()).put(CHANGE, change).toString()))) {
                is Outcome.Refused -> throw IllegalStateException(made.reason.name)
                is Outcome.Ok -> {
                    // A new request only: asking again for the one that waits sends no second alert.
                    if (kind == KIND_SWITCH_OFF) tx.emit(AuthorityEvent.SwitchOffRequested(asker))
                    tx.putProposal(AuthorityStore.Proposal("check_$id", id, KIND_OWNER_CHECK, check, STEP_OWNER_CHECK, WAITING))
                }
            }
        } ?: throw ErrorCatalog.error("no_approver", "No approver device that can decide this now is enrolled (a new approver waits 24 hours after a recovery)")
        presentedBy(asker)
        offer(p.id)
        return p
    }

    /**
     * An approver's answer to a stored request: the signature check ([OwnerAuth.verify]) and, for an
     * approval, the execution-time check and the apply, all in one store transaction. The one function a
     * local owner check and a later remote transport both call. A refusal is returned, never thrown, so
     * what the store recorded while refusing (an expiry, a late-cancel alert) is kept. When the effect itself
     * refuses (the native activation's own check), the whole step is undone: the one-use id is not spent and
     * the request stays open.
     */
    fun answer(request: String, answer: OwnerAuth.Answer): Outcome<JSONObject> {
        var declined: AuthorityStore.Request? = null
        val out = try { decide(request, answer) { declined = it } } catch (e: ApiError) { Outcome.Refused(Refusal.CHECK_FAILED, e.code) }
        declined?.let { r -> runCatching { effect(r.kind)?.declined(r) } }
        if (out is Outcome.Refused) app.log.system("audit", "security", "An owner authorization for request $request was refused: ${out.reason.name.lowercase()} ${out.detail}".trim(), level = "warn")
        return out
    }

    private fun decide(request: String, answer: OwnerAuth.Answer, onDeclined: (AuthorityStore.Request) -> Unit): Outcome<JSONObject> =
        store.tx<Outcome<JSONObject>> { tx ->
            val r = tx.request(request) ?: return@tx Outcome.Refused(Refusal.UNKNOWN_REQUEST)
            when (val v = OwnerAuth.verify(r.record(), answer, tx.ownerAuthState(selfId().orEmpty()))) {
                is OwnerAuth.Result.Refused -> Outcome.Refused(when (v.reason) {
                    OwnerAuth.Reason.EXPIRED -> Refusal.EXPIRED
                    OwnerAuth.Reason.NONCE_REUSED -> Refusal.NONCE_REUSED
                    OwnerAuth.Reason.UNKNOWN_APPROVER -> Refusal.UNKNOWN_APPROVER
                    OwnerAuth.Reason.APPROVER_WAITING -> Refusal.APPROVER_WAITING
                    else -> Refusal.CHECK_FAILED
                }, v.reason.id)
                is OwnerAuth.Result.Declined -> when (val d = tx.decline(r.id, v.approver)) {
                    is Outcome.Refused -> d
                    is Outcome.Ok -> { onDeclined(r); Outcome.Ok(JSONObject().put("status", "declined")) }
                }
                is OwnerAuth.Result.Approved -> {
                    val effect = effect(r.kind) ?: return@tx Outcome.Refused(Refusal.BAD_REQUEST, "no effect for ${r.kind}")
                    tx.execute(r.id, v.approver, effect.settingChange, { t, q -> if (!enabled) "approvers_off" else effect.check(t, q) }, effect::apply)
                }
            }
        }

    // ---- The owner's check on this device

    /** The check a waiting proposal asks for, rebuilt from the store, or null when it no longer waits. */
    fun check(proposal: String): OwnerCheckRequest? {
        val p = store.proposal(proposal)?.takeIf { it.state == WAITING } ?: return null
        return when (p.kind) {
            KIND_SELF_ENROL -> OwnerCheckRequest(p.id, app.getString(cc.bridgit.app.R.string.approver_check_enrol), cc.bridgit.app.platform.DeviceName.of(app), selfEnrol(p).first.approverProofBytes())
            KIND_OWNER_CHECK -> {
                val self = selfId() ?: return null
                val r = store.request(p.request)?.takeIf { it.state == AuthorityStore.OPEN && time.wallMs() < it.expiresMs } ?: run {
                    // The request was decided, cancelled or ran out of time: nothing is left to ask.
                    store.putProposal(p.copy(state = EXPIRED, remainingHumanStep = ""))
                    return null
                }
                OwnerCheckRequest(p.id, app.getString(cc.bridgit.app.R.string.approver_check_approve), JSONObject(p.body).optString("what"),
                    OwnerAuth.bytes(r.purpose, OwnerAuth.APPROVE, self, self, r.digest, r.id, r.generation, r.expiresMs))
            }
            else -> null
        }
    }

    /**
     * Shows the check for [proposal] if it can be shown now. Android may refuse a start from the background
     * without saying so, so nothing is assumed here: the prompt's host reports itself with [showing], and the
     * check is offered again whenever Bridgit comes to the front.
     */
    fun offer(proposal: String) {
        if (showing != null) return // one prompt at a time; the next is offered when this one has gone
        runCatching { prompt.show(proposal) }
    }

    /** The prompt's host is on screen for [proposal], or (null) has gone: then the next waiting check is offered. */
    fun showing(proposal: String?) {
        showing = proposal
        if (proposal == null && enabled) post { offerNext() }
    }

    /** Offers the oldest check that still waits and whose asker the owner did not just turn away. */
    private fun offerNext() {
        store.tx { tx -> tx.proposals(WAITING).firstOrNull { waits(tx, it) && asker(it).let { a -> a.isEmpty() || synchronized(presented) { (quietUntil[a] ?: 0L) <= time.elapsedMs() } } } }?.let { offer(it.id) }
    }

    /** The prompt's outcome for [proposal]. Any thread; the work runs on the authority thread. */
    fun answered(proposal: String, outcome: BoundOwnerCheck.Outcome): Future<*> = post {
        if (showing == proposal) showing = null
        val p = store.proposal(proposal)?.takeIf { it.state == WAITING } ?: return@post
        fun close(state: String, detail: String = "", result: JSONObject? = null) {
            store.putProposal(p.copy(state = state, remainingHumanStep = "", body = JSONObject(p.body).put("detail", detail).apply { if (result != null) put("result", result) }.toString()))
        }
        when (p.kind) {
            KIND_SELF_ENROL -> {
                val failed: String? = when (outcome) {
                    // The alert of this enrolment needs somewhere to go now, not when it was started (review 3, C5).
                    is BoundOwnerCheck.Outcome.Signed -> if (!enabled) "approvers_disabled" else if (!emailVerified()) "owner_email_required" else {
                        val (t, installSignature) = selfEnrol(p)
                        when (val v = t.verify(installSignature, outcome.signatureP1363)) {
                            is EnrolProofs.Result.Refused -> v.reason.id
                            EnrolProofs.Result.Accepted -> {
                                val o = JSONObject(p.body)
                                val a = AuthorityStore.NewApprover(t.installId, o.optString("name"), t.approverKey(), t.installKey(), Sealed.un64(o.getString("sealing_key")), Sealed.un64(o.getString("message_key")))
                                when (val e = store.enrolFirst(a, AuthorityStore.Transcript("enrol", t.installId, t.hash(), t.bytes()))) {
                                    is Outcome.Refused -> e.reason.name.lowercase()
                                    is Outcome.Ok -> null
                                }
                            }
                        }
                    }
                    BoundOwnerCheck.Outcome.Cancelled -> { closedBy(asker(p)); "cancelled" }
                    BoundOwnerCheck.Outcome.NeedsSetup -> "needs_setup"
                    is BoundOwnerCheck.Outcome.Failed -> "failed"
                }
                if (failed == null) close(DONE) else {
                    // The key that was made guards nothing: drop it, unless this install is on the list after all.
                    if (store.approvers().none { it.id == selfId() }) runCatching { keys.clear() }
                    close(if (failed == "cancelled") CANCELLED else FAILED, failed)
                }
            }
            KIND_OWNER_CHECK -> when (outcome) {
                is BoundOwnerCheck.Outcome.Signed -> {
                    val self = selfId().orEmpty()
                    val purpose = store.request(p.request)?.purpose.orEmpty()
                    val result = try { answer(p.request, OwnerAuth.Answer(OwnerAuth.LABEL, purpose, OwnerAuth.APPROVE, self, self, outcome.signatureP1363)) }
                        catch (e: Exception) { Outcome.Refused(Refusal.CHECK_FAILED, e.javaClass.simpleName) }
                    when (result) {
                        is Outcome.Ok -> close(DONE, result = result.value)
                        is Outcome.Refused -> close(FAILED, listOf(result.reason.name.lowercase(), result.detail).filter { it.isNotEmpty() }.joinToString(": "))
                    }
                }
                // The request stays open until it expires; asking again offers the check again.
                BoundOwnerCheck.Outcome.Cancelled -> { closedBy(asker(p)); close(DISMISSED, "cancelled") }
                BoundOwnerCheck.Outcome.NeedsSetup -> close(FAILED, "needs_setup")
                is BoundOwnerCheck.Outcome.Failed -> close(FAILED, "failed")
            }
        }
    }

    /** A person brought Bridgit to the front: offer a check that is waiting. */
    override fun onActivityResumed(activity: Activity) {
        if (!enabled || activity is cc.bridgit.app.security.OwnerCheckActivity) return
        post { offerNext() }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    /** A `recovery_cancel`: any accepted approver-signed request already cancels a pending recovery ([AuthorityStore.Tx.approverSigned]). */
    private object RecoveryCancelEffect : AuthorityEffect {
        override fun check(tx: AuthorityStore.Tx, r: AuthorityStore.Request): String? = if (tx.recovery().state == RecoveryMachine.PENDING) null else "not_pending"
        override fun apply(tx: AuthorityStore.Tx, r: AuthorityStore.Request): JSONObject = JSONObject().put("status", "cancelled")
    }

    private class AndroidInstall(private val app: BridgeApp) : InstallSigner {
        override fun id(): String? = InstallIdentity.status(app)?.installId
        override fun spki(): ByteArray? = InstallIdentity.publicKeySpki(app)
        override fun sign(message: ByteArray): ByteArray? = InstallIdentity.sign(app, message)
        override fun ensure() { InstallIdentity.ensure(app, cc.bridgit.app.feedback.TeamKeys.base) }
    }

    companion object {
        const val SETTING = "security.approvers_enabled"
        const val ENABLE_NOT_IN_BUILD = "not_available_in_this_build"
        const val ENABLE_REMAINING_STEP = "design pass of the approver screens"
        const val KIND_SELF_ENROL = "self_enrol"
        const val KIND_OWNER_CHECK = "owner_check"
        const val KIND_SWITCH_OFF = "setting.approvers_off"

        /** An asker raises the owner's prompt at most this often; after the owner closes one, that asker is quiet for a while. */
        const val PROMPTS_PER_WINDOW = 3
        const val PROMPT_WINDOW_MS = 10L * 60 * 1000
        const val DISMISS_QUIET_MS = 5L * 60 * 1000
        const val WAITING = "waiting_owner"
        const val DONE = "done"
        const val CANCELLED = "cancelled"
        const val DISMISSED = "dismissed"
        const val FAILED = "failed"
        const val EXPIRED = "expired"
        private const val CHANGE = "_change"

        /** `remaining_human_step` of a waiting owner check: what, where and its status. */
        val STEP_OWNER_CHECK: String = JSONObject().put("step", "owner_check").put("where", "this device: open Bridgit and pass the screen-lock check").put("status", "waiting").toString()
    }
}
