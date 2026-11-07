package cc.bridgit.app.dog

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A short search around a measured last-seen camera pose, never an unrestricted sweep. */
object PetCameraSearch {
    data class Pose(val pan: Double, val tilt: Double) {
        init { require(pan.isFinite() && tilt.isFinite() && pan in -1.0..1.0 && tilt in -1.0..1.0) }
    }
    interface Driver {
        suspend fun authorize()
        suspend fun move(pose: Pose)
        suspend fun observe(): Boolean
        suspend fun stop()
    }
    const val LOST_MS = 60_000L
    const val COOLDOWN_MS = 5 * 60_000L
    const val ANCHOR_MAX_AGE_MS = 30 * 60_000L
    const val TIMEOUT_MS = 60_000L
    fun due(now: Long, lastSeen: Long?, lastAttempt: Long?, latestDog: Boolean) = !latestDog &&
        lastSeen != null && now >= lastSeen && now - lastSeen in LOST_MS..ANCHOR_MAX_AGE_MS &&
        (lastAttempt == null || now >= lastAttempt && now - lastAttempt >= COOLDOWN_MS)

    // Stay close to the last confirmed view. Clamping and deduplication bound travel at camera limits.
    fun poses(center: Pose): List<Pose> = listOf(0.0 to 0.0,-.035 to 0.0,.035 to 0.0,
        0.0 to .035,0.0 to -.035,-.07 to 0.0,.07 to 0.0).map { (x,y) ->
        Pose((center.pan+x).coerceIn(-1.0,1.0),(center.tilt+y).coerceIn(-1.0,1.0))
    }.distinct()

    fun nearAnchor(center: Pose, original: Pose) = kotlin.math.abs(center.pan-original.pan)<=.15 && kotlin.math.abs(center.tilt-original.tilt)<=.10

    suspend fun run(driver: Driver, center: Pose, original: Pose): Boolean {
        try {
            return withTimeout(TIMEOUT_MS) {
                if(!nearAnchor(center,original)) return@withTimeout false
                for (pose in poses(center)) {
                    driver.authorize(); driver.move(pose)
                    driver.authorize(); if (driver.observe()) return@withTimeout true
                }
                driver.authorize(); driver.move(original)
                false
            }
        } finally {
            // Cancellation stops the motor; it must not issue a restore move after owner intervention.
            withContext(NonCancellable) { driver.stop() }
        }
    }
}
