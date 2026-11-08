package cc.bridgit.app.feedback

import cc.bridgit.app.interact.DisplayGeometry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0077: the spec sheet builder (shape, no identifying fields, sensor names, 8 KB cap) and the upload-on-change decision. */
class DeviceSpecsTest {
    private val display = DisplayGeometry(1344, 2992, 486, 3.0f, 0, refreshHz = 120)
    private fun facts(sensors: List<String?> = listOf("android.sensor.accelerometer", "android.sensor.gyroscope", "android.sensor.light", "android.sensor.proximity")) =
        DeviceSpecs.Facts(display, listOf(DeviceSpecs.Camera("back", 50), DeviceSpecs.Camera("front"), DeviceSpecs.Camera("sideways", 2)),
            8, 3100, "arm64-v8a", 16384, 256, sensors, "Google", "Pixel 10 Pro XL", "16", 36, "1.8.3720")

    @Test fun buildsTheBackendShape() {
        val s = DeviceSpecs.build(facts())
        val d = s.getJSONObject("display")
        assertEquals(1344, d.getInt("width_px")); assertEquals(2992, d.getInt("height_px")); assertEquals(486, d.getInt("density_dpi")); assertEquals(120, d.getInt("refresh_hz"))
        assertEquals("20:9", cc.bridgit.app.interact.CardViewport.aspectLabel(d.getInt("width_px"), d.getInt("height_px")))
        assertEquals(2992, s.getJSONObject("full_screen_card").getJSONObject("landscape").getInt("width_px"))
        assertEquals(1344, s.getJSONObject("full_screen_card").getJSONObject("portrait").getInt("width_px"))
        val cams = s.getJSONArray("cameras")
        assertEquals(2, cams.length()) // an unknown facing is left out
        assertEquals(50, cams.getJSONObject(0).getInt("megapixels")); assertFalse(cams.getJSONObject(1).has("megapixels"))
        val cpu = s.getJSONObject("cpu")
        assertEquals(8, cpu.getInt("cores")); assertEquals(3100, cpu.getInt("max_mhz")); assertEquals("arm64-v8a", cpu.getString("abi"))
        assertEquals(16384, s.getInt("memory_mb")); assertEquals(256, s.getInt("storage_gb"))
        assertEquals("android", s.getString("os")); assertEquals("16", s.getString("os_version")); assertEquals(36, s.getInt("api_level"))
        assertEquals("Pixel 10 Pro XL", s.getString("model")); assertEquals("1.8.3720", s.getString("app_version"))
    }

    @Test fun naturalOrientationEvenWhenRotated() {
        val s = DeviceSpecs.build(facts().copy(display = display.copy(widthPx = 2992, heightPx = 1344, rotation = 90)))
        assertEquals(1344, s.getJSONObject("display").getInt("width_px"))
    }

    @Test fun nothingIdentifying() {
        val s = DeviceSpecs.build(facts())
        assertTrue(DeviceSpecs.FIELDS.containsAll(s.keys().asSequence().toSet()))
        val text = s.toString().lowercase()
        for (word in listOf("serial", "imei", "mac", "account", "android_id", "install", "bgi_", "@")) assertFalse(word, text.contains(word))
    }

    @Test fun sensorNamesAreShortLowercaseTypes() {
        assertEquals("accelerometer", DeviceSpecs.sensorName("android.sensor.accelerometer"))
        assertEquals("gyro_temperature", DeviceSpecs.sensorName("com.google.sensor.Gyro-Temperature"))
        assertEquals("step_counter", DeviceSpecs.sensorName("android.sensor.step_counter"))
        assertNull(DeviceSpecs.sensorName(null)); assertNull(DeviceSpecs.sensorName("...")); assertNull(DeviceSpecs.sensorName("  "))
        assertTrue(DeviceSpecs.sensorName("x." + "a".repeat(100))!!.length <= DeviceSpecs.SENSOR_NAME_MAX)
        val s = DeviceSpecs.build(facts(listOf("android.sensor.light", "android.sensor.light", "android.sensor.accelerometer", null, "vendor.sensor.ÜBER")))
        val names = (0 until s.getJSONArray("sensors").length()).map { s.getJSONArray("sensors").getString(it) }
        assertEquals(listOf("accelerometer", "ber", "light"), names)
        assertTrue(names.all { it.matches(Regex("^[a-z0-9_]+$")) })
    }

    @Test fun sheetStaysUnderTheSizeCap() {
        val many = (0 until 500).map { "vendor.sensor.sensor_number_${it}_with_a_long_name" }
        val s = DeviceSpecs.build(facts(many))
        assertTrue(DeviceSpecs.bytes(s) <= DeviceSpecs.MAX_BYTES)
        assertTrue(s.getJSONArray("sensors").length() <= DeviceSpecs.MAX_SENSORS)
        val cams = DeviceSpecs.build(facts().copy(cameras = List(40) { DeviceSpecs.Camera("external", 12) }))
        assertEquals(DeviceSpecs.MAX_CAMERAS, cams.getJSONArray("cameras").length())
    }

    @Test fun hashIgnoresKeyOrderButNotContent() {
        val a = JSONObject("""{"os":"android","display":{"width_px":1,"height_px":2}}""")
        val b = JSONObject("""{"display":{"height_px":2,"width_px":1},"os":"android"}""")
        assertEquals(DeviceSpecs.hash(a), DeviceSpecs.hash(b))
        assertNotEquals(DeviceSpecs.hash(a), DeviceSpecs.hash(JSONObject(a.toString()).put("os_version", "16")))
        assertEquals(DeviceSpecs.hash(DeviceSpecs.build(facts())), DeviceSpecs.hash(DeviceSpecs.build(facts())))
        assertNotEquals(DeviceSpecs.hash(DeviceSpecs.build(facts())), DeviceSpecs.hash(DeviceSpecs.build(facts().copy(appVersion = "1.8.3721"))))
    }

    @Test fun uploadsOnlyWhenTheHashChanges() {
        assertEquals(DeviceSpecs.Action.UPLOAD, DeviceSpecs.action(share = true, named = true, deletePending = false, storedHash = null, newHash = "h1", force = false))
        assertEquals(DeviceSpecs.Action.NONE, DeviceSpecs.action(true, true, false, "h1", "h1", false))
        assertEquals(DeviceSpecs.Action.UPLOAD, DeviceSpecs.action(true, true, false, "h1", "h2", false))
        assertEquals(DeviceSpecs.Action.UPLOAD, DeviceSpecs.action(true, true, false, "h1", "h1", force = true))
        assertEquals(DeviceSpecs.Action.NONE, DeviceSpecs.action(true, named = false, deletePending = false, storedHash = null, newHash = null, force = true))
        assertEquals(DeviceSpecs.Action.DELETE, DeviceSpecs.action(share = false, named = true, deletePending = true, storedHash = null, newHash = null, force = false))
        assertEquals(DeviceSpecs.Action.NONE, DeviceSpecs.action(false, true, false, null, null, true))
        assertEquals(DeviceSpecs.Action.NONE, DeviceSpecs.action(false, false, true, null, null, false))
    }

    @Test fun checksAtMostOnceAnHour() {
        val t = 10_000_000L
        assertTrue(DeviceSpecs.due(0L, t, false))
        assertFalse(DeviceSpecs.due(t, t + DeviceSpecs.CHECK_EVERY_MS - 1, false))
        assertTrue(DeviceSpecs.due(t, t + DeviceSpecs.CHECK_EVERY_MS, false))
        assertTrue(DeviceSpecs.due(t, t + 1, true))
    }
}
