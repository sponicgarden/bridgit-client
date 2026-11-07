package cc.bridgit.app.lan
import org.junit.Assert.*
import org.junit.Test
class DeviceLastSeenTest {
    @Test fun agesAreBoundedAndUnknownIsHonest() {
        val now = 10_000_000L
        assertEquals("Not yet seen", DeviceLastSeen.relative(0, now))
        assertEquals("Just now", DeviceLastSeen.relative(now + 1000, now))
        assertEquals("5 min ago", DeviceLastSeen.relative(now - 300_000, now))
        assertEquals("1 hr ago", DeviceLastSeen.relative(now - 3_600_000, now))
        assertEquals("2 d ago", DeviceLastSeen.relative(1, 172_800_001))
    }
}
