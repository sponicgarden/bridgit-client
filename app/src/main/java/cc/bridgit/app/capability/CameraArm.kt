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
import cc.bridgit.app.core.camera.CameraBroker
import cc.bridgit.app.core.camera.CameraLeases

/**
 * Who holds the warmed-up camera (8.2 arm). Pure Kotlin so the hand-off rules are unit-tested: whoever
 * [claim]s an arm owns it and must release it, so a snapshot and the auto-release timer can never both
 * unbind the camera.
 */
class ArmSlot<B> {
    data class Held<B>(val agentId: String, val rear: Boolean, val binding: B, val token: Long, val generation: Int,
                       val openedAt: Long = 0, val lease: CameraBroker.Ticket? = null)

    private var held: Held<B>? = null
    private var generation = 0

    @Synchronized fun holder(): String? = held?.agentId

    /** Stores a new arm and returns its generation; the slot must be empty (claim first). */
    @Synchronized fun put(agentId: String, rear: Boolean, binding: B, token: Long, openedAt: Long = 0,
                          lease: CameraBroker.Ticket? = null): Int {
        check(held == null) { "camera already armed" }
        val g = ++generation
        held = Held(agentId, rear, binding, token, g, openedAt, lease)
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
 * 1.4-2 s. Holds a camera lease and the capture notification for at most [ArmSlot.MAX_SECONDS]; released by the
 * timer, by that agent's next snapshot, when the camera is switched off or the agent loses it, and on Stop/Pause.
 */
class CameraArm(broker: CameraBroker) {
    private val core = CameraArmCore<CameraCapture.Warm>(broker)
    val slot: ArmSlot<CameraCapture.Warm> get() = core.slot

    private class Phone(val service: BridgeService) : CameraArmCore.Device<CameraCapture.Warm> {
        override fun captureStarted(note: String) = service.captureStarted(note)
        override fun captureEnded(token: Long) = service.captureEnded(token)
        // Arming works while locked like the agent's photo it prepares; it returns no picture, so the owner's
        // locked-phone approval (captures.locked_camera_approval) is asked by that snapshot.
        override suspend fun warmUp(rear: Boolean) = service.camera.warmUp(rear, armed = true, allowLocked = true)
        override suspend fun release(binding: CameraCapture.Warm) = service.camera.release(binding)
    }

    /** Arms [rear]/front for [agentId]; a re-arm by the same agent restarts the timer. [allowed] is polled while armed. */
    suspend fun arm(service: BridgeService, agentId: String, note: String, rear: Boolean, seconds: Int, allowed: () -> Boolean) =
        core.arm(Phone(service), agentId, note, rear, seconds, allowed)

    /** Hands [agentId]'s arm to a snapshot, or null when it has none. The caller must call [finish] after the photo. */
    fun takeFor(agentId: String): ArmSlot.Held<CameraCapture.Warm>? = core.takeFor(agentId)

    /** Stop or Pause: release whatever is armed. */
    fun releaseAll(service: BridgeService) = core.releaseAll(Phone(service))

    /** Unbinds [h] (again, if the snapshot already did; unbinding twice is harmless), ends its notification, frees the lease. */
    suspend fun finish(service: BridgeService, h: ArmSlot.Held<CameraCapture.Warm>) = core.finish(Phone(service), h)
}

/** [CameraArm]'s hand-off rules over any camera binding [B], so the lease handling is unit-tested with a fake [Device]. */
class CameraArmCore<B>(private val broker: CameraBroker) {
    /** What arming needs from the phone. */
    interface Device<B> {
        fun captureStarted(note: String): Long
        fun captureEnded(token: Long)
        suspend fun warmUp(rear: Boolean): B
        suspend fun release(binding: B)
    }

    val slot = ArmSlot<B>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun arm(device: Device<B>, agentId: String, note: String, rear: Boolean, seconds: Int, allowed: () -> Boolean) {
        val prev = slot.claim(agentId)
        // A re-arm keeps the lease it already holds; a first arm needs the camera free right now (never waits).
        val lease = prev?.lease ?: broker.tryAcquire(CameraLeases.Lease.OneOff(agentId, "arm"), CameraBroker.lens(rear))
            ?: throw ErrorCatalog.error("sensor_busy", "The camera is in use. Retry in a few seconds.")
        var token = prev?.token
        // Uncancellable from here: a client gone between binding and storing the arm would leave the camera bound.
        try { withContext(NonCancellable) {
            if (token == null) token = device.captureStarted(note)
            val reuse = prev != null && prev.rear == rear
            val binding = if (reuse) prev!!.binding
                else { prev?.let { device.release(it.binding) }; device.warmUp(rear) }
            val g = slot.put(agentId, rear, binding, token!!, if (reuse) prev!!.openedAt else System.currentTimeMillis(), lease)
            val until = System.currentTimeMillis() + seconds * 1000L
            scope.launch {
                while (System.currentTimeMillis() < until && allowed()) delay(250)
                slot.claimGeneration(g)?.let { finish(device, it) }
            }
        } } catch (e: Throwable) {
            token?.let { device.captureEnded(it) }
            broker.release(lease)
            throw e
        }
    }

    fun takeFor(agentId: String): ArmSlot.Held<B>? = slot.claim(agentId)

    private fun done(device: Device<B>, held: ArmSlot.Held<B>) {
        device.captureEnded(held.token)
        broker.release(held.lease)
    }

    fun releaseAll(device: Device<B>) {
        slot.claim()?.let { h -> scope.launch { finish(device, h) } }
    }

    suspend fun finish(device: Device<B>, h: ArmSlot.Held<B>) {
        try { device.release(h.binding) } finally { done(device, h) }
    }
}
