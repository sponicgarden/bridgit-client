package cc.bridgit.app.remoteview

import cc.bridgit.app.dog.DogDay
import cc.bridgit.app.dog.DogStatus
import cc.bridgit.app.dog.DogTracker
import cc.bridgit.app.dog.Gaps
import cc.bridgit.app.dog.Reading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RemoteViewDogTest {
    @Test fun portalControlsIgnoreRetriesAndStaleRepliesButResetForNewConsent() {
        assertTrue(RemoteViewDog.newControl("consent-a", 1, null, 0))
        assertFalse(RemoteViewDog.newControl("consent-a", 1, "consent-a", 1))
        assertFalse(RemoteViewDog.newControl("consent-a", 1, "consent-a", 2))
        assertTrue(RemoteViewDog.newControl("consent-a", 2, "consent-a", 1))
        assertTrue(RemoteViewDog.newControl("consent-b", 0, "consent-a", 2))
        assertFalse(RemoteViewDog.newControl("", 1, null, 0))
        assertFalse(RemoteViewDog.newControl("consent-a", -1, null, 0))
    }

    @Test fun autoCenterCommandsNeedARevisionAndABoolean() {
        fun cmd(json: String) = RemoteViewDog.autoCenterCommand(org.json.JSONObject(json))
        assertEquals(RemoteViewDog.AutoCenterCommand(3, false), cmd("{revision:3,enabled:false}"))
        assertEquals(RemoteViewDog.AutoCenterCommand(0, true), cmd("{revision:0,enabled:true}"))
        assertEquals(null, cmd("{revision:3,enabled:'true'}"))
        assertEquals(null, cmd("{revision:-1,enabled:true}"))
        assertEquals(null, cmd("{enabled:true}"))
        assertEquals(null, cmd("{revision:2.5,enabled:true}"))
        assertEquals(null, RemoteViewDog.autoCenterCommand(null))
    }

    @Test fun samplingCommandsAreBoundedAndAllOrNothing() {
        fun cmd(json: String) = RemoteViewDog.samplingCommand(org.json.JSONObject(json))
        val c = cmd("{revision:3,all_s:20,cameras:{rear:45,'lan:Bowl-1':null}}")!!
        assertEquals(3L, c.revision); assertEquals(20, c.allS)
        assertEquals(mapOf("rear" to 45, "lan:Bowl-1" to null), c.cameras)
        assertEquals(null, cmd("{revision:4,all_s:null}")!!.allS)
        assertEquals(emptyMap<String, Int?>(), cmd("{revision:4,all_s:30,cameras:null}")!!.cameras)
        for (bad in listOf("{all_s:20}", "{revision:1}", "{revision:-1,all_s:20}", "{revision:1,all_s:9}", "{revision:1,all_s:3601}",
                "{revision:1,all_s:'20'}", "{revision:1,all_s:20.5}", "{revision:1,all_s:20,cameras:{auto:30}}",
                "{revision:1,all_s:20,cameras:{rear:5}}", "{revision:1,all_s:20,cameras:[]}"))
            assertEquals(bad, null, cmd(bad))
        assertEquals(null, RemoteViewDog.samplingCommand(null))
    }

    private val noon = 1_793_016_000_000L.let { it - it % 86_400_000L + 12 * 3_600_000L }

    @Test fun lastScanShowsEveryTestedCameraEvenWhenNothingChanged() {
        val choice = cc.bridgit.app.dog.DogCamera.pick(linkedMapOf("rear" to 0.35, "front" to 0.09, "lan:a" to 0.12, "lan:b" to null), "rear", noon)
        val o = RemoteViewDog.summary(DogStatus(running = true, choice = choice), null, emptyList(), photoOn = true).getJSONObject("last_scan")
        assertEquals(noon, o.getLong("at"))
        assertFalse(o.getBoolean("pet_seen"))
        assertEquals("rear", o.getJSONArray("cameras").getString(0))
        assertEquals(35, o.getJSONObject("scores").getInt("rear"))
        assertEquals(12, o.getJSONObject("scores").getInt("lan:a"))
        assertTrue(o.getJSONObject("scores").isNull("lan:b"))
        assertTrue(RemoteViewDog.summary(DogStatus(running = true), null, emptyList(), photoOn = true).isNull("last_scan"))
    }

    @Test fun summaryHasOnlyNumbersTimesAndFixedWords() {
        val s = DogStatus(running = true, startedAt = noon - 60_000, models = listOf("clef"), asked = 4, unchanged = 9,
            error = "Clef couldn't look at the photo: some server text", errorCode = "model_failed", frame = ByteArray(10), frameAt = noon)
        val day = DogDay(3_600_000, 2, noon - 600_000, 1, noon - 7_200_000, 3, noon - 3_600_000,
            mapOf("sleeping" to 10, "no_dog" to 3, "dark" to 2), 15, 130_000, Gaps(5_400_000, 4), null, 600_000, 9)
        val recent = listOf(Reading(noon, true, 0.964, "sleeping", 0.9, "relaxed", 0.8, "clef", reused = true))
        val o = RemoteViewDog.summary(s, day, recent, photoOn = true)
        val text = o.toString()
        assertFalse("the screen's error text never goes", text.contains("server text"))
        assertEquals("model_failed", o.getString("error"))
        assertEquals(96, o.getJSONObject("latest").getInt("dog_pct"))
        assertTrue(o.getJSONObject("latest").getBoolean("reused"))
        assertEquals(9, o.getInt("free"))
        val t = o.getJSONObject("today")
        assertEquals(9, t.getInt("free"))
        assertEquals(5_400_000, t.getLong("median_drink_ms"))
        assertTrue(t.isNull("median_meal_ms"))
        assertEquals(10, t.getJSONObject("views").getInt("sleeping"))
        assertEquals("the portal has no dark tally: too-dark photos count as no dog", 5, t.getJSONObject("views").getInt("no_dog"))
        assertFalse(t.getJSONObject("views").has("dark"))
        assertTrue(o.getBoolean("photo_on"))
        assertFalse("no photo bytes in the summary", o.has("frame") || text.contains("photo\":\""))
    }

    @Test fun captureMustWaitForItsOwnClassificationBeforeAdvancingUploadThrottle() {
        val captured = DogStatus(running = true, frame = byteArrayOf(1), frameAt = noon)
        val previous = Reading(noon - 10_000, true, 0.9, "sleeping", 0.9, "relaxed", 0.8, "clef")
        assertTrue(RemoteViewDog.awaitingReading(captured, listOf(previous), noon - 60_000, true))
        val classified = previous.copy(at = noon)
        assertFalse(RemoteViewDog.awaitingReading(captured, listOf(classified), noon - 60_000, true))
        assertFalse(RemoteViewDog.awaitingReading(captured.copy(running = false), emptyList(), noon - 60_000, true))
        assertFalse(RemoteViewDog.awaitingReading(captured.copy(error = "model failed"), emptyList(), noon - 60_000, true))
        assertFalse(RemoteViewDog.awaitingReading(captured, emptyList(), noon - 60_000, false))
        assertFalse(RemoteViewDog.awaitingReading(captured, emptyList(), noon + 1, true))
    }

    @Test fun startAndStopGoAtOnceOtherwiseEvery30s() {
        assertTrue(RemoteViewDog.due(true, null, 0, 1_000))
        assertTrue(RemoteViewDog.due(false, true, 1_000, 2_000))
        assertTrue(RemoteViewDog.due(true, false, 1_000, 2_000))
        assertFalse(RemoteViewDog.due(true, true, 1_000, 30_999))
        assertTrue(RemoteViewDog.due(true, true, 1_000, 31_000))
    }

    @Test fun trackerCountsTodaysFreeReadingsAndKeepsThem() {
        val utc = TimeZone.getTimeZone("UTC")
        val t = DogTracker()
        fun r(sec: Long, reused: Boolean) = Reading(noon + sec * 1000, true, 0.9, "sleeping", 0.9, "relaxed", 0.8, "clef", reused)
        t.add(r(0, false), utc); t.add(r(10, true), utc); t.add(r(20, true), utc)
        assertEquals(2, t.day(noon + 30_000, utc).free)
        val back = DogTracker().apply { load(JSONObject(t.toJson().toString())) }
        assertEquals(2, back.day(noon + 30_000, utc).free)
        // A new day starts at zero.
        assertEquals(0, back.day(noon + 86_400_000, utc).free)
    }

    @Test fun portalListenReportsTheOwnersMicSwitchAndPermission() {
        assertEquals("off", RemoteViewDog.builtinMic(switchOn = false, permitted = true))
        assertEquals("no_permission", RemoteViewDog.builtinMic(switchOn = true, permitted = false))
        assertEquals("available", RemoteViewDog.builtinMic(switchOn = true, permitted = true))
    }

    @Test fun portalListenErrorsAreCodesNeverRawMessages() {
        assertEquals("no_permission", RemoteViewDog.listenError("Microphone access changed", permissionMissing = true))
        assertEquals("mic_off", RemoteViewDog.listenError("Microphone access changed", permissionMissing = false))
        assertEquals("open_app", RemoteViewDog.listenError("Open Bridgit before microphone capture", false))
        assertEquals("busy", RemoteViewDog.listenError("Camera microphone busy", false))
        assertEquals("busy", RemoteViewDog.listenError("Microphone unavailable: prepare failed", false))
        assertEquals("no_track", RemoteViewDog.listenError("No supported microphone track", false))
        assertEquals("record", RemoteViewDog.listenError("rtsp://user:pass@10.0.0.2 refused", false))
        assertEquals("record", RemoteViewDog.listenError(null, false))
    }
}
