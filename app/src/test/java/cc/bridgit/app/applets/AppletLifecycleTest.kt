package cc.bridgit.app.applets

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppletLifecycleTest {
    @Test fun restartPreservesPendingStopsAndGeneration() {
        val record = JSONObject().put("id", "monitor").put("status", "active")
        val stopping = AppletLifecycle.read(record, listOf("phone", "camera")).pause()
            .acknowledge("monitor", 1, "phone", AppletTaskState.Phase.PAUSING)
        record.put("lifecycle", AppletLifecycle.json(stopping))
        val restored = AppletLifecycle.read(JSONObject(record.toString()), listOf("replacement"))
        assertEquals(setOf("camera"), restored.pending)
        assertEquals(setOf("phone", "camera"), restored.participants)
        assertEquals(1, restored.generation)
        assertEquals("pausing", AppletLifecycle.state(record))
    }

    @Test fun legacyPauseAndUpdatedParticipantsArePreserved() {
        val record = JSONObject().put("id", "monitor").put("status", "paused")
        val paused = AppletLifecycle.read(record, listOf("old"))
        record.put("lifecycle", AppletLifecycle.json(paused))
        val updated = AppletLifecycle.read(record, listOf("new")).resume()
        assertEquals(setOf("new"), updated.pending)
        assertEquals(AppletTaskState.Phase.STARTING, updated.phase)
    }

    @Test fun classifiesPassiveAndAutonomousDefinitions() {
        val passive = JSONObject().put("processing", JSONObject())
        assertEquals("passive", AppletPackage.runMode(passive))
        val autonomous = JSONObject("""{"processing":{"watches":[{}]}}""")
        assertEquals("autonomous", AppletPackage.runMode(autonomous))
        autonomous.put("run_mode", "passive")
        assertThrows(AppletPackage.Bad::class.java) { AppletPackage.runMode(autonomous) }
    }
}
