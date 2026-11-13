package cc.bridgit.app.core.authority

import android.content.Context
import cc.bridgit.app.core.authority.AuthorityStore.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

const val SECOND = 1_000L
const val HOUR = 3_600_000L

/** Clocks a test moves by hand. [run] is the device running; [off] is it powered off; [wall] alone is someone changing the clock. */
class FakeTime(var wall: Long = 1_800_000_000_000L, var elapsed: Long = 50_000L) : AuthorityTime {
    override fun wallMs() = wall
    override fun elapsedMs() = elapsed
    fun run(ms: Long) { wall += ms; elapsed += ms }
    fun off(ms: Long) { wall += ms; elapsed = 1_000 }
}

/** A store on a real database file, which a test can close and open again as a new process would. */
class AuthorityFixture(private val context: Context, private val name: String = AuthorityDb.NAME) {
    val time = FakeTime()
    val events = ArrayList<AuthorityEvent>()
    lateinit var helper: AuthorityDb
    lateinit var store: AuthorityStore
    lateinit var recovery: RecoveryMachine
    private var process = 1L
    private val others = ArrayList<AuthorityDb>()

    init {
        context.deleteDatabase(name)
        open()
    }

    private fun open() {
        helper = AuthorityDb(context, name)
        store = AuthorityStore(helper, time, { events += it }, clockSession = process)
        recovery = RecoveryMachine(store)
    }

    fun close() {
        helper.close()
        others.forEach { it.close() }
        others.clear()
    }

    /** A second store on the same file in the same app process, with its own database connection. */
    fun secondStore(): AuthorityStore = AuthorityStore(AuthorityDb(context, name).also { others += it }, time, { events += it }, clockSession = process)

    /** The app process stops and starts again. With [reboot] the device restarted too, so time-since-boot starts over. */
    fun restart(reboot: Boolean = false) {
        close()
        process++
        if (reboot) time.elapsed = 1_000
        open()
    }

    fun approver(id: String) = AuthorityStore.NewApprover(id, "Name $id", bytes(id, 1), bytes(id, 2), bytes(id, 3), bytes(id, 4))
    fun bytes(id: String, n: Int) = "$id-$n".toByteArray()
    fun transcript(kind: String, id: String) = AuthorityStore.Transcript(kind, id, ByteArray(32) { 7 }, "body $id".toByteArray())

    fun enrolFirst(id: String) = ok(store.enrolFirst(approver(id), transcript("enrol", id)))
    fun add(id: String, by: String) = ok(store.addApprover(approver(id), by, transcript("add", id)))
    fun ids() = store.approvers().map { it.id }

    fun request(id: String, expiresInMs: Long = 100 * HOUR, requester: String = "agent-1", kind: String = "setting") =
        ok(store.createRequest(AuthorityStore.NewRequest(id, ByteArray(32) { 1 }, kind, "approver_remove", requester, time.wall + expiresInMs, "{}")))

    companion object {
        fun <T> ok(o: Outcome<T>): T = when (o) {
            is Outcome.Ok -> o.value
            is Outcome.Refused -> { fail("Refused: ${o.reason} ${o.detail}"); throw AssertionError() }
        }

        fun refused(expected: AuthorityStore.Refusal, o: Outcome<*>) {
            assertEquals(expected, (o as? Outcome.Refused)?.reason)
        }
    }
}
