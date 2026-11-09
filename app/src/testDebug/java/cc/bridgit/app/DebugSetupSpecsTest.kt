package cc.bridgit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C0032: caps without "=" crashed the app (IndexOutOfBounds on a one-item split). */
class DebugSetupSpecsTest {
    @Test
    fun capsWithoutEqualsIsRejected() {
        assertNull(DebugSetupSpecs.caps("Tester"))
        assertNull(DebugSetupSpecs.caps("=camera"))
    }

    @Test
    fun capsParsesGrantsAndAll() {
        assertEquals("Tester" to setOf("camera", "mic"), DebugSetupSpecs.caps("Tester=camera, mic,"))
        assertEquals("Tester" to null, DebugSetupSpecs.caps("Tester=*"))
        assertEquals("Tester" to emptySet<String>(), DebugSetupSpecs.caps("Tester="))
    }
}
