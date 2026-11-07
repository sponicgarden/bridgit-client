package cc.bridgit.app.lancamera

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Motion is testable independently of camera credentials and the Android app. */
object CameraAim {
    interface Driver {
        suspend fun authorize()
        suspend fun move()
        suspend fun settle()
        suspend fun arrived():Boolean
        suspend fun stop():Boolean
    }
    suspend fun run(driver:Driver,timeoutMs:Long=15_000):Boolean {
        var ready=false
        try {
            ready=withTimeoutOrNull(timeoutMs) {
                driver.authorize();driver.move()
                for(attempt in 0 until 12) {
                    driver.settle();driver.authorize()
                    if(driver.arrived()) return@withTimeoutOrNull true
                }
                false
            } ?: false
        } finally {
            val stopped=withContext(NonCancellable) { runCatching { driver.stop() }.getOrDefault(false) }
            if(!stopped)ready=false
        }
        return ready
    }
}
