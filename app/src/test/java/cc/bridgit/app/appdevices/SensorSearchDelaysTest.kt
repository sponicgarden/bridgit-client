package cc.bridgit.app.appdevices

import org.junit.Assert.*
import org.junit.Test

class SensorSearchDelaysTest {
    private fun store(saved: MutableMap<String, Int>) =
        SensorSearchDelays(saved::get) { key, value -> if(value == null) saved.remove(key) else saved[key] = value }

    @Test fun settingsArePersistentAndIsolatedByApplicationAndSensor() {
        val saved = mutableMapOf<String, Int>()
        val first = store(saved)
        assertEquals(20, first.seconds("dog_monitor", "camera/rear"))
        first.set("dog_monitor", "camera/rear", 90)
        val reopened = store(saved)
        assertEquals(90, reopened.seconds("dog_monitor", "camera/rear"))
        assertEquals(20, reopened.seconds("another_app", "camera/rear"))
        assertEquals(20, reopened.seconds("dog_monitor", "camera/front"))
        assertEquals(20, reopened.seconds("dog_monitor", "microphone/rear"))
        reopened.set("dog_monitor", "camera/lan:LivingRoom-A1", 120)
        assertEquals(120, first.seconds("dog_monitor", "camera/lan:LivingRoom-A1"))
        reopened.set("dog_monitor", "camera/rear", null)
        assertEquals(20, first.seconds("dog_monitor", "camera/rear"))
    }

    @Test fun theAllValueAppliesToEverySensorWithoutItsOwn() {
        val saved = mutableMapOf<String, Int>()
        val s = store(saved)
        s.set("dog_monitor", "camera/front", 45)
        s.setAll("dog_monitor", 30)
        assertEquals(30, s.allSeconds("dog_monitor"))
        assertEquals(30, s.seconds("dog_monitor", "camera/rear"))
        assertEquals(30, s.seconds("dog_monitor", "microphone/lan:Bowl"))
        assertEquals(45, s.seconds("dog_monitor", "camera/front"))
        assertNull(s.override("dog_monitor", "camera/rear"))
        assertEquals(45, s.override("dog_monitor", "camera/front"))
        assertEquals(20, s.seconds("another_app", "camera/rear"))
        s.set("dog_monitor", "camera/front", null)
        assertEquals(30, s.seconds("dog_monitor", "camera/front"))
        s.setAll("dog_monitor", null)
        assertEquals(20, s.seconds("dog_monitor", "camera/front"))
    }

    @Test fun invalidWritesCannotReplaceTheSavedDelay() {
        val saved = mutableMapOf<String,Int>()
        val store = store(saved)
        store.set("dog_monitor", "camera/rear", 90)
        for(value in listOf(-1,0,9,3601)) {
            try { store.set("dog_monitor", "camera/rear", value); fail("Accepted $value") } catch(_:IllegalArgumentException) { }
            try { store.setAll("dog_monitor", value); fail("Accepted all $value") } catch(_:IllegalArgumentException) { }
            assertEquals(90,store.seconds("dog_monitor", "camera/rear"))
        }
        assertFalse(SensorSearchDelays.validSeconds(60.0))
        assertFalse(SensorSearchDelays.validSeconds("60"))
    }
}
