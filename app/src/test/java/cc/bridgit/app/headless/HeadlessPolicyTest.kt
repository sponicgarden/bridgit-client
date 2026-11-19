package cc.bridgit.app.headless

import org.junit.Assert.*
import org.junit.Test

class HeadlessPolicyTest {
    @Test fun automaticModeDistinguishesHeadlessDisplaysFromPhonesAndTelevisions() {
        assertTrue(HeadlessPolicy.active("auto", 1, 1))
        assertTrue(HeadlessPolicy.active("auto", 0, 0))
        assertFalse(HeadlessPolicy.active("auto", 1920, 1080))
        assertFalse(HeadlessPolicy.active("auto", 1080, 2400))
        assertTrue(HeadlessPolicy.active("headless", 1080, 2400))
    }
    @Test fun usbTransportOnlyAdmitsLiteralLoopbackInHeadlessMode() {
        for (source in listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")) {
            assertTrue(HeadlessPolicy.sourceAllowed(true, source, false))
            assertFalse(HeadlessPolicy.sourceAllowed(false, source, false))
        }
        for (source in listOf("127.0.0.2", "localhost", "192.168.1.2", "0.0.0.0", "example.org", "::ffff:192.168.1.2"))
            assertFalse(source, HeadlessPolicy.sourceAllowed(true, source, false))
        assertTrue(HeadlessPolicy.sourceAllowed(false, "100.64.0.1", true))
        assertTrue(HeadlessPolicy.sourceAllowed(true, "100.64.0.1", true))
    }
}
