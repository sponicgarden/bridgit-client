package cc.bridgit.app.ownerapi

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.authority.ApproverCapabilities
import cc.bridgit.app.core.authority.Authority
import cc.bridgit.app.core.authority.AuthoritySecurityMail
import cc.bridgit.app.core.authority.FakeTime
import cc.bridgit.app.core.authority.InstallSigner
import cc.bridgit.app.core.authority.OwnerPrompt
import cc.bridgit.app.core.authority.SoftwareApproverKeys
import cc.bridgit.app.feedback.InstallAuth
import cc.bridgit.app.security.BoundOwnerCheck
import org.robolectric.shadows.ShadowLooper
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** An install key in memory: Robolectric has no AndroidKeyStore. */
class SoftwareInstall : InstallSigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    var registered = true
    override fun id(): String? = if (registered) InstallAuth.installId(pair.public.encoded) else null
    override fun spki(): ByteArray? = if (registered) pair.public.encoded else null
    override fun sign(message: ByteArray): ByteArray? = if (!registered) null else
        InstallAuth.derToP1363(Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }.sign())
}

/**
 * The seams of [Authority] for unit tests: software keys, a capability probe a test sets, an install key in
 * memory, a clock a test moves, and a prompt that only records what it was asked to show. [pass] plays the
 * owner passing the check; [cancel] closing it.
 */
class ApproverTestKit(private val app: BridgeApp, name: String) {
    val keys = SoftwareApproverKeys()
    val install = SoftwareInstall()
    val time = FakeTime()
    val shown = ArrayList<String>()
    /** Security mail requests, in order. [mailReply] answers each; by default bridgit.cc can't be reached, so rows wait. */
    val mailed = java.util.concurrent.CopyOnWriteArrayList<org.json.JSONObject>()
    @Volatile var mailReply: () -> AuthoritySecurityMail.Reply = { throw java.io.IOException("offline") }
    var capabilities = ApproverCapabilities(apiLevel = 34, secureLockScreen = true, strongBox = false, strongBiometric = false, perUseCredentialKeys = true)
    val authority: Authority get() = app.authority

    init {
        app.deleteDatabase(name)
        authority.dbName = name
        authority.keys = keys
        authority.install = install
        authority.time = time
        authority.capabilities = { capabilities }
        authority.prompt = OwnerPrompt { id -> shown += id; true }
        authority.mailTransport = AuthoritySecurityMail.Transport { body -> mailed += org.json.JSONObject(String(body)); mailReply() }
    }

    fun emailVerified(on: Boolean = true) {
        app.settings.alertEmail = if (on) "owner@example.com" else null
        app.settings.alertState = if (on) cc.bridgit.app.alerts.Alerts.STATE_CONFIRMED else cc.bridgit.app.alerts.Alerts.STATE_NONE
    }

    fun enable(on: Boolean = true) { authority.setEnabled(on, "test"); idle() }

    /** Waits for the authority thread, running whatever it posts to the main thread meanwhile. */
    fun wait(f: Future<*>) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!f.isDone) {
            ShadowLooper.idleMainLooper()
            check(System.nanoTime() < until) { "The authority thread did not finish" }
            Thread.sleep(2)
        }
        f.get()
    }

    /** Waits for the authority thread, then for the email passes it queued. */
    fun idle() { wait(authority.post { }); wait(authority.mailIdle()) }

    /** bridgit.cc answers every security mail with "sent". Call before the first change, or a failed try's wait is still running. */
    fun mailOnline() { mailReply = { AuthoritySecurityMail.Reply(200, "{\"sent\":true}") } }

    /** The owner passes the check for [proposal]: the approver key signs the bytes the check asks for. */
    fun pass(proposal: String = shown.last(), sign: SoftwareApproverKeys = keys) {
        val check = authority.check(proposal) ?: error("Nothing waits for $proposal")
        wait(authority.answered(proposal, BoundOwnerCheck.Outcome.Signed(sign.beginSign(check.message).finish())))
        idle()
    }

    fun cancel(proposal: String = shown.last()) { wait(authority.answered(proposal, BoundOwnerCheck.Outcome.Cancelled)); idle() }
}
