package cc.bridgit.app.power

import org.junit.Assert.*
import org.junit.Test

class BatteryRuntimeTest {
    private val minute = 60_000L

    @Test fun learnsBeforeEstimatingAndGivesQuantizationRange() {
        val r = BatteryRuntime()
        assertEquals("learning", r.observe(0, 80, false).status)
        assertEquals("learning", r.observe(9 * minute, 78, false).status)
        val e = r.observe(10 * minute, 78, false)
        assertEquals("available", e.status)
        assertEquals(23_400L, e.seconds)
        assertEquals(15_600L, e.lowerSeconds)
        assertEquals(46_800L, e.upperSeconds)
        assertEquals(12.0, e.percentPerHour!!, 0.001)
    }

    @Test fun onePercentAndFlatBatteryDoNotInventAnEstimate() {
        val r = BatteryRuntime()
        r.observe(0, 80, false)
        assertNull(r.observe(20 * minute, 79, false).seconds)
        assertNull(r.observe(40 * minute, 79, false).seconds)
    }

    @Test fun chargingClearsHistoryEvenWhenLevelDoesNotRise() {
        val r = BatteryRuntime()
        r.observe(0, 80, false)
        r.observe(10 * minute, 78, false)
        val plugged = r.observe(11 * minute, 78, true)
        assertEquals("plugged_in", plugged.status)
        assertNull(plugged.seconds)
        assertEquals("learning", r.observe(12 * minute, 78, false).status)
    }

    @Test fun missingRisingStaleAndReversedTimeStartOver() {
        for (reset in listOf<(BatteryRuntime) -> Unit>(
            { it.observe(11 * minute, null, false) },
            { it.observe(11 * minute, 79, false) },
            { it.observe(41 * minute, 77, false) },
            { it.observe(0, 78, false) },
            { it.reset() }
        )) {
            val r = BatteryRuntime()
            r.observe(0, 80, false)
            assertNotNull(r.observe(10 * minute, 78, false).seconds)
            reset(r)
            assertNull(r.observe(42 * minute, 77, false).seconds)
        }
    }

    @Test fun frequentReadsDoNotChangeEstimateAndOldWorkloadsAgeOut() {
        val r = BatteryRuntime()
        for (m in 0..180) {
            // First hour drains fast, last two hours drain 2% per hour.
            val p = if (m <= 60) 100 - m / 6 else 90 - (m - 60) / 30
            r.observe(m * minute, p, false)
            r.observe(m * minute, p, false)
        }
        val e = r.observe(180 * minute, 86, false)
        assertEquals(2.0, e.percentPerHour!!, 0.001)
        assertEquals(154_800L, e.seconds)
        assertEquals(7200L, e.observedSeconds)
    }
}
