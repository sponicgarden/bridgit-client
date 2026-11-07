package cc.bridgit.app.power

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Power and sun triggers (docs/applet-reach.md part G): the pure decisions. */
class PowerRulesTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun aQuickUnplugAndReplugDoesNotFireAPowerCut() {
        var s = PowerRules.observe(PowerRules.observe(null, false, t0 - min), true, t0)
        s = PowerRules.observe(s, false, t0 + min)
        assertNull(PowerRules.due("unplugged", 600, s, t0, null, t0 + 5 * min))
        s = PowerRules.observe(s, true, t0 + 6 * min)
        assertNull(PowerRules.due("unplugged", 600, s, t0, null, t0 + 20 * min))
        // ...and a "restored" with for_s 600 doesn't fire either: the outage was 5 minutes.
        assertNull(PowerRules.due("restored", 600, s, t0, null, t0 + 6 * min))
    }

    @Test fun unpluggedFiresOnceAfterForSAndRestoredSaysHowLong() {
        var s = PowerRules.observe(PowerRules.observe(PowerRules.observe(null, false, t0 - min), true, t0), false, t0 + min)
        val cut = PowerRules.due("unplugged", 600, s, t0, null, t0 + 11 * min)!!
        assertEquals(t0 + min, cut.marker); assertEquals(10, cut.data["off_minutes"])
        assertNull("the same outage never fires twice", PowerRules.due("unplugged", 600, s, t0, cut.marker, t0 + 30 * min))
        s = PowerRules.observe(s, true, t0 + 48 * min)
        val back = PowerRules.due("restored", 600, s, t0, null, t0 + 48 * min)!!
        assertEquals(47, back.data["off_minutes"]); assertEquals(t0 + min, back.data["off_at"]); assertEquals(t0 + 48 * min, back.data["on_at"])
        assertNull(PowerRules.due("restored", 600, s, t0, back.marker, t0 + 49 * min))
        // A new outage re-arms the flow.
        s = PowerRules.observe(s, false, t0 + 60 * min)
        assertNotNull(PowerRules.due("unplugged", 600, s, t0, cut.marker, t0 + 71 * min))
    }

    @Test fun aFlowActivatedWhileChargingDoesNotFireAtOnce() {
        val s = PowerRules.observe(null, true, t0)
        assertEquals("the first reading is no plug-in", 0L, s.since)
        assertNull(PowerRules.due("plugged", 0, s, 0L, null, t0 + 2 * min))
        assertNull(PowerRules.due("restored", 0, PowerRules.observe(PowerRules.observe(null, false, t0), true, t0 + min), 0L, null, t0 + min))
        val next = PowerRules.observe(PowerRules.observe(s, false, t0 + 3 * min), true, t0 + 4 * min)
        assertNotNull(PowerRules.due("plugged", 0, next, t0 + min, null, t0 + 4 * min))
    }

    @Test fun anOutageAcrossARebootIsReportedOnceThePowerIsBack() {
        // The saved state says unplugged since 14:02; the first reading after the reboot says plugged.
        val saved = PowerRules.State(false, t0, t0 - 3600 * 1000L)
        val after = PowerRules.observe(saved, true, t0 + 47 * min)
        val back = PowerRules.due("restored", 600, after, t0 - 7200 * 1000L, null, t0 + 47 * min)!!
        assertEquals(47, back.data["off_minutes"])
        assertEquals("unchanged state is kept as is", after, PowerRules.observe(after, true, t0 + 50 * min))
    }

    @Test fun wordsReadAsTheOwnerSeesThem() {
        assertEquals("When the phone has been off charge for 10 minutes (a power cut)", PowerRules.words("unplugged", 600))
        assertEquals("When power comes back after at least 10 minutes off charge", PowerRules.words("restored", 600))
        assertEquals("When the phone comes off charge", PowerRules.words("unplugged", 0))
        assertEquals("When the phone starts charging", PowerRules.words("plugged", 0))
        assertEquals("30 minutes before sunset", SunTimes.words("sunset", -30))
    }

    private fun local(ms: Long, zone: String) = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone)).toLocalTime()
    private fun near(expected: String, ms: Long, zone: String) {
        val got = local(ms, zone); val want = java.time.LocalTime.parse(expected)
        assertTrue("$got should be within 5 minutes of $want", Math.abs(java.time.Duration.between(want, got).toMinutes()) <= 5)
    }

    @Test fun sunriseAndSunsetMatchPublishedTimes() {
        val june = LocalDate.of(2026, 6, 21).toEpochDay()
        // London midsummer: about 04:43 and 21:21 BST.
        near("04:43", SunTimes.event(june, 51.5, -0.13, true)!!, "Europe/London")
        near("21:21", SunTimes.event(june, 51.5, -0.13, false)!!, "Europe/London")
        // Warsaw midwinter: about 07:43 and 15:25 CET.
        val dec = LocalDate.of(2026, 12, 21).toEpochDay()
        near("07:43", SunTimes.event(dec, 52.23, 21.01, true)!!, "Europe/Warsaw")
        near("15:25", SunTimes.event(dec, 52.23, 21.01, false)!!, "Europe/Warsaw")
        // Sydney (east, southern summer) and Los Angeles (west): the UTC date still gives the local day's times.
        near("05:41", SunTimes.event(dec, -33.87, 151.21, true)!!, "Australia/Sydney")
        near("16:47", SunTimes.event(dec, 34.05, -118.24, false)!!, "America/Los_Angeles")
        // Tromsø in midwinter has no sunrise.
        assertNull(SunTimes.event(dec, 69.65, 18.96, true))
    }

    @Test fun nextIsTheFirstOneAfterNowWithItsOffset() {
        val noon = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli()
        val next = SunTimes.next("sunset", -30, 51.5, -0.13, noon)!!
        near("20:51", next, "Europe/London")
        val tomorrow = SunTimes.next("sunrise", 0, 51.5, -0.13, noon)!!
        assertTrue(tomorrow > noon); near("04:43", tomorrow, "Europe/London")
        assertNull(SunTimes.next("sunrise", 0, 69.65, 18.96, LocalDate.of(2026, 12, 21).toEpochDay() * 86_400_000L))
    }
}
