package cc.bridgit.app.power

/**
 * Battery report and alert rules. Pure Kotlin: [Battery] feeds it Android's raw values.
 * Android's integer codes come from BatteryManager and PowerManager; they are stable public API.
 */
object BatteryRules {
    const val LOW = 15
    const val CRITICAL = 5
    /** A low alert re-arms once the level is back at or above this. */
    const val OKAY = 20
    const val HOT_C = 45.0
    const val COOL_C = 40.0
    const val TIMEOUT_MIN_S = 15
    const val TIMEOUT_MAX_S = 1800

    fun status(code: Int) = when (code) {
        2 -> "charging"; 3 -> "discharging"; 4 -> "not_charging"; 5 -> "full"; else -> "unknown"
    }
    fun health(code: Int) = when (code) {
        2 -> "good"; 3 -> "overheat"; 4 -> "dead"; 5 -> "over_voltage"; 6 -> "failure"; 7 -> "cold"; else -> "unknown"
    }
    /** BatteryManager.BATTERY_PLUGGED_* bits. */
    fun plugged(bits: Int) = when {
        bits and 4 != 0 -> "wireless"; bits and 1 != 0 -> "ac"; bits and 2 != 0 -> "usb"; bits and 8 != 0 -> "dock"; else -> "none"
    }
    fun thermal(code: Int) = when (code) {
        0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"; else -> "unknown"
    }

    fun percent(level: Int, scale: Int): Int? = if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
    /** EXTRA_TEMPERATURE is tenths of a degree; Int.MIN_VALUE means the extra was missing. */
    fun celsius(tenths: Int): Double? = if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    fun volts(millivolts: Int): Double? = if (millivolts <= 0) null else millivolts / 1000.0
    /** BatteryManager.getIntProperty returns Int.MIN_VALUE (Long.MIN_VALUE for longs) when the phone does not report it. */
    fun property(v: Long): Long? = if (v == Long.MIN_VALUE || v == Int.MIN_VALUE.toLong()) null else v
    fun micro(v: Long): Long? = property(v)?.let { it / 1000 }

    data class Reading(val percent: Int?, val charging: Boolean, val full: Boolean, val powerSave: Boolean, val celsius: Double?)
    data class Latch(val low: Boolean = false, val critical: Boolean = false, val hot: Boolean = false)

    /** The latch for the first reading after a start: no alert for a state that was already there. */
    fun initial(r: Reading) = Latch(
        low = r.percent != null && r.percent <= LOW, critical = r.percent != null && r.percent <= CRITICAL,
        hot = r.celsius != null && r.celsius >= HOT_C)

    /**
     * Events for the change from [prev] to [now], oldest first, and the new latch.
     * Low and critical fire once while unplugged and re-arm at [OKAY]; hot fires once and re-arms at [COOL_C].
     */
    fun transitions(prev: Reading, now: Reading, latch: Latch): Pair<List<String>, Latch> {
        val out = mutableListOf<String>()
        var l = latch
        if (now.charging != prev.charging) out += if (now.charging) "battery.charging_started" else "battery.charging_stopped"
        if (now.full && !prev.full) out += "battery.full"
        if (now.powerSave != prev.powerSave) out += if (now.powerSave) "battery.saver_on" else "battery.saver_off"
        val p = now.percent
        if (p != null) {
            if (l.low && p >= OKAY) { out += "battery.okay"; l = l.copy(low = false, critical = false) }
            if (!now.charging && !l.critical && p <= CRITICAL) { out += "battery.critical"; l = l.copy(low = true, critical = true) }
            else if (!now.charging && !l.low && p <= LOW) { out += "battery.low"; l = l.copy(low = true) }
        }
        val c = now.celsius
        if (c != null) {
            if (!l.hot && c >= HOT_C) { out += "battery.hot"; l = l.copy(hot = true) }
            else if (l.hot && c <= COOL_C) { out += "battery.cooled"; l = l.copy(hot = false) }
        }
        return out to l
    }

    val EVENTS = listOf("battery.low", "battery.critical", "battery.okay", "battery.charging_started", "battery.charging_stopped",
        "battery.full", "battery.saver_on", "battery.saver_off", "battery.hot", "battery.cooled")

    /** SCREEN_OFF_TIMEOUT in ms; Int.MAX_VALUE (or anything over a day) is how phones store "never". */
    fun timeoutSeconds(ms: Int): Int? = if (ms <= 0 || ms >= 86_400_000) null else ms / 1000

    fun grantCommand(packageName: String) = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
}
