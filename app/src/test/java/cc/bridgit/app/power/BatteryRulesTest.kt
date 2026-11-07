package cc.bridgit.app.power

import cc.bridgit.app.power.BatteryRules.Latch
import cc.bridgit.app.power.BatteryRules.Reading
import org.junit.Assert.*
import org.junit.Test

class BatteryRulesTest {
    private fun r(p: Int?, charging: Boolean = false, full: Boolean = false, saver: Boolean = false, c: Double? = 30.0) = Reading(p, charging, full, saver, c)
    private fun run(vararg readings: Reading): List<String> {
        var latch = BatteryRules.initial(readings.first()); val out = mutableListOf<String>()
        readings.toList().zipWithNext().forEach { (a, b) -> val (e, l) = BatteryRules.transitions(a, b, latch); out += e; latch = l }
        return out
    }

    @Test fun lowFiresOnceAndRearmsAtOkay() {
        assertEquals(listOf("battery.low"), run(r(17), r(16), r(15), r(14), r(13)))
        assertEquals(listOf("battery.low", "battery.okay", "battery.low"), run(r(16), r(15), r(19), r(20), r(15)))
    }

    @Test fun criticalAfterLowAndAlone() {
        assertEquals(listOf("battery.low", "battery.critical"), run(r(16), r(15), r(6), r(5), r(4)))
        assertEquals(listOf("battery.critical"), run(r(16), r(3)))
    }

    @Test fun noAlertWhileChargingOrAlreadyLowAtStart() {
        assertEquals(emptyList<String>(), run(r(14, charging = true), r(10, charging = true)))
        assertEquals(emptyList<String>(), run(r(12), r(11), r(10)))
    }

    @Test fun chargingFullAndSaverTransitions() {
        assertEquals(listOf("battery.charging_started", "battery.full", "battery.charging_stopped"),
            run(r(90), r(90, charging = true), r(100, charging = true, full = true), r(100, full = true)))
        assertEquals(listOf("battery.saver_on", "battery.saver_off"), run(r(50), r(50, saver = true), r(50)))
    }

    @Test fun heatWithHysteresis() {
        assertEquals(listOf("battery.hot", "battery.cooled"), run(r(50, c = 44.0), r(50, c = 45.0), r(50, c = 41.0), r(50, c = 40.0), r(50, c = 46.0).copy(celsius = 39.0)))
        assertEquals(Latch(hot = true), BatteryRules.initial(r(50, c = 47.0)))
    }

    @Test fun conversions() {
        assertEquals(50, BatteryRules.percent(50, 100)); assertEquals(50, BatteryRules.percent(128, 256)); assertNull(BatteryRules.percent(-1, 100))
        assertEquals(31.5, BatteryRules.celsius(315)!!, 0.001); assertNull(BatteryRules.celsius(Int.MIN_VALUE))
        assertEquals(4.123, BatteryRules.volts(4123)!!, 0.0001); assertNull(BatteryRules.volts(0))
        assertNull(BatteryRules.micro(Int.MIN_VALUE.toLong())); assertNull(BatteryRules.micro(Long.MIN_VALUE)); assertEquals(-350L, BatteryRules.micro(-350_000))
        assertEquals("wireless", BatteryRules.plugged(4)); assertEquals("usb", BatteryRules.plugged(2)); assertEquals("none", BatteryRules.plugged(0))
        assertEquals(60, BatteryRules.timeoutSeconds(60_000)); assertNull(BatteryRules.timeoutSeconds(Int.MAX_VALUE)); assertNull(BatteryRules.timeoutSeconds(0))
        assertEquals("full", BatteryRules.status(5)); assertEquals("good", BatteryRules.health(2)); assertEquals("severe", BatteryRules.thermal(3))
    }
}
