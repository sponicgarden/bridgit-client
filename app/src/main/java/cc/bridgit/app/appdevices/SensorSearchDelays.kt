package cc.bridgit.app.appdevices

/**
 * The sampling interval, per applet: how often an applet samples a sensor it isn't using right now (Pet monitor:
 * how long without seeing the pet before it tests the other cameras, and how often the food-bowl microphone takes
 * a sample). One value for all of an applet's cameras and microphones ([allSeconds], default [DEFAULT_SECONDS]),
 * and an optional override per sensor (camera/rear differs from microphone/rear). A sensor without an override
 * follows the all value. Storage is injected so applets share the policy without sharing their preferences.
 */
class SensorSearchDelays(private val read: (String) -> Int?, private val write: (String, Int?) -> Unit) {
    companion object {
        /** 20 s since 4 Nov 2026 (owner request; was 60). */
        const val DEFAULT_SECONDS = 20
        const val MIN_SECONDS = 10
        const val MAX_SECONDS = 3600
        /** The sensor key of the all value. */
        private const val ALL = "*"
        fun validSeconds(value: Any?) = value is Int && value in MIN_SECONDS..MAX_SECONDS ||
            value is Long && value in MIN_SECONDS.toLong()..MAX_SECONDS.toLong()
    }
    private fun key(application: String, sensor: String): String {
        require(application.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        require(sensor == ALL || sensor.matches(Regex("[a-z][a-zA-Z0-9_:/-]{0,127}")))
        return "$application|$sensor"
    }
    private fun stored(application: String, sensor: String): Int? =
        read(key(application, sensor))?.takeIf { it in MIN_SECONDS..MAX_SECONDS }

    /** The value every sensor of [application] follows unless it has its own. */
    fun allSeconds(application: String): Int = stored(application, ALL) ?: DEFAULT_SECONDS
    /** null restores [DEFAULT_SECONDS]. */
    fun setAll(application: String, seconds: Int?) {
        require(seconds == null || validSeconds(seconds))
        write(key(application, ALL), seconds)
    }
    /** The sensor's own value, or null when it follows the all value. */
    fun override(application: String, sensor: String): Int? = stored(application, sensor)

    fun seconds(application: String, sensor: String): Int = stored(application, sensor) ?: allSeconds(application)
    /** null: the sensor follows the all value again. */
    fun set(application: String, sensor: String, seconds: Int?) {
        require(seconds == null || validSeconds(seconds))
        write(key(application, sensor), seconds)
    }
}
