package cc.bridgit.app.capability

import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ErrorCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/**
 * Who holds the warmed-up camera (8.2 arm). Pure Kotlin so the hand-off rules are unit-tested: whoever
 * [claim]s an arm owns it and must release it, so a snapshot and the auto-release timer can never both
 * unbind the camera.
 */
class ArmSlot<B> {
    data class Held<B>(val agentId: String, val rear: Boolean, val binding: B, val token: Long, val generation: Int,
                       val openedAt: Long = 0)

    private var held: Held<B>? = null
    private var generation = 0

    @Synchronized fun holder(): String? = held?.agentId

    /** Stores a new arm and returns its generation; the slot must be empty (claim first). */
    @Synchronized fun put(agentId: String, rear: Boolean, binding: B, token: Long, openedAt: Long = 0): Int {
        check(held == null) { "camera already armed" }
        val g = ++generation
        held = Held(agentId, rear, binding, token, g, openedAt)
        return g
    }

    /** Takes the arm if [agentId] holds it; null = whoever holds it (Stop, Pause). */
    @Synchronized fun claim(agentId: String? = null): Held<B>? {
        val h = held ?: return null
        if (agentId != null && h.agentId != agentId) return null
        held = null
        return h
    }

    /** Takes the arm only if it is still the one from [generation] (a re-arm makes older timers stale). */
    @Synchronized fun claimGeneration(generation: Int): Held<B>? {
        val h = held ?: return null
        if (h.generation != generation) return null
        held = null
        return h
    }

    companion object {
        const val MAX_SECONDS = 10
        const val DEFAULT_SECONDS = 10
        /** Exposure needs about this long after the camera opens; an armed photo uses the fast capture mode, which doesn't search for it. */
        const val SETTLE_MS = 1000L

        /** How long a snapshot of [held] must still wait for exposure to settle. */
        fun settleWaitMs(held: Held<*>, now: Long): Long = (held.openedAt + SETTLE_MS - now).coerceIn(0, SETTLE_MS)

        /** "seconds" from the request body: a whole number 1-10, default 10. Null = invalid. */
        fun seconds(raw: Any?): Int? = when (raw) {
            null, org.json.JSONObject.NULL -> DEFAULT_SECONDS
            is Int -> raw.takeIf { it in 1..MAX_SECONDS }
            is Long -> raw.takeIf { it in 1..MAX_SECONDS }?.toInt()
            is Double -> raw.takeIf { it == Math.floor(it) && it in 1.0..MAX_SECONDS.toDouble() }?.toInt()
            else -> null
        }
    }
}

/**
 * `POST /v1/camera/arm`: opens the camera ahead of a photo so the next snapshot fires in about 0.3 s instead of
 * 1.4-2 s. Holds the camera lock and the capture notification for at most [ArmSlot.MAX_SECONDS]; released by the
 * timer, by that agent's next snapshot, when the camera is switched off or the agent loses it, and on Stop/Pause.
 */
class CameraArm(private val lock: Mutex) {
    val slot = ArmSlot<CameraCapture.Warm>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Arms [rear]/front for [agentId]; a re-arm by the same agent restarts the timer. [allowed] is polled while armed. */
    suspend fun arm(service: BridgeService, agentId: String, note: String, rear: Boolean, seconds: Int, allowed: () -> Boolean) {
        val prev = slot.claim(agentId)
        if (prev == null && !lock.tryLock())
            throw ErrorCatalog.error("sensor_busy", "The camera is in use. Retry in a few seconds.")
        var token = prev?.token
        // Uncancellable from here: a client gone between binding and storing the arm would leave the camera bound.
        try { withContext(NonCancellable) {
            if (token == null) token = service.captureStarted(note)
            val reuse = prev != null && prev.rear == rear
            val binding = if (reuse) prev!!.binding
                else { prev?.let { service.camera.release(it.binding) }; service.camera.warmUp(rear, armed = true) }
            val g = slot.put(agentId, rear, binding, token!!, if (reuse) prev!!.openedAt else System.currentTimeMillis())
            val until = System.currentTimeMillis() + seconds * 1000L
            scope.launch {
                while (System.currentTimeMillis() < until && allowed()) delay(250)
                slot.claimGeneration(g)?.let { finish(service, it) }
            }
        } } catch (e: Throwable) {
            token?.let { service.captureEnded(it) }
            lock.unlock()
            throw e
        }
    }

    /** Hands [agentId]'s arm to a snapshot, or null when it has none. The caller must call [finish] after the photo. */
    fun takeFor(agentId: String): ArmSlot.Held<CameraCapture.Warm>? = slot.claim(agentId)

    private fun done(service: BridgeService, held: ArmSlot.Held<CameraCapture.Warm>) {
        service.captureEnded(held.token)
        lock.unlock()
    }

    /** Stop or Pause: release whatever is armed. */
    fun releaseAll(service: BridgeService) {
        slot.claim()?.let { h -> scope.launch { finish(service, h) } }
    }

    /** Unbinds [h] (again, if the snapshot already did; unbinding twice is harmless), ends its notification, frees the lock. */
    suspend fun finish(service: BridgeService, h: ArmSlot.Held<CameraCapture.Warm>) {
        try { service.camera.release(h.binding) } finally { done(service, h) }
    }
}
